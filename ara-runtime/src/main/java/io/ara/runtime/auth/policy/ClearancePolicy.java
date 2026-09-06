package io.ara.runtime.auth.policy;

import io.ara.core.auth.AbacPolicy;
import io.ara.core.auth.PolicyDecision;
import io.ara.core.auth.PolicyEvaluationContext;

/**
 * Denies unless the subject's {@code clearanceLevel} is at least the resource's declared
 * {@code requiredClearance} (ADR-033 Fase 2b, S8 — `docs/adr/ADR-033-implementation-plan.md`
 * §2b.3, `ara-private`). Abstains ({@link PolicyDecision#NOT_APPLICABLE}) for a resource
 * that declares no {@code requiredClearance} at all — nothing to check.
 */
public final class ClearancePolicy implements AbacPolicy {

    private ClearancePolicy() {}

    public static ClearancePolicy standard() {
        return new ClearancePolicy();
    }

    @Override
    public PolicyDecision evaluate(PolicyEvaluationContext ctx) {
        String required = ctx.resource().requiredClearance();
        if (required == null || required.isBlank()) {
            return PolicyDecision.NOT_APPLICABLE;
        }
        ClearanceLevel requiredLevel = ClearanceLevel.parse(required);
        ClearanceLevel subjectLevel  = ClearanceLevel.parse(ctx.subject().clearanceLevel());
        return subjectLevel.compareTo(requiredLevel) >= 0 ? PolicyDecision.PERMIT : PolicyDecision.DENY;
    }
}
