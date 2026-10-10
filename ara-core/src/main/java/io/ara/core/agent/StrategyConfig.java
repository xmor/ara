package io.ara.core.agent;

import java.util.Map;
import java.util.Objects;

/**
 * Typed, strategy-specific configuration companion to {@link AgentConfig}.
 *
 * <p>Replaces the flat nullable fields that were scattered on {@code AgentConfig}
 * (e.g. {@code maxReflections}, {@code replanStrategy}) with a sealed type hierarchy
 * where each permitted type carries exactly the parameters its strategy needs.
 *
 * <p>The built-in permitted types are sealed to the strategies {@code ExecutionPlanner}
 * ships ({@code ReactStrategy}, {@code ReSpActStrategy}, {@code PlanExecuteStrategy},
 * {@code ReflexionStrategy}, {@code ReflActStrategy}). A consumer's own strategy cannot
 * add a permitted type — a sealed hierarchy permits only its declared subtypes — so
 * {@link Custom} is the one open variant: it carries the strategy name plus an untyped
 * parameter map, letting a third-party strategy keep typed configuration without a
 * new built-in variant per consumer.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Reflexion with 3 retries
 * AgentConfig config = AgentConfig.defaults()
 *     .agentType("code-fixer")
 *     .strategyConfig(new StrategyConfig.Reflexion(3, null, null))
 *     .build();
 *
 * // Plan-execute with on_failure replan, max 6 steps
 * AgentConfig config = AgentConfig.defaults()
 *     .agentType("researcher")
 *     .strategyConfig(new StrategyConfig.PlanExecute("on_failure", 6, 3))
 *     .build();
 *
 * // Simple react (default) — no strategyConfig needed
 * AgentConfig config = AgentConfig.defaults()
 *     .agentType("assistant")
 *     .build();
 * }</pre>
 *
 * <p>Each permitted type exposes a {@link #strategyName()} method that returns the
 * string key used by {@code ExecutionPlanner} to
 * select the strategy. When {@code strategyConfig} is {@code null} on
 * {@code AgentConfig}, the string {@code plannerStrategy} field governs selection
 * (preserved for backward compatibility with REST/persistence callers that pass
 * strategy names as plain strings).
 */
