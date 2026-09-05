package io.ara.runtime.auth;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.auth.AuthorizationException;
import io.ara.core.auth.ScopeSet;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.runtime.hitl.InMemoryApprovalGate;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ADR-033 Fase 7 (S4, `docs/adr/ADR-033-implementation-plan.md`, `ara-private`) —
 * {@link ScopeVerifier#checkApproved}. Reuses the existing ADR-048 {@link ApprovalGate}
 * rather than a parallel mechanism — see the method's own javadoc for why.
 */
class ScopeVerifierApprovalTest {

    private static AraAgent agentRequiringApproval(boolean requiresApproval) {
        AgentId id = AgentId.of("target");
        AgentConfig config = AgentConfig.defaults().agentId(id).agentType("t")
                .requiresApproval(requiresApproval).build();
        return new AraAgent() {
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return config; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.success(task.taskId(), id, "ok", 1, 0, 0.0, Duration.ZERO, List.of());
            }
            @Override public void terminate() {}
        };
    }

    @Test
    void noGateConfigured_isANoOp_evenIfTheAgentRequiresApproval() {
        AraAgent agent = agentRequiringApproval(true);
        assertDoesNotThrow(() -> ScopeVerifier.checkApproved(agent, null, "caller", ScopeSet.EMPTY));
    }

    @Test
    void agentDoesNotRequireApproval_isANoOp_evenWithAGateConfigured() {
        AraAgent agent = agentRequiringApproval(false);
        ApprovalGate gate = new InMemoryApprovalGate();
        assertDoesNotThrow(() -> ScopeVerifier.checkApproved(agent, gate, "caller", ScopeSet.EMPTY));
    }

    @Test
    void approved_letsTheCallProceed() throws InterruptedException {
        AraAgent agent = agentRequiringApproval(true);
        InMemoryApprovalGate gate = new InMemoryApprovalGate();

        Thread operator = approveFirstPendingAfter(gate, Duration.ofMillis(50));
        operator.start();

        assertDoesNotThrow(() -> ScopeVerifier.checkApproved(agent, gate, "caller", ScopeSet.of("ops")));
        operator.join();
    }

    @Test
    void modified_alsoLetsTheCallProceed_thisIsAnAuthorizationGateNotAPayloadRewrite() throws InterruptedException {
        AraAgent agent = agentRequiringApproval(true);
        InMemoryApprovalGate gate = new InMemoryApprovalGate();

        Thread operator = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            List<ApprovalRequest> pending = gate.getPendingRequests();
            if (!pending.isEmpty()) {
                gate.submit(pending.get(0).requestId(), new ApprovalDecision.Modified("irrelevant-here"));
            }
        });
        operator.start();

        assertDoesNotThrow(() -> ScopeVerifier.checkApproved(agent, gate, "caller", ScopeSet.of("ops")));
        operator.join();
    }

    @Test
    void rejected_throwsApprovalRequired() throws InterruptedException {
        AraAgent agent = agentRequiringApproval(true);
        InMemoryApprovalGate gate = new InMemoryApprovalGate();

        Thread operator = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            List<ApprovalRequest> pending = gate.getPendingRequests();
            if (!pending.isEmpty()) {
                gate.submit(pending.get(0).requestId(), new ApprovalDecision.Rejected("not today"));
            }
        });
        operator.start();

        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> ScopeVerifier.checkApproved(agent, gate, "caller", ScopeSet.of("ops")));
        assertEquals(AuthorizationException.Reason.APPROVAL_REQUIRED, e.reason());
        operator.join();
    }

    @Test
    void gateFailure_throwsApprovalRequired_failsClosed() {
        AraAgent agent = agentRequiringApproval(true);
        ApprovalGate failingGate = new ApprovalGate() {
            @Override public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
                return CompletableFuture.failedFuture(new RuntimeException("gate is down"));
            }
            @Override public void submit(String requestId, ApprovalDecision decision) {
                throw new UnsupportedOperationException();
            }
            @Override public List<ApprovalRequest> getPendingRequests() { return List.of(); }
        };

        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> ScopeVerifier.checkApproved(agent, failingGate, "caller", ScopeSet.of("ops")));
        assertEquals(AuthorizationException.Reason.APPROVAL_REQUIRED, e.reason());
    }

    private static Thread approveFirstPendingAfter(InMemoryApprovalGate gate, Duration delay) {
        return new Thread(() -> {
            try { Thread.sleep(delay.toMillis()); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            List<ApprovalRequest> pending = gate.getPendingRequests();
            if (!pending.isEmpty()) {
                gate.submit(pending.get(0).requestId(), new ApprovalDecision.Approved());
            }
        });
    }
}
