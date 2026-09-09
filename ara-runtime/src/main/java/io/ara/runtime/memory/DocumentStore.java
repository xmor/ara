package io.ara.runtime.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ara.core.memory.EmbeddingClient;
import io.ara.core.retriever.RetrievedChunk;
import io.ara.core.retriever.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Qdrant-backed document store for Retrieval-Augmented Generation (RAG).
 *
 * <p>Documents are split into overlapping text chunks, embedded, and stored
 * in a dedicated Qdrant collection. Each chunk carries a payload with the
 * source document id, title, and chunk index so search results can be
 * attributed back to their origin.
 *
 * <p>This store is independent of {@link QdrantSemanticStore} (agent episodic
 * memory) and uses a separate collection to avoid mixing agent memories with
 * indexed knowledge-base documents.
 *
 * <h2>Qdrant REST endpoints used</h2>
 * <ul>
 *   <li>{@code PUT  /collections/{name}}                 — create (idempotent)</li>
 *   <li>{@code PUT  /collections/{name}/points}          — bulk upsert chunks</li>
 *   <li>{@code POST /collections/{name}/points/delete}   — delete by doc_id filter</li>
 *   <li>{@code POST /collections/{name}/points/search}   — nearest-neighbour search</li>
 * </ul>
 *
 * <h2>Chunk payload schema</h2>
 * <pre>
 *   { "doc_id": "doc-001", "title": "...", "content": "...",
 *     "chunk_index": 0, "indexed_at": "2025-..." }
 * </pre>
 */
public final class DocumentStore implements KbStore {

