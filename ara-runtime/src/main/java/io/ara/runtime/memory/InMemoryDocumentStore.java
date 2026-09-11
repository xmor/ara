package io.ara.runtime.memory;

import io.ara.core.memory.EmbeddingClient;
import io.ara.core.retriever.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Pure in-memory document store for RAG — no external infrastructure required.
 *
 * <p>Vectors are stored in a Java list and searched with brute-force cosine
 * similarity (O(n·d)). Suitable for development, testing, and small knowledge
 * bases (up to ~10 k chunks). For production use {@link DocumentStore} (Qdrant).
 *
 * <p>All state is lost when the JVM stops — documents are re-indexed from H2
 * on startup via {@code KnowledgeBaseService#init()}.
 */
public final class InMemoryDocumentStore implements KbStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryDocumentStore.class);

    private record Entry(String docId, String title, String content, float[] vector) {}

    private final String          kbId;
    private final EmbeddingClient embeddingClient;
    /**
     * Backing store of scored chunks. An {@link ArrayList} guarded by {@link #lock} rather
     * than a {@code CopyOnWriteArrayList}: indexing a document split into N chunks with COW
     * copies the whole backing array once per chunk — O(N²) copy work on ingest — for a
     * structure whose writes, while infrequent, are bulk. Reads (search, delete scans) take
     * the read lock; the write lock is held only for the duration of one document's indexing
     * or deletion.
     */
    private final List<Entry> entries = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** In-memory registry of indexed documents: docId → title. */
    private final Map<String, String> docRegistry = new LinkedHashMap<>();

    public InMemoryDocumentStore(String kbId, EmbeddingClient embeddingClient) {
        this.kbId            = Objects.requireNonNull(kbId,            "kbId must not be null");
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient must not be null");
    }

    // ── No-op lifecycle ───────────────────────────────────────────────────────

    /** No-op: no external collection to create. */
    public void ensureCollection() {
        log.info("[InMemoryDocumentStore] kb=[{}] ready (in-memory)", kbId);
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    public int indexDocument(String docId, String title, String content) {
        Objects.requireNonNull(docId,   "docId must not be null");
        Objects.requireNonNull(title,   "title must not be null");
        Objects.requireNonNull(content, "content must not be null");

        List<String> chunks = DocumentStore.chunk(content);
        log.info("[InMemoryDocumentStore] Indexing '{}' → {} chunks", title, chunks.size());

        lock.writeLock().lock();
        try {
            for (String chunkText : chunks) {
                List<Float> vector = embeddingClient.embed(chunkText);
                entries.add(new Entry(docId, title, chunkText, normalize(toFloatArray(vector))));
            }
            docRegistry.put(docId, title);
        } finally {
            lock.writeLock().unlock();
        }
        log.info("[InMemoryDocumentStore] Indexed '{}' ({} chunks)", title, chunks.size());
        return chunks.size();
    }

    public boolean deleteDocument(String docId) {
        Objects.requireNonNull(docId, "docId must not be null");
        lock.writeLock().lock();
        try {
            entries.removeIf(e -> e.docId().equals(docId));
        } finally {
            lock.writeLock().unlock();
        }
        boolean known;
        synchronized (docRegistry) { known = docRegistry.remove(docId) != null; }
        log.info("[InMemoryDocumentStore] Deleted document '{}'", docId);
        return known;
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public List<DocumentChunk> search(String query, int maxResults) {
        if (query == null || query.isBlank() || maxResults <= 0) return List.of();

        float[] qNorm = normalize(toFloatArray(embeddingClient.embed(query)));

        // Bounded top-k via a min-heap: O(n log k) rather than the O(n log n) full sort
        // the old list-and-sort paid — k is typically ≤ 20 while n can be thousands.
        lock.readLock().lock();
        try {
            record Scored(Entry entry, float score) {}
            PriorityQueue<Scored> heap =
                    new PriorityQueue<>(maxResults + 1, Comparator.comparingDouble(Scored::score));
            for (Entry e : entries) {
                float score = dot(qNorm, e.vector());
                if (heap.size() < maxResults) {
                    heap.offer(new Scored(e, score));
                } else if (score > heap.peek().score()) {
                    heap.poll();
                    heap.offer(new Scored(e, score));
                }
            }
            return heap.stream()
                    .sorted(Comparator.comparingDouble(Scored::score).reversed())
                    .map(s -> new DocumentChunk(
                            s.entry().docId(), s.entry().title(), s.entry().content(), 0, s.score()))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, int maxResults) {
        return search(query, maxResults).stream()
                .map(c -> new RetrievedChunk(c.docId(), c.title(), c.content(), c.score()))
                .toList();
    }

    public Map<String, String> listDocuments() {
        synchronized (docRegistry) { return Collections.unmodifiableMap(new LinkedHashMap<>(docRegistry)); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Cosine over L2-normalised vectors. Storing and querying normalised vectors turns
     * cosine similarity into a plain dot product — the old {@code cosine} recomputed both
     * norms again for every entry, and the query norm once per entry, on every search.
     * A zero vector normalises to itself and then scores 0 against everything, matching the
     * old {@code na == 0 || nb == 0} guard's outcome without the per-entry branching.
     *
     * <p>Everything else in the class is unchanged by the swap: ranking is the same cosine
     * distance, to the precision of a single float dot product.
     */
    private static float dot(float[] a, float[] b) {
        float sum = 0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) sum += a[i] * b[i];
        return sum;
    }

    private static float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        norm = Math.sqrt(norm);
        if (norm == 0) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    private static float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }
}
