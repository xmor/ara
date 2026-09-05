package io.ara.runtime.trace;

import io.ara.core.trace.BlobStore;
import io.ara.core.trace.ReplayEngine;
import io.ara.core.trace.ReplayResult;
import io.ara.core.trace.SpanStatus;
import io.ara.core.trace.TraceSpan;
import io.ara.core.trace.TraceStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ReplayEngine} over {@link TraceStore} + {@link BlobStore} (ADR-0079). Replays a
 * historic run by re-walking its recorded spans in order and, for each occurrence:
 *
 * <ol>
 *   <li>if {@code overrides} names it — write a span carrying the replacement
 *       {@code outputRef} directly (D2);</li>
 *   <li>else if its recorded {@code outputRef} still resolves in the {@link BlobStore} —
 *       a cache hit: copy the recorded output unchanged (D1);</li>
 *   <li>else — a cache miss: hand it to the {@link ReplayExecutor} for live re-execution,
 *       or, when none is wired, record a {@link SpanStatus.Failed} span marking the
 *       occurrence as unresolved (ADR-0079 Negative: a run whose blobs were dropped by
 *       ADR-0061 retention degrades to re-execution).</li>
 * </ol>
 *
 * <p>Every span is written under {@code replayRunId} — the original run is never touched
 * ({@link TraceStore} is append-only, D3). This L0 form does not re-resolve an
 * occurrence's input against upstream substitutions, so the automatic downstream
 * propagation of D4 arrives with the emission-point wiring; today an {@code overrides}
 * entry substitutes exactly the named occurrence.
 */
public final class TraceReplayEngine implements ReplayEngine {

    /**
     * Live re-execution of one occurrence on a cache miss — the seam the workflow
     * scheduler / ReAct loop fills once emission points call the engine. Returns the
     * {@code outputRef} of the freshly produced output, or {@code null} if it could not
     * re-execute.
     */
    @FunctionalInterface
    public interface ReplayExecutor {
        String reexecute(TraceSpan original);
    }

    private final TraceStore traceStore;
    private final BlobStore blobStore;
    private final ReplayExecutor executor;   // nullable

    public TraceReplayEngine(TraceStore traceStore, BlobStore blobStore) {
        this(traceStore, blobStore, null);
    }

    public TraceReplayEngine(TraceStore traceStore, BlobStore blobStore, ReplayExecutor executor) {
        this.traceStore = Objects.requireNonNull(traceStore, "traceStore must not be null");
        this.blobStore  = Objects.requireNonNull(blobStore, "blobStore must not be null");
        this.executor   = executor;
    }

    @Override
    public ReplayResult replay(String originalRunId, String replayRunId, Map<String, String> overrides) {
        Objects.requireNonNull(originalRunId, "originalRunId must not be null");
        Objects.requireNonNull(replayRunId, "replayRunId must not be null");
        if (originalRunId.equals(replayRunId)) {
            throw new IllegalArgumentException("replayRunId must differ from originalRunId (ADR-0079 D3)");
        }
        Map<String, String> subs = overrides == null ? Map.of() : overrides;

        List<TraceSpan> original = traceStore.findByRunId(originalRunId);
        if (original.isEmpty()) {
            throw new IllegalArgumentException("no run to replay: " + originalRunId);
        }

        List<TraceSpan> replayed = new ArrayList<>(original.size());
        int hits = 0;
        int overridden = 0;
        int misses = 0;

        for (TraceSpan span : original) {
            String key = span.spanId();
            if (subs.containsKey(key)) {
                replayed.add(rebase(span, replayRunId, subs.get(key), new SpanStatus.Completed(), null));
                overridden++;
            } else if (span.outputRef() != null && blobStore.get(span.outputRef()).isPresent()) {
                replayed.add(rebase(span, replayRunId, span.outputRef(), span.status(), span.failureKind()));
                hits++;
            } else {
                String fresh = executor != null ? executor.reexecute(span) : null;
                if (fresh != null) {
                    replayed.add(rebase(span, replayRunId, fresh, new SpanStatus.Completed(), null));
                } else {
                    replayed.add(rebase(span, replayRunId, null,
                            new SpanStatus.Failed("replay cache miss: recorded output unavailable, no executor"),
                            "OTHER"));
                }
                misses++;
            }
        }

        replayed.forEach(traceStore::append);
        return new ReplayResult(replayRunId, replayed, hits, overridden, misses);
    }

    private static TraceSpan rebase(TraceSpan original, String replayRunId, String outputRef,
                                    SpanStatus status, String failureKind) {
        return TraceSpan.builder(replayRunId, original.spanId(), original.agentId())
                .parentSpanId(original.parentSpanId())
                .specHash(original.specHash())
                .promptRef(original.promptRef())
                .outputRef(outputRef)
                .tokensIn(original.tokensIn())
                .tokensOut(original.tokensOut())
                .cost(original.cost())
                .status(status)
                .contextProvenanceUntrusted(original.contextProvenanceUntrusted())
                .startedAt(original.startedAt())
                .endedAt(original.endedAt())
                .failureKind(status instanceof SpanStatus.Failed ? failureKind : null)
                .build();
    }
}
