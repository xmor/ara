package io.ara.core.bus;

import io.ara.core.auth.ScopeSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-033 Fase 2 — {@link AgentMessage#senderScopes()} defaults to {@link ScopeSet#EMPTY}
 * (zero behaviour change for every existing caller) and survives {@link
 * AgentMessage#withSenderScopes}.
 */
class AgentMessageScopesTest {

    @Test
    void factoryMethods_defaultSenderScopesToEmpty() {
        AgentMessage message = AgentMessage.of("caller", "target", "do it");
        assertEquals(ScopeSet.EMPTY, message.senderScopes());
    }

    @Test
    void withSenderScopes_returnsACopyCarryingTheNewScopes() {
        AgentMessage original = AgentMessage.of("caller", "target", "do it");
        AgentMessage withScopes = original.withSenderScopes(ScopeSet.of("finance:write"));

        assertEquals(ScopeSet.EMPTY, original.senderScopes(), "the original is unchanged");
        assertEquals(ScopeSet.of("finance:write"), withScopes.senderScopes());
        assertEquals(original.messageId(), withScopes.messageId(), "identity fields are preserved");
    }

    @Test
    void reply_carriesNoSenderScopes() {
        AgentMessage request = AgentMessage.of("caller", "target", "do it")
                .withSenderScopes(ScopeSet.of("finance:write"));
        AgentMessage reply = AgentMessage.reply(request, "target", "done");

        assertEquals(ScopeSet.EMPTY, reply.senderScopes());
    }
}
