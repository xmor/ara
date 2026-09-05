package io.ara.runtime.auth;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.auth.AuthorizationException;
import io.ara.core.auth.ScopeSet;
import io.ara.core.common.AgentId;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 9 (S7, `docs/adr/ADR-033-implementation-plan.md` §9, `ara-private`) —
 * {@link TenantScopeHelper}. DONE-WHEN letterale del piano: "agente con scope
 * [\"acme:finance\"] non riesce ad accedere ad agente con requiredScopes=[\"other:finance\"]."
 *
 * <p>These pass through {@link ScopeVerifier} unmodified — the point of the ADR is
 * precisely that no new enforcement code exists for tenancy, only a naming convention.
 */
class TenantScopeHelperTest {

    private static AraAgent agentRequiring(ScopeSet requiredScopes) {
        AgentId id = AgentId.of("target");
        AgentConfig config = AgentConfig.defaults().agentId(id).agentType("t")
                .requiredScopes(List.copyOf(requiredScopes.scopes())).build();
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
    void forTenant_prefixesEveryScope() {
        ScopeSet scopes = TenantScopeHelper.forTenant("acme", "finance:read", "ops");
        assertEquals(ScopeSet.of("acme:finance:read", "acme:ops"), scopes);
    }

    @Test
    void global_prefixesWithGlobal() {
        assertEquals(ScopeSet.of("global:admin:audit"), TenantScopeHelper.global("admin:audit"));
    }

    @Test
    void forTenant_rejectsBlankTenantId() {
        assertThrows(IllegalArgumentException.class, () -> TenantScopeHelper.forTenant(""));
        assertThrows(IllegalArgumentException.class, () -> TenantScopeHelper.forTenant(null));
    }

    @Test
    void crossTenantScopes_neverIntersect_isolationIsAPropertyOfTheStrings() {
        ScopeSet acme  = TenantScopeHelper.forTenant("acme", "finance");
        ScopeSet other = TenantScopeHelper.forTenant("other", "finance");
        assertTrue(acme.intersect(other).isEmpty(), "same suffix, different tenant prefix — disjoint strings");
    }

    @Test
    void agentScopedToOneTenant_cannotInvokeAnAgentRequiringAnotherTenantsScope() {
        AraAgent target = agentRequiring(TenantScopeHelper.forTenant("other", "finance"));
        ScopeSet caller = TenantScopeHelper.forTenant("acme", "finance");

        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> ScopeVerifier.checkAuthorized(target, caller));
        assertEquals(AuthorizationException.Reason.AGENT_NOT_AUTHORIZED, e.reason());
    }

    @Test
    void agentScopedToTheSameTenant_canInvoke() {
        AraAgent target = agentRequiring(TenantScopeHelper.forTenant("acme", "finance"));
        ScopeSet caller = TenantScopeHelper.forTenant("acme", "finance");

        assertDoesNotThrow(() -> ScopeVerifier.checkAuthorized(target, caller));
    }

    @Test
    void globalScope_worksAcrossAnyTenantsCaller() {
        AraAgent target = agentRequiring(TenantScopeHelper.global("admin:audit"));

        assertDoesNotThrow(() -> ScopeVerifier.checkAuthorized(target, TenantScopeHelper.global("admin:audit")));
    }
}
