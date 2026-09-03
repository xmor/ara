package io.ara.core.spec;

import io.ara.core.agent.AgentConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0072 — {@link InMemorySpecArchive}: the minimal {@link SpecArchive} the recipe-cache
 * resolver needs. A label with no promoted variant is a cache miss; a demoted label
 * becomes a miss again (rollback, ADR-0083).
 */
class SpecArchiveTest {

    private static AgentConfig config(String type) {
        return AgentConfig.defaults().agentType(type).build();
    }

    @Test
    void bestFor_isEmptyUntilSomethingIsPromoted() {
        SpecArchive archive = SpecArchive.inMemory();
        assertTrue(archive.bestFor("billing").isEmpty());
    }

    @Test
    void promote_thenBestFor_returnsTheConfig_andDemoteMakesItAMissAgain() {
        InMemorySpecArchive archive = new InMemorySpecArchive();
        AgentConfig cfg = config("billing-handler");

        archive.promote("billing", cfg);
        assertEquals("billing-handler", archive.bestFor("billing").orElseThrow().agentType());

        archive.demote("billing");
        assertTrue(archive.bestFor("billing").isEmpty(), "a rollback leaves the label a cache miss");
    }

    @Test
    void promote_replacesThePriorPromotedVariant() {
        InMemorySpecArchive archive = new InMemorySpecArchive();
        archive.promote("billing", config("v1"));
        archive.promote("billing", config("v2"));
        assertEquals("v2", archive.bestFor("billing").orElseThrow().agentType());
    }

    @Test
    void nullGuards() {
        InMemorySpecArchive archive = new InMemorySpecArchive();
        assertThrows(NullPointerException.class, () -> archive.bestFor(null));
        assertThrows(NullPointerException.class, () -> archive.promote(null, config("x")));
        assertThrows(NullPointerException.class, () -> archive.promote("l", null));
    }
}
