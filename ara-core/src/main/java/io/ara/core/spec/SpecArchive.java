package io.ara.core.spec;

import io.ara.core.agent.AgentConfig;

import java.util.Optional;

/**
 * The minimal slice of the variant archive that the recipe-cache fast-path needs
 * (ADR-0072 D3/D4). The full archive — quality-diversity selection, lineage, staleness —
 * is ADR-0082's job; this port exposes only what {@code RecipeCacheResolver} asks of it:
 * "for this task class, is there a promoted variant, and what is its behavioural config?"
 *
 * <p><b>Only promoted (Default) variants.</b> {@link #bestFor} must never return a
 * {@code draft}/{@code shadow}/{@code canary} or a {@code stale} variant (ADR-0072 D4,
 * ADR-0062): a fast-path that resolved an unvalidated variant would use production traffic
 * as its test bed, defeating the promotion gate (ADR-0083). The filtering lives in the
 * implementation, not in the caller.
 *
 * <p>{@code AgentConfig}, not {@code AgentSpec}: the resolver only needs the behavioural
 * payload, and keeping this port on the published {@code ara-core} type decouples it from
 * where {@code AgentSpec} lives and from ADR-0082's eventual shape.
 */
public interface SpecArchive {

    /**
     * The behavioural config of the best promoted variant for {@code label} (a
     * {@code task_class}), or empty if there is no promoted variant — the cache miss that
     * falls to the factory's {@code else} arc (ADR-0072 D2).
     */
    Optional<AgentConfig> bestFor(String label);

    /** A process-local archive that holds only what is explicitly {@code put} into it. */
    static SpecArchive inMemory() {
        return new InMemorySpecArchive();
    }
}
