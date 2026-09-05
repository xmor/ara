package io.ara.core.eval.evaluator;

import io.ara.core.agent.AgentResponse;
import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvaluationResult;
import io.ara.core.eval.EvaluationStrategy;

/**
 * A best-effort programmatic assertion (ADR-019 level-2), dispatching on whichever key the
 * case's {@code evaluationConfig} carries:
 *
 * <ul>
 *   <li>{@code "expected"} → exact match;</li>
 *   <li>{@code "pattern"} → regex;</li>
 *   <li>{@code "substring"} → contains;</li>
 *   <li>none of the above → {@code non_empty} (the weak floor a
 *       {@code RegressionCaseBuilder}-derived case starts with until a human tightens it,
 *       ADR-0071 D3).</li>
 * </ul>
 */
public final class AssertionEvaluator implements EvaluationStrategy {

    private final ExactMatchEvaluator exact = new ExactMatchEvaluator();
    private final RegexEvaluator regex = new RegexEvaluator();
    private final ContainsEvaluator contains = new ContainsEvaluator();
    private final NonEmptyEvaluator nonEmpty = new NonEmptyEvaluator();

    @Override
    public String strategyId() {
        return "assertion";
    }

    @Override
    public EvaluationResult evaluate(AgentResponse response, EvalCase evalCase) {
        var config = evalCase.evaluationConfig();
        if (config.containsKey("expected")) {
            return exact.evaluate(response, evalCase);
        }
        if (config.containsKey("pattern")) {
            return regex.evaluate(response, evalCase);
        }
        if (config.containsKey("substring")) {
            return contains.evaluate(response, evalCase);
        }
        return nonEmpty.evaluate(response, evalCase);
    }
}
