package io.ara.core.spec;

import io.ara.core.agent.AgentConfig;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local {@link SpecArchive}: one promoted config per task-class label, set with
 * {@link #promote}. A reference implementation for tests and single-process use — a real
 * archive (ADR-0082) implements the same {@link #bestFor} contract over its own
 * quality-diversity store, filtering out non-promoted and stale variants.
 */
public final class InMemorySpecArchive implements SpecArchive {

    private final ConcurrentHashMap<String, AgentConfig> promotedByLabel = new ConcurrentHashMap<>();

    @Override
    public Optional<AgentConfig> bestFor(String label) {
        return Optional.ofNullable(promotedByLabel.get(Objects.requireNonNull(label, "label must not be null")));
    }

    /** Records {@code config} as the promoted (Default) variant for {@code label}, replacing any prior one. */
    public void promote(String label, AgentConfig config) {
        promotedByLabel.put(Objects.requireNonNull(label, "label must not be null"),
                Objects.requireNonNull(config, "config must not be null"));
    }

    /** Removes the promoted variant for {@code label} — a rollback (ADR-0083) leaves the label a cache miss. */
    public void demote(String label) {
        promotedByLabel.remove(Objects.requireNonNull(label, "label must not be null"));
    }
}
