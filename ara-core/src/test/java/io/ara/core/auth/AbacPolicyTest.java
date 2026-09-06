package io.ara.core.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-033 Fase 2b (S8, `docs/adr/ADR-033-implementation-plan.md` §2b.1, `ara-private`) —
 * {@link AbacPolicy#and}/{@link AbacPolicy#or} combinators, including the
 * {@link PolicyDecision#NOT_APPLICABLE} abstention case the plan's own sketch does not
 * spell out explicitly but the implementation must still handle sensibly.
 */
class AbacPolicyTest {

    private static final PolicyEvaluationContext CTX = new PolicyEvaluationContext(
            new SubjectAttributes("agent-1", "t", null, null, ScopeSet.EMPTY),
            new ResourceAttributes("target", "t", null, null, false),
            ActionAttributes.INVOKE,
            new EnvironmentAttributes(Instant.now(), 0, "req-1"),
            null);

    private static AbacPolicy constant(PolicyDecision decision) {
        return ctx -> decision;
    }

    @Test
    void and_denyThenAnything_isDeny() {
        assertEquals(PolicyDecision.DENY,
                constant(PolicyDecision.DENY).and(constant(PolicyDecision.PERMIT)).evaluate(CTX));
    }

    @Test
    void and_permitThenOther_defersToOther() {
        assertEquals(PolicyDecision.NOT_APPLICABLE,
                constant(PolicyDecision.PERMIT).and(constant(PolicyDecision.NOT_APPLICABLE)).evaluate(CTX));
    }

    @Test
    void and_notApplicableThenOther_defersToOther_neverAFalsePermit() {
        assertEquals(PolicyDecision.DENY,
                constant(PolicyDecision.NOT_APPLICABLE).and(constant(PolicyDecision.DENY)).evaluate(CTX));
    }

    @Test
    void or_permitThenAnything_isPermit() {
        assertEquals(PolicyDecision.PERMIT,
                constant(PolicyDecision.PERMIT).or(constant(PolicyDecision.DENY)).evaluate(CTX));
    }

    @Test
    void or_denyThenOther_defersToOther() {
        assertEquals(PolicyDecision.NOT_APPLICABLE,
                constant(PolicyDecision.DENY).or(constant(PolicyDecision.NOT_APPLICABLE)).evaluate(CTX));
    }
}
