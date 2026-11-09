package io.ara.runtime.wiring;

import io.ara.runtime.artifact.ArtifactSink;
import io.ara.core.agent.AgentConfig;
import io.ara.core.llm.LlmClient;
import io.ara.core.media.MediaStore;
import io.ara.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of everything a session needs to execute, pinned for the session's
 * whole lifetime (ADR-039 §1, §6 "Forma di design").
 *
 * <p>Aggregates the config (parameter/governance axes) and the already-resolved
 * collaborators the strategy calls — not raw leases. The {@link #llm()} policy
 * (single / failover / round-robin) is resolved once at build time by {@code
 * WiringFactory}, over transports already leased from the shared registries; {@link
 * #leases()} is what {@link #close()} releases at session teardown.
 *
 * <p>{@link #mediaStore()} is the store the runtime was built with, kept here because it is the session
 * that decides which of its media a tool may read (see {@code MediaAccess}). {@code artifactSink} cannot
 * stand in for it: a runtime without an artifact extractor gets the sink that does nothing, which does not
 * hold the store.
 */
public record AgentWiring(
        AgentConfig config,
        LlmClient llm,
        ToolRegistry toolRegistry,
        List<Lease<?>> leases,
        ArtifactSink artifactSink,
        MediaStore mediaStore
) implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AgentWiring.class);

    public AgentWiring {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(llm, "llm must not be null");
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        leases = List.copyOf(Objects.requireNonNull(leases, "leases must not be null"));
        Objects.requireNonNull(artifactSink, "artifactSink must not be null");
        Objects.requireNonNull(mediaStore, "mediaStore must not be null");
    }

    /**
     * The shape from before {@code mediaStore} existed, with {@link MediaStore#noop()}: a wiring built by
     * hand, and the deprecated static one, offer no media access to their tools, as before.
     */
    public AgentWiring(AgentConfig config, LlmClient llm, ToolRegistry toolRegistry, List<Lease<?>> leases,
                       ArtifactSink artifactSink) {
        this(config, llm, toolRegistry, leases, artifactSink, MediaStore.noop());
    }

    /**
     * The shape from before {@code artifactSink} existed, with a sink that emits nothing: every wiring
     * built by hand, and every agent of a runtime that never asked for artifacts, keeps behaving as before.
     */
    public AgentWiring(AgentConfig config, LlmClient llm, ToolRegistry toolRegistry, List<Lease<?>> leases) {
        this(config, llm, toolRegistry, leases, ArtifactSink.none());
    }

    /**
     * Releases every leased resource. Idempotent (each {@link Lease#close()} is) and
     * best-effort: a failure releasing one lease does not prevent the rest from closing.
     */
    @Override
    public void close() {
        for (Lease<?> lease : leases) {
            try {
                lease.close();
            } catch (RuntimeException e) {
                log.warn("Error releasing a lease while closing AgentWiring", e);
            }
        }
    }
}
