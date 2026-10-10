package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;

import java.time.Instant;
import java.util.List;

/**
 * Immutable collaborators shared by every phase of one {@link PlanExecuteStrategy#execute} pass.
 * Replaces the positional parameter lists the phase helpers used to take — {@code executeStep}
 * alone received 17 positional arguments, five of them {@code int}s/arrays, which is the textbook
 * setup for an argument-order bug the compiler cannot catch. Same pattern as
 * {@code ReactStrategy.DispatchContext}.
 *
 * <p>{@code resolvedTools} is what the agent configured and what the planner is shown;
 * {@code stepTools} is that plus {@code close_step}, what a step's model is shown.
 *
 * <p>Immutable, so safe to share across the threads of one pass.
 */
record PlanRun(
        AgentTask task,
        LlmClient llm,
        LlmCallContext ctx,
        ToolRegistry tools,
        List<AraTool> resolvedTools,
        List<AraTool> stepTools,
        AgentConfig config,
        String systemPrompt,
        String plannerCatalog,
        String stepCatalog,
        Instant deadline,
        int maxIterations,
        int maxStepRounds,
        boolean nativeTools) {

    /** Throws {@link ExecutionTimeoutException} once the task's deadline has passed. */
    void checkTimeout() {
        if (Instant.now().isAfter(deadline)) {
            throw new ExecutionTimeoutException(config.executionTimeout());
        }
    }

    /**
     * Cooperative cancellation: {@code AgentInstance.terminate(session)} interrupts the
     * executing thread. Returns {@code true} when the current task should stop.
     */
    static boolean cancelled() {
        return Thread.currentThread().isInterrupted();
    }
}
