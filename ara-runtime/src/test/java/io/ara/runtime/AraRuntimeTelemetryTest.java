package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.LlmSelectionPolicy;
import io.ara.core.telemetry.SpanStatus;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.ScriptedLlmClient;
import io.ara.runtime.telemetry.RecordingTelemetry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the OpenTelemetry wiring added to {@link AraRuntime}: {@code llm.complete}
 * (via {@code InstrumentedLlmClient}), {@code agent.execute} (via {@code AgentInstance}),
 * and {@code tool.execute} (via {@code TelemetryToolRegistry}).
 */
class AraRuntimeTelemetryTest {

    private static ToolRegistry singleTool(AraTool tool) {
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(tool); }
            @Override public Optional<AraTool> findById(String id) {
                return tool.toolId().equals(id) ? Optional.of(tool) : Optional.empty();
            }
            @Override public ToolResult execute(String id, String json) {
                return tool.toolId().equals(id) ? tool.execute(json) : ToolResult.failure(id, "not found");
            }
        };
    }

    private static final AraTool NOOP_TOOL = new AraTool() {
        @Override public String toolId() { return "noop"; }
        @Override public String description() { return "does nothing"; }
        @Override public String argumentSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public ToolResult execute(String argumentJson) { return ToolResult.success(toolId(), "ok"); }
    };

    @Test
    void noopTelemetry_emitsNoSpans() {
        RecordingTelemetry telemetry = new RecordingTelemetry();   // used only to prove noop never touches it
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("done").build())
                .build();   // telemetry NOT set -> defaults to noop

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentType("t").primaryLlm(LlmProfile.of("default")).plannerStrategy("react").build());
        agent.execute(AgentTask.of("hi"));

        assertTrue(telemetry.spans().isEmpty(), "an unrelated RecordingTelemetry must stay empty when not wired in");
    }

    @Test
    void agentExecute_emitsSpanWithSuccessAttributeAndStatus() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("done").build())
                .telemetry(telemetry)
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("buddy"))
                .agentType("t").primaryLlm(LlmProfile.of("default")).plannerStrategy("react").build());
        AgentResponse response = agent.execute(AgentTask.of("hi"));

        assertTrue(response.isSuccess());
        List<RecordingTelemetry.RecordedSpan> agentSpans = telemetry.spansNamed("agent.execute");
        assertEquals(1, agentSpans.size());
        RecordingTelemetry.RecordedSpan span = agentSpans.get(0);
        assertEquals("buddy", span.attributes().get("agent.id"));
        assertEquals(Boolean.TRUE, span.attributes().get("agent.success"));
        assertEquals(SpanStatus.OK, span.status());
    }

    @Test
    void agentExecute_failureSetsErrorStatus() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        .then(new io.ara.core.llm.LlmCompletion("stuck", 10, 10, "length", null))
                        .build())
                .telemetry(telemetry)
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentType("t").primaryLlm(LlmProfile.of("default")).plannerStrategy("react")
                .maxIterations(1).build());
        AgentResponse response = agent.execute(AgentTask.of("hi"));

        assertFalse(response.isSuccess());
        RecordingTelemetry.RecordedSpan span = telemetry.spansNamed("agent.execute").get(0);
        assertEquals(Boolean.FALSE, span.attributes().get("agent.success"));
        assertEquals(SpanStatus.ERROR, span.status());
        assertEquals("MAX_ITERATIONS", span.attributes().get("agent.failure_kind"),
                "hitting maxIterations without a final answer must classify as MAX_ITERATIONS");
    }

    @Test
    void agentExecute_recordsIterationsAndTokensTotal_onSuccess() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        .thenToolCall("noop", "{}")
                        .thenFinalAnswer("done")
                        .build())
                .telemetry(telemetry)
                .toolRegistry(singleTool(NOOP_TOOL))
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentType("t").primaryLlm(LlmProfile.of("default")).plannerStrategy("react")
                .enabledTools(List.of("noop")).maxIterations(5).build());
        AgentResponse response = agent.execute(AgentTask.of("hi"));

        RecordingTelemetry.RecordedSpan span = telemetry.spansNamed("agent.execute").get(0);
        assertEquals((long) response.iterationsUsed(), span.attributes().get("agent.iterations"));
        assertEquals((long) response.totalTokens(),    span.attributes().get("agent.tokens_total"));
        assertTrue(response.iterationsUsed() > 0);
        assertFalse(span.attributes().containsKey("agent.failure_kind"),
                "a successful execution must not carry a failure_kind attribute");
    }

    @Test
    void llmComplete_spanEmittedForEachLlmCall() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        .thenToolCall("noop", "{}")
                        .thenFinalAnswer("done")
                        .build())
                .telemetry(telemetry)
                .toolRegistry(singleTool(NOOP_TOOL))
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentType("t").primaryLlm(LlmProfile.of("default")).plannerStrategy("react")
                .enabledTools(List.of("noop")).maxIterations(5).build());
        agent.execute(AgentTask.of("hi"));

        // one llm.complete per ReAct iteration: tool-call round + final-answer round
        assertEquals(2, telemetry.spansNamed("llm.complete").size());
        assertTrue(telemetry.spansNamed("llm.complete").stream()
                .allMatch(s -> "scripted-stub".equals(s.attributes().get("llm.provider"))));
    }

    @Test
    void toolExecute_spanEmittedWithToolIdAndSuccess() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        .thenToolCall("noop", "{}")
                        .thenFinalAnswer("done")
                        .build())
                .telemetry(telemetry)
                .toolRegistry(singleTool(NOOP_TOOL))
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentType("t").primaryLlm(LlmProfile.of("default")).plannerStrategy("react")
                .enabledTools(List.of("noop")).maxIterations(5).build());
        agent.execute(AgentTask.of("hi"));

        List<RecordingTelemetry.RecordedSpan> toolSpans = telemetry.spansNamed("tool.execute");
        assertEquals(1, toolSpans.size());
        assertEquals("noop", toolSpans.get(0).attributes().get("tool.id"));
        assertEquals(Boolean.TRUE, toolSpans.get(0).attributes().get("tool.success"));
        assertEquals(SpanStatus.OK, toolSpans.get(0).status());
    }

    @Test
    void toolExecute_carriesNativeToolCallId_whenLlmProvidesOne() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        LlmCompletion nativeToolCall = new LlmCompletion(
                "calling noop", 10, 10, "tool_calls",
                """
                {"tool_id":"noop","arguments":{}}""",
                "call-abc123");
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        .then(nativeToolCall)
                        .thenFinalAnswer("done")
                        .build())
                .telemetry(telemetry)
                .toolRegistry(singleTool(NOOP_TOOL))
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentType("t").primaryLlm(LlmProfile.of("default")).plannerStrategy("react")
                .enabledTools(List.of("noop")).maxIterations(5).build());
        agent.execute(AgentTask.of("hi"));

        assertEquals("call-abc123",
                telemetry.spansNamed("tool.execute").get(0).attributes().get("tool.call_id"));
    }

    @Test
    void toolExecute_omitsToolCallId_whenNoneWasProvided() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        .thenToolCall("noop", "{}")   // no native toolCallId
                        .thenFinalAnswer("done")
                        .build())
                .telemetry(telemetry)
                .toolRegistry(singleTool(NOOP_TOOL))
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentType("t").primaryLlm(LlmProfile.of("default")).plannerStrategy("react")
                .enabledTools(List.of("noop")).maxIterations(5).build());
        agent.execute(AgentTask.of("hi"));

        assertFalse(telemetry.spansNamed("tool.execute").get(0).attributes().containsKey("tool.call_id"));
    }

    @Test
    void sessionId_isTaggedOnAllThreeSpanKinds_whenTaskCarriesASession() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        .thenToolCall("noop", "{}")
                        .thenFinalAnswer("done")
                        .build())
                .telemetry(telemetry)
                .toolRegistry(singleTool(NOOP_TOOL))
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentType("t").primaryLlm(LlmProfile.of("default")).plannerStrategy("react")
                .enabledTools(List.of("noop")).maxIterations(5).build());
        agent.execute(AgentTask.of("hi").withSessionId(
                io.ara.core.agent.SessionId.of("sess-telemetry")));

        assertEquals("sess-telemetry",
                telemetry.spansNamed("agent.execute").get(0).attributes().get("session.id"));
        assertEquals("sess-telemetry",
                telemetry.spansNamed("tool.execute").get(0).attributes().get("session.id"));
        telemetry.spansNamed("llm.complete").forEach(span ->
                assertEquals("sess-telemetry", span.attributes().get("session.id"),
                        "every llm.complete span for this task must carry the task's session id"));
    }

    @Test
    void toolExecute_carriesTaskId_matchingTheParentAgentExecuteSpan() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        .thenToolCall("noop", "{}")
                        .thenFinalAnswer("done")
                        .build())
                .telemetry(telemetry)
                .toolRegistry(singleTool(NOOP_TOOL))
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentType("t").primaryLlm(LlmProfile.of("default")).plannerStrategy("react")
                .enabledTools(List.of("noop")).maxIterations(5).build());
        AgentTask task = AgentTask.of("hi");
        agent.execute(task);

        Object agentSpanTaskId = telemetry.spansNamed("agent.execute").get(0).attributes().get("task.id");
        Object toolSpanTaskId  = telemetry.spansNamed("tool.execute").get(0).attributes().get("task.id");
        assertEquals(task.taskId(), agentSpanTaskId);
        assertEquals(task.taskId(), toolSpanTaskId,
                "tool.execute must carry the same task.id as its parent agent.execute span, "
                + "so the two can be correlated without relying on the span tree alone");
    }

    /**
     * Regression test: {@code AgentResponse.llmProvider()} must reflect the client
     * that actually served the request when routed through {@link LlmSelectionPolicy#FAILOVER},
     * not just the configured primary alias. Previously {@code AgentInstance} read
     * {@code lastUsedProviderId()} off a field that stays {@code null} whenever an
     * {@code LlmRouter} is in play (the only path {@code AgentFactory} ever wires),
     * so the failover outcome was silently discarded.
     */
    @Test
    void agentExecute_llmProviderReflectsFailoverOutcome_notJustTheAlias() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("primary", new AlwaysFailingLlmClient())
                .llmClient("backup", ScriptedLlmClient.script().thenFinalAnswer("done").build())
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentType("t")
                .primaryLlm(LlmProfile.of("primary"))
                .fallbackLlm(LlmProfile.of("backup"))
                .llmSelectionPolicy(LlmSelectionPolicy.FAILOVER)
                .plannerStrategy("react")
                .build());

        AgentResponse response = agent.execute(AgentTask.of("hi"));

        assertTrue(response.isSuccess());
        assertEquals("primary/scripted-stub", response.llmProvider(),
                "llmProvider must combine the configured alias with the client that actually "
                + "answered (via FailoverLlmClient.lastUsedProviderId()), not degrade to the alias alone");
    }

    /** Always throws a retryable {@link LlmException} — used to force failover to the next client. */
    private static final class AlwaysFailingLlmClient implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            throw LlmException.rateLimit("primary", "simulated rate limit");
        }
        @Override public String providerId() { return "primary"; }
    }

    /**
     * ADR-0078 D5 / this session's wiring pass: {@code AraRuntime.Builder.defaultMemoryManager}
     * is the one production call site that already constructs a real
     * {@code SlidingWindowMemoryManager} (ADR-0086, for any agent with a positive
     * {@code workingMemoryTokenBudget}) — it now forwards the runtime's own {@code telemetry}
     * instead of leaving that constructor argument to default to {@code noop()}. Same
     * eviction-forcing recipe as {@code AraRuntimeMemoryWiringTest.positiveBudget_...}, plus a
     * real {@code AraTelemetry} to prove the span reaches it end-to-end through the runtime,
     * not just when {@code SlidingWindowMemoryManager} is constructed directly in isolation.
     */
    @Test
    void defaultMemoryManager_forwardsTheRuntimesTelemetry_evictionSpanIsRecorded() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("reply padding padding padding").build())
                .telemetry(telemetry)
                .build();
        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentType("t").primaryLlm(LlmProfile.of("default")).plannerStrategy("react")
                .maxConversationTurns(20)
                .workingMemoryTokenBudget(30)
                .workingMemoryEviction("drop_oldest")
                .build());
        io.ara.core.agent.SessionId session = io.ara.core.agent.SessionId.of("s1");

        for (int i = 0; i < 10; i++) {
            AgentResponse r = agent.execute(AgentTask.of("message " + i + " padding padding padding")
                    .withSessionId(session));
            assertTrue(r.isSuccess(), () -> "execute failed: " + r.failureReason());
        }

        List<RecordingTelemetry.RecordedSpan> evictSpans = telemetry.spansNamed("memory.evict");
        assertFalse(evictSpans.isEmpty(),
                "a bounded working-memory budget must evict over 10 turns, and every eviction must "
                + "now reach the runtime's real AraTelemetry instead of the noop() default");
        assertEquals("DROP_OLDEST", evictSpans.get(0).attributes().get("policy"));
    }
}
