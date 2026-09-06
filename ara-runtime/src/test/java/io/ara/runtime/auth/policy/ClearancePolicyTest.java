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

/**
 * ADR-033 Fase 2b DONE-WHEN, verbatim: "Agente con clearance=standard non accede ad
 * agente con requiredClearance=confidential anche avendo gli scope corretti"; "Agente
 * con clearance=confidential accede normalmente".
 */
class ClearancePolicyTest {

    private static PolicyEvaluationContext ctx(String subjectClearance, String requiredClearance) {
        return new PolicyEvaluationContext(
                new SubjectAttributes("caller", "t", null, subjectClearance, ScopeSet.EMPTY),
                new ResourceAttributes("target", "t", null, requiredClearance, false),
                ActionAttributes.INVOKE,
                new EnvironmentAttributes(Instant.now(), 0, "req-1"),
                null);
    }

    @Test
    void resourceRequiresNoClearance_abstains() {
        assertEquals(PolicyDecision.NOT_APPLICABLE,
                ClearancePolicy.standard().evaluate(ctx("STANDARD", null)));
        assertEquals(PolicyDecision.NOT_APPLICABLE,
                ClearancePolicy.standard().evaluate(ctx("STANDARD", "")));
    }

    @Test
    void subjectBelowRequiredClearance_denies() {
        assertEquals(PolicyDecision.DENY,
                ClearancePolicy.standard().evaluate(ctx("STANDARD", "CONFIDENTIAL")));
    }

    @Test
    void subjectAtRequiredClearance_permits() {
        assertEquals(PolicyDecision.PERMIT,
                ClearancePolicy.standard().evaluate(ctx("CONFIDENTIAL", "CONFIDENTIAL")));
    }

    @Test
    void subjectAboveRequiredClearance_permits() {
        assertEquals(PolicyDecision.PERMIT,
                ClearancePolicy.standard().evaluate(ctx("SECRET", "CONFIDENTIAL")));
    }

    @Test
    void unrecognizedSubjectClearance_treatedAsLowestTier_denied() {
        assertEquals(PolicyDecision.DENY,
                ClearancePolicy.standard().evaluate(ctx("not-a-real-level", "SENSITIVE")));
    }
}
