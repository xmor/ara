package io.ara.core.auth;

import java.util.Objects;

/**
 * Attributes of the caller in an ABAC policy decision (ADR-033 Fase 2b, S8 — Livello 1b,
 * opt-in — `docs/adr/ADR-033-implementation-plan.md` §2b.1, `ara-private`).
 *
 * <p>A strict superset of what {@link ScopeSet}-based authorization (Fasi 1-9) already
 * checks: {@code grantedScopes} is carried here too so a policy can combine both signals
 * (e.g. "SENSITIVE clearance AND finance:read") without a caller having to evaluate scope
 * and ABAC separately.
 *
 * @param agentId       the caller's own id
 * @param agentType     the caller's declared {@code AgentConfig.agentType()}
 * @param tenantId      opaque tenant identifier; {@code null} for a single-tenant deployment
 *                      or an M2M caller with no tenant scoping (ADR-033 Fase 9's naming
 *                      convention lives in the scope string itself, not here — this field is
 *                      for a policy that wants the tenant as a first-class attribute instead)
 * @param clearanceLevel a clearance tier name (e.g. {@code "STANDARD"}, {@code "SECRET"}),
 *                       as a plain {@code String} so a deployment can use its own
 *                       vocabulary; {@code io.ara.runtime.auth.policy.ClearancePolicy}
 *                       parses it defensively (unknown/{@code null} treated as the lowest tier)
 * @param grantedScopes the caller's own {@link ScopeSet}, exactly as ADR-033 Fasi 1-9 use it
 */
public record SubjectAttributes(
        String   agentId,
        String   agentType,
        String   tenantId,
        String   clearanceLevel,
        ScopeSet grantedScopes
) {

    public SubjectAttributes {
        Objects.requireNonNull(agentId, "agentId must not be null");
        grantedScopes = Objects.requireNonNullElse(grantedScopes, ScopeSet.EMPTY);
    }
}
