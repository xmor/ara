package io.ara.runtime.bus;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.auth.AuthorizationException;
import io.ara.core.auth.ScopeSet;
import io.ara.core.bus.AgentMessage;
import io.ara.core.common.AgentId;
import io.ara.runtime.agent.AgentRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ADR-033 Fase 2 — {@link LocalMessageBus} checks {@link AgentMessage#senderScopes()}
 * against the recipient's {@code requiredScopes} before dispatch, on both delivery paths.
 */
class LocalMessageBusScopeEnforcementTest {

    private static AraAgent agentRequiring(String requiredScope, AtomicBoolean invoked) {
        AgentId id = AgentId.of("target");
        AgentConfig config = AgentConfig.defaults()
                .agentId(id).agentType("t")
                .requiredScopes(List.of(requiredScope))
                .build();
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
    void request_deniesACallerMissingTheRequiredScope_andNeverInvokesTheRecipient() {
        AgentRegistry registry = new AgentRegistry();
        AtomicBoolean invoked = new AtomicBoolean();
        registry.register(agentRequiring("finance:write", invoked));
        LocalMessageBus bus = new LocalMessageBus(registry);

        AgentMessage message = AgentMessage.of("caller", "target", "do it")
                .withSenderScopes(ScopeSet.of("ops"));

        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> bus.request(message, Duration.ofSeconds(5)));
        assertEquals(AuthorizationException.Reason.AGENT_NOT_AUTHORIZED, e.reason());
        assertFalse(invoked.get(), "the recipient must never run once the scope check fails");
    }

    @Test
    void request_permitsACallerHoldingTheRequiredScope() {
        AgentRegistry registry = new AgentRegistry();
        AtomicBoolean invoked = new AtomicBoolean();
        registry.register(agentRequiring("finance:write", invoked));
        LocalMessageBus bus = new LocalMessageBus(registry);

        AgentMessage message = AgentMessage.of("caller", "target", "do it")
                .withSenderScopes(ScopeSet.of("finance:write"));

        assertDoesNotThrow(() -> bus.request(message, Duration.ofSeconds(5)));
        assertEquals(true, invoked.get());
    }

    @Test
    void request_withNoSenderScopesDeclared_isDeniedByARecipientThatRequiresOne() {
        // ScopeSet.EMPTY is the default AgentMessage.of(...) carries — this is what
        // guarantees zero behaviour change for every existing caller: it only starts
        // failing once a recipient actually declares a requiredScope, which none do today.
        AgentRegistry registry = new AgentRegistry();
        AtomicBoolean invoked = new AtomicBoolean();
        registry.register(agentRequiring("finance:write", invoked));
        LocalMessageBus bus = new LocalMessageBus(registry);

        AgentMessage message = AgentMessage.of("caller", "target", "do it");

        assertThrows(AuthorizationException.class, () -> bus.request(message, Duration.ofSeconds(5)));
    }

    @Test
    void anUnconfiguredRecipient_acceptsAnyCaller_zeroBehaviorChange() {
        AgentRegistry registry = new AgentRegistry();
        AtomicBoolean invoked = new AtomicBoolean();
        AgentId id = AgentId.of("open");
        AgentConfig config = AgentConfig.defaults().agentId(id).agentType("t").build();
        registry.register(new AraAgent() {
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return config; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                invoked.set(true);
                return AgentResponse.success(task.taskId(), id, "ok", 1, 0, 0.0, Duration.ZERO, List.of());
            }
            @Override public void terminate() {}
        });
        LocalMessageBus bus = new LocalMessageBus(registry);

        assertDoesNotThrow(() -> bus.request(AgentMessage.of("caller", "open", "do it"), Duration.ofSeconds(5)));
        assertEquals(true, invoked.get());
    }

    @Test
    void send_fireAndForget_swallowsTheDenialAndNeverInvokesTheRecipient() throws InterruptedException {
        AgentRegistry registry = new AgentRegistry();
        AtomicBoolean invoked = new AtomicBoolean();
        registry.register(agentRequiring("finance:write", invoked));
        LocalMessageBus bus = new LocalMessageBus(registry);

        AgentMessage message = AgentMessage.of("caller", "target", "do it")
                .withSenderScopes(ScopeSet.of("ops"));

        assertDoesNotThrow(() -> bus.send(message), "fire-and-forget must not throw to the caller");
        Thread.sleep(200); // send() dispatches on its own virtual thread
        assertFalse(invoked.get());
    }
}
