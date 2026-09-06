package io.ara.runtime.auth;

import io.ara.core.auth.ActionAttributes;
import io.ara.core.auth.EnvironmentAttributes;
import io.ara.core.auth.PolicyDecision;
import io.ara.core.auth.PolicyEvaluationContext;
import io.ara.core.auth.ResourceAttributes;
import io.ara.core.auth.ScopeSet;
import io.ara.core.auth.SubjectAttributes;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-033 Fase 2b (S8, `docs/adr/ADR-033-implementation-plan.md` §2b.2, `ara-private`) —
 * {@link CompositeAbacPolicyEngine}'s two combining algorithms, verbatim from the plan's
 * own DONE-WHEN: "DENY_OVERRIDES: una sola DENY blocca tutto", "PERMIT_OVERRIDES: una
 * sola PERMIT sblocca tutto".
 */
class CompositeAbacPolicyEngineTest {

    private static final PolicyEvaluationContext CTX = new PolicyEvaluationContext(
            new SubjectAttributes("agent-1", "t", null, null, ScopeSet.EMPTY),
            new ResourceAttributes("target", "t", null, null, false),
            ActionAttributes.INVOKE,
            new EnvironmentAttributes(Instant.now(), 0, "req-1"),
            null);

    private static CompositeAbacPolicyEngine.NamedPolicy named(String name, PolicyDecision decision) {
        return new CompositeAbacPolicyEngine.NamedPolicy(name, ctx -> decision);
    }

    @Test
    void denyOverrides_oneDenyAmongPermits_blocksTheWhole() {
        CompositeAbacPolicyEngine engine = new CompositeAbacPolicyEngine(
                List.of(named("a", PolicyDecision.PERMIT), named("b", PolicyDecision.DENY),
                        named("c", PolicyDecision.PERMIT)),
                CompositeAbacPolicyEngine.CombiningAlgorithm.DENY_OVERRIDES);

        assertEquals(PolicyDecision.DENY, engine.evaluate(CTX));
    }

    @Test
    void denyOverrides_noDeny_defaultsToPermit() {
        CompositeAbacPolicyEngine engine = new CompositeAbacPolicyEngine(
                List.of(named("a", PolicyDecision.NOT_APPLICABLE), named("b", PolicyDecision.NOT_APPLICABLE)),
                CompositeAbacPolicyEngine.CombiningAlgorithm.DENY_OVERRIDES);

        assertEquals(PolicyDecision.PERMIT, engine.evaluate(CTX));
    }

    @Test
    void permitOverrides_onePermitAmongDenies_unlocksTheWhole() {
        CompositeAbacPolicyEngine engine = new CompositeAbacPolicyEngine(
                List.of(named("a", PolicyDecision.DENY), named("b", PolicyDecision.PERMIT)),
                CompositeAbacPolicyEngine.CombiningAlgorithm.PERMIT_OVERRIDES);

        assertEquals(PolicyDecision.PERMIT, engine.evaluate(CTX));
    }

    @Test
    void permitOverrides_noPermit_defaultsToDeny() {
        CompositeAbacPolicyEngine engine = new CompositeAbacPolicyEngine(
                List.of(named("a", PolicyDecision.NOT_APPLICABLE)),
                CompositeAbacPolicyEngine.CombiningAlgorithm.PERMIT_OVERRIDES);

        assertEquals(PolicyDecision.DENY, engine.evaluate(CTX));
    }

    @Test
    void noPolicies_denyOverridesDefaultsToPermit_permitOverridesDefaultsToDeny() {
        CompositeAbacPolicyEngine denyOverrides = new CompositeAbacPolicyEngine(
                List.of(), CompositeAbacPolicyEngine.CombiningAlgorithm.DENY_OVERRIDES);
        CompositeAbacPolicyEngine permitOverrides = new CompositeAbacPolicyEngine(
                List.of(), CompositeAbacPolicyEngine.CombiningAlgorithm.PERMIT_OVERRIDES);

        assertEquals(PolicyDecision.PERMIT, denyOverrides.evaluate(CTX));
        assertEquals(PolicyDecision.DENY, permitOverrides.evaluate(CTX));
    }
}
