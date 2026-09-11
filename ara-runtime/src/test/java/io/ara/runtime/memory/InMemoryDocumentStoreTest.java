package io.ara.runtime.memory;

import io.ara.core.memory.EmbeddingClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioural tests for {@link InMemoryDocumentStore} — ranking, cap, lifecycle, and the
 * concurrent access the read/write lock guards. The ranking assertions rely on a
 * keyword-based deterministic embedding (below), not on a real model.
 */
class InMemoryDocumentStoreTest {

    private static final class KeywordEmbeddingClient implements EmbeddingClient {

        /** Feature j is 1 when {@code text} contains the j-th keyword. */
        private final List<String> keywords;

        private KeywordEmbeddingClient(List<String> keywords) {
            this.keywords = keywords;
        }

        @Override
        public List<Float> embed(String text) {
            return keywords.stream()
                    .map(k -> text.contains(k) ? 1f : 0f)
                    .toList();
        }

        @Override
        public int dimensions() {
            return keywords.size();
        }
    }

    private static final KeywordEmbeddingClient EMBED =
            new KeywordEmbeddingClient(List.of("cat", "dog", "bird", "apple"));

    private static InMemoryDocumentStore store() {
        return new InMemoryDocumentStore("test-kb", EMBED);
    }

    @Test
    void search_ranksBySimilarityAndCapsResults() {
        InMemoryDocumentStore kb = store();
        assertEquals(1, kb.indexDocument("doc-cat", "A cat", "a cat sits here"));
        assertEquals(1, kb.indexDocument("doc-cat-dog", "Cat and dog", "a cat and a dog"));
        assertEquals(1, kb.indexDocument("doc-bird", "A bird", "a bird flies"));

        List<DocumentChunk> hits = kb.search("a cat", 2);

        assertEquals(2, hits.size());
        assertEquals("doc-cat", hits.get(0).docId());
        // Normalised cosine: a pure "cat" vector against the same payload scores 1.0, the
        // mixed "cat+dog" half that, and every non-matching chunk scores 0.
        assertEquals(1.0f, hits.get(0).score(), 1e-5f);
        assertTrue(hits.get(0).score() > hits.get(1).score());
        assertTrue(hits.get(1).score() > 0);
    }

    @Test
    void search_limitIsRespected() {
        InMemoryDocumentStore kb = store();
        for (int i = 0; i < 5; i++) {
            kb.indexDocument("doc-" + i, "t" + i, "a cat" + i);
        }
        // All five chunks score 1.0 against "cat" (same keyword vector).
        assertEquals(3, kb.search("cat", 3).size());
    }

    @Test
    void search_invalidInputsReturnEmpty() {
        InMemoryDocumentStore kb = store();
        kb.indexDocument("d", "t", "a cat");
        assertTrue(kb.search(null, 5).isEmpty());
        assertTrue(kb.search("", 5).isEmpty());
        assertTrue(kb.search("   ", 5).isEmpty());
        assertTrue(kb.search("cat", 0).isEmpty());
        assertTrue(kb.search("cat", -1).isEmpty());
    }

    @Test
    void search_zeroVectorScoresZeroNotNaN() {
        InMemoryDocumentStore kb = new InMemoryDocumentStore(
                "k", new KeywordEmbeddingClient(List.of("cat")));
        kb.indexDocument("d", "t", "no keyword present");
        // Query "cat" matches nothing, but search has no score threshold — it must retain
        // the chunk with score 0.0, not drop it, and must never surface a NaN.
        List<DocumentChunk> hits = kb.search("cat", 5);
        assertEquals(1, hits.size());
        for (DocumentChunk c : hits) {
            assertEquals(0.0f, c.score(), 1e-5f, "zero vector must not produce NaN");
        }
    }

    @Test
    void deleteDocument_removesChunksAndRegistryEntry() {
        InMemoryDocumentStore kb = store();
        kb.indexDocument("d1", "Cat", "a cat");
        kb.indexDocument("d2", "Dog", "a dog");

        assertTrue(kb.deleteDocument("d2"));
        assertFalse(kb.deleteDocument("unknown-doc"));

        // Search has no score threshold, so the surviving "cat" chunk still ranks against
        // a "dog" query — with score 0 — but the deleted document must be gone entirely.
        List<DocumentChunk> afterDelete = kb.search("dog", 5);
        assertFalse(afterDelete.isEmpty());
        assertTrue(afterDelete.stream().allMatch(c -> c.docId().equals("d1")));
        assertEquals(List.of("d1"), List.copyOf(kb.listDocuments().keySet()));
    }

    @Test
    void reIndexingSameDocIdKeepsOldAndNewChunks() {
        InMemoryDocumentStore kb = store();
        assertEquals(1, kb.indexDocument("d", "v1", "a cat"));
        assertEquals(1, kb.indexDocument("d", "v2", "a dog"));

        // indexDocument appends rather than replaces: both versions' chunks stay indexed,
        // and the matching one ranks first.
        List<DocumentChunk> dogHits = kb.search("dog", 5);
        assertEquals(2, dogHits.size());
        assertEquals(1.0f, dogHits.get(0).score(), 1e-5f);

        List<DocumentChunk> catHits = kb.search("cat", 5);
        assertEquals(2, catHits.size());
        assertEquals(1.0f, catHits.get(0).score(), 1e-5f);
    }

    @Test
    void concurrentIndexingAndSearchingDoNotCorrupt() throws Exception {
        InMemoryDocumentStore kb = store();
        int writers = 4;
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[writers];
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < writers; i++) {
            final int id = i;
            threads[i] = Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 25; j++) {
                        kb.indexDocument("doc-" + id + "-" + j, "t", id % 2 == 0 ? "a cat" : "a dog");
                        kb.search(j % 2 == 0 ? "cat" : "dog", 3);
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(30));
        }
        assertEquals(0, failures.get(), "neither indexing nor searching may throw under concurrency");
        // Registry must remain intact after all writes.
        assertTrue(kb.listDocuments().size() >= writers);
    }
}