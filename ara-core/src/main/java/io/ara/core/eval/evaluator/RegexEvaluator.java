package io.ara.core.eval.evaluator;

import io.ara.core.agent.AgentResponse;
import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvaluationResult;
import io.ara.core.eval.EvaluationStrategy;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Passes when {@code evaluationConfig["pattern"]} is found in the agent's output.
 * {@code evaluationConfig["full_match"] == "true"} requires the pattern to match the whole
 * output. One of ADR-019's built-ins (ADR-0070 D1), the §3.1.2 level-2 kind.
 */
public final class RegexEvaluator implements EvaluationStrategy {

    @Override
    public String strategyId() {
        return "regex";
    }

    @Override
    public EvaluationResult evaluate(AgentResponse response, EvalCase evalCase) {
        String pattern = evalCase.evaluationConfig().get("pattern");
        if (pattern == null) {
            return EvaluationResult.error("regex needs evaluationConfig[\"pattern\"]");
        }
        boolean fullMatch = "true".equalsIgnoreCase(evalCase.evaluationConfig().get("full_match"));
        String content = response.content() == null ? "" : response.content();
        try {
            Pattern p = Pattern.compile(pattern, Pattern.DOTALL);
            var matcher = p.matcher(content);
            boolean ok = fullMatch ? matcher.matches() : matcher.find();
            return ok
                    ? EvaluationResult.pass(1.0, "output " + (fullMatch ? "matches" : "contains a match for") + " /" + pattern + "/")
                    : EvaluationResult.fail(0.0, "output has no " + (fullMatch ? "full " : "") + "match for /" + pattern + "/");
        } catch (PatternSyntaxException bad) {
            return EvaluationResult.error("invalid regex: " + bad.getMessage());
        }
    }
}
