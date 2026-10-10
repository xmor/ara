package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.ExecutionStrategy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Selects the appropriate {@link ExecutionStrategy} for a given {@link AgentConfig}.
 *
 * <p><b>This class selects; it does not plan.</b> "Planner" here means the thing that picks
 * which strategy runs a task. Breaking a task into steps is done by a strategy —
 * {@code "plan_execute"} ({@link PlanExecuteStrategy}) — which this class merely looks up by
 * name. The name is historical and is kept because it is public API; anyone searching for
 * "the planner" in order to change how a plan is produced should go to that strategy instead.
 *
 * <p>The planner holds an immutable registry of named strategies built at startup.
 * Selection is O(1) via a hash map lookup on {@link AgentConfig#plannerStrategy()}.
 * An unknown strategy name is fail-fast: {@link #select} throws {@link
 * IllegalStateException} listing the registered names, so a configuration typo
 * surfaces loudly at task time instead of silently degrading to another strategy.
 *
 * <p>New strategies are registered at startup through the {@link Builder}. There is
 * no runtime re-registration — hot-swapping strategies is a Meta-Agent concern that
 * creates a new planner and re-wires the factory.
 */
public final class ExecutionPlanner {

    /**
     * The stack's default strategy name — what {@link AgentConfig} itself defaults
     * its {@code plannerStrategy} to. Retained purely as documentation: {@link
     * #select} plays no fallback role for it anymore, but an {@code AgentConfig}
     * left at its own default only resolves if a strategy registered under this
     * name actually exists.
     */
    static final String DEFAULT_STRATEGY = "react";

    private final Map<String, ExecutionStrategy> strategies;

    private ExecutionPlanner(Map<String, ExecutionStrategy> strategies) {
        this.strategies = Map.copyOf(strategies);
    }

    /**
     * Selects the strategy indicated by {@code config.plannerStrategy()}.
     *
     * <p>Fail-fast by design: an unregistered name is an error, not a silent degrade.
     * A fallback to the default "react" strategy would make a config mismatch
     * invisible — the agent would run ReAct while logs and telemetry claimed another
     * strategy, and the mismatch would only surface as confusing behavior. (This
     * planner used to warn-and-fall-back; the guardrail was removed, see the
     * {@code strategy/README.md}.)
     *
     * @param config the agent configuration containing the strategy name
     * @return the matching {@link ExecutionStrategy}; never {@code null}
     * @throws IllegalStateException if {@code config.plannerStrategy()} names a
     *                               strategy that is not registered
     */
    public ExecutionStrategy select(AgentConfig config) {
        String name = Objects.requireNonNull(config, "config must not be null").plannerStrategy();
        ExecutionStrategy strategy = strategies.get(name);

        if (strategy == null) {
            throw new IllegalStateException(
                    "No strategy [%s] registered. Registered: %s"
                            .formatted(name, strategies.keySet()));
        }
        return strategy;
    }

    /**
     * Returns {@code true} if a strategy with the given name is registered.
     *
     * @param name the strategy name to look up
     * @return {@code true} if found
     */
    public boolean hasStrategy(String name) {
        return strategies.containsKey(name);
    }

    /**
     * Returns a new {@link Builder} for constructing an {@code ExecutionPlanner}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Fluent builder for {@link ExecutionPlanner}.
     *
     * <p>At least one strategy must be registered before {@link #build()} is called.
     * The name an instance resolves under is exactly {@link
     * ExecutionStrategy#strategyName()} — there is no implicit alias to a default.
     */
    public static final class Builder {

        private final Map<String, ExecutionStrategy> strategies = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Registers a strategy under its {@link ExecutionStrategy#strategyName()}.
         *
         * @param strategy the strategy to register; must not be {@code null}
         * @return this builder
         */
        public Builder register(ExecutionStrategy strategy) {
            Objects.requireNonNull(strategy, "strategy must not be null");
            strategies.put(strategy.strategyName(), strategy);
            return this;
        }

        /**
         * Builds the {@link ExecutionPlanner}.
         *
         * @return the immutable planner
         * @throws IllegalStateException if no strategies have been registered
         */
        public ExecutionPlanner build() {
            if (strategies.isEmpty()) {
                throw new IllegalStateException(
                        "At least one ExecutionStrategy must be registered before building ExecutionPlanner");
            }
            return new ExecutionPlanner(strategies);
        }
    }
}