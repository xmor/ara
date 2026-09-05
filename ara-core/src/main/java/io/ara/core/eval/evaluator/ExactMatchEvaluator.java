package io.ara.core.eval.evaluator;

import io.ara.core.agent.AgentResponse;
import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvaluationResult;
import io.ara.core.eval.EvaluationStrategy;

/**
 * Passes when the agent's output equals {@code evaluationConfig["expected"]} exactly
 * (trimmed). {@code evaluationConfig["ignore_case"] == "true"} makes the comparison
 * case-insensitive. One of ADR-019's eight built-ins (ADR-0070 D1), the deterministic
 * §3.1.2 level-1 kind.
 */
public final class ExactMatchEvaluator implements EvaluationStrategy {

    @Override
    public String strategyId() {
        return "exact_match";
    }

    @Override
    public EvaluationResult evaluate(AgentResponse response, EvalCase evalCase) {
        String expected = evalCase.evaluationConfig().get("expected");
        if (expected == null) {
            return EvaluationResult.error("exact_match needs evaluationConfig[\"expected\"]");
        }
        boolean ignoreCase = "true".equalsIgnoreCase(evalCase.evaluationConfig().get("ignore_case"));
        String actual = response.content() == null ? "" : response.content().strip();
        String want = expected.strip();
        boolean match = ignoreCase ? actual.equalsIgnoreCase(want) : actual.equals(want);
        return match
                ? EvaluationResult.pass(1.0, "output matches the expected value exactly")
                : EvaluationResult.fail(0.0, "expected <" + want + ">, got <" + actual + ">");
    }
}
