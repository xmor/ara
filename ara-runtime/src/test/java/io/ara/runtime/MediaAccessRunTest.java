package io.ara.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ara.core.agent.AgentConfig;
import io.ara.core.common.AgentId;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.CheckpointStore;
import io.ara.core.agent.ConversationTurn;
import io.ara.core.agent.RunCheckpoint;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.ToolExchange;
import io.ara.core.common.Money;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.media.MediaAccess;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaStore;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.media.SessionMediaAccess;
import io.ara.runtime.stubs.ScriptedLlmClient;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a tool can read of the media of its session, through the whole runtime: which media are visible, when
 * they become visible, and what is not. The unit behaviour of the view is in {@code SessionMediaAccessTest};
 * this is the part that depends on where the runtime binds it.
 */
class MediaAccessRunTest {

    private static final AgentId AGENT = AgentId.of("a");

    /** A tool whose behaviour each test sets, and which keeps what it saw. */
    private static final class Probe implements AraTool {
        volatile Function<AgentTask, ToolResult> behaviour = task -> ToolResult.success("probe", "ok");
        final List<Optional<MediaAccess>> accessSeen = new CopyOnWriteArrayList<>();

        @Override public String toolId() { return "probe"; }
        @Override public String description() { return "probes what the run can see"; }
        @Override public String argumentSchema() { return "{\"type\":\"object\"}"; }
        @Override public ToolResult execute(String argumentJson) {
            throw new AssertionError("the probe needs the task");
        }
        @Override public ToolResult execute(String argumentJson, AgentTask task) {
            accessSeen.add(task.runContext().mediaAccess());
            return behaviour.apply(task);
        }
    }

