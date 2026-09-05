package io.ara.runtime.bus;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.auth.AuthorizationException;
import io.ara.core.bus.AgentMessage;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.runtime.agent.AgentRegistry;
import io.ara.runtime.hitl.InMemoryApprovalGate;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 7 (S4) end-to-end through the real {@link LocalMessageBus} dispatch path —
 * distinct from {@link io.ara.runtime.auth.ScopeVerifierApprovalTest}, which exercises
 * {@code ScopeVerifier.checkApproved} in isolation.
 */
class LocalMessageBusApprovalGateTest {

    private static AraAgent agentRequiringApproval(boolean requiresApproval, AtomicBoolean invoked) {
        AgentId id = AgentId.of("target");
        AgentConfig config = AgentConfig.defaults().agentId(id).agentType("t")
                .requiresApproval(requiresApproval).build();
        return new AraAgent() {
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return config; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                invoked.set(true);
                return AgentResponse.success(task.taskId(), id, "ok", 1, 0, 0.0, Duration.ZERO, List.of());
            }
            @Override public void terminate() {}
        };
    }

    @Test
    void request_blocksUntilApproved_thenInvokesTheRecipient() throws InterruptedException {
        AgentRegistry registry = new AgentRegistry();
        AtomicBoolean invoked = new AtomicBoolean();
        registry.register(agentRequiringApproval(true, invoked));
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        LocalMessageBus bus = new LocalMessageBus(registry, io.ara.core.telemetry.AraTelemetry.noop(), gate);

        Thread operator = new Thread(() -> {
            List<ApprovalRequest> pending = awaitPending(gate);
            gate.submit(pending.get(0).requestId(), new ApprovalDecision.Approved());
        });
        operator.start();

        assertDoesNotThrow(() -> bus.request(AgentMessage.of("caller", "target", "do it"), Duration.ofSeconds(5)));
        assertTrue(invoked.get());
        operator.join();
    }

    @Test
    void request_deniesOnRejection_andNeverInvokesTheRecipient() throws InterruptedException {
        AgentRegistry registry = new AgentRegistry();
        AtomicBoolean invoked = new AtomicBoolean();
        registry.register(agentRequiringApproval(true, invoked));
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        LocalMessageBus bus = new LocalMessageBus(registry, io.ara.core.telemetry.AraTelemetry.noop(), gate);

        Thread operator = new Thread(() -> {
            List<ApprovalRequest> pending = awaitPending(gate);
            gate.submit(pending.get(0).requestId(), new ApprovalDecision.Rejected("no"));
        });
        operator.start();

        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> bus.request(AgentMessage.of("caller", "target", "do it"), Duration.ofSeconds(5)));
        assertEquals(AuthorizationException.Reason.APPROVAL_REQUIRED, e.reason());
        assertFalse(invoked.get());
        operator.join();
    }

    @Test
    void request_withNoGateConfigured_ignoresTheAgentsRequiresApprovalFlag_zeroBehaviorChange() {
        AgentRegistry registry = new AgentRegistry();
        AtomicBoolean invoked = new AtomicBoolean();
        registry.register(agentRequiringApproval(true, invoked));
        // The two-arg constructor — no ApprovalGate — is the pre-Fase-7 shape.
        LocalMessageBus bus = new LocalMessageBus(registry);

        assertDoesNotThrow(() -> bus.request(AgentMessage.of("caller", "target", "do it"), Duration.ofSeconds(5)));
        assertTrue(invoked.get());
    }

    @Test
    void request_deniesForMissingScopeBeforeEverCheckingApproval() {
        // checkAuthorized runs first: an agent that both requires a scope and requires
        // approval must not spend a human's attention on a caller that was never going
        // to pass the scope check anyway.
        AgentRegistry registry = new AgentRegistry();
        AtomicBoolean invoked = new AtomicBoolean();
        AgentId id = AgentId.of("target");
        AgentConfig config = AgentConfig.defaults().agentId(id).agentType("t")
                .requiredScopes(List.of("finance:write"))
                .requiresApproval(true)
                .build();
        registry.register(new AraAgent() {
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return config; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                invoked.set(true);
                return AgentResponse.success(task.taskId(), id, "ok", 1, 0, 0.0, Duration.ZERO, List.of());
            }
            @Override public void terminate() {}
        });
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        LocalMessageBus bus = new LocalMessageBus(registry, io.ara.core.telemetry.AraTelemetry.noop(), gate);

        assertThrows(AuthorizationException.class,
                () -> bus.request(AgentMessage.of("caller", "target", "do it"), Duration.ofSeconds(5)));
        assertFalse(invoked.get());
        assertTrue(gate.getPendingRequests().isEmpty(), "no approval request should ever be raised for a scope-denied caller");
    }

    private static List<ApprovalRequest> awaitPending(InMemoryApprovalGate gate) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<ApprovalRequest> pending = gate.getPendingRequests();
            if (!pending.isEmpty()) return pending;
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        throw new IllegalStateException("no pending approval request appeared in time");
    }
}
