package io.ara.core.trace;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of a {@link ReplayEngine#replay} (ADR-0079): the new run's id, its spans,
 * and how each original occurrence was resolved.
 *
 * <p>{@link #cacheHitRatio()} quantifies the causal cone of any {@code overrides}: an
 * exact replay (no overrides) is all cache hits (ratio {@code 1.0}, output bit-identical);
 * a substitution near the graph root drives the ratio down as its downstream re-executes.
 *
 * @param replayRunId the new run's id
 * @param spans       every {@link TraceSpan} written under {@code replayRunId}, in order
 * @param cacheHits   occurrences whose recorded output was reused unchanged
 * @param overridden  occurrences replaced directly from {@code overrides}
 * @param cacheMisses occurrences that had to be re-executed (or could not be resolved)
 */
public record ReplayResult(
        String          replayRunId,
        List<TraceSpan> spans,
        int             cacheHits,
        int             overridden,
        int             cacheMisses
) {

    public ReplayResult {
        Objects.requireNonNull(replayRunId, "replayRunId must not be null");
        spans = List.copyOf(Objects.requireNonNullElse(spans, List.of()));
        if (cacheHits < 0 || overridden < 0 || cacheMisses < 0) {
            throw new IllegalArgumentException("counts must be >= 0");
        }
    }

    /** Total occurrences replayed. */
    public int total() {
        return cacheHits + overridden + cacheMisses;
    }

    /** {@code cacheHits / total} — {@code 1.0} for an empty run, {@code 1.0} for an exact replay. */
    public double cacheHitRatio() {
        int t = total();
        return t == 0 ? 1.0 : (double) cacheHits / t;
    }
}
