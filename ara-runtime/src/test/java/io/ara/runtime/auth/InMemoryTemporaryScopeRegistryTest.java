package io.ara.runtime.auth;

import io.ara.core.auth.ScopeGrant;
import io.ara.core.auth.ScopeSet;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 8 (S5, `docs/adr/ADR-033-implementation-plan.md` §8.2, `ara-private`) —
 * {@link InMemoryTemporaryScopeRegistry}.
 */
class InMemoryTemporaryScopeRegistryTest {

    private static ScopeGrant grant(ScopeSet scopes, Instant expiresAt, int remainingUses) {
        return new ScopeGrant(scopes, expiresAt, remainingUses, "operator-1", "test");
    }

    @Test
    void noGrant_effectiveTemporaryScopesIsEmpty() {
        InMemoryTemporaryScopeRegistry reg = new InMemoryTemporaryScopeRegistry();
        assertTrue(reg.effectiveTemporaryScopes("agent-1").isEmpty());
    }

    @Test
    void aValidGrant_contributesItsScopes() {
        InMemoryTemporaryScopeRegistry reg = new InMemoryTemporaryScopeRegistry();
        reg.grant("agent-1", grant(ScopeSet.of("tools:shell"), null, -1));

        assertEquals(ScopeSet.of("tools:shell"), reg.effectiveTemporaryScopes("agent-1"));
    }

    @Test
    void multipleGrants_unionTogether() {
        InMemoryTemporaryScopeRegistry reg = new InMemoryTemporaryScopeRegistry();
        reg.grant("agent-1", grant(ScopeSet.of("tools:shell"), null, -1));
        reg.grant("agent-1", grant(ScopeSet.of("finance:read"), null, -1));

        assertEquals(ScopeSet.of("tools:shell", "finance:read"), reg.effectiveTemporaryScopes("agent-1"));
    }

    @Test
    void expiredGrant_contributesNothing() {
        InMemoryTemporaryScopeRegistry reg = new InMemoryTemporaryScopeRegistry();
        reg.grant("agent-1", grant(ScopeSet.of("tools:shell"), Instant.now().minus(Duration.ofMinutes(1)), -1));

        assertTrue(reg.effectiveTemporaryScopes("agent-1").isEmpty());
    }

    @Test
    void limitedUseGrant_stopsContributingAfterExhaustion() {
        InMemoryTemporaryScopeRegistry reg = new InMemoryTemporaryScopeRegistry();
        reg.grant("agent-1", grant(ScopeSet.of("tools:shell"), null, 1));

        assertEquals(ScopeSet.of("tools:shell"), reg.effectiveTemporaryScopes("agent-1"),
                "first read: the single use is still there to consume");
        assertTrue(reg.effectiveTemporaryScopes("agent-1").isEmpty(),
                "second read: the grant was already consumed by the first read");
    }

    @Test
    void reading_isolatesOneAgentFromAnother() {
        InMemoryTemporaryScopeRegistry reg = new InMemoryTemporaryScopeRegistry();
        reg.grant("agent-1", grant(ScopeSet.of("tools:shell"), null, -1));

        assertTrue(reg.effectiveTemporaryScopes("agent-2").isEmpty());
    }

    @Test
    void revokeAll_removesEveryGrantForThatAgent() {
        InMemoryTemporaryScopeRegistry reg = new InMemoryTemporaryScopeRegistry();
        reg.grant("agent-1", grant(ScopeSet.of("tools:shell"), null, -1));

        reg.revokeAll("agent-1");

        assertTrue(reg.effectiveTemporaryScopes("agent-1").isEmpty());
    }
}
