package io.ara.core.auth;

import java.util.Objects;

/**
 * Attributes of the target agent (or tool) in an ABAC policy decision (ADR-033 Fase 2b, S8
 * — `docs/adr/ADR-033-implementation-plan.md` §2b.1, `ara-private`).
 *
 * @param agentId              the target's own id
 * @param agentType            the target's declared {@code AgentConfig.agentType()}
 * @param dataClassification  a free-form classification tag (e.g. {@code "critical"},
 *                            {@code "public"}); {@code io.ara.runtime.auth.policy.BusinessHoursPolicy}
 *                            reads this to decide whether an off-hours call is even relevant
 * @param requiredClearance   a clearance tier name (see {@link SubjectAttributes#clearanceLevel()});
 *                            {@code null}/blank means no clearance requirement
 * @param requiresApproval    mirrors {@code AgentConfig.requiresApproval()} (ADR-033 Fase 7)
 *                            for a policy that wants to reason about it directly rather than
 *                            re-deriving it from the target's config
 */
public record ResourceAttributes(
        String  agentId,
        String  agentType,
        String  dataClassification,
        String  requiredClearance,
        boolean requiresApproval
) {

    public ResourceAttributes {
        Objects.requireNonNull(agentId, "agentId must not be null");
    }
}
