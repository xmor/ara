package io.ara.runtime.bus;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.DelegateStateAccess;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.SessionStore;
import io.ara.core.auth.ScopeSet;
import io.ara.core.bus.AgentMessage;
import io.ara.core.bus.MessageBus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ADR-0077 D2/D3: the non-escalation invariant at its one real chokepoint —
 * {@link AgentDelegationTool#delegate}. The scope set handed to a delegate is
 * {@code incoming ∩ ownGrantedScopes}, and while nothing sets the key the hop is untouched.
 */
class AgentDelegationToolScopeAttenuationTest {

    private final AtomicReference<AgentMessage> sent = new AtomicReference<>();

    private final MessageBus capturingBus = new MessageBus() {
        @Override public void send(AgentMessage message) { sent.set(message); }
        @Override public AgentMessage request(AgentMessage message, Duration timeout) {
            sent.set(message);
            return AgentMessage.reply(message, message.recipientId(), "done");
        }
    };

    private AgentDelegationTool tool(ScopeSet ownGranted) {
        return new AgentDelegationTool(capturingBus, "caller", Duration.ofSeconds(1),
                DelegateStateAccess.OVERLAY, SessionStore.noop(), ownGranted);
    }

    @Test
    void narrowsToIntersectionOfCallerScopesAndOwnGrantedScopes() {
        AgentTask callerTask = AgentTask.of("parent")
                .withAttachment(RunContext.SCOPES_KEY, ScopeSet.of("read", "delete"));

        tool(ScopeSet.of("read", "write", "admin"))
                .execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", callerTask);

        ScopeSet effective = sent.get().runContext().opaque(RunContext.SCOPES_KEY, ScopeSet.class);
        assertEquals(ScopeSet.of("read"), effective, "{read,delete} ∩ {read,write,admin} = {read}");
    }

    @Test
    void anAgentWithNoGrantedScopesPassesNothingAlong() {
        AgentTask callerTask = AgentTask.of("parent")
                .withAttachment(RunContext.SCOPES_KEY, ScopeSet.of("read", "write"));

        tool(ScopeSet.EMPTY)
                .execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", callerTask);

        ScopeSet effective = sent.get().runContext().opaque(RunContext.SCOPES_KEY, ScopeSet.class);
        assertEquals(ScopeSet.EMPTY, effective);
    }

    @Test
    void whenTheCallerCarriesNoScopeSetTheHopIsUntouched() {
        tool(ScopeSet.of("read", "write"))
                .execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", AgentTask.of("plain parent"));

        assertNull(sent.get().runContext().opaque(RunContext.SCOPES_KEY, ScopeSet.class),
                "nothing sets the key today — the narrowing branch is inert");
    }

    @Test
    void narrowingComposesAcrossTwoHops() {
        // hop 1: caller {a,b,c} → B (granted {b,c,d}) ⇒ {b,c}
        AgentTask atA = AgentTask.of("A").withAttachment(RunContext.SCOPES_KEY, ScopeSet.of("a", "b", "c"));
        new AgentDelegationTool(capturingBus, "A", Duration.ofSeconds(1),
                DelegateStateAccess.OVERLAY, SessionStore.noop(), ScopeSet.of("b", "c", "d"))
                .execute("{\"agent_id\":\"B\",\"task\":\"x\"}", atA);
        ScopeSet atB = sent.get().runContext().opaque(RunContext.SCOPES_KEY, ScopeSet.class);
        assertEquals(ScopeSet.of("b", "c"), atB);

        // hop 2: {b,c} → C (granted {c,d,e}) ⇒ {c}
        AgentTask fromB = AgentTask.of("B").withAttachment(RunContext.SCOPES_KEY, atB);
        new AgentDelegationTool(capturingBus, "B", Duration.ofSeconds(1),
                DelegateStateAccess.OVERLAY, SessionStore.noop(), ScopeSet.of("c", "d", "e"))
                .execute("{\"agent_id\":\"C\",\"task\":\"y\"}", fromB);
        ScopeSet atC = sent.get().runContext().opaque(RunContext.SCOPES_KEY, ScopeSet.class);
        assertEquals(ScopeSet.of("c"), atC);
    }
}
