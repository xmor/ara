package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.auth.ExecutionContext;
import io.ara.core.auth.ScopeSet;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.ScriptedLlmClient;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 6 §6.1 (`docs/adr/ADR-033-implementation-plan.md`, `ara-private`) —
 * {@link AraRuntime#executeOnBehalfOf}: Delegation, not Impersonation. The agent's own
 * {@code grantedScopes} act as a ceiling the user's claimed scopes can never exceed, and
 * vice versa — exactly {@link ExecutionContext#effectiveScopes()}'s intersection rule,
 * exercised here through a real agent and real tool dispatch via the real entry point.
 *
 * <p>The tool under test records the {@link ExecutionContext} it sees on a side channel
 * ({@code AtomicReference}) rather than returning it as the agent's final answer: {@link
 * ScriptedLlmClient}'s scripted FINAL_ANSWER step is a fixed string, not a read of the
 * tool's observation, so {@code AgentResponse.content()} can't be used to inspect it here.
 */
class AraRuntimeExecuteOnBehalfOfTest {

    private static AraTool reportingTool(AtomicReference<ExecutionContext> seen) {
        return new AraTool() {
            @Override public String toolId() { return "report_context"; }
            @Override public String description() { return "Reports the caller's current ExecutionContext."; }
            @Override public String argumentSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            @Override public ToolResult execute(String argumentJson) {
                return ToolResult.failure(toolId(), "no task attached");
            }
            @Override public ToolResult execute(String argumentJson, AgentTask task) {
                seen.set(task.executionContext().orElse(null));
                return ToolResult.success(toolId(), "reported");
            }
        };
    }

    /** Task-forwarding registry — the ToolRegistry default degrades and drops the task. */
    private static ToolRegistry taskForwardingRegistry(AraTool tool) {
        Map<String, AraTool> byId = new LinkedHashMap<>();
        byId.put(tool.toolId(), tool);
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.copyOf(byId.values()); }
            @Override public Optional<AraTool> findById(String id) { return Optional.ofNullable(byId.get(id)); }
            @Override public ToolResult execute(String id, String json) {
                return byId.get(id).execute(json);
            }
            @Override public ToolResult execute(String id, String json, AgentTask task) {
                return byId.get(id).execute(json, task);
            }
        };
    }

    private record Fixture(AraRuntime runtime, AraAgent agent) {}

    private static Fixture buildAgent(List<String> grantedScopes, AtomicReference<ExecutionContext> seen) {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("model", ScriptedLlmClient.script()
                        .thenToolCall("report_context", "{}")
                        .thenFinalAnswer("done")
                        .build())
                .toolRegistry(taskForwardingRegistry(reportingTool(seen)))
                .build();
        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("finance-agent"))
                .systemPrompt("Report your current authorization context.")
                .primaryLlm(LlmProfile.of("model"))
                .plannerStrategy("react")
                .enabledTools(List.of("report_context"))
                .grantedScopes(grantedScopes)
                .maxIterations(4)
                .build());
        return new Fixture(runtime, agent);
    }

    @Test
    void effectiveScopes_isIntersectionOfAgentCeilingAndUserScopes() {
        AtomicReference<ExecutionContext> seen = new AtomicReference<>();
        Fixture fx = buildAgent(List.of("finance:read", "finance:write"), seen);

        AgentResponse response = fx.runtime().executeOnBehalfOf(
                fx.agent(), AgentTask.of("go"), "user-1", ScopeSet.of("finance:read"));

        assertTrue(response.isSuccess(), response.failureReason());
        assertEquals(ScopeSet.of("finance:read"), seen.get().effectiveScopes(),
                "the agent's own ceiling includes finance:write, but user-1 never claimed it");
    }

    @Test
    void agentCeiling_boundsAUserClaimingMoreThanTheAgentItselfHolds() {
        AtomicReference<ExecutionContext> seen = new AtomicReference<>();
        // The agent's own role never included finance:write, regardless of what the user claims.
        Fixture fx = buildAgent(List.of("finance:read"), seen);

        AgentResponse response = fx.runtime().executeOnBehalfOf(
                fx.agent(), AgentTask.of("go"), "user-1", ScopeSet.of("finance:read", "finance:write"));

        assertTrue(response.isSuccess(), response.failureReason());
        assertEquals(ScopeSet.of("finance:read"), seen.get().effectiveScopes(),
                "the agent cannot forward authority it was never itself granted, no matter what the user holds");
    }

    @Test
    void twoDifferentUsers_onTheSameAgentRole_getIndependentlyAttenuatedGrants() {
        // Two separate agent instances of the same role/ceiling — not one instance handling
        // two calls in a row, which ScriptedLlmClient's single-use script can't do anyway
        // (each executeOnBehalfOf here drives one full ReAct run, one tool call + one final
        // answer; a second run on the same script would fall through to its fallback
        // completion and never dispatch the tool a second time).
        AtomicReference<ExecutionContext> seenByReader = new AtomicReference<>();
        Fixture reader = buildAgent(List.of("finance:read", "finance:write"), seenByReader);
        reader.runtime().executeOnBehalfOf(reader.agent(), AgentTask.of("go"), "reader", ScopeSet.of("finance:read"));
        assertEquals(ScopeSet.of("finance:read"), seenByReader.get().effectiveScopes());
        assertEquals("reader", seenByReader.get().subjectId());

        AtomicReference<ExecutionContext> seenByWriter = new AtomicReference<>();
        Fixture writer = buildAgent(List.of("finance:read", "finance:write"), seenByWriter);
        writer.runtime().executeOnBehalfOf(writer.agent(), AgentTask.of("go"), "writer",
                ScopeSet.of("finance:read", "finance:write"));
        assertEquals(ScopeSet.of("finance:read", "finance:write"), seenByWriter.get().effectiveScopes());
        assertEquals("writer", seenByWriter.get().subjectId());
    }

    @Test
    void plainExecute_withoutExecuteOnBehalfOf_carriesNoExecutionContext_zeroBehaviorChange() {
        AtomicReference<ExecutionContext> seen = new AtomicReference<>();
        Fixture fx = buildAgent(List.of("finance:read"), seen);

        AgentResponse response = fx.agent().execute(AgentTask.of("go"));

        assertTrue(response.isSuccess(), response.failureReason());
        assertNull(seen.get(), "a task never routed through executeOnBehalfOf carries no ExecutionContext");
    }
}
