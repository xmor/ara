package io.ara.core.agent;

import io.ara.core.common.AgentId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 1 — the three authorization scope lists are additive on
 * {@link ExecutionConfig}/{@link AgentConfig} with an empty default (no behaviour change),
 * survive {@code toBuilder()}, and {@link AgentCard} defaults to "open".
 */
class AgentConfigScopesTest {

    @Test
    void defaultConfig_hasEmptyScopeLists() {
        AgentConfig config = AgentConfig.defaults().agentType("analyst").build();

        assertTrue(config.grantedScopes().isEmpty());
        assertTrue(config.visibleToScopes().isEmpty());
        assertTrue(config.requiredScopes().isEmpty());
    }

    @Test
    void builderSetsScopes_andToBuilderPreservesThem() {
        AgentConfig config = AgentConfig.defaults()
                .agentType("finance")
                .grantedScopes(List.of("finance:read", "ops"))
                .visibleToScopes(List.of("finance:read"))
                .requiredScopes(List.of("finance:read"))
                .build();

        assertEquals(List.of("finance:read", "ops"), config.grantedScopes());
        assertEquals(List.of("finance:read"), config.visibleToScopes());
        assertEquals(List.of("finance:read"), config.requiredScopes());

        AgentConfig round = config.toBuilder().build();
        assertEquals(config.grantedScopes(), round.grantedScopes());
        assertEquals(config.visibleToScopes(), round.visibleToScopes());
        assertEquals(config.requiredScopes(), round.requiredScopes());
    }

    @Test
    void scopeListsAreDefensivelyCopiedAndImmutable() {
        AgentConfig config = AgentConfig.defaults()
                .agentType("x")
                .grantedScopes(new java.util.ArrayList<>(List.of("ops")))
                .build();
        assertThrows(UnsupportedOperationException.class, () -> config.grantedScopes().add("hr"));
    }

    @Test
    void legacyExecutionConfigConstructor_stillCompilesAndDefaultsScopesToEmpty() {
        ExecutionConfig legacy = new ExecutionConfig(
                "react", null, List.of(), List.of(), 10, java.time.Duration.ofMinutes(5),
                4096, false, null, SessionBusyPolicy.REJECT, null,
                DelegateStateAccess.OVERLAY, ExecutionConfig.DEFAULT_SESSION_TTL);
        assertTrue(legacy.grantedScopes().isEmpty());
        assertTrue(legacy.visibleToScopes().isEmpty());
        assertTrue(legacy.requiredScopes().isEmpty());
    }

    @Test
    void agentCard_legacyConstructorDefaultsToOpen() {
        AgentCard card = new AgentCard(AgentId.of("a1"), "A", "desc", "1.0.0",
                new AgentCapabilities(false, false, List.of("react")));

        assertTrue(card.requiredScopes().isEmpty());
        assertEquals(List.of("none"), card.authenticationSchemes());
    }

    @Test
    void agentCard_carriesScopesWhenGiven() {
        AgentCard card = new AgentCard(AgentId.of("a1"), "A", "desc", "1.0.0",
                new AgentCapabilities(false, false, List.of("react")),
                List.of("finance:read"), List.of("oauth2"));

        assertEquals(List.of("finance:read"), card.requiredScopes());
        assertEquals(List.of("oauth2"), card.authenticationSchemes());
    }
}
