package io.ara.core.memory;

import java.util.List;
import java.util.Objects;

/**
 * One semantic-memory entry as it is handed to {@link SemanticStore#upsertAll}, decoupled
 * from the {@code (agentId, role, type, content, vector)} positional arguments of {@link
 * SemanticStore#upsert} so a batch can be passed as a single list.
 */
public record SemanticEntry(String role, String type, String content, List<Float> vector) {

    public SemanticEntry {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(vector, "vector must not be null");
    }
}