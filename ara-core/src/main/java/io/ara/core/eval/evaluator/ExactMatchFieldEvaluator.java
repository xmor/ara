package io.ara.core.eval.evaluator;

import io.ara.core.agent.AgentResponse;
import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvaluationResult;
import io.ara.core.eval.EvaluationStrategy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passes when a {@code "<FIELD>: <value>"} line in the agent's output equals {@code
 * evaluationConfig["expected"]} exactly (trimmed) — {@link ExactMatchEvaluator}'s sibling
 * for an output that carries one directive line among free text, the shape {@code
 * LlmJudgeEvaluator}'s {@code SCORE:} line and {@code LlmFailureClassifier}'s {@code
 * CATEGORY:}/{@code CONFIDENCE:} lines both already use. {@code evaluationConfig["field"]}
 * names the directive (case-sensitive in the label, matched case-insensitively in the
 * output the way those two classes parse their own replies); {@code
 * evaluationConfig["ignore_case"] == "true"} makes the value comparison case-insensitive
 * too. The <em>last</em> matching line wins, same precedence those two classes use for
 * their own directive lines.
 *
 * <p>Built for ADR-0080 D4's classifier-accuracy suite ({@code
 * io.ara.meta.attribution.FailureAttributionAccuracySuite}, {@code ara-meta}) — validating
 * that a classifier's {@code CATEGORY:} line agrees with a human label — but generic: any
 * evaluator, drafter, or judge whose reply carries a labelled directive line can be graded
 * the same way, not only that one case.
 */
public final class ExactMatchFieldEvaluator implements EvaluationStrategy {

    @Override
    public String strategyId() {
        return "exact_match_field";
    }

    @Override
    public EvaluationResult evaluate(AgentResponse response, EvalCase evalCase) {
        String field = evalCase.evaluationConfig().get("field");
        if (field == null || field.isBlank()) {
            return EvaluationResult.error("exact_match_field needs evaluationConfig[\"field\"]");
        }
        String expected = evalCase.evaluationConfig().get("expected");
        if (expected == null) {
            return EvaluationResult.error("exact_match_field needs evaluationConfig[\"expected\"]");
        }
        boolean ignoreCase = "true".equalsIgnoreCase(evalCase.evaluationConfig().get("ignore_case"));

        String content = response.content();
        if (content == null) {
            return EvaluationResult.fail(0.0, "no output to extract '" + field + "' from");
        }

        Pattern directive = Pattern.compile(
                "(?im)^\\s*" + Pattern.quote(field) + "\\s*:\\s*(\\S+)\\s*$");
        Matcher m = directive.matcher(content);
        String actual = null;
        while (m.find()) {
            actual = m.group(1);   // last occurrence wins
        }
        if (actual == null) {
            return EvaluationResult.fail(0.0, "no '" + field + ": <value>' line found in the output");
        }

        String want = expected.strip();
        boolean match = ignoreCase ? actual.equalsIgnoreCase(want) : actual.equals(want);
        return match
                ? EvaluationResult.pass(1.0, "'" + field + "' matches the expected value exactly")
                : EvaluationResult.fail(0.0, "expected " + field + "=<" + want + ">, got <" + actual + ">");
    }
}
