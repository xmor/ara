package io.ara.core.trace;

import io.ara.core.common.Money;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0068: {@link TraceSpan} as the common shape (D1), content-addressed {@link BlobStore}
 * (D2), the recorded-not-computed provenance flag (D3), and {@link TraceStore} as a
 * run-queryable append-only log distinct from {@code CheckpointStore} (D4).
 */
class TracePersistenceTest {

    private static final Instant T0 = Instant.parse("2026-09-03T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-03T10:00:02Z");

    private static TraceSpan.Builder span(String runId, String spanId) {
        return TraceSpan.builder(runId, spanId, "agent-1").startedAt(T0).endedAt(T1);
    }

    // D1 — the span shape: guards and nullable optionals
    @Test
    void span_requiresCoreFieldsAndOrderedTimestamps() {
        assertThrows(NullPointerException.class, () -> span("r", "s").startedAt(null).build());
        assertThrows(IllegalArgumentException.class, () -> span("", "s").build());
        assertThrows(IllegalArgumentException.class,
                () -> span("r", "s").tokensIn(-1).build());
        assertThrows(IllegalArgumentException.class,
                () -> TraceSpan.builder("r", "s", "a").startedAt(T1).endedAt(T0).build());
    }

    @Test
    void span_optionalRefsAndSpecHashMayBeNull() {
        TraceSpan s = span("run-42", "node-a#0").build();

        assertNull(s.specHash());
        assertNull(s.promptRef());
        assertNull(s.outputRef());
        assertNull(s.parentSpanId());
        assertEquals(Money.ZERO_EUR, s.cost());
        assertInstanceOf(SpanStatus.Completed.class, s.status());
        assertEquals("run-42", s.runId());
    }

    // D6 (ADR-0074) — failureKind: nullable, only valid on a Failed span
    @Test
    void span_failureKindIsNullByDefaultAndOnlyValidForFailedStatus() {
        assertNull(span("r", "s").build().failureKind());

        TraceSpan failed = span("r", "s")
                .status(new SpanStatus.Failed("Cost budget exceeded"))
                .failureKind("BUDGET_EXCEEDED")
                .build();
        assertEquals("BUDGET_EXCEEDED", failed.failureKind());

        assertThrows(IllegalArgumentException.class,
                () -> span("r", "s").failureKind("BUDGET_EXCEEDED").build());
        assertThrows(IllegalArgumentException.class,
                () -> span("r", "s").status(new SpanStatus.Suspended("waiting"))
                        .failureKind("TIMEOUT").build());
    }

    @Test
    void span_preAdr0074ConstructorLeavesFailureKindUnset() {
        TraceSpan s = new TraceSpan("r", "s", null, "a", null, null, null,
                0, 0, Money.ZERO_EUR, new SpanStatus.Completed(), false, T0, T1);
        assertNull(s.failureKind());
    }

    @Test
    void span_carriesTheProvenanceFlagAsRecorded() {
        TraceSpan trusted = span("r", "s").build();
        TraceSpan untrusted = span("r", "s2").contextProvenanceUntrusted(true).build();

        assertTrue(!trusted.contextProvenanceUntrusted());
        assertTrue(untrusted.contextProvenanceUntrusted());
    }

    // SpanStatus — same three outcomes as ADR-052 D1, Failed/Suspended carry a reason
    @Test
    void spanStatus_failedAndSuspendedRequireAReason() {
        assertThrows(NullPointerException.class, () -> new SpanStatus.Failed(null));
        assertThrows(NullPointerException.class, () -> new SpanStatus.Suspended(null));

        SpanStatus st = new SpanStatus.Failed("tool timed out");
        String label = switch (st) {
            case SpanStatus.Completed c -> "ok";
            case SpanStatus.Failed f -> f.reason();
            case SpanStatus.Suspended s -> s.reason();
        };
        assertEquals("tool timed out", label);
    }

