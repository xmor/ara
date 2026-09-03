package io.ara.core.trace;

import java.time.Instant;
import java.util.List;

/**
 * The first store that makes an execution queryable by {@code runId} (ADR-0068 D4):
 * an append-only log of {@link TraceSpan}s.
 *
 * <p>Distinct from {@code CheckpointStore} (ADR-048 D2), not an extension of it. A
 * checkpoint store answers "what is the latest state to resume from" and may discard a
 * superseded checkpoint once a resume succeeds; a trace store answers "what happened, start
 * to finish, for this run" and its lifetime is governed by the differentiated retention of
 * ADR-0061, not by a resume. Same events, opposite lifecycles.
 */
public interface TraceStore {

    /** Appends {@code span} to its run's log. */
    void append(TraceSpan span);

    /** Every span recorded for {@code runId}, in append order — empty if none. */
    List<TraceSpan> findByRunId(String runId);

    /**
     * Every span, across all runs, whose {@link TraceSpan#startedAt()} is at or after
     * {@code since} — the cross-run read the ADR-0074 dashboard aggregates (D2/D4). Pass
     * {@link Instant#EPOCH} for the cumulative horizon (D5). Order is unspecified.
     */
    List<TraceSpan> findSince(Instant since);

    /** A process-local reference implementation — not durable across a JVM restart. */
    static TraceStore inMemory() {
        return new InMemoryTraceStore();
    }
}
