package io.ara.runtime.memory;

import io.ara.core.retriever.Retriever;

import java.util.Map;
import java.util.Objects;

/**
 * Common interface for knowledge-base backing stores (Qdrant and in-memory).
 */
public interface KbStore extends Retriever {

    void ensureCollection();

    int indexDocument(String docId, String title, String content);

    /**
     * Indexes {@code content} cut by {@code policy} instead of the default split. A store that cannot honour a
     * policy of its own refuses with {@link UnsupportedOperationException} rather than index with a different one
     * than was asked; the default policy is always accepted.
     */
    default int indexDocument(String docId, String title, String content, ChunkPolicy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        if (policy.isDefault()) {
            return indexDocument(docId, title, content);
        }
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not support a custom chunk policy");
    }

    boolean deleteDocument(String docId);

    Map<String, String> listDocuments();
}