    private static final Logger      log    = LoggerFactory.getLogger(DocumentStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();


    private final QdrantConfig  config;
    private final EmbeddingClient embeddingClient;
    private final HttpClient    http;

    /** In-memory registry of indexed documents: docId → title. */
    private final Map<String, String> docRegistry = new ConcurrentHashMap<>();

    public DocumentStore(QdrantConfig config, EmbeddingClient embeddingClient) {
        this.config          = Objects.requireNonNull(config,          "config must not be null");
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient must not be null");
        this.http            = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Creates the collection in Qdrant if it does not already exist. */
    public void ensureCollection() {
        try {
            ObjectNode vectors = MAPPER.createObjectNode();
            vectors.put("size",     config.vectorSize());
            vectors.put("distance", "Cosine");

            ObjectNode body = MAPPER.createObjectNode();
            body.set("vectors", vectors);

            HttpResponse<String> r = send("PUT", "/collections/" + config.collectionName(), body);
            if (r.statusCode() == 200 || r.statusCode() == 409) {
                log.info("[DocumentStore] Collection '{}' ready (dims={})",
                        config.collectionName(), config.vectorSize());
            } else {
                throw new RuntimeException("Failed to create collection: HTTP " + r.statusCode()
                        + " — " + r.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("ensureCollection failed: " + e.getMessage(), e);
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Splits {@code content} into chunks, embeds each, and upserts them
     * into Qdrant.  Idempotent: re-indexing the same {@code docId} with
     * new content will create new points; call {@link #deleteDocument} first
     * to replace an existing document cleanly.
     *
     * @param docId   unique document identifier (e.g. {@code "doc-001"})
     * @param title   display name shown in search results
     * @param content full document text
     * @return number of chunks indexed
     */
    public int indexDocument(String docId, String title, String content) {
        Objects.requireNonNull(docId,   "docId must not be null");
        Objects.requireNonNull(title,   "title must not be null");
        Objects.requireNonNull(content, "content must not be null");

        List<String> chunks = chunk(content);
        log.info("[DocumentStore] Indexing '{}' → {} chunks", title, chunks.size());

        ArrayNode points = MAPPER.createArrayNode();
        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            List<Float> vector = embeddingClient.embed(chunkText);

            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("doc_id",      docId);
            payload.put("title",       title);
            payload.put("content",     chunkText);
            payload.put("chunk_index", i);
            payload.put("indexed_at",  java.time.Instant.now().toString());

            ObjectNode point = MAPPER.createObjectNode();
            point.put("id",     UUID.randomUUID().toString());
            point.set("vector",  floatArray(vector));
            point.set("payload", payload);
            points.add(point);
        }

        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.set("points", points);

            HttpResponse<String> r = send("PUT",
                    "/collections/" + config.collectionName() + "/points", body);
            if (r.statusCode() != 200) {
                throw new RuntimeException("Upsert failed: HTTP " + r.statusCode() + " — " + r.body());
            }

            docRegistry.put(docId, title);
            log.info("[DocumentStore] Indexed '{}' ({} chunks)", title, chunks.size());
            return chunks.size();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("indexDocument failed: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes all chunks belonging to {@code docId} from Qdrant using a
     * payload filter.
     *
     * @param docId the document to remove
     * @return {@code true} if the document was known, {@code false} if not found
     */
    public boolean deleteDocument(String docId) {
        Objects.requireNonNull(docId, "docId must not be null");
        try {
            ObjectNode matchValue = MAPPER.createObjectNode();
            matchValue.put("value", docId);

            ObjectNode fieldCond = MAPPER.createObjectNode();
            fieldCond.put("key", "doc_id");
            fieldCond.set("match", matchValue);

            ArrayNode must = MAPPER.createArrayNode();
            must.add(fieldCond);

            ObjectNode filter = MAPPER.createObjectNode();
            filter.set("must", must);

            ObjectNode body = MAPPER.createObjectNode();
            body.set("filter", filter);

            HttpResponse<String> r = send("POST",
                    "/collections/" + config.collectionName() + "/points/delete", body);

            boolean known = docRegistry.remove(docId) != null;
            if (r.statusCode() == 200) {
                log.info("[DocumentStore] Deleted document '{}'", docId);
            } else {
                log.warn("[DocumentStore] Delete returned HTTP {} for docId={}", r.statusCode(), docId);
            }
            return known;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("deleteDocument failed: " + e.getMessage(), e);
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Searches for the most relevant chunks for {@code query}.
     *
     * @param query      natural-language search query
     * @param maxResults maximum number of chunks to return
     * @return scored chunks ordered by descending relevance
     */
    public List<DocumentChunk> search(String query, int maxResults) {
        Objects.requireNonNull(query, "query must not be null");
        if (query.isBlank()) return List.of();

        try {
            List<Float> vector = embeddingClient.embed(query);

            ObjectNode body = MAPPER.createObjectNode();
            body.set("vector",       floatArray(vector));
            body.put("limit",        maxResults);
            body.put("with_payload", true);

            HttpResponse<String> r = send("POST",
                    "/collections/" + config.collectionName() + "/points/search", body);

            if (r.statusCode() != 200) {
                log.warn("[DocumentStore] Search returned HTTP {}: {}", r.statusCode(), r.body());
                return List.of();
            }

            JsonNode results = MAPPER.readTree(r.body()).path("result");
            List<DocumentChunk> hits = new ArrayList<>();
            for (JsonNode hit : results) {
                JsonNode p = hit.path("payload");
                hits.add(new DocumentChunk(
                        p.path("doc_id").asText(),
                        p.path("title").asText(),
                        p.path("content").asText(),
                        p.path("chunk_index").asInt(0),
                        (float) hit.path("score").asDouble(0)
                ));
            }
            return List.copyOf(hits);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("search failed: " + e.getMessage(), e);
        }
    }

    /** Implements {@link Retriever} — adapts {@link DocumentChunk} to {@link RetrievedChunk}. */
    @Override
    public List<RetrievedChunk> retrieve(String query, int maxResults) {
        return search(query, maxResults).stream()
                .map(c -> new RetrievedChunk(c.docId(), c.title(), c.content(), c.score()))
                .toList();
    }

    /** Returns an unmodifiable snapshot of indexed documents: docId → title. */
    public Map<String, String> listDocuments() {
        return Collections.unmodifiableMap(docRegistry);
    }

    // ── Chunking ──────────────────────────────────────────────────────────────

    /**
     * Delegates to {@link ChunkUtil#chunk(String)} which splits the text into
 * overlapping chunks (max size {@value ChunkUtil#CHUNK_SIZE} characters and {@value ChunkUtil#CHUNK_OVERLAP} characters overlap). The method exists for
     * backward compatibility with the original API.
     */
     static List<String> chunk(String text) {
         return ChunkUtil.chunk(text);
     }


    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private HttpResponse<String> send(String method, String path, ObjectNode body)
            throws Exception {
        String json = MAPPER.writeValueAsString(body);
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");
        if (config.apiKey() != null) b.header("api-key", config.apiKey());
        b.method(method, HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static ArrayNode floatArray(List<Float> v) {
        ArrayNode n = MAPPER.createArrayNode();
        for (Float f : v) n.add(f);
        return n;
    }
}