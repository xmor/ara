package io.ara.core.auth;

/**
 * A single attribute-based access control rule (ADR-033 Fase 2b, S8 — Livello 1b, opt-in
 * — `docs/adr/ADR-033-implementation-plan.md` §2b.1, `ara-private`).
 *
 * <p>Composable via {@link #and} / {@link #or}, both short-circuiting on the decisive
 * outcome for that combinator ({@link PolicyDecision#DENY} for {@code and},
 * {@link PolicyDecision#PERMIT} for {@code or}) and deferring to the other policy
 * otherwise — including when this policy abstains with {@link PolicyDecision#NOT_APPLICABLE}.
 */
@FunctionalInterface
public interface AbacPolicy {

    PolicyDecision evaluate(PolicyEvaluationContext ctx);

    /**
     * A policy that denies if either {@code this} or {@code other} denies, otherwise
     * defers to {@code other}'s decision (so two abstaining policies still combine to
     * {@link PolicyDecision#NOT_APPLICABLE}, not a false {@code PERMIT}).
     */
    default AbacPolicy and(AbacPolicy other) {
        return ctx -> {
            PolicyDecision d = this.evaluate(ctx);
            return d == PolicyDecision.DENY ? d : other.evaluate(ctx);
        };
    }

    /**
     * A policy that permits if either {@code this} or {@code other} permits, otherwise
     * defers to {@code other}'s decision.
     */
    default AbacPolicy or(AbacPolicy other) {
        return ctx -> {
            PolicyDecision d = this.evaluate(ctx);
            return d == PolicyDecision.PERMIT ? d : other.evaluate(ctx);
        };
    }
}
