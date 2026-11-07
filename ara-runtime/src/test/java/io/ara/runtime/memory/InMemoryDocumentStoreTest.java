package io.ara.runtime.memory;

import io.ara.core.memory.EmbeddingClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** Counts calls to each method separately, so a test can prove which path was taken. */
    private static final class CountingEmbeddingClient implements EmbeddingClient {
        final AtomicInteger embedCalls    = new AtomicInteger();
        final AtomicInteger embedAllCalls = new AtomicInteger();

        @Override
        public List<Float> embed(String text) {
            embedCalls.incrementAndGet();
            return List.of(1f);
        }

        @Override
        public List<List<Float>> embedAll(List<String> texts) {
            embedAllCalls.incrementAndGet();
            return texts.stream().<List<Float>>map(t -> List.of(1f)).toList();
        }

        @Override
        public int dimensions() { return 1; }
    }

    @Test
    void indexDocument_batchesEveryChunkIntoOneEmbedAllCall() {
        CountingEmbeddingClient counting = new CountingEmbeddingClient();
        InMemoryDocumentStore kb = new InMemoryDocumentStore("k", counting);

        // Long enough to force several 600-char chunks (ChunkUtil.CHUNK_SIZE), so a
        // regression back to a per-chunk embed() loop would show up as embedAllCalls == 1
        // but embedCalls > 0, or as embedAllCalls > 1.
        String longContent = "paragraph one. ".repeat(100);
        int chunkCount = kb.indexDocument("doc", "title", longContent);

        assertTrue(chunkCount > 1, "the test fixture must actually produce multiple chunks");
        assertEquals(1, counting.embedAllCalls.get(),
                "indexDocument must call embedAll exactly once regardless of chunk count");
        assertEquals(0, counting.embedCalls.get(),
                "indexDocument must not fall back to a per-chunk embed() loop");
    }

    @Test
    void indexDocument_withAPolicyCutsTheChunksAsAsked() {
        String content = "cat sentence about a cat. ".repeat(80);
        InMemoryDocumentStore defaults = store();
        InMemoryDocumentStore narrow = store();

        int byDefault = defaults.indexDocument("d", "t", content);
        int withPolicy = narrow.indexDocument("d", "t", content, new ChunkPolicy(200, 20));

        assertTrue(withPolicy > byDefault, "withPolicy=" + withPolicy + " byDefault=" + byDefault);
        assertEquals(byDefault, store().indexDocument("d", "t", content, ChunkPolicy.DEFAULT),
                "the default policy is the three-argument split");
        assertTrue(narrow.retrieve("cat", 50).stream().allMatch(c -> c.content().length() <= 200));
    }

    @Test
    void kbStoreDefaultMethodRefusesACustomPolicyItCannotHonour() {
        KbStore minimal = new KbStore() {
            @Override public void ensureCollection() { }
            @Override public int indexDocument(String docId, String title, String content) { return 7; }
            @Override public boolean deleteDocument(String docId) { return false; }
            @Override public java.util.Map<String, String> listDocuments() { return java.util.Map.of(); }
            @Override public List<io.ara.core.retriever.RetrievedChunk> retrieve(String query, int maxResults) { return List.of(); }
        };
        assertEquals(7, minimal.indexDocument("d", "t", "c", ChunkPolicy.DEFAULT));
        assertThrows(UnsupportedOperationException.class, () -> minimal.indexDocument("d", "t", "c", new ChunkPolicy(200, 20)));
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

    /**
     * {@code docs/analysis/concurrency-hardening.md} §3 N1 row 6, §4 U26 — regression test.
     *
     * <p>Before U26, {@code indexDocument} called {@code embeddingClient.embed} (a network call
     * to an embedding provider, in production) once per chunk while holding {@code
     * lock.writeLock()} — a slow or stalled provider call serialized every concurrent
     * {@code search} (which only needs the read lock) behind it. This blocks {@code embed}
     * on a latch to keep it in flight, and checks that a concurrent {@code search} on the same
     * store returns promptly instead of waiting for it.
     */
    @Test
    void indexDocument_doesNotHoldTheLockWhileEmbedding_soSearchProceedsConcurrently() throws Exception {
        CountDownLatch embedStarted = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        EmbeddingClient blockingOnce = new EmbeddingClient() {
            private volatile boolean first = true;

            @Override
            public List<Float> embed(String text) {
                if (first) {
                    first = false;
                    embedStarted.countDown();
                    try {
                        assertTrue(proceed.await(10, TimeUnit.SECONDS), "test must release the blocked embed call");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return EMBED.embed(text);
            }

            @Override
            public int dimensions() {
                return EMBED.dimensions();
            }
        };
        InMemoryDocumentStore kb = new InMemoryDocumentStore("kb", blockingOnce);

        Thread indexer = Thread.ofVirtual().start(() -> kb.indexDocument("d1", "t", "a cat"));
        try {
            assertTrue(embedStarted.await(5, TimeUnit.SECONDS), "indexDocument's embed call must have started");

            long startNanos = System.nanoTime();
            List<DocumentChunk> hits = kb.search("cat", 5);   // must not wait for the write lock
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

            assertTrue(hits.isEmpty(), "the document being indexed has not been published yet");
            assertTrue(elapsedMs < 2000,
                    "search must not block on indexDocument's still-in-flight embed() call "
                            + "(U26: embedding must run with no lock held), took " + elapsedMs + "ms");
        } finally {
            proceed.countDown();
            indexer.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertEquals(1, kb.search("cat", 5).size(), "the document must be published once embedding finishes");
    }

    /**
     * {@code docs/analysis/concurrency-hardening.md} §4 U26 — regression test for a second bug
     * found while implementing U26: {@code docRegistry} used to be guarded by {@code
     * lock.writeLock()} in {@code indexDocument} but by {@code synchronized (docRegistry)} in
     * {@code deleteDocument}/{@code listDocuments} — two uncoordinated locks over the same plain
     * {@link java.util.LinkedHashMap}, giving those call sites no mutual exclusion with each
     * other at all. Stresses concurrent {@code indexDocument} and {@code listDocuments} calls;
     * a corrupted map would surface here as a thrown exception (e.g.
     * {@code ConcurrentModificationException}, or worse, a resize-loop hang) rather than a
     * clean assertion failure, so the meaningful signal is that this completes at all.
     */
    @Test
    void concurrentIndexingAndListingDocRegistryDoesNotCorrupt() throws Exception {
        InMemoryDocumentStore kb = store();
        int writers = 4;
        int readers = 4;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < writers; i++) {
            final int id = i;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 50; j++) {
                        kb.indexDocument("doc-" + id + "-" + j, "t", "a cat");
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }));
        }
        for (int i = 0; i < readers; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 200; j++) {
                        kb.listDocuments();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(30));
        }

        assertEquals(0, failures.get(), "concurrent indexDocument + listDocuments must never throw");
        assertEquals(writers * 50, kb.listDocuments().size(), "every indexed document must be registered exactly once");
    }
}