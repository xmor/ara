package io.ara.runtime.strategy;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.llm.LlmCompletion;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable per-run accumulators: iteration/token tallies, the execution trace and the notes the
 * steps leave for each other. Replaces the previous {@code int[] iters = {0}}
 * single-element-array idiom — a named object mutated in place says what it is; a one-slot array
 * only says how it was smuggled past Java's by-value parameters.
 *
 * <p>Not thread-safe; confine to the one thread that runs the plan (steps run one at a time).
 */
final class PlanTally {
    int iterations;
    int promptTokens;
    int outputTokens;
    final List<ExecutionStep> steps = new ArrayList<>();
    /**
     * Facts the steps left for the steps after them, in the order they were left. Never
     * truncated: a fact cut in half is worse than none.
     */
    final List<String> notes = new ArrayList<>();
    private final AgentTask task;

    PlanTally(AgentTask task) {
        this.task = task;
    }

    /** The one place a step is recorded: adds it to {@link #steps} and announces it to the run's listener. */
    void record(ExecutionStep step) {
        RunEvents.record(task, steps, step);
    }

    // replan() returns a step list, not an ExecutionResult, so a budget breach detected
    // there is parked here for the caller to return — keeps the failure reason honest
    // ("budget exceeded") instead of masquerading as "produced no result".
    ExecutionResult budgetFailure;

    void addUsage(LlmCompletion completion) {
        promptTokens += completion.promptTokens();
        outputTokens += completion.outputTokens();
    }

    /** A failed result carrying everything tallied so far. */
    ExecutionResult fail(String reason) {
        return ExecutionResult.failure(reason, iterations, promptTokens, outputTokens, steps);
    }
}
