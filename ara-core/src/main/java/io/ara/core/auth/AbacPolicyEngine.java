package io.ara.core.auth;

/**
 * Folds one or more {@link AbacPolicy} evaluations into a single {@link PolicyDecision}
 * (ADR-033 Fase 2b, S8 — `docs/adr/ADR-033-implementation-plan.md` §2b.1, `ara-private`).
 *
 * <p>{@code io.ara.runtime.auth.CompositeAbacPolicyEngine} is the reference implementation
 * — a named, ordered list of policies combined under a chosen combining algorithm
 * (deny-overrides or permit-overrides).
 */
public interface AbacPolicyEngine {

    PolicyDecision evaluate(PolicyEvaluationContext ctx);
}
