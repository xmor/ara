package io.ara.core.eval.evaluator;

import io.ara.core.agent.AgentResponse;
import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvaluationResult;
import io.ara.core.eval.EvaluationStrategy;

/**
 * Passes when the agent's output contains the substring {@code evaluationConfig["substring"]}
 * (or {@code ["expected"]} as a fallback key). {@code ["ignore_case"] == "true"} lowercases
 * both sides. A lenient level-1 check for cases where an exact match is too strict.
 */
public final class ContainsEvaluator implements EvaluationStrategy {

    @Override
    public String strategyId() {
        return "contains";
    }

    @Override
    public EvaluationResult evaluate(AgentResponse response, EvalCase evalCase) {
        String needle = evalCase.evaluationConfig().getOrDefault("substring",
                evalCase.evaluationConfig().get("expected"));
        if (needle == null) {
            return EvaluationResult.error("contains needs evaluationConfig[\"substring\"]");
        }
        boolean ignoreCase = "true".equalsIgnoreCase(evalCase.evaluationConfig().get("ignore_case"));
        String content = response.content() == null ? "" : response.content();
        boolean found = ignoreCase
                ? content.toLowerCase(java.util.Locale.ROOT).contains(needle.toLowerCase(java.util.Locale.ROOT))
                : content.contains(needle);
        return found
                ? EvaluationResult.pass(1.0, "output contains <" + needle + ">")
                : EvaluationResult.fail(0.0, "output does not contain <" + needle + ">");
    }
}
