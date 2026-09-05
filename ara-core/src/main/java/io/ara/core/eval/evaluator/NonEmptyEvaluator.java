package io.ara.core.eval.evaluator;

import io.ara.core.agent.AgentResponse;
import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvaluationResult;
import io.ara.core.eval.EvaluationStrategy;

/**
 * Passes when the agent produced any non-blank output. The weakest possible floor — used
 * as the fallback verifier for a regression case whose failure was structural but whose
 * exact assertion a human has not yet supplied (ADR-0071); a case relying only on this is
 * a candidate for a stronger verifier, not a finished one.
 */
public final class NonEmptyEvaluator implements EvaluationStrategy {

    @Override
    public String strategyId() {
        return "non_empty";
    }

    @Override
    public EvaluationResult evaluate(AgentResponse response, EvalCase evalCase) {
        String content = response.content();
        return content != null && !content.isBlank()
                ? EvaluationResult.pass(1.0, "output is non-blank")
                : EvaluationResult.fail(0.0, "output is blank");
    }
}
