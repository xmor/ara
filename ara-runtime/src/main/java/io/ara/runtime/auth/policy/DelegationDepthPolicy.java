package io.ara.runtime.auth.policy;

import io.ara.core.auth.AbacPolicy;
import io.ara.core.auth.PolicyDecision;
import io.ara.core.auth.PolicyEvaluationContext;

/**
 * Denies a call whose {@link io.ara.core.auth.EnvironmentAttributes#delegationDepth()}
 * exceeds a configured maximum (ADR-033 Fase 2b, S8 —
 * `docs/adr/ADR-033-implementation-plan.md` §2b.3, `ara-private`) — a circuit breaker on
 * how many hops a delegation chain may run, independent of whether every individual hop's
 * scope attenuation (Fase 5) is itself satisfied.
 */
public final class DelegationDepthPolicy implements AbacPolicy {

    private final int maxDepth;

    private DelegationDepthPolicy(int maxDepth) {
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must be >= 0");
        }
        this.maxDepth = maxDepth;
    }

    public static DelegationDepthPolicy max(int maxDepth) {
        return new DelegationDepthPolicy(maxDepth);
    }

    @Override
    public PolicyDecision evaluate(PolicyEvaluationContext ctx) {
        return ctx.environment().delegationDepth() > maxDepth ? PolicyDecision.DENY : PolicyDecision.PERMIT;
    }
}
