package io.ara.runtime.trace;

import io.ara.core.trace.BlobStore;
import io.ara.core.trace.ReplayResult;
import io.ara.core.trace.SpanStatus;
import io.ara.core.trace.TraceSpan;
import io.ara.core.trace.TraceStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0079: exact replay reuses recorded output bit-for-bit, an override substitutes a
 * named occurrence, a missing blob degrades to re-execution, and the original run is never
 * touched.
 */
class TraceReplayEngineTest {

    private static final Instant T0 = Instant.parse("2026-09-04T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-04T10:00:01Z");

    private final TraceStore traces = TraceStore.inMemory();
    private final BlobStore blobs = BlobStore.inMemory();

    private String blob(String text) {
        return blobs.put(text.getBytes(StandardCharsets.UTF_8));
    }

    private void recordSpan(String runId, String spanId, String promptRef, String outputRef) {
        traces.append(TraceSpan.builder(runId, spanId, "agent-1")
                .promptRef(promptRef).outputRef(outputRef)
                .startedAt(T0).endedAt(T1)
                .status(new SpanStatus.Completed())
                .build());
    }

    private void threeSpanRun(String runId) {
        recordSpan(runId, "n0#0", blob("prompt A"), blob("output A"));
        recordSpan(runId, "n1#0", blob("prompt B"), blob("output B"));
        recordSpan(runId, "n2#0", blob("prompt C"), blob("output C"));
    }

    @Test
    void exactReplayReusesEveryRecordedOutputAndLeavesTheOriginalUntouched() {
        threeSpanRun("run-1");
        ReplayResult result = new TraceReplayEngine(traces, blobs).replay("run-1", "run-1-replay", Map.of());

        assertEquals(3, result.cacheHits());
        assertEquals(0, result.overridden());
        assertEquals(0, result.cacheMisses());
        assertEquals(1.0, result.cacheHitRatio(), 1e-9);

        List<TraceSpan> replayed = traces.findByRunId("run-1-replay");
        assertEquals(List.of("n0#0", "n1#0", "n2#0"), replayed.stream().map(TraceSpan::spanId).toList());
        assertEquals(blob("output B"), replayed.get(1).outputRef(), "recorded output reused unchanged");
        assertEquals(3, traces.findByRunId("run-1").size(), "the original run is not rewritten");
    }

    @Test
    void anOverrideSubstitutesExactlyTheNamedOccurrence() {
        threeSpanRun("run-1");
        String replacement = blob("a different output B");

        ReplayResult result = new TraceReplayEngine(traces, blobs)
                .replay("run-1", "run-1-cf", Map.of("n1#0", replacement));

        assertEquals(2, result.cacheHits());
        assertEquals(1, result.overridden());
        List<TraceSpan> replayed = traces.findByRunId("run-1-cf");
        assertEquals(replacement, replayed.get(1).outputRef());
        assertEquals(blob("output A"), replayed.get(0).outputRef(), "untouched occurrences still hit");
    }

    @Test
    void aMissingRecordedBlobDegradesToACacheMiss() {
        // record spans that point at outputRefs never put into the blob store
        recordSpan("run-2", "n0#0", "promptref", "missing-output-ref-0");
        recordSpan("run-2", "n1#0", "promptref", "missing-output-ref-1");

        ReplayResult noExecutor = new TraceReplayEngine(traces, blobs)
                .replay("run-2", "run-2-replay", Map.of());

        assertEquals(0, noExecutor.cacheHits());
        assertEquals(2, noExecutor.cacheMisses());
        assertInstanceOf(SpanStatus.Failed.class,
                traces.findByRunId("run-2-replay").get(0).status());
    }

    @Test
    void anExecutorResolvesACacheMissWithFreshOutput() {
        recordSpan("run-3", "n0#0", "promptref", "gone");
        String fresh = blob("freshly re-executed output");

        ReplayResult result = new TraceReplayEngine(traces, blobs, original -> fresh)
                .replay("run-3", "run-3-replay", Map.of());

        assertEquals(1, result.cacheMisses());
        TraceSpan span = traces.findByRunId("run-3-replay").get(0);
        assertEquals(fresh, span.outputRef());
        assertInstanceOf(SpanStatus.Completed.class, span.status());
    }

    @Test
    void rejectsASameIdReplayAndAnUnknownRun() {
        threeSpanRun("run-1");
        TraceReplayEngine engine = new TraceReplayEngine(traces, blobs);

        assertThrows(IllegalArgumentException.class, () -> engine.replay("run-1", "run-1", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> engine.replay("nope", "nope-replay", Map.of()));
    }

    @Test
    void replayResultRatioHandlesEmptyAndPartialCones() {
        assertEquals(1.0, new ReplayResult("r", List.of(), 0, 0, 0).cacheHitRatio(), 1e-9);
        assertEquals(0.5, new ReplayResult("r", List.of(), 2, 1, 1).cacheHitRatio(), 1e-9);
        assertTrue(new ReplayResult("r", List.of(), 2, 1, 1).total() == 4);
    }
}
