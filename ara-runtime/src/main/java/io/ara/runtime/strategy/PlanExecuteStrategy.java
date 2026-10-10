package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Plan-then-execute strategy (ReWOO-inspired).
 *
 * <p>Three phases:
 * <ol>
 *   <li><b>Planning</b> — one LLM call produces a numbered list of steps.</li>
 *   <li><b>Execution</b> — each step runs in an isolated context: the model sees the
 *       original task, the full plan, and compact summaries of completed steps rather
 *       than the entire growing working memory. Step results are stored in a
 *       {@code stepResults} map, keeping the per-call token budget predictable and
 *       independent of plan length.</li>
 *   <li><b>Synthesis</b> — one LLM call receives {@code task + plan + stepResults}
 *       (compact, O(N × avg_result)) and produces the final answer.</li>
 * </ol>
 *
 * <p>Optional re-planning: if {@code replanPolicy = "on_failure"} (see
 * {@link StrategyConfig.PlanExecute#replanPolicy()}), a failed step triggers at most
 * {@value #MAX_REPLAN_ATTEMPTS} re-plan attempts that regenerate only the remaining
 * steps rather than the full plan.
 *
 * <p>This class orchestrates the three phases and owns the decisions between them (when a failed
 * or "revise" step is answered by a replan, how the plan is rewritten). The work of each phase
 * lives in {@link PlanPlanning} (asking for and reading the plan, replanning),
 * {@link PlanStepExecutor} (running one step and reading how it was closed, see
 * {@link CloseStep}) and the synthesis step here; {@link PlanRun} and {@link PlanTally} carry
 * what is fixed and what accumulates during one pass. Stateless itself: it is shared by
 * concurrent tasks.
 */
public final class PlanExecuteStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(PlanExecuteStrategy.class);

    private static final int MAX_REPLAN_ATTEMPTS = 2;

    private static final String SYNTHESIS_SUFFIX = """

    Produce the complete final answer based on the execution results provided.
    Be thorough and self-contained.
    """;

    @Override
    public String strategyName() {
        return "plan_execute";
    }

    @Override
    public ExecutionResult execute(
            AgentTask task,
            LlmClient llm,
            MemoryManager memory,
            ToolRegistry tools,
            AgentConfig config
    ) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(llm, "llm must not be null");
        Objects.requireNonNull(memory, "memory must not be null");
        Objects.requireNonNull(tools, "tools must not be null");
        Objects.requireNonNull(config, "config must not be null");

        StrategyConfig.PlanExecute pe = (config.strategyConfig() instanceof StrategyConfig.PlanExecute p)
                ? p : StrategyConfig.PlanExecute.defaults();

        List<AraTool> resolvedTools = tools.resolveEnabled(
                config.enabledTools() != null ? config.enabledTools() : List.of());
        if (resolvedTools.stream().anyMatch(tool -> CloseStep.TOOL_ID.equals(tool.toolId()))) {
            // Failing here, rather than letting the agent's tool shadow ours or ours shadow its,
            // is the only outcome that cannot silently break a step: one of the two would stop
            // working with no sign of why.
            return ExecutionResult.failure("plan_execute reserves the tool name '" + CloseStep.TOOL_ID
                    + "' for closing a step, but this agent has a tool with that name; rename it",
                    0, 0, 0, List.of());
        }
        PlanRun run = newRun(task, llm, memory, tools, config, resolvedTools, pe);
        PlanTally tally = new PlanTally(task);

        // ── Phase 1: Planning ──────────────────────────────────────────────────
        PlanPlanning.Planning planning = PlanPlanning.plan(
                run, tally, pe.maxPlanSteps(), "on_failure".equalsIgnoreCase(pe.replanPolicy()));
        Progress progress;
        switch (planning) {
            case PlanPlanning.Planning.Failed failed -> { return failed.result(); }
            case PlanPlanning.Planning.Planned planned -> progress = new Progress(new ArrayList<>(planned.steps()));
        }
        log.debug("Plan ({} steps) for task [{}]: {}", progress.plan.size(), task.taskId(), progress.plan);

        // ── Phase 2: Execution ─────────────────────────────────────────────────
        Optional<ExecutionResult> aborted = executeSteps(run, tally, progress, pe.replanPolicy());
        if (aborted.isPresent()) {
            return aborted.get();
        }

        // ── Phase 3: Synthesis ─────────────────────────────────────────────────
        return synthesize(run, tally, progress);
    }

    /**
     * Everything that is fixed for one pass, built once. See ReactStrategy for why native
     * function-calling clients get structured tool specs attached to the step-execution call
     * context instead of the text catalog and instructions (planning and synthesis never invoke
     * tools either way, so they are left untouched).
     *
     * <p>The catalogs are precomputed here and carried on the run, like the system prompt:
     * the tools are stable for the whole pass, yet the step messages used to re-serialise every
     * tool schema on each round.
     */
    private static PlanRun newRun(
            AgentTask task, LlmClient llm, MemoryManager memory, ToolRegistry tools,
            AgentConfig config, List<AraTool> resolvedTools, StrategyConfig.PlanExecute pe) {
        List<AraTool> stepTools = new ArrayList<>(resolvedTools);
        stepTools.add(CloseStep.TOOL);
        boolean nativeTools = llm.supportsNativeTools();
        String plannerCatalog = ToolCatalogFormatter.formatForPlanning(resolvedTools);
        String stepCatalog = nativeTools ? "" : ToolCatalogFormatter.format(stepTools);
        if (pe.parallelSteps() > 1) {
            log.warn("maxParallelSteps={} is set but parallel steps are not implemented yet; "
                    + "steps run one at a time", pe.parallelSteps());
        }
        return new PlanRun(
                task, llm, LlmCallContext.of(config, task), tools,
                resolvedTools, List.copyOf(stepTools),
                config, ReactExecutionSupport.extractSystemPrompt(memory),
                plannerCatalog, stepCatalog,
                Instant.now().plus(config.executionTimeout()),
                config.maxIterations(), pe.maxStepRoundsPerStep(),
                nativeTools);
    }

    /**
     * Where phase 2 stands: the plan (which a replan may rewrite from the current step on), the
     * results of the steps done so far, the step to run next and how many replans were used.
     *
     * <p>{@code results} is a compact key-value store, not appended to working memory: each step
     * call receives only the system prompt, the task, a plan summary and a summary of the
     * previous results. Not thread-safe; steps run one at a time.
     */
    private static final class Progress {
        List<String> plan;
        final Map<Integer, String> results = new LinkedHashMap<>();
        int stepIdx;
        int replanAttempts;

        Progress(List<String> plan) {
            this.plan = plan;
        }
    }

    /**
     * Runs the plan's steps in order until all are done.
     *
     * @return empty when every step finished; otherwise the result that ends the task
     */
    private static Optional<ExecutionResult> executeSteps(
            PlanRun run, PlanTally tally, Progress progress, String replanPolicy) {
        while (progress.stepIdx < progress.plan.size()) {
            if (PlanRun.cancelled()) {
                return Optional.of(tally.fail("Cancelled"));
            }
            run.checkTimeout();
            if (tally.iterations >= run.maxIterations()) {
                return Optional.of(tally.fail(
                        "Max iterations reached while executing step " + (progress.stepIdx + 1)));
            }

            StepOutcome outcome = PlanStepExecutor.executeStep(
                    progress.stepIdx, progress.plan, progress.results, run, tally);

            if (tally.budgetFailure != null) {
                return Optional.of(tally.budgetFailure);
            }
            if (PlanRun.cancelled()) {
                return Optional.of(tally.fail("Cancelled"));
            }

            Optional<ExecutionResult> ended = switch (outcome) {
                case StepOutcome.Done done -> stepDone(done, progress);
                case StepOutcome.Revise revise -> stepRevised(revise, progress, replanPolicy, run, tally);
                case StepOutcome.Failed failed -> stepFailed(failed, progress, replanPolicy, run, tally);
            };
            if (ended.isPresent()) {
                return ended;
            }
        }
        return Optional.empty();
    }

    private static Optional<ExecutionResult> stepDone(StepOutcome.Done done, Progress progress) {
        progress.results.put(progress.stepIdx, done.result());
        log.debug("Step {}/{} completed ({} chars)",
                progress.stepIdx + 1, progress.plan.size(), done.result().length());
        progress.stepIdx++;
        return Optional.empty();
    }

    /**
     * The step itself worked: keep its result and move on. Only the steps still ahead are put
     * back to the planner — and only when there is still budget to replan and something left to
     * replan; otherwise the reason is not lost, it becomes a note the remaining steps (and the
     * synthesis) can see.
     */
    private static Optional<ExecutionResult> stepRevised(
            StepOutcome.Revise revise, Progress progress, String replanPolicy, PlanRun run, PlanTally tally) {
        progress.results.put(progress.stepIdx, revise.result());
        progress.stepIdx++;
        int next = progress.stepIdx;
        if (next >= progress.plan.size() || !mayReplan(replanPolicy, progress.replanAttempts, run, tally)) {
            tally.notes.add("Step " + next + " asked for the rest of the plan to be revised: " + revise.reason());
            return Optional.empty();
        }
        progress.replanAttempts++;
        List<String> revisedSteps = PlanPlanning.replan(progress.plan, progress.results,
                new PlanPlanning.ReplanRequest(next, revise.reason(), false), run, tally);
        if (tally.budgetFailure != null) {
            return Optional.of(tally.budgetFailure);
        }
        if (revisedSteps.isEmpty()) {
            tally.notes.add("Step " + next + " asked for the rest of the plan to be revised ("
                    + revise.reason() + "), but no revised plan could be read; the original plan continues.");
        } else {
            replaceFrom(progress, next, revisedSteps);
        }
        return Optional.empty();
    }

    /**
     * A step produced nothing usable. With replanning allowed the remaining plan is rebuilt and
     * the same step index is tried again on the new plan (empty is returned and the loop goes on);
     * otherwise the task fails, naming the step and why.
     */
    private static Optional<ExecutionResult> stepFailed(
            StepOutcome.Failed failed, Progress progress, String replanPolicy, PlanRun run, PlanTally tally) {
        int idx = progress.stepIdx;
        String failureDesc = "Step " + (idx + 1) + " of " + progress.plan.size()
                + " [" + progress.plan.get(idx) + "] " + failed.reason();
        log.warn(failureDesc);

        if (mayReplan(replanPolicy, progress.replanAttempts, run, tally)) {
            progress.replanAttempts++;
            log.debug("Replanning after step {} failure (attempt {}/{})",
                    idx + 1, progress.replanAttempts, MAX_REPLAN_ATTEMPTS);

            List<String> revisedSteps = PlanPlanning.replan(progress.plan, progress.results,
                    new PlanPlanning.ReplanRequest(idx, failureDesc, true), run, tally);
            if (tally.budgetFailure != null) {
                return Optional.of(tally.budgetFailure);
            }
            if (!revisedSteps.isEmpty()) {
                replaceFrom(progress, idx, revisedSteps);
                log.debug("Revised plan ({} steps from step {}): {}", revisedSteps.size(), idx + 1, revisedSteps);
                return Optional.empty();
            }
        }
        return Optional.of(tally.fail(failureDesc));
    }

    /** Keeps the steps before {@code fromStep} and puts {@code revised} after them. */
    private static void replaceFrom(Progress progress, int fromStep, List<String> revised) {
        List<String> newPlan = new ArrayList<>(progress.plan.subList(0, fromStep));
        newPlan.addAll(revised);
        progress.plan = newPlan;
    }

    /** Phase 3: one call that turns the task, the plan, the results and the notes into the answer. */
    private static ExecutionResult synthesize(PlanRun run, PlanTally tally, Progress progress) {
        if (PlanRun.cancelled()) {
            return tally.fail("Cancelled");
        }
        run.checkTimeout();
        if (tally.iterations >= run.maxIterations()) {
            return tally.fail("Max iterations reached before synthesis");
        }
        tally.iterations++;

        LlmCompletion completion;
        try {
            completion = ReactExecutionSupport.completeWithRetry(
                    run.llm(), buildSynthesisMessages(run, progress.plan, progress.results, tally.notes),
                    run.ctx(), run.deadline(), run.config(), run.task().taskId());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return tally.fail("Cancelled");
        } catch (Exception e) {
            return tally.fail("Synthesis failed: " + ReactExecutionSupport.describeLlmFailure(e));
        }
        run.checkTimeout();
        tally.addUsage(completion);
        ExecutionResult budgetExceeded = ReactExecutionSupport.chargeRunBudget(
                run.config(), run.task(), completion.promptTokens(), completion.outputTokens(),
                tally.iterations, tally.promptTokens, tally.outputTokens, tally.steps);
        if (budgetExceeded != null) {
            return budgetExceeded;
        }

        String finalAnswer = completion.text() != null ? completion.text().strip() : "";
        if (finalAnswer.isBlank()) {
            // Synthesis returned nothing (context overflow or empty response) —
            // fall back to concatenating step results directly
            log.warn("Synthesis returned empty response, falling back to step results");
            finalAnswer = buildFallbackAnswer(progress.plan, progress.results);
        }
        tally.record(ExecutionStep.finalAnswer(finalAnswer, tally.iterations));
        return ExecutionResult.success(finalAnswer, tally.iterations,
                tally.promptTokens, tally.outputTokens, tally.steps);
    }

    /** Whether a step that went wrong may be answered with another planning call. */
    private static boolean mayReplan(String replanPolicy, int replanAttempts, PlanRun run, PlanTally tally) {
        return "on_failure".equalsIgnoreCase(replanPolicy)
                && replanAttempts < MAX_REPLAN_ATTEMPTS
                && tally.iterations < run.maxIterations();
    }

    /**
     * Synthesis phase: compact prompt with task + plan + all step results.
     * Token usage is predictable regardless of how many tool rounds each step used.
     */
    private static List<LlmMessage> buildSynthesisMessages(
            PlanRun run, List<String> plan, Map<Integer, String> stepResults, List<String> notes) {

        StringBuilder ctx = new StringBuilder();
        ctx.append("Task: ").append(run.task().input()).append("\n\n");
        ctx.append("Execution results:\n");
        for (int i = 0; i < plan.size(); i++) {
            String result = stepResults.getOrDefault(i, "(not executed)");
            ctx.append("Step %d — %s:%n%s%n%n".formatted(i + 1, plan.get(i), result));
        }
        String notesBlock = CloseStep.formatNotes(notes);
        if (notesBlock != null) {
            ctx.append(notesBlock).append("\n\n");
        }
        ctx.append("Based on the above, produce the complete final answer.");

        return List.of(
                new LlmMessage("system", run.systemPrompt() + SYNTHESIS_SUFFIX),
                new LlmMessage("user", ctx.toString())
        );
    }

    private static String buildFallbackAnswer(List<String> plan, Map<Integer, String> stepResults) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plan.size(); i++) {
            String result = stepResults.get(i);
            if (result != null && !result.isBlank()) {
                sb.append(result).append("\n\n");
            }
        }
        return sb.toString().strip();
    }
}