public sealed interface StrategyConfig permits
        StrategyConfig.React,
        StrategyConfig.PlanExecute,
        StrategyConfig.Reflexion,
        StrategyConfig.ReflAct,
        StrategyConfig.Custom {

    /** Returns the string key used by {@code ExecutionPlanner} to select this strategy. */
    String strategyName();

    // ── built-in strategies ───────────────────────────────────────────────────

    /** Standard ReAct (Reason + Act) loop — the default strategy. No extra config needed. */
    record React() implements StrategyConfig {
        @Override public String strategyName() { return "react"; }
    }

    /**
     * Plan-then-execute strategy (ReWOO-inspired).
     *
     * @param replanPolicy          {@code "never"} or {@code "on_failure"}; default {@code "never"}
     * @param maxPlanSteps          max steps the planner may generate; default 8
     * @param maxStepRoundsPerStep  max Think→Act→Observe rounds per step, counting the one that closes it with {@code close_step}; default 6
     * @param maxParallelSteps      how many independent steps should run at once, or {@code null}
     *                              for one at a time (the default). <b>Accepted and stored, not
     *                              yet honoured:</b> steps still run one after the other and the
     *                              strategy logs a warning when this is above 1. Nullable on
     *                              purpose: a document or a three-argument config that never
     *                              mentions it stays exactly what it was, down to its hash
     */
    record PlanExecute(
            String  replanPolicy,
            int     maxPlanSteps,
            int     maxStepRoundsPerStep,
            Integer maxParallelSteps
    ) implements StrategyConfig {

        /** The configuration without parallelism: steps run one after the other. */
        public PlanExecute(String replanPolicy, int maxPlanSteps, int maxStepRoundsPerStep) {
            this(replanPolicy, maxPlanSteps, maxStepRoundsPerStep, null);
        }

        public PlanExecute {
            Objects.requireNonNull(replanPolicy, "replanPolicy must not be null");
            if (!replanPolicy.equals("never") && !replanPolicy.equals("on_failure"))
                throw new IllegalArgumentException(
                        "replanPolicy must be 'never' or 'on_failure', got: " + replanPolicy);
            if (maxPlanSteps < 1)
                throw new IllegalArgumentException("maxPlanSteps must be >= 1, got: " + maxPlanSteps);
            if (maxStepRoundsPerStep < 1)
                throw new IllegalArgumentException(
                        "maxStepRoundsPerStep must be >= 1, got: " + maxStepRoundsPerStep);
            if (maxParallelSteps != null && maxParallelSteps < 1)
                throw new IllegalArgumentException(
                        "maxParallelSteps must be >= 1 when given, got: " + maxParallelSteps);
        }

        /** Steps that may run at once: {@link #maxParallelSteps} or 1 when it is not set. */
        public int parallelSteps() { return maxParallelSteps == null ? 1 : maxParallelSteps; }

        @Override public String strategyName() { return "plan_execute"; }

        /** Returns a {@code PlanExecute} config with production defaults. */
        public static PlanExecute defaults() { return new PlanExecute("never", 8, 6); }
    }

    /**
     * Reflexion strategy — verbal reinforcement loop.
     *
     * @param maxReflections    max reflection-and-retry cycles; 0 = no reflection (falls back to delegate); default 2
     * @param reflectionPrompt  custom reflection prompt template; {@code null} uses the built-in default
     * @param reflectionProvider optional LLM provider id for the reflection call; {@code null} = same as agent
     */
    record Reflexion(
            int    maxReflections,
            String reflectionPrompt,
            String reflectionProvider
    ) implements StrategyConfig {

        public Reflexion {
            if (maxReflections < 0)
                throw new IllegalArgumentException("maxReflections must be >= 0, got: " + maxReflections);
        }

        @Override public String strategyName() { return "reflexion"; }

        /** Returns a {@code Reflexion} config with production defaults (2 retries, built-in prompt). */
        public static Reflexion defaults() { return new Reflexion(2, null, null); }
    }

    /**
     * ReflAct strategy — a single ReAct loop with in-place, in-loop self-correction.
     *
     * <p>Unlike {@link Reflexion} (a decorator that, on total delegate failure, wipes
     * working memory and restarts the whole episode with a critique of the failed
     * attempt), {@code ReflActStrategy} reflects <em>within</em> one ongoing episode: a
     * failed tool call, or {@code unproductiveStreak} consecutive iterations that
     * neither dispatch a tool nor produce a final answer, triggers a short course-
     * correction that is appended to the same working memory — nothing is reset, the
     * loop simply continues with the correction now in context.
     *
     * @param maxReflections       max in-loop reflections for the whole task; 0 disables
     *                             self-correction entirely (equivalent to plain ReAct);
     *                             default 3
     * @param unproductiveStreak   consecutive no-tool/no-final-answer iterations that
     *                             trigger a reflection; must be >= 1; default 2
     * @param reflectOnToolFailure whether a failed tool call triggers an immediate
     *                             reflection, independent of the unproductive-streak
     *                             counter; default {@code true}
     * @param reflectionProvider   optional LLM provider id for the reflection call
     *                             (dual-model separation: a cheaper/faster model executes,
     *                             a stronger one critiques); {@code null} = same model as
     *                             the main loop
     */
    record ReflAct(
            int     maxReflections,
            int     unproductiveStreak,
            boolean reflectOnToolFailure,
            String  reflectionProvider
    ) implements StrategyConfig {

        public ReflAct {
            if (maxReflections < 0)
                throw new IllegalArgumentException("maxReflections must be >= 0, got: " + maxReflections);
            if (unproductiveStreak < 1)
                throw new IllegalArgumentException("unproductiveStreak must be >= 1, got: " + unproductiveStreak);
        }

        @Override public String strategyName() { return "reflact"; }

        /** Returns a {@code ReflAct} config with production defaults (3 reflections, streak of 2, reflect-on-failure). */
        public static ReflAct defaults() { return new ReflAct(3, 2, true, null); }
    }

    /**
     * Configuration for a strategy that is not one of the built-ins — the extension seam
     * for a consumer's own {@link ExecutionStrategy} registered through {@code
     * AraRuntime.Builder.extraStrategies(...)}.
     *
     * <p>A sealed hierarchy cannot be extended from outside this module, so a third-party
     * strategy has no way to add a permitted type of its own. Without an open variant it
     * would have to smuggle its parameters through {@code AgentConfig} string fields or a
     * side channel the framework knows nothing about. {@code Custom} carries the strategy
     * name plus an untyped parameter map instead: the framework never interprets the map,
     * the strategy reads its own keys.
     *
     * @param strategyName the strategy this config belongs to; must be non-blank and match
     *                     the registered strategy's {@code strategyName()}
     * @param params       strategy-specific parameters; {@code null} is treated as empty,
     *                     and the map is copied defensively (no null keys or values)
     */
    record Custom(String strategyName, Map<String, Object> params) implements StrategyConfig {

        public Custom {
            Objects.requireNonNull(strategyName, "strategyName must not be null");
            if (strategyName.isBlank())
                throw new IllegalArgumentException("strategyName must not be blank");
            params = params == null ? Map.of() : Map.copyOf(params);
        }

        /** Returns a {@code Custom} config for {@code strategyName} with no parameters. */
        public static Custom of(String strategyName) { return new Custom(strategyName, Map.of()); }
    }

    // ── static factory methods ────────────────────────────────────────────────

    static React           react()           { return new React(); }
    static PlanExecute     planExecute()      { return PlanExecute.defaults(); }
    static Reflexion       reflexion()        { return Reflexion.defaults(); }
    static ReflAct         reflact()          { return ReflAct.defaults(); }
}
