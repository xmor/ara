package io.ara.runtime.hitl;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the opt-in matrix that decides whether {@link ApprovalToolRegistry} is
 * inserted into an agent's registry chain at all.
 *
 * <p>Approval is gated on <em>two</em> independent switches — a gate configured on the
 * runtime, and {@code humanApprovalRequired} on the agent — so there are four
 * combinations and only one of them gates. The two mixed cases are the interesting
 * ones: each is a plausible misconfiguration, and both must fail open quietly rather
 * than throwing, because neither is an error.
 */
class AraRuntimeApprovalGateTest {

    /** Approves everything and counts, so "was the gate consulted" is assertable. */
    private static final class CountingGate implements ApprovalGate {
        final List<ApprovalRequest> seen = new CopyOnWriteArrayList<>();

        @Override public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
            seen.add(request);
            return CompletableFuture.completedFuture(new ApprovalDecision.Approved());
        }

        @Override public void submit(String requestId, ApprovalDecision decision) {
            throw new UnsupportedOperationException();
        }

        @Override public List<ApprovalRequest> getPendingRequests() { return List.copyOf(seen); }
    }

    private static final AraTool ECHO = new AraTool() {
        @Override public String toolId() { return "echo"; }
        @Override public String description() { return "echoes"; }
        @Override public String argumentSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public ToolResult execute(String argumentJson) { return ToolResult.success(toolId(), "ok"); }
    };

    private static ToolRegistry echoRegistry() {
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(ECHO); }
            @Override public Optional<AraTool> findById(String id) {
                return ECHO.toolId().equals(id) ? Optional.of(ECHO) : Optional.empty();
            }
            @Override public ToolResult execute(String toolId, String argumentJson) {
                return ECHO.execute(argumentJson);
            }
        };
    }

    private static ScriptedLlmClient oneToolCallThenDone() {
        return ScriptedLlmClient.script()
                .thenToolCall("echo", "{}")
                .thenFinalAnswer("done")
                .build();
    }

    /** Runs one agent that makes exactly one tool call, and reports what the gate saw. */
    private static List<ApprovalRequest> runOnce(ApprovalGate gate, boolean humanApprovalRequired) {
        CountingGate counting = gate instanceof CountingGate c ? c : null;

        AraRuntime.Builder builder = AraRuntime.builder()
                .llmClient(oneToolCallThenDone())
                .toolRegistry(echoRegistry());
        if (gate != null) builder.approvalGate(gate);

        try (AraRuntime runtime = builder.build()) {
            AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                    .agentId(AgentId.of("gated-agent"))
                    .agentType("t")
                    .primaryLlm(LlmProfile.of("stub"))
                    .plannerStrategy("react")
                    .enabledTools(List.of("echo"))
                    .humanApprovalRequired(humanApprovalRequired)
                    .maxIterations(5)
                    .build());

            AgentResponse response = agent.execute(AgentTask.of("hi"));
            assertTrue(response.isSuccess(), response.failureReason());
        }
        return counting == null ? List.of() : counting.seen;
    }

    @Test
    void gateConfiguredAndAgentOptedIn_everyToolCallIsApproved() {
        CountingGate gate = new CountingGate();
        List<ApprovalRequest> seen = runOnce(gate, true);

        assertEquals(1, seen.size(), "the single tool call must have been submitted for approval");
        assertEquals("echo", seen.getFirst().action());
        assertEquals("gated-agent", seen.getFirst().agentId());
    }

    @Test
    void gateConfiguredButAgentNotOptedIn_isNotGated() {
        CountingGate gate = new CountingGate();
        assertTrue(runOnce(gate, false).isEmpty(),
                "a gate on the runtime must not gate agents that did not ask for it");
    }

    @Test
    void agentOptedInButNoGateConfigured_runsUngatedInsteadOfFailing() {
        // The plausible misconfiguration: humanApprovalRequired(true) with no gate wired.
        // It must not throw and must not silently block — the run completes ungated.
        assertTrue(runOnce(null, true).isEmpty());
    }

    @Test
    void neitherSwitchSet_isNotGated() {
        assertTrue(runOnce(null, false).isEmpty());
    }

    // ── the accessor external surfaces resolve approvals through ──────────────

    @Test
    void approvalGateAccessor_isNullByDefaultAndReturnsWhatWasConfigured() {
        try (AraRuntime plain = AraRuntime.builder().llmClient(oneToolCallThenDone()).build()) {
            assertNull(plain.approvalGate(), "no gate unless one is configured");
        }

        CountingGate gate = new CountingGate();
        try (AraRuntime wired = AraRuntime.builder()
                .llmClient(oneToolCallThenDone())
                .approvalGate(gate)
                .build()) {
            assertSame(gate, wired.approvalGate(),
                    "an HTTP surface resolves pending approvals through this exact instance");
        }
    }
}
