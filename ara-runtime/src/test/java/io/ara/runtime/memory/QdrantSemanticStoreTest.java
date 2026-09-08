package io.ara.runtime.memory;

import io.ara.core.memory.MemoryEntry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * First real test of {@link QdrantSemanticStore} (previously untested — zero coverage,
 * verified 2026-09-08) plus the ADR-0060 D3 anti-leakage guard for the semantic-memory
 * mechanism.
 *
 * <p>Tenant scoping here is a caller convention, not an interface change (ADR-0060 D2):
 * {@link QdrantSemanticStore} already treats {@code agentId} as an opaque partition key
 * used identically for the stored payload and the search filter, so a caller composing
 * {@code "<tenant>:<agentId>"} before calling {@link QdrantSemanticStore#upsert}/{@link
 * QdrantSemanticStore#search} gets isolation for free, with no new code in this class.
 * {@code tenantScopedAgentId_neverLeaksAcrossTenants} proves that convention, once
 * followed, actually delivers zero-leakage against a real HTTP round trip through {@link
 * FakeQdrantServer} — not just against the string composition in isolation.
 */
class QdrantSemanticStoreTest {

    private static List<Float> vector(float... vs) {
        List<Float> v = new ArrayList<>();
        for (float f : vs) {
            v.add(f);
        }
        return v;
    }

    private static QdrantSemanticStore storeFor(FakeQdrantServer fake) {
        URI uri = URI.create(fake.baseUrl());
        return new QdrantSemanticStore(new QdrantConfig(uri.getHost(), uri.getPort(), null, "test-collection", 3));
    }

    @Test
    void upsertThenSearch_roundTripsForTheSameAgent() throws Exception {
        try (FakeQdrantServer fake = FakeQdrantServer.start()) {
            QdrantSemanticStore store = storeFor(fake);
            store.ensureCollection();
            store.upsert("agent-1", "episode", "task_completed", "paid the invoice", vector(0.1f, 0.2f, 0.3f));

            List<MemoryEntry> results = store.search("agent-1", vector(0.1f, 0.2f, 0.3f), 10);

            assertEquals(1, results.size());
            assertEquals("paid the invoice", results.get(0).content());
        }
    }

    @Test
    void differentRawAgentIds_alreadyIsolate_baselineFilterSanity() throws Exception {
        try (FakeQdrantServer fake = FakeQdrantServer.start()) {
            QdrantSemanticStore store = storeFor(fake);
            store.ensureCollection();
            store.upsert("agent-x", "episode", "task_completed", "x's memory", vector(1f, 0f, 0f));
            store.upsert("agent-y", "episode", "task_completed", "y's memory", vector(1f, 0f, 0f));

            List<MemoryEntry> xResults = store.search("agent-x", vector(1f, 0f, 0f), 10);

            assertEquals(1, xResults.size());
            assertEquals("x's memory", xResults.get(0).content());
        }
    }

    // ── ADR-0060 D3 — the anti-leakage guard, against a real HTTP round trip ──────────

    @Test
    void tenantScopedAgentId_neverLeaksAcrossTenants_evenWithTheSameRawAgentId() throws Exception {
        try (FakeQdrantServer fake = FakeQdrantServer.start()) {
            QdrantSemanticStore store = storeFor(fake);
            store.ensureCollection();
            // Same raw agent id ("support-bot"), two different tenants — the ADR-0060 D2
            // convention is the tenant prefix, composed by the caller before either call.
            store.upsert("tenantA:support-bot", "episode", "task_completed",
                    "tenant A's confidential refund policy detail", vector(0.5f, 0.5f, 0.5f));
            store.upsert("tenantB:support-bot", "episode", "task_completed",
                    "tenant B's own note", vector(0.5f, 0.5f, 0.5f));

            List<MemoryEntry> tenantBResults = store.search("tenantB:support-bot", vector(0.5f, 0.5f, 0.5f), 10);

            assertEquals(1, tenantBResults.size(), "tenant A's entry must not appear in tenant B's results");
            assertEquals("tenant B's own note", tenantBResults.get(0).content());
            assertTrue(tenantBResults.stream().noneMatch(e -> e.content().contains("tenant A")),
                    "tenant B must never see tenant A's content, even under the same raw agent id");
        }
    }

    @Test
    void tenantScopedAgentId_bothDirectionsIsolate() throws Exception {
        try (FakeQdrantServer fake = FakeQdrantServer.start()) {
            QdrantSemanticStore store = storeFor(fake);
            store.ensureCollection();
            store.upsert("tenantA:analyst", "episode", "task_completed", "A's data", vector(0f, 1f, 0f));
            store.upsert("tenantB:analyst", "episode", "task_completed", "B's data", vector(0f, 1f, 0f));

            List<MemoryEntry> aResults = store.search("tenantA:analyst", vector(0f, 1f, 0f), 10);
            List<MemoryEntry> bResults = store.search("tenantB:analyst", vector(0f, 1f, 0f), 10);

            assertEquals(List.of("A's data"), aResults.stream().map(MemoryEntry::content).toList());
            assertEquals(List.of("B's data"), bResults.stream().map(MemoryEntry::content).toList());
        }
    }
}
