package io.ara.runtime.auth.policy;

import io.ara.core.auth.ActionAttributes;
import io.ara.core.auth.EnvironmentAttributes;
import io.ara.core.auth.PolicyDecision;
import io.ara.core.auth.PolicyEvaluationContext;
import io.ara.core.auth.ResourceAttributes;
import io.ara.core.auth.ScopeSet;
import io.ara.core.auth.SubjectAttributes;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DelegationDepthPolicyTest {

    private static PolicyEvaluationContext ctxAtDepth(int depth) {
        return new PolicyEvaluationContext(
                new SubjectAttributes("caller", "t", null, null, ScopeSet.EMPTY),
                new ResourceAttributes("target", "t", null, null, false),
                ActionAttributes.DELEGATE,
                new EnvironmentAttributes(Instant.now(), depth, "req-1"),
                null);
    }

    @Test
    void atOrBelowMaxDepth_permits() {
        DelegationDepthPolicy policy = DelegationDepthPolicy.max(3);
        assertEquals(PolicyDecision.PERMIT, policy.evaluate(ctxAtDepth(0)));
        assertEquals(PolicyDecision.PERMIT, policy.evaluate(ctxAtDepth(3)));
    }

    @Test
    void beyondMaxDepth_denies() {
        assertEquals(PolicyDecision.DENY, DelegationDepthPolicy.max(3).evaluate(ctxAtDepth(4)));
    }

    @Test
    void maxDepthZero_onlyDirectCallsPermitted() {
        DelegationDepthPolicy policy = DelegationDepthPolicy.max(0);
        assertEquals(PolicyDecision.PERMIT, policy.evaluate(ctxAtDepth(0)));
        assertEquals(PolicyDecision.DENY, policy.evaluate(ctxAtDepth(1)));
    }

    @Test
    void negativeMaxDepth_rejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> DelegationDepthPolicy.max(-1));
    }
}
