package io.ara.core.auth;

/**
 * The outcome of a single {@link AbacPolicy} evaluation (ADR-033 Fase 2b, S8 —
 * `docs/adr/ADR-033-implementation-plan.md` §2b.1, `ara-private`).
 *
 * <p>Three values, not a boolean, because a policy commonly has nothing to say about a
 * given call (e.g. {@code BusinessHoursPolicy} on a resource that isn't {@code critical}):
 * {@link #NOT_APPLICABLE} lets a {@link AbacPolicyEngine} distinguish "this policy
 * abstains" from "this policy actively permits", which matters for how {@code
 * CompositeAbacPolicyEngine} folds several policies together.
 */
public enum PolicyDecision {
    PERMIT,
    DENY,
    NOT_APPLICABLE
}
