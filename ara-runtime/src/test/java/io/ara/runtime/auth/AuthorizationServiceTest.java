package io.ara.runtime.auth;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.auth.AbacPolicyEngine;
import io.ara.core.auth.ActionAttributes;
import io.ara.core.auth.AuthorizationException;
import io.ara.core.auth.EnvironmentAttributes;
import io.ara.core.auth.PolicyEvaluationContext;
import io.ara.core.auth.ResourceAttributes;
import io.ara.core.auth.ScopeSet;
import io.ara.core.auth.SubjectAttributes;
import io.ara.core.common.AgentId;
import io.ara.runtime.auth.policy.ClearancePolicy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ADR-033 Fase 2b §2b.4 (`docs/adr/ADR-033-implementation-plan.md`, `ara-private`) —
 * {@link AuthorizationService}: scope check always runs, ABAC only when configured.
 */
class AuthorizationServiceTest {

    private static AraAgent agentRequiring(String requiredScope) {
        AgentId id = AgentId.of("target");
        AgentConfig config = AgentConfig.defaults().agentId(id).agentType("t")
                .requiredScopes(requiredScope == null ? List.of() : List.of(requiredScope))
                .build();
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

    /** {@link AuthorizationService} takes an {@link AbacPolicyEngine}, not a bare {@code AbacPolicy}. */
    private static AbacPolicyEngine engineOf(io.ara.core.auth.AbacPolicy policy) {
        return new AbacPoliciesBuilder().add("policy", policy).build();
    }

    private static PolicyEvaluationContext ctxWithClearance(String subjectClearance, String requiredClearance) {
        return new PolicyEvaluationContext(
                new SubjectAttributes("caller", "t", null, subjectClearance, ScopeSet.EMPTY),
                new ResourceAttributes("target", "t", null, requiredClearance, false),
                ActionAttributes.INVOKE,
                new EnvironmentAttributes(Instant.now(), 0, "req-1"),
                null);
    }

    @Test
    void abacDisabled_scopeCheckAloneGates_zeroBehaviorChange() {
        AuthorizationService service = new AuthorizationService(null);
        assertFalse(service.abacEnabled());

        AraAgent target = agentRequiring("finance:read");
        assertThrows(AuthorizationException.class,
                () -> service.authorize(ScopeSet.EMPTY, target, ctxWithClearance(null, null)));
        assertDoesNotThrow(
                () -> service.authorize(ScopeSet.of("finance:read"), target, ctxWithClearance(null, null)));
    }

    @Test
    void abacEnabled_scopeCheckStillRunsFirst_deniesBeforeAbacIsEvenConsulted() {
        AuthorizationService service = new AuthorizationService(engineOf(ClearancePolicy.standard()));

        AraAgent target = agentRequiring("finance:read");
        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> service.authorize(ScopeSet.EMPTY, target, ctxWithClearance("SECRET", null)));
        assertEquals(AuthorizationException.Reason.AGENT_NOT_AUTHORIZED, e.reason(),
                "the scope failure must surface, not an ABAC one — ABAC runs only after scopes pass");
    }

    @Test
    void abacEnabled_scopePassesButAbacDenies_throwsAbacPolicyDenied() {
        AuthorizationService service = new AuthorizationService(engineOf(ClearancePolicy.standard()));

        AraAgent target = agentRequiring(null);
        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> service.authorize(ScopeSet.EMPTY, target, ctxWithClearance("STANDARD", "CONFIDENTIAL")));
        assertEquals(AuthorizationException.Reason.ABAC_POLICY_DENIED, e.reason());
    }

    @Test
    void abacEnabled_scopePassesAndAbacPermits_proceeds() {
        AuthorizationService service = new AuthorizationService(engineOf(ClearancePolicy.standard()));

        AraAgent target = agentRequiring(null);
        assertDoesNotThrow(() -> service.authorize(ScopeSet.EMPTY, target,
                ctxWithClearance("CONFIDENTIAL", "CONFIDENTIAL")));
    }
}
