package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AraAgents;
import io.ara.core.common.AgentId;
import io.ara.core.spec.InMemorySpecArchive;
import io.ara.core.spec.SpecArchive;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0072 — {@link RecipeCacheResolver} as a third {@link ClassifyAndActSpec.AgentResolver}:
 * a hit builds a worker from the archived promoted config, a miss returns {@code null} so
 * the label falls to the router's mandatory {@code else} arc.
 */
class RecipeCacheResolverTest {

    private static AgentConfig config(String type) {
        return AgentConfig.defaults().agentType(type).agentId(AgentId.of(type)).build();
    }

    /** Factory that records how often it built an agent and from which config. */
    private static final class RecordingFactory implements java.util.function.Function<AgentConfig, AraAgent> {
        final AtomicInteger builds = new AtomicInteger();
        volatile AgentConfig lastConfig;

        @Override public AraAgent apply(AgentConfig cfg) {
            builds.incrementAndGet();
            lastConfig = cfg;
            return AraAgents.deterministic(cfg.agentId(), t -> "recipe:" + cfg.agentType());
        }
    }

    @Test
    void cacheHit_buildsAWorkerFromTheArchivedConfig() {
        InMemorySpecArchive archive = new InMemorySpecArchive();
        AgentConfig promoted = config("billing-handler");
        archive.promote("billing", promoted);
        RecordingFactory factory = new RecordingFactory();

        AraAgent resolved = new RecipeCacheResolver(archive, factory).resolve("billing");

        assertEquals(1, factory.builds.get());
        assertSame(promoted, factory.lastConfig, "the worker is built from the archived promoted config");
        assertEquals("recipe:billing-handler",
                AraAgents.ask(resolved, "invoice #42").content());
    }

    @Test
    void cacheMiss_returnsNull_soTheLabelFallsToTheElseArc() {
        RecordingFactory factory = new RecordingFactory();

        AraAgent resolved = new RecipeCacheResolver(SpecArchive.inMemory(), factory).resolve("tech");

        assertNull(resolved, "no promoted variant → null → router's mandatory else arc (ADR-050)");
        assertEquals(0, factory.builds.get(), "no build attempted on a miss");
    }

    @Test
    void isCacheHit_reportsWithoutBuildingAnAgent() {
        InMemorySpecArchive archive = new InMemorySpecArchive();
        archive.promote("billing", config("billing-handler"));
        RecordingFactory factory = new RecordingFactory();
        RecipeCacheResolver resolver = new RecipeCacheResolver(archive, factory);

        assertTrue(resolver.isCacheHit("billing"));
        assertFalse(resolver.isCacheHit("tech"));
        assertFalse(resolver.isCacheHit(null));
        assertEquals(0, factory.builds.get(), "isCacheHit is pure — no agent built");
    }

    @Test
    void isAValidAgentResolver_pluggableIntoClassifyAndActBindings() {
        InMemorySpecArchive archive = new InMemorySpecArchive();
        archive.promote("billing", config("billing-handler"));
        ClassifyAndActSpec.AgentResolver resolver =
                new RecipeCacheResolver(archive, new RecordingFactory());

        ClassifyAndActSpec.Bindings bindings = ClassifyAndActSpec.Bindings.of(resolver);
        assertEquals("recipe:billing-handler",
                AraAgents.ask(bindings.agents().resolve("billing"), "x").content());
        assertNull(bindings.agents().resolve("unknown"));
    }
}
