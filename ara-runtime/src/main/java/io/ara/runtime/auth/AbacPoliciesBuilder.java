package io.ara.runtime.auth;

import io.ara.core.auth.AbacPolicy;
import io.ara.core.auth.AbacPolicyEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fluent accumulator for {@code AraRuntime.Builder.abacPolicies(...)} (ADR-033 Fase 2b,
 * S8 — `docs/adr/ADR-033-implementation-plan.md` §2b.5, `ara-private`):
 *
 * <pre>{@code
 * AraRuntime.builder()
 *     .llmClient(client)
 *     .abacPolicies(policies -> policies
 *         .add("clearance",      ClearancePolicy.standard())
 *         .add("business-hours", BusinessHoursPolicy.forZone("Europe/Rome"))
 *         .add("max-depth",      DelegationDepthPolicy.max(3))
 *         .combineWith(CompositeAbacPolicyEngine.CombiningAlgorithm.DENY_OVERRIDES))
 *     .build();
 * }</pre>
 *
 * <p>Default combining algorithm is {@code DENY_OVERRIDES} when {@link #combineWith} is
 * never called — the safer default: any one configured policy can block a call, none of
 * them alone is required to explicitly permit it.
 */
public final class AbacPoliciesBuilder {

    private final List<CompositeAbacPolicyEngine.NamedPolicy> policies = new ArrayList<>();
    private CompositeAbacPolicyEngine.CombiningAlgorithm algorithm =
            CompositeAbacPolicyEngine.CombiningAlgorithm.DENY_OVERRIDES;

    public AbacPoliciesBuilder add(String name, AbacPolicy policy) {
        policies.add(new CompositeAbacPolicyEngine.NamedPolicy(name, policy));
        return this;
    }

    public AbacPoliciesBuilder combineWith(CompositeAbacPolicyEngine.CombiningAlgorithm algorithm) {
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm must not be null");
        return this;
    }

    public AbacPolicyEngine build() {
        return new CompositeAbacPolicyEngine(policies, algorithm);
    }
}
