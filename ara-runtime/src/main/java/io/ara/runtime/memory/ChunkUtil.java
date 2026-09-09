package io.ara.runtime.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for splitting a large text document into overlapping chunks suitable for
 * embedding and storage. The implementation mirrors the original logic that lived in
 * {@link DocumentStore} and is now shared by both {@link DocumentStore} and
 * {@link InMemoryDocumentStore}.
 *
 * <p>The chunking algorithm works in three stages:
 * <ol>
 *   <li>Split the input on paragraph boundaries (two or more newlines).</li>
 *   <li>If a paragraph still exceeds {@link #CHUNK_SIZE}, attempt to split on sentence
 *       boundaries (". ") before falling back to a hard character window.</li>
 *   <li>Maintain an overlap of {@link #CHUNK_OVERLAP} characters between consecutive
 *       chunks to preserve context.</li>
 * </ol>
 *
 * <p>The constants are deliberately generous to favour early eviction rather than
 * exceeding a model's context window.
 */
public final class ChunkUtil {

    /** Maximum characters per chunk before forcing a further split. */
    /** Maximum characters per chunk before forcing a further split. */
    public static final int CHUNK_SIZE = 600;
    /** Number of characters that overlap between adjacent chunks. */
    /** Number of characters that overlap between adjacent chunks. */
    public static final int CHUNK_OVERLAP = 80;

    private ChunkUtil() {
        // Utility class – prevent instantiation.
    }

    /**
     * Splits {@code text} into a list of chunks according to the rules described in the
     * class Javadoc. The returned list is immutable; if the input is empty a singleton
     * list containing the original text is returned.
     */
    public static List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\n\n+");

        StringBuilder current = new StringBuilder();
        for (String para : paragraphs) {
            String p = para.strip();
            if (p.isBlank()) {
                continue;
            }

            if (current.length() + p.length() + 2 <= CHUNK_SIZE) {
                if (!current.isEmpty()) {
                    current.append("\n\n");
                }
                current.append(p);
            } else {
                if (!current.isEmpty()) {
                    chunks.add(current.toString());
                    // Preserve overlap from the tail of the previous chunk.
                    String tail = current.toString();
                    current.setLength(0);
                    if (tail.length() > CHUNK_OVERLAP) {
                        current.append(tail.substring(tail.length() - CHUNK_OVERLAP));
                    } else {
                        current.append(tail);
                    }
                }
                if (p.length() > CHUNK_SIZE) {
                    splitLarge(p, chunks);
                } else {
                    if (!current.isEmpty()) {
                        current.append("\n\n");
                    }
                    current.append(p);
                }
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks.isEmpty() ? List.of(text) : List.copyOf(chunks);
    }

    /**
     * Helper that breaks a large {@code text} segment into smaller pieces. It first tries
     * to break on a sentence boundary (". ") and, if that would still produce a segment
     * larger than {@link #CHUNK_SIZE / 2}, falls back to a hard character cut.
     */
    private static void splitLarge(String text, List<String> out) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            // Try to break on a period followed by a space.
            if (end < text.length()) {
                int dot = text.lastIndexOf(". ", end);
                if (dot > start + CHUNK_SIZE / 2) {
                    end = dot + 2; // include the period and space.
                }
            }
            out.add(text.substring(start, end).strip());
            // Move start forward, keeping an overlap so that context is not lost.
            start = Math.max(start + 1, end - CHUNK_OVERLAP);
        }
    }
}
