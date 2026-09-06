package io.ara.runtime.auth;

import io.ara.core.auth.AbacPolicy;
import io.ara.core.auth.AbacPolicyEngine;
import io.ara.core.auth.PolicyDecision;
import io.ara.core.auth.PolicyEvaluationContext;

import java.util.List;
import java.util.Objects;

/**
 * Reference {@link AbacPolicyEngine}: a named, ordered list of {@link AbacPolicy}
 * evaluations folded into one decision under a chosen combining algorithm (ADR-033
 * Fase 2b, S8 — `docs/adr/ADR-033-implementation-plan.md` §2b.2, `ara-private`).
 *
 * <p>Built via {@link AbacPoliciesBuilder}, not this class's own constructor directly —
 * see that class for the fluent {@code .add(name, policy).combineWith(algorithm)} API
 * {@code AraRuntime.Builder.abacPolicies(...)} exposes.
 */
public final class CompositeAbacPolicyEngine implements AbacPolicyEngine {

    /**
     * How several policies' individual decisions combine into one (standard XACML-style
     * combining algorithms, restricted to the two this ADR needs).
     */
    public enum CombiningAlgorithm {
        /** A single {@link PolicyDecision#DENY} from any policy denies the whole evaluation; default {@link PolicyDecision#PERMIT} otherwise. */
        DENY_OVERRIDES,
        /** A single {@link PolicyDecision#PERMIT} from any policy permits the whole evaluation; default {@link PolicyDecision#DENY} otherwise. */
        PERMIT_OVERRIDES
    }

    /** A policy paired with the name it was registered under (audit/debugging only — never read by {@link #evaluate}). */
    public record NamedPolicy(String name, AbacPolicy policy) {
        public NamedPolicy {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(policy, "policy must not be null");
        }
    }

    private final List<NamedPolicy>   policies;
    private final CombiningAlgorithm  algorithm;

    public CompositeAbacPolicyEngine(List<NamedPolicy> policies, CombiningAlgorithm algorithm) {
        this.policies  = List.copyOf(Objects.requireNonNull(policies, "policies must not be null"));
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm must not be null");
    }

    /** The registered policies, in evaluation order — for introspection/logging, never mutated here. */
    public List<NamedPolicy> policies() {
        return policies;
    }

    public CombiningAlgorithm algorithm() {
        return algorithm;
    }

    @Override
    public PolicyDecision evaluate(PolicyEvaluationContext ctx) {
        for (NamedPolicy named : policies) {
            PolicyDecision d = named.policy().evaluate(ctx);
            if (algorithm == CombiningAlgorithm.DENY_OVERRIDES && d == PolicyDecision.DENY) {
                return PolicyDecision.DENY;
            }
            if (algorithm == CombiningAlgorithm.PERMIT_OVERRIDES && d == PolicyDecision.PERMIT) {
                return PolicyDecision.PERMIT;
            }
        }
        return algorithm == CombiningAlgorithm.DENY_OVERRIDES ? PolicyDecision.PERMIT : PolicyDecision.DENY;
    }
}
