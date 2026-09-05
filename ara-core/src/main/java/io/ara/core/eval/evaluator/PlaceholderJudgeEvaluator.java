package io.ara.core.eval.evaluator;

import io.ara.core.agent.AgentResponse;
import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvaluationResult;
import io.ara.core.eval.EvaluationStrategy;

/**
 * A stand-in for the LLM-judge strategy (ADR-019 level-3, ADR-0059 D2) so a suite
 * containing {@code "judge"} cases still runs. It is <em>advisory</em>: it returns a
 * neutral score of {@code 0.5} and never vetoes — a real judge is an {@code AraAgent}
 * (ADR-0046/ADR-0080 D4), which this increment does not wire.
 *
 * <p>A runtime-side caller registers a real judge over the {@code "judge"} id to replace
 * this.
 */
public final class PlaceholderJudgeEvaluator implements EvaluationStrategy {

    @Override
    public String strategyId() {
        return "judge";
    }

    @Override
    public EvaluationResult evaluate(AgentResponse response, EvalCase evalCase) {
        return new EvaluationResult(true, 0.5,
                "LLM judge not wired in this increment — advisory neutral score (ADR-0080 D4 follow-up)",
                java.util.Map.of("placeholder", "true"));
    }
}
