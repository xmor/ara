package io.ara.runtime.bus;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.auth.AuthorizationException;
import io.ara.core.auth.ScopeGrant;
import io.ara.core.auth.ScopeSet;
import io.ara.core.bus.AgentMessage;
import io.ara.core.common.AgentId;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.runtime.agent.AgentRegistry;
import io.ara.runtime.auth.InMemoryTemporaryScopeRegistry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 8 (S5) end-to-end DONE-WHEN, verbatim (`docs/adr/ADR-033-implementation-plan.md`
 * §8, `ara-private`): "agente senza tools:shell negli scope statici. Grant temporaneo con
 * maxUses=1. Prima invocazione: ok. Seconda invocazione: AuthorizationException. Grant
 * scaduto per TTL: AuthorizationException." — through the real {@link LocalMessageBus}
 * dispatch path, not the pure registry logic already covered by
 * {@link io.ara.runtime.auth.InMemoryTemporaryScopeRegistryTest}.
 */
class LocalMessageBusTemporaryScopeTest {

    private static AraAgent agentRequiring(String requiredScope, AtomicBoolean invoked) {
        AgentId id = AgentId.of("target");
        AgentConfig config = AgentConfig.defaults().agentId(id).agentType("t")
                .requiredScopes(List.of(requiredScope)).build();
        return new AraAgent() {
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return config; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                invoked.set(true);
                return AgentResponse.success(task.taskId(), id, "ok", 1, 0, 0.0, Duration.ZERO, List.of());
            }
            @Override public void terminate() {}
        };
    }

    @Test
    void firstInvocation_succeeds_secondFailsOnceTheSingleUseIsSpent() {
        AgentRegistry registry = new AgentRegistry();
        AtomicBoolean invoked = new AtomicBoolean();
        registry.register(agentRequiring("tools:shell", invoked));

        InMemoryTemporaryScopeRegistry temp = new InMemoryTemporaryScopeRegistry();
        // Caller has no static scope at all — the grant is the only source of authority.
        temp.grant("caller", new ScopeGrant(ScopeSet.of("tools:shell"), null, 1, "operator-1", "one-off fix"));

        LocalMessageBus bus = new LocalMessageBus(registry, AraTelemetry.noop(), null, temp);

        assertDoesNotThrow(() -> bus.request(AgentMessage.of("caller", "target", "do it"), Duration.ofSeconds(5)));
        assertTrue(invoked.get(), "first invocation: the single use was still available");

        invoked.set(false);
        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> bus.request(AgentMessage.of("caller", "target", "do it"), Duration.ofSeconds(5)));
        assertEquals(AuthorizationException.Reason.AGENT_NOT_AUTHORIZED, e.reason());
        assertFalse(invoked.get(), "second invocation: the grant was already spent");
    }

    @Test
    void expiredGrant_deniesEvenOnTheFirstInvocation() {
        AgentRegistry registry = new AgentRegistry();
        AtomicBoolean invoked = new AtomicBoolean();
        registry.register(agentRequiring("tools:shell", invoked));

        InMemoryTemporaryScopeRegistry temp = new InMemoryTemporaryScopeRegistry();
        temp.grant("caller", new ScopeGrant(ScopeSet.of("tools:shell"),
                Instant.now().minus(Duration.ofMinutes(1)), -1, "operator-1", "already expired"));

        LocalMessageBus bus = new LocalMessageBus(registry, AraTelemetry.noop(), null, temp);

        assertThrows(AuthorizationException.class,
                () -> bus.request(AgentMessage.of("caller", "target", "do it"), Duration.ofSeconds(5)));
        assertFalse(invoked.get());
    }

    @Test
    void temporaryGrant_extendsStaticScopes_neverReplacesThem() {
        // A caller that already holds a DIFFERENT static scope keeps it; the temporary
        // grant only adds tools:shell on top — union, not a swap.
        AgentRegistry registry = new AgentRegistry();
        AtomicBoolean invoked = new AtomicBoolean();
        registry.register(agentRequiring("tools:shell", invoked));

        InMemoryTemporaryScopeRegistry temp = new InMemoryTemporaryScopeRegistry();
        temp.grant("caller", new ScopeGrant(ScopeSet.of("tools:shell"), null, -1, "operator-1", "extend"));

        LocalMessageBus bus = new LocalMessageBus(registry, AraTelemetry.noop(), null, temp);

        AgentMessage message = AgentMessage.of("caller", "target", "do it").withSenderScopes(ScopeSet.of("ops"));
        assertDoesNotThrow(() -> bus.request(message, Duration.ofSeconds(5)));
        assertTrue(invoked.get());
    }

    @Test
    void noTemporaryScopeRegistryConfigured_zeroBehaviorChange() {
        AgentRegistry registry = new AgentRegistry();
        AtomicBoolean invoked = new AtomicBoolean();
        registry.register(agentRequiring("tools:shell", invoked));

        // Pre-Fase-8 constructor shape — no registry at all.
        LocalMessageBus bus = new LocalMessageBus(registry);

        assertThrows(AuthorizationException.class,
                () -> bus.request(AgentMessage.of("caller", "target", "do it"), Duration.ofSeconds(5)));
        assertFalse(invoked.get());
    }
}
