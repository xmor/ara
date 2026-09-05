package io.ara.runtime.auth;

import io.ara.core.auth.ScopeSet;

import java.util.Arrays;

/**
 * Convention helper for multi-tenant isolation via scope naming (ADR-033 Fase 9, S7,
 * `docs/adr/ADR-033-implementation-plan.md` §9, `ara-private`) — {@code "<tenant>:<scope>"}.
 *
 * <p>ARA does not parse or interpret this prefix anywhere in the authorization model:
 * {@link ScopeSet} treats {@code "acme:finance:read"} as one opaque string, no different
 * from any other scope. Isolation is a consequence of that opacity, not a mechanism this
 * class implements: two tenants' scopes never share a string, so {@link
 * ScopeSet#grants}/{@link ScopeSet#intersect}/{@link ScopeSet#visibleTo} never cross a
 * tenant boundary by construction — there is no new enforcement code to write or forget.
 * This helper exists purely so callers build the prefix consistently instead of
 * string-concatenating it by hand at every call site.
 *
 * <pre>{@code
 * // Agent scoped to tenant "acme":
 * AgentConfig.defaults()
 *     .grantedScopes(TenantScopeHelper.forTenant("acme", "finance:read", "ops").scopes().stream().toList())
 *     ...
 *
 * // A scope meant to be valid across every tenant (e.g. a platform-operator role):
 * TenantScopeHelper.global("admin:audit")
 * }</pre>
 */
public final class TenantScopeHelper {

    private TenantScopeHelper() {}

    /** {@code "<tenantId>:<scope>"} for each of {@code scopes} — this tenant's own scopes. */
    public static ScopeSet forTenant(String tenantId, String... scopes) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be null or blank");
        }
        return ScopeSet.of(Arrays.stream(scopes)
                .map(s -> tenantId + ":" + s)
                .toArray(String[]::new));
    }

    /** {@code "global:<scope>"} for each of {@code scopes} — valid across every tenant. */
    public static ScopeSet global(String... scopes) {
        return ScopeSet.of(Arrays.stream(scopes)
                .map(s -> "global:" + s)
                .toArray(String[]::new));
    }
}
