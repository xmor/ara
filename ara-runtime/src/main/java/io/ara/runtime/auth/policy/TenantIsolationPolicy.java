package io.ara.runtime.auth.policy;

import io.ara.core.auth.AbacPolicy;
import io.ara.core.auth.PolicyDecision;
import io.ara.core.auth.PolicyEvaluationContext;

/**
 * Denies if the subject's {@code tenantId} differs from the tenant prefix found in the
 * resource's {@code agentId} (ADR-033 Fase 2b, S8 —
 * `docs/adr/ADR-033-implementation-plan.md` §2b.3, `ara-private`) — the same
 * {@code "<tenant>:..."} convention {@code io.ara.runtime.auth.TenantScopeHelper}
 * (ADR-033 Fase 9) applies to scope strings, applied here to agent ids instead.
 *
 * <p>Abstains in two cases, both meaning "nothing to isolate here": the subject itself
 * carries no {@code tenantId} (a pure M2M caller with no tenant scoping at all), or the
 * resource's {@code agentId} carries no recognizable {@code "<prefix>:"} — a shared/global
 * agent id, not one namespaced to any particular tenant.
 */
public final class TenantIsolationPolicy implements AbacPolicy {

    private TenantIsolationPolicy() {}

    public static TenantIsolationPolicy create() {
        return new TenantIsolationPolicy();
    }

    @Override
    public PolicyDecision evaluate(PolicyEvaluationContext ctx) {
        String subjectTenant = ctx.subject().tenantId();
        if (subjectTenant == null || subjectTenant.isBlank()) {
            return PolicyDecision.NOT_APPLICABLE;
        }
        String resourcePrefix = tenantPrefixOf(ctx.resource().agentId());
        if (resourcePrefix == null) {
            return PolicyDecision.NOT_APPLICABLE;
        }
        return subjectTenant.equals(resourcePrefix) ? PolicyDecision.PERMIT : PolicyDecision.DENY;
    }

    private static String tenantPrefixOf(String agentId) {
        int idx = agentId.indexOf(':');
        return idx > 0 ? agentId.substring(0, idx) : null;
    }
}
