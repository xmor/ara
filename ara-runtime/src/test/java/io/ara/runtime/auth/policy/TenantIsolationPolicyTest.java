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

class TenantIsolationPolicyTest {

    private static PolicyEvaluationContext ctx(String subjectTenant, String resourceAgentId) {
        return new PolicyEvaluationContext(
                new SubjectAttributes("caller", "t", subjectTenant, null, ScopeSet.EMPTY),
                new ResourceAttributes(resourceAgentId, "t", null, null, false),
                ActionAttributes.INVOKE,
                new EnvironmentAttributes(Instant.now(), 0, "req-1"),
                null);
    }

    @Test
    void sameTenantPrefix_permits() {
        assertEquals(PolicyDecision.PERMIT,
                TenantIsolationPolicy.create().evaluate(ctx("acme", "acme:finance-agent")));
    }

    @Test
    void differentTenantPrefix_denies() {
        assertEquals(PolicyDecision.DENY,
                TenantIsolationPolicy.create().evaluate(ctx("acme", "other:finance-agent")));
    }

    @Test
    void subjectWithNoTenant_abstains_pureM2mNotTenantScoped() {
        assertEquals(PolicyDecision.NOT_APPLICABLE,
                TenantIsolationPolicy.create().evaluate(ctx(null, "acme:finance-agent")));
        assertEquals(PolicyDecision.NOT_APPLICABLE,
                TenantIsolationPolicy.create().evaluate(ctx("", "acme:finance-agent")));
    }

    @Test
    void resourceWithNoTenantPrefix_abstains_sharedOrGlobalAgent() {
        assertEquals(PolicyDecision.NOT_APPLICABLE,
                TenantIsolationPolicy.create().evaluate(ctx("acme", "shared-service")));
    }
}
