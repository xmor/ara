package io.ara.runtime.workflow;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.runtime.strategy.RunEvents;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.llm.LlmClient;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.ToolRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adapts a {@link Workflow} to the {@link ExecutionStrategy} contract: a workflow is an
 * agent, hosted the same way {@code PipelineStrategy} hosts {@link
 * io.ara.runtime.pipeline.AgentPipeline}. Hosting a workflow inside a real {@code
 * AgentInstance} gets it per-session isolation (one execution at a time per session,
 * the busy policy) and cooperative
 * cancellation, the {@code agent.execute} telemetry span, and the interceptor chain for
 * free, instead of the hand-rolled {@code GraphAgent} this retires (mutable {@code
 * volatile} state, {@code RunContext} discarded at the boundary, token/cost always
 * reported {@code 0, 0, 0.0} — an agent that hides its real spend poisons any cost
 * governance above it, and the isolation and busy policy had to be rebuilt by hand).
 *
 * <p>Ignores {@code llm}, {@code memory}, and {@code tools} entirely — same reasoning as
 * {@code PipelineStrategy}: a workflow's real work happens inside its own nodes.
 *
 * <p><b>Token and cost reporting.</b> For a graph of opaque nodes there is no
 * {@code AgentResponse} to read from, so {@link ExecutionResult#promptTokens()}/
 * {@link ExecutionResult#outputTokens()} are {@code 0} — the node's work is a plain
 * function with no usage to report. An <b>agent-shaped</b> node (see {@link
 * Workflow.Builder#agent}) is different: the agent's {@code AgentResponse} is captured in
 * the journal, so those totals are summed here from {@link WorkflowResult} and the split is
 * real. A node that declares a {@link WorkflowNode#cost()} is still charged to the run's
 * {@link io.ara.core.budget.RunBudget} if one is configured — the single cost governor
 * for the whole run; surfacing that
 * declared spend through this {@code ExecutionResult} is a separate increment — it needs
 * {@link Workflow#run} to hand back the budget's final {@code Spend}.
 *
 * <p>Package-private for the same reason as {@code PipelineStrategy}: {@link
 * WorkflowAgents#of} builds a fresh, single-strategy planner dedicated to one workflow
 * instance, so {@link #strategyName()} only ever needs to be unique within that planner.
 */
final class WorkflowStrategy implements ExecutionStrategy {

    /** Default strategy name used when the caller doesn't supply an {@link AgentConfig} of their own. */
    static final String DEFAULT_STRATEGY_NAME = "workflow";

    /**
     * P7/U20, 2026-09-23: shared, process-wide, in place of a fresh {@code
     * try-with-resources} executor per {@link #execute} call. {@code ExecutorService
     * .close()} (the JDK 19+ default {@link AutoCloseable} behaviour a
     * try-with-resources block invokes) waits for every submitted task to finish before
     * returning — including one abandoned past {@link #deadline}'s bound below, which
     * would otherwise turn a bounded run into an unbounded {@code execute()} call anyway.
     * Exactly {@code AgentPipeline}'s own {@code NODE_POOL} precedent (see its Javadoc for
     * the full reasoning): a virtual-thread-per-task executor holds no pooled resource
     * between runs, so one process-wide instance has nothing to release and nothing a
     * per-run {@code close()} was ever protecting.
     */
    private static final ExecutorService NODE_POOL = Executors.newVirtualThreadPerTaskExecutor();

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

        // P7/U20: bounds the run by this agent's own executionTimeout — without it,
        // a node body that never returns (or a mapOver child — see DataflowScheduler's
        // own deadline Javadoc) blocked drive() forever, with no independent bound of
        // its own separate from whatever the caller enforces outside this call.
        Instant deadline = Instant.now().plus(config.executionTimeout());
        // The task's attachments are part of the workflow's request, the same way they are for any agent: the nodes that
        // declared they read them are handed them, and a workflow where none does refuses the request instead of dropping
        // them.
        WorkflowResult result = workflow.run(task.input(), task.media(), NODE_POOL, List.of(), deadline);

        // No single designated "output node" exists in the graph model — the last
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
                // A workflow node's completion is not a tool result — no toolId, no success
                // flag: the text-only factory keeps those fields null.
                RunEvents.record(task, steps, ExecutionStep.observation(
                        finished.nodeId() + "#" + finished.occurrence() + ": " + completed.content(), iteration));
            }
        }

        ExecutionResult executed = result.ok()
                ? ExecutionResult.success(output, iteration, result.totalPromptTokens(), result.totalOutputTokens(), steps)
                : ExecutionResult.failure(result.failureReason(), output, iteration,
                        result.totalPromptTokens(), result.totalOutputTokens(), steps);
        // The real cost of the nodes that ran — not the token counts re-priced with this
        // agent's own rates, which say nothing about the models the inner agents used.
        return result.spend().map(spend -> executed.withCost(spend.money())).orElse(executed);
    }
}