    private static ToolRegistry registryOf(Probe probe) {
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(probe); }
            @Override public Optional<AraTool> findById(String id) {
                return probe.toolId().equals(id) ? Optional.of(probe) : Optional.empty();
            }
            @Override public ToolResult execute(String id, String json) { return probe.execute(json, null); }
            @Override public ToolResult execute(String id, String json, AgentTask task) { return probe.execute(json, task); }
        };
    }

    /** One tool call then an answer, once per turn the test will run. */
    private static LlmClient script(int turns) {
        ScriptedLlmClient.Builder builder = ScriptedLlmClient.script();
        for (int i = 0; i < turns; i++) {
            builder.thenToolCall("probe", "{}").thenFinalAnswer("done " + i);
        }
        return builder.build();
    }

    private static AraRuntime runtime(LlmClient llm, Probe probe, MediaStore store) {
        return AraRuntime.builder().llmClient(llm).toolRegistry(registryOf(probe)).mediaStore(store).build();
    }

    private static AgentConfig config(boolean replayToolCalls) {
        return AgentConfig.defaults().agentId(AGENT).agentType("t").primaryLlm(LlmProfile.of("default"))
                .plannerStrategy("react").enabledTools(List.of("probe")).maxIterations(5)
                .maxConversationTurns(4).replayToolCalls(replayToolCalls).build();
    }

    private static AgentResponse run(io.ara.core.agent.AraAgent agent, AgentTask task) {
        AgentResponse response = agent.execute(task);
        assertTrue(response.isSuccess(), () -> "the run failed: " + response.failureReason());
        return response;
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ── Whether a tool gets access at all ─────────────────────────────────────────────────────────────

    @Test
    void aRuntimeWithAStoreOffersItsToolsMediaAccess() {
        Probe probe = new Probe();
        try (AraRuntime runtime = runtime(script(1), probe, MediaStore.inMemory())) {
            run(runtime.createAgent(config(false)), AgentTask.of("go").withSessionId(SessionId.of("s1")));
        }

        assertEquals(1, probe.accessSeen.size());
        assertTrue(probe.accessSeen.get(0).isPresent());
    }

    @Test
    void aRuntimeWithoutAStoreOffersNone() {
        Probe probe = new Probe();
        try (AraRuntime runtime = runtime(script(1), probe, MediaStore.noop())) {
            run(runtime.createAgent(config(false)), AgentTask.of("go").withSessionId(SessionId.of("s1")));
        }

        assertTrue(probe.accessSeen.get(0).isEmpty());
    }

    @Test
    void aViewInheritedFromAnotherAgentIsRemovedWhenThisRuntimeHasNoStore() {
        // A sub-agent of a runtime without media must not carry its caller's view into a run that has none.
        Probe probe = new Probe();
        MediaAccess callers = new SessionMediaAccess(MediaStore.inMemory(), List.of());
        try (AraRuntime runtime = runtime(script(1), probe, MediaStore.noop())) {
            run(runtime.createAgent(config(false)), AgentTask.of("go").withSessionId(SessionId.of("s1"))
                    .withAttachment(RunContext.MEDIA_ACCESS_KEY, callers));
        }

        assertTrue(probe.accessSeen.get(0).isEmpty());
    }

    // ── What is visible ───────────────────────────────────────────────────────────────────────────────

    @Test
    void aMediaTheTaskCarriesIsVisibleToItsTools() {
        MediaStore store = MediaStore.inMemory();
        MediaRef attached = store.put("contract.png", "image/png", bytes("contract"));
        Probe probe = new Probe();
        List<String> answers = new CopyOnWriteArrayList<>();
        probe.behaviour = task -> {
            answers.add(task.runContext().mediaAccess().orElseThrow().resolve(attached.shortId()).isPresent() ? "found" : "missing");
            return ToolResult.success("probe", "ok");
        };
        try (AraRuntime runtime = runtime(script(1), probe, store)) {
            run(runtime.createAgent(config(false)),
                    AgentTask.of("look", List.of(attached)).withSessionId(SessionId.of("s1")));
        }

        assertEquals(List.of("found"), answers);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aMediaAToolReturnedIsVisibleOnTheNextTurnWhetherOrNotTheAgentReplaysToolCalls(boolean replayToolCalls) {
        MediaStore store = MediaStore.inMemory();
        Probe probe = new Probe();
        List<MediaRef> produced = new CopyOnWriteArrayList<>();
        List<String> secondTurn = new CopyOnWriteArrayList<>();
        try (AraRuntime runtime = runtime(script(2), probe, store)) {
            io.ara.core.agent.AraAgent agent = runtime.createAgent(config(replayToolCalls));
            SessionId session = SessionId.of("s1");

            probe.behaviour = task -> {
                MediaRef crop = task.runContext().mediaAccess().orElseThrow().put("crop-1.png", "image/png", bytes("crop"));
                produced.add(crop);
                return ToolResult.success("probe", "Crop saved.", List.of(crop));
            };
            run(agent, AgentTask.of("crop it").withSessionId(session));

            probe.behaviour = task -> {
                MediaAccess access = task.runContext().mediaAccess().orElseThrow();
                secondTurn.add(access.resolve(produced.get(0).shortId()).isPresent() ? "found" : "missing");
                return ToolResult.success("probe", "ok");
            };
            run(agent, AgentTask.of("what did you crop?").withSessionId(session));
        }

        assertEquals(List.of("found"), secondTurn,
                "the history must say the session saw this image, with or without replay of tool calls");
    }

    @Test
    void anAttachmentOfAnEarlierTurnIsVisibleOnALaterOne() {
        MediaStore store = MediaStore.inMemory();
        MediaRef attached = store.put("contract.png", "image/png", bytes("contract"));
        Probe probe = new Probe();
        List<String> answers = new CopyOnWriteArrayList<>();
        probe.behaviour = task -> {
            answers.add(task.runContext().mediaAccess().orElseThrow().resolve(attached.shortId()).isPresent() ? "found" : "missing");
            return ToolResult.success("probe", "ok");
        };
        try (AraRuntime runtime = runtime(script(2), probe, store)) {
            io.ara.core.agent.AraAgent agent = runtime.createAgent(config(false));
            SessionId session = SessionId.of("s1");
            run(agent, AgentTask.of("here is a contract", List.of(attached)).withSessionId(session));
            run(agent, AgentTask.of("crop the signature").withSessionId(session));
        }

        assertEquals(List.of("found", "found"), answers);
    }

    @Test
    void aMediaOfAnotherSessionIsNotVisibleEvenThoughTheStoreHoldsIt() {
        MediaStore store = MediaStore.inMemory();
        MediaRef theirs = store.put("theirs.png", "image/png", bytes("theirs"));
        Probe probe = new Probe();
        List<String> answers = new CopyOnWriteArrayList<>();
        probe.behaviour = task -> {
            answers.add(task.runContext().mediaAccess().orElseThrow().resolve(theirs.shortId()).isPresent() ? "found" : "missing");
            return ToolResult.success("probe", "ok");
        };
        try (AraRuntime runtime = runtime(script(2), probe, store)) {
            io.ara.core.agent.AraAgent agent = runtime.createAgent(config(false));
            run(agent, AgentTask.of("theirs", List.of(theirs)).withSessionId(SessionId.of("other")));
            run(agent, AgentTask.of("mine").withSessionId(SessionId.of("mine")));
        }

        assertEquals(List.of("found", "missing"), answers);
    }

    @Test
    void aSubAgentSeesItsOwnSessionAndNotItsCallers() {
        // The view is replaced at every agent: the task below arrives carrying a caller's view, as a task
        // delegated from another agent does, and the tool must be given this session's instead.
        MediaStore store = MediaStore.inMemory();
        MediaRef callersOnly = store.put("callers.png", "image/png", bytes("callers"));
        SessionMediaAccess callersView = new SessionMediaAccess(store, List.of(callersOnly));
        Probe probe = new Probe();
        List<String> answers = new CopyOnWriteArrayList<>();
        probe.behaviour = task -> {
            MediaAccess access = task.runContext().mediaAccess().orElseThrow();
            assertNotSame(callersView, access);
            answers.add(access.resolve(callersOnly.shortId()).isPresent() ? "found" : "missing");
            return ToolResult.success("probe", "ok");
        };
        try (AraRuntime runtime = runtime(script(1), probe, store)) {
            run(runtime.createAgent(config(false)), AgentTask.of("delegated").withSessionId(SessionId.of("sub"))
                    .withAttachment(RunContext.MEDIA_ACCESS_KEY, callersView));
        }

        assertEquals(List.of("missing"), answers);
    }

    @Test
    void aRunResumedFromACheckpointSeesTheMediaInTheMemoryItWasSavedWith() {
        MediaStore store = MediaStore.inMemory();
        MediaRef crop = store.put("crop-1.png", "image/png", bytes("crop"));
        List<LlmMessage> memory = List.of(
                LlmMessage.user("crop the photo"),
                LlmMessage.assistantToolCall("call-1", "probe", "{}"),
                LlmMessage.tool("call-1", "probe", "Crop saved.", List.of(crop)));
        RunCheckpoint checkpoint = new RunCheckpoint(SessionId.of("s1"), "task-1", "crop the photo", 1,
                memory, List.of(), 0, 0, Money.zero("EUR"));
        Probe probe = new Probe();
        List<String> answers = new CopyOnWriteArrayList<>();
        probe.behaviour = task -> {
            answers.add(task.runContext().mediaAccess().orElseThrow().resolve(crop.shortId()).isPresent() ? "found" : "missing");
            return ToolResult.success("probe", "ok");
        };
        try (AraRuntime runtime = AraRuntime.builder().llmClient(script(1)).toolRegistry(registryOf(probe))
                .mediaStore(store).checkpointStore(CheckpointStore.inMemory()).build()) {
            run(runtime.createAgent(config(false)), AgentTask.resuming(checkpoint));
        }

        assertEquals(List.of("found"), answers,
                "the media this run had already been shown before it was suspended are still visible");
    }

    // ── What the history keeps ────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theHistoryKeepsTheCallsThatReturnedMediaWhetherOrNotTheAgentReplaysToolCalls(boolean replayToolCalls) {
        MediaStore store = MediaStore.inMemory();
        Probe probe = new Probe();
        probe.behaviour = task -> ToolResult.success("probe", "Crop saved.",
                List.of(task.runContext().mediaAccess().orElseThrow().put("crop-1.png", "image/png", bytes("crop"))));
        List<ConversationTurn> history;
        try (AraRuntime runtime = runtime(script(1), probe, store)) {
            io.ara.core.agent.AraAgent agent = runtime.createAgent(config(replayToolCalls));
            run(agent, AgentTask.of("crop it").withSessionId(SessionId.of("s1")));
            history = runtime.conversationHistory(AGENT, SessionId.of("s1"));
        }

        List<ToolExchange> calls = history.get(0).toolCalls();
        assertEquals(1, calls.size());
        assertEquals(1, calls.get(0).media().size());
    }

    @Test
    void aCallThatReturnedNoMediaIsNotKeptByAnAgentThatDoesNotReplayToolCalls() {
        // Keeping the media-bearing calls must not turn into keeping every call: an agent that did not ask
        // for its tool calls to be remembered still has none remembered.
        Probe probe = new Probe();
        List<ConversationTurn> history;
        try (AraRuntime runtime = runtime(script(1), probe, MediaStore.inMemory())) {
            run(runtime.createAgent(config(false)), AgentTask.of("go").withSessionId(SessionId.of("s1")));
            history = runtime.conversationHistory(AGENT, SessionId.of("s1"));
        }

        assertFalse(history.isEmpty());
        assertTrue(history.get(0).toolCalls().isEmpty());
    }

    @Test
    void aCallKeptOnlyForItsMediaIsNotReplayedToTheModel() {
        // The exchange is in the history so the session can say what it saw; replaying it is a separate
        // decision, taken by the agent's own setting, and this agent has not asked.
        MediaStore store = MediaStore.inMemory();
        Probe probe = new Probe();
        probe.behaviour = task -> ToolResult.success("probe", "Crop saved.",
                List.of(task.runContext().mediaAccess().orElseThrow().put("crop-1.png", "image/png", bytes("crop"))));
        List<List<LlmMessage>> requests = new CopyOnWriteArrayList<>();
        LlmClient recording = new LlmClient() {
            private final LlmClient script = script(2);
            @Override public io.ara.core.llm.LlmCompletion complete(List<LlmMessage> messages,
                                                                      io.ara.core.llm.LlmCallContext context) {
                requests.add(List.copyOf(messages));
                return script.complete(messages, context);
            }
            @Override public String providerId() { return "recording"; }
            @Override public boolean supportsNativeTools() { return true; }
        };
        try (AraRuntime runtime = runtime(recording, probe, store)) {
            io.ara.core.agent.AraAgent agent = runtime.createAgent(config(false));
            SessionId session = SessionId.of("s1");
            run(agent, AgentTask.of("crop it").withSessionId(session));
            run(agent, AgentTask.of("and now?").withSessionId(session));
        }

        List<LlmMessage> secondTurn = requests.get(requests.size() - 1);
        assertTrue(secondTurn.stream().noneMatch(m -> m.role().equals("tool") || m.role().equals("assistant_tool_call")),
                "nothing of the earlier call reaches the model: " + secondTurn);
    }
}
