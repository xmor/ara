package io.ara.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaStore;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.llm.DelegatingLlmClient;
import io.ara.runtime.stubs.ScriptedLlmClient;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

/** [V16] {@code view_media} through the whole runtime: a media of an earlier turn comes back, a stranger's does not. */
class ViewMediaRunTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    /** Records the messages of every model call, then answers from the script. */
    private static final class Recording extends DelegatingLlmClient {
        final List<List<LlmMessage>> calls = new CopyOnWriteArrayList<>();

        Recording(LlmClient script) { super(script); }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            calls.add(List.copyOf(messages));
            return super.complete(messages, context);
        }
    }

    private static final ToolRegistry NO_TOOLS = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(); }
        @Override public Optional<AraTool> findById(String id) { return Optional.empty(); }
        @Override public ToolResult execute(String id, String json) { return ToolResult.failure(id, "no such tool"); }
    };

    private static AgentConfig config(List<String> tools) {
        return AgentConfig.defaults().agentId(AgentId.of("a")).agentType("t").primaryLlm(LlmProfile.of("default"))
                .plannerStrategy("react").enabledTools(tools).maxIterations(5).maxConversationTurns(4).build();
    }

    /** The message carrying a tool's result; the text protocol sends it as a user turn, native calling as a tool message. */
    private static Optional<LlmMessage> observation(List<LlmMessage> call, String marker) {
        return call.stream().filter(m -> m.content() != null && m.content().contains(marker)
                && ("tool".equals(m.role()) || m.content().startsWith("Observation:"))).findFirst();
    }

    @Test
    void aMediaOfAnEarlierTurnIsShownAgainAndAStrangersIsRefused() {
        MediaStore store = MediaStore.inMemory();
        MediaRef upload = store.put("passport.png", "image/png", PNG);
        MediaRef stranger = store.put("someone-elses.png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 2}); // ids are content digests: other bytes, other id
        Recording llm = new Recording(ScriptedLlmClient.script()
                .thenFinalAnswer("a passport")
                .thenToolCall("view_media", "{\"media_id\":\"" + upload.shortId() + "\"}")
                .thenFinalAnswer("I see it")
                .thenToolCall("view_media", "{\"media_id\":\"" + stranger.shortId() + "\"}")
                .thenFinalAnswer("refused").build());
        try (AraRuntime runtime = AraRuntime.builder().llmClient(llm).toolRegistry(NO_TOOLS).mediaStore(store).build()) {
            var agent = runtime.createAgent(config(List.of("view_media")));
            SessionId session = SessionId.of("s1");

            assertTrue(agent.execute(AgentTask.of("what is this?", List.of(upload)).withSessionId(session)).isSuccess());

            AgentResponse second = agent.execute(AgentTask.of("show me that again").withSessionId(session));
            assertTrue(second.isSuccess(), () -> String.valueOf(second.failureReason()));
            LlmMessage shown = observation(llm.calls.get(2), "Showing passport.png").orElseThrow(() -> new AssertionError(llm.calls.get(2).toString()));
            assertEquals(List.of(upload), shown.media(), "a media of the previous turn comes back through the tool");

            AgentResponse third = agent.execute(AgentTask.of("and the other one?").withSessionId(session));
            assertTrue(third.isSuccess(), () -> String.valueOf(third.failureReason()));
            LlmMessage refused = observation(llm.calls.get(llm.calls.size() - 1), "No media with id").orElseThrow();
            assertFalse(refused.hasMedia(), "an id outside the session is refused, in words the model can act on");
            assertTrue(llm.calls.get(llm.calls.size() - 1).stream().noneMatch(m -> m.media().contains(stranger)));
        }
    }

    @Test
    void theToolIsOfferedOnlyWhenTheAgentListsItAndTheRuntimeHasAStore() {
        MediaStore store = MediaStore.inMemory();
        Recording withTool = new Recording(ScriptedLlmClient.script().thenFinalAnswer("ok").build());
        Recording withoutListing = new Recording(ScriptedLlmClient.script().thenFinalAnswer("ok").build());
        Recording noStore = new Recording(ScriptedLlmClient.script().thenFinalAnswer("ok").build());

        assertTrue(catalogOf(withTool, store, List.of("view_media")).contains("view_media"));
        assertFalse(catalogOf(withoutListing, store, List.of()).contains("view_media"),
                "an agent that never listed it keeps the prompt it always had");
        assertFalse(catalogOf(noStore, MediaStore.noop(), List.of("view_media")).contains("view_media"),
                "with no store there is nothing to show");
    }

    private static String catalogOf(Recording llm, MediaStore store, List<String> tools) {
        try (AraRuntime runtime = AraRuntime.builder().llmClient(llm).toolRegistry(NO_TOOLS).mediaStore(store).build()) {
            runtime.createAgent(config(tools)).execute(AgentTask.of("hi"));
        }
        return llm.calls.get(0).stream().map(LlmMessage::content).reduce("", (a, b) -> a + "\n" + b);
    }
}
