package io.ara.runtime.memory;

import java.util.List;

/**
 * How a document is cut into chunks before it is embedded: the largest chunk, in characters, and how many
 * characters of the previous chunk are repeated at the start of the next so a sentence cut by the boundary is
 * still whole in one of the two.
 *
 * <p>{@link #DEFAULT} is what {@link KbStore#indexDocument(String, String, String)} has always used. A different
 * policy goes through {@link KbStore#indexDocument(String, String, String, ChunkPolicy)}.
 *
 * <p>The bounds keep the splitter well-behaved: a chunk under {@value #MIN_SIZE} characters is hardly a passage, one
 * over {@value #MAX_SIZE} is likely to exceed an embedding model's input, and an overlap of half the chunk or more
 * would make the window stop advancing.
 *
 * @param size    largest chunk, in characters
 * @param overlap characters repeated between consecutive chunks; at least 0 and less than half of {@code size}
 */
public record ChunkPolicy(int size, int overlap) {

    public static final int MIN_SIZE = 100;
    public static final int MAX_SIZE = 8000;

    /** The split every store has always applied: {@link ChunkUtil#CHUNK_SIZE} and {@link ChunkUtil#CHUNK_OVERLAP}. */
    public static final ChunkPolicy DEFAULT = new ChunkPolicy(ChunkUtil.CHUNK_SIZE, ChunkUtil.CHUNK_OVERLAP);

    public ChunkPolicy {
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException("chunk size must be between " + MIN_SIZE + " and " + MAX_SIZE
                    + " characters, got " + size);
        }
        if (overlap < 0 || overlap >= size / 2) {
            throw new IllegalArgumentException("chunk overlap must be at least 0 and less than half of the size ("
                    + size / 2 + "), got " + overlap);
        }
    }

    public boolean isDefault() {
        return equals(DEFAULT);
    }

    /** Splits {@code text} the way this policy says. */
    public List<String> split(String text) {
        return ChunkUtil.split(text, size, overlap);
    }
}
