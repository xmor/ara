package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.ConversationTurn;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.ToolExchange;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two turns of the same session through the whole runtime: the first calls a tool, the second must see that call in
 * its history when the agent keeps tool calls, in the form its client speaks, and must not when it does not. This is
 * the case that motivated keeping them: a repeated device command answered "done" with no call, because the history
 * showed the first command only as a sentence.
 */
class AraRuntimeToolCallReplayTest {

    /** A scripted client that keeps every request it receives. */
    private static final class Recording implements LlmClient {
        final LlmClient delegate = ScriptedLlmClient.script()
                .thenToolCall("lights", "{\"on\":false}")
                .thenFinalAnswer("The light is off.")
                .thenFinalAnswer("Done.")
                .build();
        final List<List<LlmMessage>> requests = new CopyOnWriteArrayList<>();
        final boolean nativeTools;

        Recording(boolean nativeTools) {
            this.nativeTools = nativeTools;
        }

        @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            requests.add(List.copyOf(messages));
            return delegate.complete(messages, context);
        }
        @Override public String providerId() { return "recording"; }
        @Override public boolean supportsNativeTools() { return nativeTools; }
    }

    private static final AraTool LIGHTS = new AraTool() {
        @Override public String toolId() { return "lights"; }
        @Override public String description() { return "switches the light"; }
        @Override public String argumentSchema() { return "{\"type\":\"object\",\"properties\":{\"on\":{\"type\":\"boolean\"}}}"; }
        @Override public ToolResult execute(String argumentJson) { return ToolResult.success(toolId(), "switched off"); }
    };

    private static final ToolRegistry REGISTRY = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(LIGHTS); }
        @Override public Optional<AraTool> findById(String id) {
            return LIGHTS.toolId().equals(id) ? Optional.of(LIGHTS) : Optional.empty();
        }
        @Override public ToolResult execute(String id, String json) { return LIGHTS.execute(json); }
    };

    private record Run(Recording llm, List<ConversationTurn> history) {
        /** The messages of the last request, the one of the second turn. */
        List<LlmMessage> secondTurn() {
            return llm.requests.get(llm.requests.size() - 1);
        }
    }

    private static Run twoTurns(boolean keepToolCalls, boolean nativeTools) {
        Recording llm = new Recording(nativeTools);
        AraRuntime runtime = AraRuntime.builder().llmClient(llm).toolRegistry(REGISTRY).build();
        try {
            AgentId id = AgentId.of("home");
            AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                    .agentId(id).agentType("t").primaryLlm(LlmProfile.of("default")).plannerStrategy("react")
                    .enabledTools(List.of("lights")).maxIterations(5)
                    .maxConversationTurns(4).replayToolCalls(keepToolCalls)
                    .build());
            SessionId session = SessionId.of("s1");
            AgentResponse first = agent.execute(AgentTask.of("Switch the light off.").withSessionId(session));
            assertTrue(first.isSuccess(), () -> "first turn failed: " + first.failureReason());
            AgentResponse second = agent.execute(AgentTask.of("Switch it back on.").withSessionId(session));
            assertTrue(second.isSuccess(), () -> "second turn failed: " + second.failureReason());
            return new Run(llm, runtime.conversationHistory(id, session));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void theSecondTurnSeesTheFirstTurnsCallAsANativeCallAndResult() {
        Run run = twoTurns(true, true);

        List<LlmMessage> messages = run.secondTurn();
        LlmMessage call = messages.stream().filter(m -> m.role().equals("assistant_tool_call")).findFirst().orElseThrow();
        LlmMessage result = messages.stream().filter(m -> m.role().equals("tool")).findFirst().orElseThrow();
        assertEquals("lights", call.toolName());
        assertEquals("{\"on\":false}", call.content());
        assertEquals(call.toolCallId(), result.toolCallId(), "the result answers that call");
        assertEquals("switched off", result.content(), "the latest replayed turn keeps its result");
        assertTrue(messages.indexOf(call) < messages.indexOf(result));
    }

    @Test
    void theCallSitsBetweenTheQuestionAndTheAnswerOfItsTurn() {
        List<String> roles = twoTurns(true, true).secondTurn().stream().map(LlmMessage::role).toList();

        int call = roles.indexOf("assistant_tool_call");
        assertEquals("user", roles.get(call - 1), "after the question");
        assertEquals("tool", roles.get(call + 1));
        assertEquals("assistant", roles.get(call + 2), "before the answer");
    }

    @Test
    void forAClientWithoutNativeToolsTheCallIsReplayedInTheTextProtocol() {
        List<LlmMessage> messages = twoTurns(true, false).secondTurn();

        assertTrue(messages.stream().anyMatch(m -> m.role().equals("assistant")
                && m.content().contains("\"tool_id\":\"lights\"")), "the tool-call line");
        assertTrue(messages.stream().anyMatch(m -> m.role().equals("user")
                && m.content().equals("Observation: switched off")));
        assertTrue(messages.stream().noneMatch(m -> m.role().equals("assistant_tool_call")));
    }

    @Test
    void theCallIsStoredInTheSessionHistory() {
        List<ConversationTurn> history = twoTurns(true, true).history();

        assertEquals(List.of(new ToolExchange("lights", "{\"on\":false}", "switched off")), history.get(0).toolCalls());
        assertTrue(history.get(1).toolCalls().isEmpty(), "the second turn called nothing");
    }

    @Test
    void anAgentThatDoesNotKeepToolCallsReplaysQuestionsAndAnswersAsBefore() {
        Run run = twoTurns(false, true);

        assertFalse(run.secondTurn().stream().anyMatch(m -> m.role().equals("assistant_tool_call")
                || m.role().equals("tool")));
        assertTrue(run.history().get(0).toolCalls().isEmpty(), "and stores none");
    }
}
