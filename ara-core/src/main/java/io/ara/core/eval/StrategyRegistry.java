package io.ara.core.eval;

import io.ara.core.eval.evaluator.AssertionEvaluator;
import io.ara.core.eval.evaluator.ContainsEvaluator;
import io.ara.core.eval.evaluator.ExactMatchEvaluator;
import io.ara.core.eval.evaluator.JsonWellFormedEvaluator;
import io.ara.core.eval.evaluator.NonEmptyEvaluator;
import io.ara.core.eval.evaluator.PlaceholderJudgeEvaluator;
import io.ara.core.eval.evaluator.RegexEvaluator;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves an {@link EvalCase#evaluationStrategy()} id to an {@link EvaluationStrategy}
 * (ADR-0070 / ADR-019). {@code EvalRunner} looks each case's verifier up here; an
 * unregistered id is a hard failure at run time, never a silently skipped case.
 *
 * <p>{@link #defaults()} ships the deterministic built-ins (exact-match, regex, contains,
 * non-empty, well-formed-JSON, a composite assertion, and an advisory judge placeholder).
 * A caller registers a real LLM judge or a full JSON-Schema validator over the same ids to
 * upgrade them.
 */
public final class StrategyRegistry {

    private final Map<String, EvaluationStrategy> byId = new ConcurrentHashMap<>();

    /** Registers {@code strategy} under its own {@link EvaluationStrategy#strategyId()}. */
    public StrategyRegistry register(EvaluationStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        return register(strategy.strategyId(), strategy);
    }

    /** Registers {@code strategy} under an explicit id, replacing any prior one. */
    public StrategyRegistry register(String id, EvaluationStrategy strategy) {
        Objects.requireNonNull(id, "id must not be null");
        byId.put(id, Objects.requireNonNull(strategy, "strategy must not be null"));
        return this;
    }

    public Optional<EvaluationStrategy> resolve(String id) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(id, "id must not be null")));
    }

    public boolean has(String id) {
        return byId.containsKey(id);
    }

    /** The deterministic built-ins plus an advisory judge placeholder. */
    public static StrategyRegistry defaults() {
        StrategyRegistry r = new StrategyRegistry();
        r.register("exact_match", new ExactMatchEvaluator());
        r.register("regex", new RegexEvaluator());
        r.register("contains", new ContainsEvaluator());
        r.register("non_empty", new NonEmptyEvaluator());
        r.register("json_well_formed", new JsonWellFormedEvaluator());
        r.register("schema", new JsonWellFormedEvaluator());   // L0 stand-in — see JsonWellFormedEvaluator
        r.register("assertion", new AssertionEvaluator());
        r.register("judge", new PlaceholderJudgeEvaluator());
        return r;
    }
}
