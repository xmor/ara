package io.ara.core.eval;

import io.ara.core.agent.AgentResponse;

import java.util.Locale;

/**
 * Pluggable check that turns an agent's output on one {@link EvalCase} into an
 * {@link EvaluationResult} — ADR-019's design, relocated into {@code io.ara.core.eval}
 * (ADR-0070 D1). ADR-019's eight built-in evaluators (exact-match, regex, LLM-judge,
 * executable tests, HTML/SQL/Playwright) remain reusable as-is where an environment is
 * available.
 *
 * <p><b>Simplification vs ADR-019</b>: the third {@code ExecutionEnvironment} parameter is
 * dropped here. {@code EnvironmentProvisioner} / provisioned environments are explicitly
 * deferred by ADR-0070 ("resta riusabile dove applicabile", not built now); an evaluator
 * that needs one carries it through its own {@link EvalCase#evaluationConfig()}.
 */
@FunctionalInterface
public interface EvaluationStrategy {

    EvaluationResult evaluate(AgentResponse response, EvalCase evalCase);

    /** Stable id derived from the class name — {@code FooEvaluator} → {@code "foo"}. */
    default String strategyId() {
        return getClass().getSimpleName()
                .toLowerCase(Locale.ROOT)
                .replace("evaluator", "")
                .replace("strategy", "");
    }
}
