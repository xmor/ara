package io.ara.core.eval;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One evaluation case — ADR-019's {@code BenchmarkCase}, relocated and extended with
 * {@code holdout} and {@code tags} (ADR-0070 D1).
 *
 * <p>{@code holdout == true} means this case is <em>never</em> shown to a proposal cycle
 * (ADR-0081): {@code EvalRunner.run(...)} skips it, only {@code runHoldoutOnly(...)} — the
 * promotion gate (ADR-0083) — runs it. A cycle that could see the hold-out during
 * iteration would optimise against it (source anti-pattern #6).
 *
 * @param caseId             stable id, unique within its suite
 * @param suiteId            the {@link EvalSuite} this case belongs to
 * @param holdout            NEW — kept out of proposal iteration (ADR-0081)
 * @param tags               NEW — for the per-tag breakdown ({@link EvalResult#perTag()}, ADR-0074/0080)
 * @param origin             {@code "curated"} | {@code "synthetic"} | {@code "production_failure"} (ADR-0071)
 * @param input              the task text given to the agent
 * @param context            task context entries
 * @param evaluationStrategy the {@link EvaluationStrategy#strategyId()} that scores this case
 * @param evaluationConfig   strategy-specific configuration
 * @param seqNo              ordering within the suite
 */
public record EvalCase(
        String              caseId,
        String              suiteId,
        boolean             holdout,
        List<String>        tags,
        String              origin,
        String              input,
        Map<String, String> context,
        String              evaluationStrategy,
        Map<String, String> evaluationConfig,
        int                 seqNo
) {

    public EvalCase {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(suiteId, "suiteId must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(evaluationStrategy, "evaluationStrategy must not be null");
        if (caseId.isBlank())  throw new IllegalArgumentException("caseId must not be blank");
        if (suiteId.isBlank()) throw new IllegalArgumentException("suiteId must not be blank");
        tags             = List.copyOf(Objects.requireNonNullElse(tags, List.of()));
        origin           = Objects.requireNonNullElse(origin, "curated");
        context          = Map.copyOf(Objects.requireNonNullElse(context, Map.of()));
        evaluationConfig = Map.copyOf(Objects.requireNonNullElse(evaluationConfig, Map.of()));
    }

    /** A curated (non-hold-out) case with no context or extra config. */
    public static EvalCase curated(String caseId, String suiteId, String input,
                                   String evaluationStrategy, int seqNo) {
        return new EvalCase(caseId, suiteId, false, List.of(), "curated", input,
                Map.of(), evaluationStrategy, Map.of(), seqNo);
    }

    /** A copy with {@code holdout} set — the promotion-gate partition (ADR-0083). */
    public EvalCase asHoldout() {
        return new EvalCase(caseId, suiteId, true, tags, origin, input, context,
                evaluationStrategy, evaluationConfig, seqNo);
    }
}
