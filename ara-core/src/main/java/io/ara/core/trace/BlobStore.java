package io.ara.core.trace;

import java.util.Optional;

/**
 * Content-addressed store for the raw prompt/output bytes a {@link TraceSpan} refers to
 * (ADR-0068 D2). {@link #put} returns the SHA-256 hex of the content — the same hashing
 * principle as {@code SpecLineage.hash} (ADR-0065) and {@code PromptCatalogEntry} lineage
 * (ADR-0066), here over arbitrary bytes — so an identical prompt reused across a thousand
 * spans is stored once.
 */
public interface BlobStore {

    /** Stores {@code content} and returns its content ref (lowercase hex SHA-256). Idempotent. */
    String put(byte[] content);

    /** The bytes previously stored under {@code ref}, if any. */
    Optional<byte[]> get(String ref);

    /** A process-local reference implementation — not durable across a JVM restart. */
    static BlobStore inMemory() {
        return new InMemoryBlobStore();
    }
}