    // D2 — content-addressing: same bytes -> same ref, stored once; round-trips
    @Test
    void blobStore_isContentAddressedAndDeduplicates() {
        BlobStore blobs = BlobStore.inMemory();
        byte[] prompt = "You are an analyst. Analyse: {doc}".getBytes(StandardCharsets.UTF_8);

        String ref1 = blobs.put(prompt);
        String ref2 = blobs.put(prompt.clone());

        assertEquals(ref1, ref2);
        assertEquals(64, ref1.length());
        assertArrayEquals(prompt, blobs.get(ref1).orElseThrow());
        assertTrue(blobs.get("deadbeef").isEmpty());
    }

    @Test
    void blobStore_returnsDefensiveCopies() {
        BlobStore blobs = BlobStore.inMemory();
        byte[] original = {1, 2, 3};
        String ref = blobs.put(original);
        original[0] = 9;                       // mutate the array we handed in
        blobs.get(ref).orElseThrow()[1] = 9;   // mutate the array we got back

        assertArrayEquals(new byte[]{1, 2, 3}, blobs.get(ref).orElseThrow());
    }

    @Test
    void span_referencesBlobsByRef_notInline() {
        BlobStore blobs = BlobStore.inMemory();
        String promptRef = blobs.put("the prompt".getBytes(StandardCharsets.UTF_8));
        String outputRef = blobs.put("the answer".getBytes(StandardCharsets.UTF_8));

        TraceSpan s = span("run-1", "agent-1#3")
                .promptRef(promptRef).outputRef(outputRef)
                .tokensIn(120).tokensOut(45)
                .cost(Money.of("0.0031", "EUR"))
                .specHash("a".repeat(64))
                .build();

        assertEquals(promptRef, s.promptRef());
        assertEquals("the answer", new String(blobs.get(s.outputRef()).orElseThrow(), StandardCharsets.UTF_8));
    }

    // D4 — TraceStore: append-only, queryable by runId, runs isolated, snapshot immutable
    @Test
    void traceStore_appendsAndQueriesByRunIdInOrder() {
        TraceStore store = TraceStore.inMemory();
        store.append(span("run-A", "s0").build());
        store.append(span("run-A", "s1").status(new SpanStatus.Failed("boom")).build());
        store.append(span("run-B", "s0").build());

        List<TraceSpan> runA = store.findByRunId("run-A");
        assertEquals(List.of("s0", "s1"), runA.stream().map(TraceSpan::spanId).toList());
        assertInstanceOf(SpanStatus.Failed.class, runA.get(1).status());
        assertEquals(1, store.findByRunId("run-B").size());
        assertTrue(store.findByRunId("run-unknown").isEmpty());
    }

    // ADR-0074 D2/D4 — findSince: cross-run read filtered by span start
    @Test
    void traceStore_findSinceReturnsSpansAcrossRunsAtOrAfterInstant() {
        TraceStore store = TraceStore.inMemory();
        Instant early = Instant.parse("2026-08-01T00:00:00Z");
        Instant late  = Instant.parse("2026-09-03T09:59:59Z");
        store.append(TraceSpan.builder("run-A", "old", "a").startedAt(early).endedAt(early.plusSeconds(1)).build());
        store.append(span("run-A", "recent").build());          // starts at T0
        store.append(span("run-B", "recent").build());

        assertEquals(3, store.findSince(Instant.EPOCH).size());
        assertEquals(2, store.findSince(late).size());
        assertEquals(0, store.findSince(T1.plusSeconds(1)).size());
        assertTrue(store.findSince(T0).stream().allMatch(s -> !s.startedAt().isBefore(T0)));
    }

    @Test
    void traceStore_snapshotIsImmutableAndDecoupledFromLaterAppends() {
        TraceStore store = TraceStore.inMemory();
        store.append(span("r", "s0").build());
        List<TraceSpan> snapshot = store.findByRunId("r");
        store.append(span("r", "s1").build());

        assertEquals(1, snapshot.size(), "an earlier snapshot does not see later appends");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(span("r", "x").build()));
        assertEquals(2, store.findByRunId("r").size());
    }

    @Test
    void traceStore_inMemoryFactoryReturnsIndependentInstances() {
        assertSame(InMemoryTraceStore.class, TraceStore.inMemory().getClass());
        TraceStore a = TraceStore.inMemory();
        TraceStore b = TraceStore.inMemory();
        a.append(span("r", "s").build());
        assertTrue(b.findByRunId("r").isEmpty());
    }
}
