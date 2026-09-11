package io.ara.runtime.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ara.core.memory.EpisodeLabel;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.SemanticEntry;
import io.ara.core.memory.SemanticStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Thin wrapper around the Qdrant REST API for storing and retrieving
 * semantic memory entries.
 *
 * <p>All ARA agents share a single collection (configured in {@link QdrantConfig}).
 * Entries are partitioned by {@code agent_id} in the payload and every search
 * applies a {@code must-match} filter on that field, so agents only see their
 * own memories.
 *
 * <p><b>Multi-tenant scoping (ADR-0060 D2) is a caller convention, not a parameter of
 * this class.</b> {@code agentId} is treated as an opaque string, used identically for
 * the stored payload and the search filter — so a caller in a multi-tenant deployment
 * composes {@code "<tenant>:<agentId>"} before calling {@link #upsert}/{@link #search},
 * and the existing exact-match filter isolates tenants for free, on the same collection,
 * with no change to this class. Forgetting to compose the prefix at a single call site
 * is exactly the class of bug ADR-044 documents for a comparable framework (a derived key
 * omitting one identity dimension) — this class cannot enforce the convention itself, only
 * apply whatever string it is given.
 *
 * <h2>Qdrant REST endpoints used</h2>
 * <ul>
 *   <li>{@code PUT  /collections/{name}}                       — create (idempotent)</li>
 *   <li>{@code PUT  /collections/{name}/points}                — upsert a point</li>
 *   <li>{@code POST /collections/{name}/points/search}         — vector search</li>
 * </ul>
 *
 * <h2>Point payload schema</h2>
 * <pre>
 *   {
 *     "agent_id"  : "agent-0",
 *     "content"   : "the text that was embedded",
 *     "role"      : "episode | observation | …",
 *     "type"      : "task_completed | reflexion | …",
 *     "timestamp" : "2025-01-01T12:00:00Z"
 *   }
 * </pre>
 */
public final class QdrantSemanticStore implements SemanticStore {

    private static final Logger      log    = LoggerFactory.getLogger(QdrantSemanticStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QdrantConfig config;
    private final HttpClient   http;

    public QdrantSemanticStore(QdrantConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.http   = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Ensures the collection exists in Qdrant. Safe to call multiple times —
     * returns silently if the collection already exists.
     *
     * @throws RuntimeException if Qdrant is unreachable or returns an unexpected error
     */
    public void ensureCollection() {
        try {
            ObjectNode vectors = MAPPER.createObjectNode();
            vectors.put("size",     config.vectorSize());
            vectors.put("distance", "Cosine");

            ObjectNode body = MAPPER.createObjectNode();
            body.set("vectors", vectors);

            HttpResponse<String> r = send("PUT",
                    "/collections/" + config.collectionName(), body);

            // 200 = created, 409 = already exists — both are acceptable
            if (r.statusCode() != 200 && r.statusCode() != 409) {
                // parse Qdrant status field for more detail
                JsonNode resp = MAPPER.readTree(r.body());
                String status = resp.path("status").asText("unknown");
                if (!"ok".equalsIgnoreCase(status) && !"acknowledged".equalsIgnoreCase(status)) {
                    throw new RuntimeException(
                            "Failed to create Qdrant collection: HTTP " + r.statusCode()
                            + " — " + r.body());
                }
            }
            log.info("[Qdrant] Collection '{}' ready (dims={})",
                    config.collectionName(), config.vectorSize());

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Qdrant ensureCollection failed: " + e.getMessage(), e);
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Inserts or updates a single point in the collection.
     *
     * @param agentId  the owning agent's id (used for partition filtering)
     * @param role     memory role: e.g. {@code "episode"}, {@code "observation"}
     * @param type     episode type: e.g. {@code "task_completed"}, {@code "reflexion"}
     * @param content  the original text that was embedded
     * @param vector   the embedding vector (must have {@link QdrantConfig#vectorSize} elements)
     */
    public void upsert(String agentId, String role, String type,
                       String content, List<Float> vector) {
        try {
            ArrayNode points = new ArrayNode(MAPPER.getNodeFactory());
            points.add(buildPoint(agentId, new SemanticEntry(role, type, content, vector)));

            HttpResponse<String> r = send("PUT",
                    "/collections/" + config.collectionName() + "/points",
                    body(points));

            if (r.statusCode() != 200) {
                log.warn("[Qdrant] Upsert returned HTTP {} for agent={}: {}",
                        r.statusCode(), agentId, r.body());
            } else {
                log.debug("[Qdrant] Upserted point for agent={} type={}", agentId, type);
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Qdrant upsert failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void upsertAll(String agentId, List<SemanticEntry> entries) {
        if (entries.isEmpty()) {
            return;   // a batch of zero is a wasted request
        }
        try {
            ArrayNode points = MAPPER.createArrayNode();
            for (SemanticEntry e : entries) {
                points.add(buildPoint(agentId, e));
            }

            HttpResponse<String> r = send("PUT",
                    "/collections/" + config.collectionName() + "/points",
                    body(points));

            if (r.statusCode() != 200) {
                log.warn("[Qdrant] Batch upsert returned HTTP {} for agent={}: {}",
                        r.statusCode(), agentId, r.body());
            } else {
                log.debug("[Qdrant] Batch upsert of {} point(s) for agent={}", entries.size(), agentId);
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Qdrant batch upsert failed: " + e.getMessage(), e);
        }
    }

    /** One Qdrant point: a fresh id, the vector, and the payload schema of the class javadoc. */
    private ObjectNode buildPoint(String agentId, SemanticEntry e) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("agent_id",  agentId);
        payload.put("content",   e.content());
        payload.put("role",      e.role());
        payload.put("type",      e.type());
        payload.put("timestamp", Instant.now().toString());

        ObjectNode point = MAPPER.createObjectNode();
        point.put("id", UUID.randomUUID().toString());
        point.set("vector",  floatArray(e.vector()));
        point.set("payload", payload);
        return point;
    }

    private ObjectNode body(ArrayNode points) {
        ObjectNode body = MAPPER.createObjectNode();
        body.set("points", points);
        return body;
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Searches for the {@code limit} most semantically similar entries
     * owned by {@code agentId}.
     *
     * @param agentId     the owning agent's id (partition filter)
     * @param queryVector the query embedding
     * @param limit       maximum number of results
     * @return an ordered list of matching {@link MemoryEntry} instances,
     *         closest match first
     */
    public List<MemoryEntry> search(String agentId, List<Float> queryVector, int limit) {
        try {
            // filter: must match agent_id
            ObjectNode matchValue = MAPPER.createObjectNode();
            matchValue.put("value", agentId);

            ObjectNode fieldCond = MAPPER.createObjectNode();
            fieldCond.put("key", "agent_id");
            fieldCond.set("match", matchValue);

            ArrayNode must = MAPPER.createArrayNode();
            must.add(fieldCond);

            ObjectNode filter = MAPPER.createObjectNode();
            filter.set("must", must);

            ObjectNode body = MAPPER.createObjectNode();
            body.set("vector",       floatArray(queryVector));
            body.put("limit",        limit);
            body.set("filter",       filter);
            body.put("with_payload", true);

            HttpResponse<String> r = send("POST",
                    "/collections/" + config.collectionName() + "/points/search", body);

            if (r.statusCode() != 200) {
                log.warn("[Qdrant] Search returned HTTP {} for agent={}: {}",
                        r.statusCode(), agentId, r.body());
                return List.of();
            }

            JsonNode root    = MAPPER.readTree(r.body());
            JsonNode results = root.path("result");
            List<MemoryEntry> entries = new ArrayList<>();

            for (JsonNode hit : results) {
                JsonNode p       = hit.path("payload");
                String   role    = p.path("role").asText("episode");
                String   content = p.path("content").asText("");
                String   type    = p.path("type").asText(null);
                if (!content.isBlank()) {
                    entries.add(MemoryEntry.of(role, content, type != null ? new EpisodeLabel(type) : null));
                }
            }

            log.debug("[Qdrant] Search for agent={} → {} results", agentId, entries.size());
            return List.copyOf(entries);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Qdrant search failed: " + e.getMessage(), e);
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private HttpResponse<String> send(String method, String path, ObjectNode body)
            throws Exception {
        String json = MAPPER.writeValueAsString(body);

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");

        if (config.apiKey() != null) {
            b.header("api-key", config.apiKey());
        }

        b.method(method, HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static ArrayNode floatArray(List<Float> vector) {
        ArrayNode node = MAPPER.createArrayNode();
        for (Float v : vector) node.add(v);
        return node;
    }
}