package io.ara.core.trace;

import java.util.Map;

/**
 * Replays a historic run (ADR-0079): re-walks the {@link TraceSpan}s recorded for one
 * {@code run_id} and produces a <em>new</em> run under a fresh id, reusing the recorded
 * output of every occurrence whose input is unchanged and only re-executing where it is not.
 *
 * <p>Not a new caching mechanism: {@code promptRef}/{@code outputRef} (ADR-0068 D2) are
 * <em>already</em> a content-addressed cache — an identical resolved prompt hashes to the
 * same {@code outputRef}, reachable from {@link BlobStore} without calling the LLM. This
 * port reads that cache with a comparison rule; it does not rebuild it.
 *
 * <p><b>Counterfactual comparison</b> is the same rule with one input: {@code overrides}
 * substitutes the output of a named occurrence ({@code spanId → replacement outputRef}).
 * Occurrences causally downstream are not handled specially — once emission points
 * re-resolve their input against the substituted value, their recomputed {@code promptRef}
 * no longer matches the recorded one and they fall into the cache-miss branch like any
 * other occurrence. The cache-miss frontier <em>is</em> the causal cone of the
 * substitution, by construction (ADR-0079 D2/D4).
 *
 * <p>A replay always writes a new {@code run_id}, never rewrites the original — {@link
 * TraceStore} is append-only, and both traces stay independently queryable and diffable
 * afterwards (ADR-0079 D3/D5).
 */
public interface ReplayEngine {

    /**
     * Replays {@code originalRunId} as {@code replayRunId}.
     *
     * @param originalRunId the historic run to replay; must exist in the {@link TraceStore}
     * @param replayRunId   the id for the new run; must differ from {@code originalRunId} (D3)
     * @param overrides      {@code spanId → replacement outputRef} (a {@link BlobStore} ref),
     *                       for the counterfactual; empty for an exact replay
     */
    ReplayResult replay(String originalRunId, String replayRunId, Map<String, String> overrides);
}
