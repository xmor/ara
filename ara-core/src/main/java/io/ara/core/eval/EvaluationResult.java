package io.ara.core.eval;

import java.util.Map;
import java.util.Objects;

/**
 * The outcome of one {@link EvaluationStrategy} applied to one agent output — relocated
 * verbatim from ADR-019's design into {@code io.ara.core.eval} (ADR-0070 D1, *Nota di
 * verifica*: ADR-019's namespace is a third repository this backlog does not touch).
 *
 * @param passed   whether the strategy considers the output correct
 * @param score    quality in {@code [0.0, 1.0]}
 * @param rationale human-readable explanation
 * @param metadata strategy-specific detail (e.g. diff, matched group)
 */
public record EvaluationResult(
        boolean             passed,
        double              score,
        String              rationale,
        Map<String, String> metadata
) {
    public EvaluationResult {
        if (score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be in [0.0, 1.0], got: " + score);
        }
        rationale = Objects.requireNonNullElse(rationale, "");
        metadata  = Map.copyOf(Objects.requireNonNullElse(metadata, Map.of()));
    }

    public static EvaluationResult pass(double score, String rationale) {
        return new EvaluationResult(true, score, rationale, Map.of());
    }

    public static EvaluationResult fail(double score, String rationale) {
        return new EvaluationResult(false, score, rationale, Map.of());
    }

    public static EvaluationResult error(String reason) {
        return new EvaluationResult(false, 0.0, "Evaluation error: " + reason, Map.of());
    }
}
