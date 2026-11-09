package io.ara.core.memory;

import io.ara.core.media.MediaRef;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A single entry in any of the ARA memory tiers.
 *
 * <p>{@link #media} holds references, not bytes, and that is what keeps the working-memory
 * window usable with a document attached. With the payload inline, a 2 MB PDF becomes ~2.7
 * million base64 characters, which the char-based token estimate reads as ~680k tokens: with
 * any realistic budget the eviction loop then empties the window down to one entry — and the
 * survivor is the document, so the agent loses its own system prompt and keeps the base64.
 * A reference is a few dozen characters, so no eviction policy has to be clever about it.
 *
 * <p>Media belongs to the entry rather than sitting beside it precisely so eviction cannot
 * separate the two: dropping the text while keeping the attachment (or the reverse) leaves a
 * message no provider accepts. Being one record makes that structurally impossible instead of
 * something the eviction code has to remember, the way it has to for tool-call groups.
 *
 * @param role      speaker role: "system", "user", "assistant", "tool", "episode"
 * @param content   the text content of the entry
 * @param timestamp when this entry was recorded
 * @param metadata  optional structured metadata ({@link ToolCallMetadata},
 *                  {@link EpisodeLabel}); {@code null} when the entry carries none
 * @param media     references to images and documents attached to this entry, in presentation
 *                  order; never {@code null}, empty for the text-only entries that are the
 *                  overwhelming majority
 */
public record MemoryEntry(
        String role,
        String content,
        Instant timestamp,
        EntryMetadata metadata,
        List<MediaRef> media
) {
    public MemoryEntry {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        media = media != null ? List.copyOf(media) : List.of();
    }

    /**
     * Text-only 4-arg constructor, kept so that adding {@code media} to the canonical
     * constructor leaves every existing call site compiling and behaving identically.
     */
    public MemoryEntry(String role, String content, Instant timestamp, EntryMetadata metadata) {
        this(role, content, timestamp, metadata, List.of());
    }

    /** Creates a memory entry with the current timestamp and no metadata. */
    public static MemoryEntry of(String role, String content) {
        return new MemoryEntry(role, content, Instant.now(), null, List.of());
    }

    /** Creates a memory entry with the current timestamp and structured metadata. */
    public static MemoryEntry of(String role, String content, EntryMetadata metadata) {
        return new MemoryEntry(role, content, Instant.now(), metadata, List.of());
    }

    /**
     * Creates a memory entry carrying both metadata and media: the combination a tool result with an
     * image needs, and the one the narrower factories could not express.
     */
    public static MemoryEntry of(String role, String content, EntryMetadata metadata, List<MediaRef> media) {
        return new MemoryEntry(role, content, Instant.now(), metadata, media);
    }

    /** Creates a memory entry carrying media references and no metadata. */
    public static MemoryEntry of(String role, String content, List<MediaRef> media) {
        return new MemoryEntry(role, content, Instant.now(), null, media);
    }

    /** {@code true} when this entry carries at least one media reference. */
    public boolean hasMedia() {
        return !media.isEmpty();
    }
}
