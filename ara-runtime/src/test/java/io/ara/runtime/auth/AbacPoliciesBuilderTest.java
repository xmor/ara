package io.ara.runtime.auth;

import io.ara.core.auth.ActionAttributes;
import io.ara.core.auth.AbacPolicyEngine;
import io.ara.core.auth.EnvironmentAttributes;
import io.ara.core.auth.PolicyDecision;
import io.ara.core.auth.PolicyEvaluationContext;
import io.ara.core.auth.ResourceAttributes;
import io.ara.core.auth.ScopeSet;
import io.ara.core.auth.SubjectAttributes;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ADR-033 Fase 2b §2b.5 — {@link AbacPoliciesBuilder}'s fluent API and default algorithm. */
class AbacPoliciesBuilderTest {

    private static final PolicyEvaluationContext CTX = new PolicyEvaluationContext(
            new SubjectAttributes("agent-1", "t", null, null, ScopeSet.EMPTY),
            new ResourceAttributes("target", "t", null, null, false),
            ActionAttributes.INVOKE,
            new EnvironmentAttributes(Instant.now(), 0, "req-1"),
            null);

    @Test
    void build_returnsACompositeAbacPolicyEngine_withRegisteredPoliciesInOrder() {
        AbacPolicyEngine engine = new AbacPoliciesBuilder()
                .add("first", ctx -> PolicyDecision.NOT_APPLICABLE)
                .add("second", ctx -> PolicyDecision.DENY)
                .build();

        assertTrue(engine instanceof CompositeAbacPolicyEngine);
        assertEquals(2, ((CompositeAbacPolicyEngine) engine).policies().size());
        assertEquals(PolicyDecision.DENY, engine.evaluate(CTX));
    }

    @Test
    void defaultAlgorithm_isDenyOverrides_whenCombineWithNeverCalled() {
        AbacPolicyEngine engine = new AbacPoliciesBuilder()
                .add("permit", ctx -> PolicyDecision.PERMIT)
                .add("deny", ctx -> PolicyDecision.DENY)
                .build();

        assertEquals(PolicyDecision.DENY, engine.evaluate(CTX));
    }

    @Test
    void combineWith_overridesTheDefaultAlgorithm() {
        AbacPolicyEngine engine = new AbacPoliciesBuilder()
                .add("permit", ctx -> PolicyDecision.PERMIT)
                .add("deny", ctx -> PolicyDecision.DENY)
                .combineWith(CompositeAbacPolicyEngine.CombiningAlgorithm.PERMIT_OVERRIDES)
                .build();

        assertEquals(PolicyDecision.PERMIT, engine.evaluate(CTX));
    }

    @Test
    void noPoliciesAdded_denyOverridesDefaultPermitsEverything() {
        AbacPolicyEngine engine = new AbacPoliciesBuilder().build();
        assertEquals(PolicyDecision.PERMIT, engine.evaluate(CTX));
    }
}
