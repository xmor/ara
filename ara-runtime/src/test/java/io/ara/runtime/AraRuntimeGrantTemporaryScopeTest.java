package io.ara.runtime;

import io.ara.core.auth.ScopeGrant;
import io.ara.core.auth.ScopeSet;
import io.ara.core.common.AgentId;
import io.ara.runtime.stubs.ScriptedLlmClient;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 8 §8.4 — {@link AraRuntime#grantTemporaryScope}'s own contract: it builds
 * the {@link ScopeGrant} correctly from its arguments. The grant's actual effect at
 * dispatch time — union with static scopes, consumption, expiry — is
 * {@link io.ara.runtime.bus.LocalMessageBusTemporaryScopeTest}'s job, exercised there
 * against the real {@code LocalMessageBus} without needing a full agent/ReAct round trip.
 */
class AraRuntimeGrantTemporaryScopeTest {

    private static AraRuntime runtime() {
        return AraRuntime.builder()
                .llmClient("model", ScriptedLlmClient.script().thenFinalAnswer("unused").build())
                .build();
    }

    @Test
    void grant_carriesTheRequestedScopesAndReason() {
        ScopeGrant grant = runtime().grantTemporaryScope(
                AgentId.of("agent-1"), ScopeSet.of("tools:shell"), Duration.ofMinutes(10), 1, "one-off fix");

        assertEquals(ScopeSet.of("tools:shell"), grant.scopes());
        assertEquals(1, grant.remainingUses());
        assertEquals("one-off fix", grant.grantReason());
        assertTrue(grant.isValid());
    }

    @Test
    void nullTtl_meansNoExpiry() {
        ScopeGrant grant = runtime().grantTemporaryScope(
                AgentId.of("agent-1"), ScopeSet.of("tools:shell"), null, -1, "no time limit");

        assertNull(grant.expiresAt());
        assertTrue(grant.isValid());
    }

    @Test
    void ttl_setsAnExpiryInTheFuture() {
        Instant before = Instant.now();
        ScopeGrant grant = runtime().grantTemporaryScope(
                AgentId.of("agent-1"), ScopeSet.of("tools:shell"), Duration.ofMinutes(30), -1, "bounded");

        assertTrue(grant.expiresAt().isAfter(before));
        assertTrue(grant.expiresAt().isBefore(before.plus(Duration.ofMinutes(31))));
    }
}
