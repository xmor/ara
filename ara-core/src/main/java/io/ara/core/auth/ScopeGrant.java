package io.ara.core.auth;

import java.time.Instant;
import java.util.Objects;

/**
 * A scope grant limited in time and/or use-count (ADR-033 Fase 8, S5) —
 * `docs/adr/ADR-033-implementation-plan.md` §8.1, `ara-private`.
 *
 * <p>Extends, never replaces, an actor's static {@code grantedScopes}: {@link
 * ScopeSet#union} is the only way this type composes with anything else in the model —
 * an expired or exhausted grant simply stops contributing, it never subtracts from what
 * an actor already holds.
 *
 * @param scopes         the scopes this grant contributes while {@link #isValid()}
 * @param expiresAt      absolute instant after which the grant is no longer valid;
 *                       {@code null} means no time limit
 * @param remainingUses  invocations left before the grant is exhausted; {@code -1} means
 *                       unlimited
 * @param grantedBy      id of the agent or user who issued this grant (audit)
 * @param grantReason    human-readable justification (audit)
 */
public record ScopeGrant(
        ScopeSet scopes,
        Instant  expiresAt,
        int      remainingUses,
        String   grantedBy,
        String   grantReason
) {

    public ScopeGrant {
        Objects.requireNonNull(scopes, "scopes must not be null");
        Objects.requireNonNull(grantedBy, "grantedBy must not be null");
        Objects.requireNonNull(grantReason, "grantReason must not be null");
        if (remainingUses < -1) {
            throw new IllegalArgumentException("remainingUses must be -1 (unlimited) or >= 0");
        }
    }

    /** {@code true} if this grant still contributes scopes: not expired, and has uses left. */
    public boolean isValid() {
        boolean notExpired = expiresAt == null || Instant.now().isBefore(expiresAt);
        boolean hasUses    = remainingUses == -1 || remainingUses > 0;
        return notExpired && hasUses;
    }

    /**
     * Returns a copy with one use consumed. A no-op (returns {@code this}) for an
     * unlimited-use grant — there is nothing to decrement.
     */
    public ScopeGrant consume() {
        if (remainingUses == -1) return this;
        return new ScopeGrant(scopes, expiresAt, remainingUses - 1, grantedBy, grantReason);
    }
}
