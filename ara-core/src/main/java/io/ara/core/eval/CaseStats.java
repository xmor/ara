package io.ara.core.eval;

import java.util.Objects;

/**
 * The distribution of scores for one {@link EvalCase} over N≥3 runs — not a single lucky
 * number (ADR-0070 D2, source §3.5.2: "una singola esecuzione non è una misura").
 *
 * @param caseId    the case these stats aggregate
 * @param meanScore arithmetic mean of the N run scores
 * @param stdev     population standard deviation of the N run scores
 * @param n         number of runs; {@code >= 3}
 * @param holdout   whether this case is part of the hold-out partition (carried through for {@link EvalResult})
 */
public record CaseStats(String caseId, double meanScore, double stdev, int n, boolean holdout) {

    /** The runner enforces this minimum before a {@code verdict} is computed (ADR-0070 D2/D3). */
    public static final int MIN_RUNS = 3;

    public CaseStats {
        Objects.requireNonNull(caseId, "caseId must not be null");
        if (n < MIN_RUNS) {
            throw new IllegalArgumentException("a CaseStats needs at least " + MIN_RUNS + " runs, got: " + n);
        }
        if (stdev < 0.0) {
            throw new IllegalArgumentException("stdev must be >= 0, got: " + stdev);
        }
    }

    /** Aggregates {@code scores} (length ≥ 3) into mean + population stdev. */
    public static CaseStats of(String caseId, boolean holdout, double... scores) {
        Objects.requireNonNull(scores, "scores must not be null");
        if (scores.length < MIN_RUNS) {
            throw new IllegalArgumentException(
                    "need at least " + MIN_RUNS + " run scores, got: " + scores.length);
        }
        double sum = 0.0;
        for (double s : scores) sum += s;
        double mean = sum / scores.length;
        double sq = 0.0;
        for (double s : scores) sq += (s - mean) * (s - mean);
        double stdev = Math.sqrt(sq / scores.length);
        return new CaseStats(caseId, mean, stdev, scores.length, holdout);
    }
}
