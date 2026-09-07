package io.ara.runtime.trace;

import io.ara.core.telemetry.SpanStatus;
import io.ara.core.trace.BlobStore;
import io.ara.core.trace.ReplayResult;
import io.ara.core.trace.TraceSpan;
import io.ara.core.trace.TraceStore;
import io.ara.runtime.telemetry.RecordingTelemetry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ADR-0079 D5: {@code replay.run} — no preexisting span on this operation, a new one.
 */
class TraceReplayEngineTelemetryTest {

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
                .status(new io.ara.core.trace.SpanStatus.Completed())
                .build());
    }

    private void threeSpanRun(String runId) {
        recordSpan(runId, "n0#0", blob("prompt A"), blob("output A"));
        recordSpan(runId, "n1#0", blob("prompt B"), blob("output B"));
        recordSpan(runId, "n2#0", blob("prompt C"), blob("output C"));
    }

    @Test
    void exactReplayEmitsOneSpanWithFullCacheHitRatio() {
        threeSpanRun("run-1");
        RecordingTelemetry telemetry = new RecordingTelemetry();

        new TraceReplayEngine(traces, blobs, null, telemetry).replay("run-1", "run-1-replay", Map.of());

        List<RecordingTelemetry.RecordedSpan> spans = telemetry.spansNamed("replay.run");
        assertEquals(1, spans.size());
        RecordingTelemetry.RecordedSpan span = spans.get(0);
        assertEquals(SpanStatus.OK, span.status());
        assertEquals("run-1", span.attributes().get("original_run_id"));
        assertEquals("run-1-replay", span.attributes().get("replay_run_id"));
        assertEquals(0L, span.attributes().get("overrides_count"));
        assertEquals(1.0, (double) span.attributes().get("cache_hit_ratio"), 1e-9);
    }

    @Test
    void anOverrideLowersTheCacheHitRatioAndCountsInOverridesCount() {
        threeSpanRun("run-1");
        String replacement = blob("a different output B");
        RecordingTelemetry telemetry = new RecordingTelemetry();

        ReplayResult result = new TraceReplayEngine(traces, blobs, null, telemetry)
                .replay("run-1", "run-1-replay", Map.of("n1#0", replacement));

        RecordingTelemetry.RecordedSpan span = telemetry.spansNamed("replay.run").get(0);
        assertEquals(1L, span.attributes().get("overrides_count"));
        assertEquals(result.cacheHitRatio(), (double) span.attributes().get("cache_hit_ratio"), 1e-9);
        assertEquals(2.0 / 3.0, (double) span.attributes().get("cache_hit_ratio"), 1e-9);
    }

    @Test
    void aMissingRunStillEmitsASpanMarkedError() {
        RecordingTelemetry telemetry = new RecordingTelemetry();

        assertThrows(IllegalArgumentException.class, () ->
                new TraceReplayEngine(traces, blobs, null, telemetry).replay("nope", "nope-replay", Map.of()));

        List<RecordingTelemetry.RecordedSpan> spans = telemetry.spansNamed("replay.run");
        assertEquals(1, spans.size());
        assertEquals(SpanStatus.ERROR, spans.get(0).status());
        assertEquals(true, spans.get(0).hasException());
    }
}
