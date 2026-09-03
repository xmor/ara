package io.ara.core.trace;

import io.ara.core.internal.Sha256;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local {@link BlobStore} backed by a {@link ConcurrentHashMap} keyed by content
 * ref. Deduplication is automatic: the same bytes hash to the same ref and overwrite
 * their own identical entry.
 */
public final class InMemoryBlobStore implements BlobStore {

    private final ConcurrentHashMap<String, byte[]> byRef = new ConcurrentHashMap<>();

    @Override
    public String put(byte[] content) {
        Objects.requireNonNull(content, "content must not be null");
        String ref = Sha256.hex(content);
        byRef.putIfAbsent(ref, content.clone());
        return ref;
    }

    @Override
    public Optional<byte[]> get(String ref) {
        byte[] stored = byRef.get(Objects.requireNonNull(ref, "ref must not be null"));
        return Optional.ofNullable(stored == null ? null : stored.clone());
    }
}
