package io.ara.runtime.workflow;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.llm.LlmClient;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adapts a {@link Workflow} to the {@link ExecutionStrategy} contract (ADR-052 D7), the
 * same move {@code PipelineStrategy} already makes for {@link
 * io.ara.runtime.pipeline.AgentPipeline}: hosting a workflow inside a real {@code
 * AgentInstance} gets it per-session isolation and busy policy (ADR-016), cooperative
 * cancellation, the {@code agent.execute} telemetry span, and the interceptor chain for
 * free, instead of the hand-rolled {@code GraphAgent} this retires (mutable {@code
 * volatile} state, {@code RunContext} discarded at the boundary, token/cost always
 * reported {@code 0, 0, 0.0} — the anti-pattern ADR-051 documents).
 *
 * <p>Ignores {@code llm}, {@code memory}, and {@code tools} entirely — same reasoning as
 * {@code PipelineStrategy}: a workflow's real work happens inside its own nodes.
 *
 * <p><b>What this cannot report yet.</b> {@link WorkflowNode#body()} is a plain {@code
 * Function<String,String>} — D1/D2 do not require it to be agent-shaped — so there is no
 * {@code AgentResponse} anywhere to read token usage or cost from, unlike {@code
 * PipelineStrategy} aggregating each step's own response. {@link
 * ExecutionResult#promptTokens()}/{@link ExecutionResult#outputTokens()} are always
 * {@code 0} here; a node that declares a {@link WorkflowNode#cost()} still has it charged
 * to the run's {@link io.ara.core.budget.RunBudget} if one is configured (ADR-054 D6) —
 * that spend just is not surfaced through this {@code ExecutionResult} today. Making it
 * so needs {@link Workflow#run} to hand back the budget's final {@code Spend}, which is a
 * separate, later increment.
 *
 * <p>Package-private for the same reason as {@code PipelineStrategy}: {@link
 * WorkflowAgents#of} builds a fresh, single-strategy planner dedicated to one workflow
 * instance, so {@link #strategyName()} only ever needs to be unique within that planner.
 */
final class WorkflowStrategy implements ExecutionStrategy {

    /** Default strategy name used when the caller doesn't supply an {@link AgentConfig} of their own. */
    static final String DEFAULT_STRATEGY_NAME = "workflow";

    private final Workflow workflow;
    private final String   strategyName;

    /**
     * @param workflow     the workflow this strategy runs
     * @param strategyName the name to register this strategy under — see {@code
     *                     PipelineStrategy}'s constructor Javadoc for why this is
     *                     configurable rather than a fixed constant.
     */
    WorkflowStrategy(Workflow workflow, String strategyName) {
        this.workflow     = Objects.requireNonNull(workflow, "workflow must not be null");
        this.strategyName = Objects.requireNonNull(strategyName, "strategyName must not be null");
    }

    @Override
    public String strategyName() {
        return strategyName;
    }

    @Override
    public ExecutionResult execute(
            AgentTask task, LlmClient llm, MemoryManager memory, ToolRegistry tools, AgentConfig config) {

        WorkflowResult result;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            result = workflow.run(task.input(), pool);
        }

        // No single designated "output node" exists in the D1 graph model — the last
        // entry to actually finish (chronological journal order, not declaration order)
        // is the same "best answer so far" convention PipelineStrategy applies to its
        // own last-executed step, extended to a graph that may have run several nodes
        // concurrently rather than one at a time.
        List<ExecutionStep> steps = new ArrayList<>();
        String output = "";
        int iteration = 0;
        for (JournalEntry entry : result.journal()) {
            if (entry instanceof JournalEntry.Finished finished
                    && finished.outcome() instanceof NodeOutcome.Completed completed) {
                iteration++;
                output = completed.content();
                steps.add(ExecutionStep.observation(
                        finished.nodeId() + "#" + finished.occurrence() + ": " + completed.content(), iteration));
            }
        }

        return result.ok()
                ? ExecutionResult.success(output, iteration, 0, 0, steps)
                : ExecutionResult.failure(result.failureReason(), output, iteration, 0, 0, steps);
    }
}
