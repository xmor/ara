package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.CheckpointStore;
import io.ara.core.agent.RunCheckpoint;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.StepType;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.media.MediaRef;
import io.ara.core.common.Money;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-048 D2: a ReAct run is snapshotted at every iteration boundary, and the snapshot is
 * removed once the run has ended — a finished run has nothing left to resume.
 */
class RunCheckpointingTest {

    private static final AraTool ECHO = new AraTool() {
        @Override public String toolId() { return "echo"; }
        @Override public String description() { return "echo"; }
        @Override public String argumentSchema() { return "{}"; }
        @Override public ToolResult execute(String argumentJson) { return ToolResult.success("echo", "ECHO"); }
    };

    private static final ToolRegistry REGISTRY = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) { return ids.contains("echo") ? List.of(ECHO) : List.of(); }
        @Override public Optional<AraTool> findById(String id) { return "echo".equals(id) ? Optional.of(ECHO) : Optional.empty(); }
        @Override public ToolResult execute(String id, String json) { return ECHO.execute(json); }
    };

    /** A store that behaves as the in-memory one and also remembers every checkpoint it was given. */
    private static final class RecordingStore implements CheckpointStore {
        final CheckpointStore inner = CheckpointStore.inMemory();
        final List<RunCheckpoint> saved = new CopyOnWriteArrayList<>();

        @Override public void save(RunCheckpoint checkpoint) { saved.add(checkpoint); inner.save(checkpoint); }
        @Override public Optional<RunCheckpoint> loadLatest(SessionId sessionId, String taskId) { return inner.loadLatest(sessionId, taskId); }
        @Override public void delete(SessionId sessionId, String taskId) { inner.delete(sessionId, taskId); }
        @Override public List<RunCheckpoint> awaitingApproval() { return inner.awaitingApproval(); }
    }

    private static AgentResponse run(CheckpointStore store, AgentTask task, LlmClient llm) {
        return run(store, task, llm, "react");
    }

    private static AgentResponse run(CheckpointStore store, AgentTask task, LlmClient llm, String strategy) {
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient(llm)
                .toolRegistry(REGISTRY)
                .checkpointStore(store)
                .build()) {
            return runtime.createAgent(AgentConfig.defaults().agentType("t").primaryLlm(LlmProfile.of("default"))
                    .plannerStrategy(strategy).enabledTools(List.of("echo")).build()).execute(task);
        }
    }

    /** Answers from a script and remembers the messages of its last request. */
    private static final class RecordingClient implements LlmClient {
        private final LlmClient script = ScriptedLlmClient.script().thenFinalAnswer("resumed").build();
        volatile List<LlmMessage> lastMessages = List.of();

        @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            lastMessages = List.copyOf(messages);
            return script.complete(messages, context);
        }
        @Override public String providerId() { return "recording-test"; }
    }

    private static RunCheckpoint checkpointOfAToolCallRun() {
        RecordingStore store = new RecordingStore();
        run(store, AgentTask.of("say hello"), toolCallThenAnswer());
        return store.saved.get(0);
    }

    private static ScriptedLlmClient toolCallThenAnswer() {
        return ScriptedLlmClient.script()
                .thenToolCall("echo", "{\"text\":\"hello\"}")
                .thenFinalAnswer("done")
                .build();
    }

    @Test
    void theIterationThatCalledATool_isCheckpointedWithItsMemoryAndSteps() {
        RecordingStore store = new RecordingStore();

        AgentResponse response = run(store, AgentTask.of("say hello"), toolCallThenAnswer());

        assertTrue(response.isSuccess(), "the run itself must work: " + response.failureReason());
        assertEquals(1, store.saved.size(),
                "the first iteration ends with a tool call and loops again; the final one returns and is not a boundary");
        RunCheckpoint checkpoint = store.saved.get(0);
        assertEquals(1, checkpoint.iteration());
        assertTrue(checkpoint.workingMemory().stream().anyMatch(m -> m.content().contains("ECHO")),
                "the tool observation must be in the saved working memory: " + checkpoint.workingMemory());
        assertTrue(checkpoint.steps().stream().anyMatch(s -> s.type() == StepType.TOOL_CALL && "echo".equals(s.toolId())));
    }

    @Test
    void whenTheRunEnds_itsCheckpointIsRemoved() {
        RecordingStore store = new RecordingStore();

        run(store, AgentTask.of("say hello"), toolCallThenAnswer());

        RunCheckpoint saved = store.saved.get(0);
        assertTrue(store.loadLatest(saved.sessionId(), saved.taskId()).isEmpty(),
                "a finished run has nothing to resume");
    }

    @Test
    void aStoreThatFails_doesNotFailTheRun() {
        CheckpointStore failing = new CheckpointStore() {
            @Override public void save(RunCheckpoint checkpoint) { throw new IllegalStateException("disk full"); }
            @Override public Optional<RunCheckpoint> loadLatest(SessionId sessionId, String taskId) { return Optional.empty(); }
            @Override public void delete(SessionId sessionId, String taskId) { throw new IllegalStateException("disk full"); }
            @Override public List<RunCheckpoint> awaitingApproval() { return List.of(); }
        };

        AgentResponse response = run(failing, AgentTask.of("say hello"), toolCallThenAnswer());

        assertTrue(response.isSuccess(), "the checkpoint is a safety net, not a dependency: " + response.failureReason());
        assertEquals("done", response.content());
    }

    @Test
    void aResumedRun_continuesFromTheCheckpoint_insteadOfStartingOver() {
        RunCheckpoint checkpoint = checkpointOfAToolCallRun();
        RecordingClient llm = new RecordingClient();

        AgentResponse response = run(CheckpointStore.inMemory(), AgentTask.resuming(checkpoint), llm);

        assertTrue(response.isSuccess(), "the resumed run must work: " + response.failureReason());
        assertEquals("resumed", response.content());
        assertEquals(checkpoint.taskId(), response.taskId(), "a resumed run is the same task");
        assertEquals(checkpoint.iteration() + 1, response.iterationsUsed(),
                "the iteration count carries over: one done, one more to finish");
        assertTrue(response.inputTokens() > checkpoint.promptTokens(),
                "the tokens already spent stay in the total");
        assertTrue(llm.lastMessages.stream().anyMatch(m -> m.content().contains("say hello")),
                "the model must see the original request: " + llm.lastMessages);
        assertTrue(llm.lastMessages.stream().anyMatch(m -> m.content().contains("ECHO")),
                "the model must see the tool result already obtained: " + llm.lastMessages);
    }

    /**
     * The restore used to rebuild each entry by hand and lost the media of any entry that carried
     * tool-call metadata. Nothing produced such an entry until a tool could return an image, so
     * the loss was latent; this fixes it in place before that day.
     */
    @Test
    void aResumedRun_keepsTheMediaOfAToolResultTheCheckpointCarries() {
        MediaRef crop = new MediaRef("3f9a1c2e7b4d", "image/png", "crop-1.png", 12L, null);
        List<LlmMessage> memory = List.of(
                LlmMessage.user("say hello"),
                LlmMessage.assistantToolCall("call-1", "echo", "{\"text\":\"hello\"}"),
                new LlmMessage("tool", "ECHO: hello", "call-1", "echo", List.of(crop)));
        RunCheckpoint checkpoint = new RunCheckpoint(SessionId.of("s1"), "task-1", "say hello", 1,
                memory, List.of(), 0, 0, Money.zero("EUR"));
        RecordingClient llm = new RecordingClient();

        AgentResponse response = run(CheckpointStore.inMemory(), AgentTask.resuming(checkpoint), llm);

        assertTrue(response.isSuccess(), "the resumed run must work: " + response.failureReason());
        LlmMessage toolResult = llm.lastMessages.stream()
                .filter(m -> "tool".equals(m.role())).findFirst().orElseThrow();
        assertEquals("call-1", toolResult.toolCallId(), "the call id still pairs the result with its call");
        assertEquals(List.of(crop), toolResult.media(), "the media of the tool result must survive a resume");
    }

    @Test
    void aStrategyThatCannotResume_failsTheRun_ratherThanStartingOver() {
        RunCheckpoint checkpoint = checkpointOfAToolCallRun();

        AgentResponse response = run(CheckpointStore.inMemory(), AgentTask.resuming(checkpoint),
                new RecordingClient(), "plan_execute");

        assertTrue(!response.isSuccess());
        assertTrue(response.failureReason().contains("cannot resume"), response.failureReason());
    }
}
