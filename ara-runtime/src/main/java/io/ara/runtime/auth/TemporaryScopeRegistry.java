package io.ara.runtime.auth;

import io.ara.core.auth.ScopeGrant;
import io.ara.core.auth.ScopeSet;

/**
 * Store of active {@link ScopeGrant}s per agent (ADR-033 Fase 8, S5,
 * `docs/adr/ADR-033-implementation-plan.md` §8.2, `ara-private`).
 *
 * <p>Backs {@code AraRuntime.grantTemporaryScope} and is consulted by {@code
 * io.ara.runtime.bus.LocalMessageBus} on every dispatch: the caller's effective scopes are
 * {@code staticScopes.union(effectiveTemporaryScopes(agentId))} — a temporary grant only
 * ever widens what an actor can do, and only for as long as it stays valid.
 */
public interface TemporaryScopeRegistry {

    /** Registers {@code grant} for {@code agentId}, alongside any grants already active. */
    void grant(String agentId, ScopeGrant grant);

    /**
     * The union of scopes contributed by every currently-{@link ScopeGrant#isValid()} grant
     * for {@code agentId} — {@link ScopeSet#EMPTY} if none. Calling this <em>consumes one
     * use</em> from each grant it reads (ADR-033 Fase 8 §8.2): an unlimited-use grant is
     * unaffected, a grant that reaches zero remaining uses stops contributing from the next
     * call onward.
     */
    ScopeSet effectiveTemporaryScopes(String agentId);

    /** Discards every grant held for {@code agentId}, valid or not. */
    void revokeAll(String agentId);

    /** A registry that grants nothing and remembers nothing — the pre-Fase-8 shape. */
    static TemporaryScopeRegistry noop() {
        return new TemporaryScopeRegistry() {
            @Override public void grant(String agentId, ScopeGrant grant) {}
            @Override public ScopeSet effectiveTemporaryScopes(String agentId) { return ScopeSet.EMPTY; }
            @Override public void revokeAll(String agentId) {}
        };
    }
}
