package io.ara.core.eval;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The aggregated outcome of running an eval suite against one agent spec over N≥3 runs
 * per case (ADR-0070 D4).
 *
 * @param evalId       stable id of this eval run
 * @param specHash     the {@code AgentSpec} that was evaluated (ADR-0065) — a hash string,
 *                     so this type has no dependency on where {@code AgentSpec} itself lives
 * @param suiteId      the {@link EvalSuite} that was run
 * @param nRunsPerCase N used (≥ {@link CaseStats#MIN_RUNS})
 * @param perCase      {@link CaseStats} keyed by {@code caseId}
 * @param perTag       mean score aggregated per tag declared on the cases (ADR-0070 D5)
 * @param regressions  {@code caseId}s that passed before this run and fail now
 * @param verdict      the {@link Verdict} from the ADR-0059 cascade
 */
public record EvalResult(
        String                 evalId,
        String                 specHash,
        String                 suiteId,
        int                    nRunsPerCase,
        Map<String, CaseStats> perCase,
        Map<String, Double>    perTag,
        List<String>           regressions,
        Verdict                verdict
) {

    public EvalResult {
        Objects.requireNonNull(evalId, "evalId must not be null");
        Objects.requireNonNull(specHash, "specHash must not be null");
        Objects.requireNonNull(suiteId, "suiteId must not be null");
        Objects.requireNonNull(verdict, "verdict must not be null");
        if (nRunsPerCase < CaseStats.MIN_RUNS) {
            throw new IllegalArgumentException(
                    "nRunsPerCase must be >= " + CaseStats.MIN_RUNS + ", got: " + nRunsPerCase);
        }
        perCase     = Map.copyOf(Objects.requireNonNullElse(perCase, Map.of()));
        perTag      = Map.copyOf(Objects.requireNonNullElse(perTag, Map.of()));
        regressions = List.copyOf(Objects.requireNonNullElse(regressions, List.of()));
    }

    /** {@code true} when every case's {@link CaseStats} was aggregated over enough runs. */
    public boolean hasEnoughRuns() {
        return perCase.values().stream().allMatch(s -> s.n() >= CaseStats.MIN_RUNS);
    }
}
