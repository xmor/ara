package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
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

    /** Where the plan goes when a config asks for it to be approved; {@code null}: there is nowhere to send it. */
    private final ApprovalGate approvalGate;
    private final Duration approvalTimeout;

    /** A strategy that cannot have its plan approved: a config that asks for it fails the task. */
    public PlanExecuteStrategy() {
        this(null, Duration.ZERO);
    }

    /**
     * A strategy that can have its plan approved by a person, through {@code approvalGate}, for the
     * configs that ask for it ({@code StrategyConfig.PlanExecute.approvePlan}). The gate is the
     * runtime's own, the one that gates tool calls; the strategy is built once and shared, so the
     * gate is given here and not looked up per task.
     *
     * @param approvalTimeout how long a person has to decide before the task fails
     */
    public PlanExecuteStrategy(ApprovalGate approvalGate, Duration approvalTimeout) {
        this.approvalGate = approvalGate;
        this.approvalTimeout = Objects.requireNonNull(approvalTimeout, "approvalTimeout must not be null");
    }

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

        // Resolved against this task, not the caller-independent catalog: the planner must
        // never be shown a tool that this caller will be denied at dispatch.
        List<AraTool> resolvedTools = tools.resolveEnabled(
                config.enabledTools() != null ? config.enabledTools() : List.of(), task);
        if (resolvedTools.stream().anyMatch(tool -> CloseStep.TOOL_ID.equals(tool.toolId()))) {
            // Failing here, rather than letting the agent's tool shadow ours or ours shadow its,
            // is the only outcome that cannot silently break a step: one of the two would stop
            // working with no sign of why.
            return ExecutionResult.failure("plan_execute reserves the tool name '" + CloseStep.TOOL_ID
                    + "' for closing a step, but this agent has a tool with that name; rename it",
                    0, 0, 0, List.of());
        }
        if (pe.approvesPlan() && approvalGate == null) {
            // Failing before the first model call, not after the plan is made: a plan nobody can
            // approve must not run as if it had been, and the run has spent nothing yet.
            return ExecutionResult.failure("plan_execute is set to have its plan approved, but the runtime "
                    + "has no ApprovalGate; configure one with AraRuntime.builder().approvalGate(...)",
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
            case PlanPlanning.Planning.Planned planned ->
                    progress = new Progress(new ArrayList<>(planned.steps()), new ArrayList<>(planned.dependsOn()));
        }
        if (pe.approvesPlan()) {
            switch (PlanApproval.review(run, tally, progress.plan, approvalGate, approvalTimeout)) {
                case PlanApproval.Review.Stopped stopped -> { return stopped.result(); }
                case PlanApproval.Review.Approved approved -> {
                    // A person may have added, removed or reordered steps — the dependency
                    // indices resolved against the original plan cannot be trusted against
                    // whatever they sent back, so the approved plan runs one step at a time,
                    // exactly like a plan with no dependency information at all.
                    progress.plan = new ArrayList<>(approved.steps());
                    progress.dependsOn = emptyDependencies(progress.plan.size());
                }
            }
        }
        log.debug("Plan ({} steps) for task [{}]: {}", progress.plan.size(), task.taskId(), progress.plan);

        // ── Phase 2: Execution ─────────────────────────────────────────────────
        Optional<ExecutionResult> aborted = executeSteps(run, tally, progress, pe.replanPolicy(), pe.parallelSteps());
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
     * Where phase 2 stands: the plan (which a replan may rewrite from the current step on), each
     * step's dependencies (resolved to indices, kept in step with {@code plan} — see
     * {@link #replaceFrom}), the results of the steps done so far, the step to run next and how
     * many replans were used.
     *
     * <p>{@code results} is a compact key-value store, not appended to working memory: each step
     * call receives only the system prompt, the task, a plan summary and a summary of the
     * previous results. Not thread-safe; steps run one at a time or, inside one batch, on
     * worker threads that each write their own local tally and nothing of {@code Progress}
     * itself until the batch is done and the caller merges in plan order.
     */
    private static final class Progress {
        List<String> plan;
        List<java.util.Set<Integer>> dependsOn;
        final Map<Integer, String> results = new LinkedHashMap<>();
        int stepIdx;
        int replanAttempts;
        /**
         * Set by {@link #replaceFrom} whenever a replan rewrites the plan from some step on;
         * checked (and cleared) by the batch loop in {@code executeSteps} to stop applying the
         * rest of a batch's already-computed outcomes once they no longer describe steps the
         * (possibly different) rewritten plan still has at those indices. Not reliable as "did
         * {@code stepIdx} move": {@code stepRevised}'s successful-replan path advances
         * {@code stepIdx} unconditionally before deciding whether to replan at all, so comparing
         * indices cannot tell the two apart from here.
         */
        boolean planRewritten;

        Progress(List<String> plan, List<java.util.Set<Integer>> dependsOn) {
            this.plan = plan;
            this.dependsOn = dependsOn;
        }
    }

    /** {@code count} empty dependency sets — "no known dependency", the one-at-a-time default. */
    private static List<java.util.Set<Integer>> emptyDependencies(int count) {
        List<java.util.Set<Integer>> empty = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            empty.add(java.util.Set.of());
        }
        return empty;
    }

    /**
     * The steps that may run together starting at {@code progress.stepIdx}: as many consecutive
     * steps as declare no dependency on anything at or after {@code stepIdx} itself — i.e. every
     * dependency they have is already satisfied before this batch starts — capped at
     * {@code maxParallel}.
     *
     * <p>This is deliberately not full topological batching across the remaining plan: steps run
     * in written order (unchanged from before parallelism existed), so the question is only "how
     * far past {@code stepIdx} can the next batch reach", never "which steps anywhere in the plan
     * are independent of each other". A step whose dependency falls <em>inside</em> this batch —
     * on another step that would start in the same breath, not one already finished — stops the
     * batch before it, since that dependency's result would not exist yet.
     *
     * <p>Always returns at least {@code [stepIdx]}: a batch is never empty while there is a step
     * left to run.
     */
    private static List<Integer> nextBatch(Progress progress, int maxParallel) {
        int start = progress.stepIdx;
        List<Integer> batch = new ArrayList<>(maxParallel);
        batch.add(start);
        for (int candidate = start + 1; candidate < progress.plan.size() && batch.size() < maxParallel; candidate++) {
            java.util.Set<Integer> deps = progress.dependsOn.get(candidate);
            boolean allSatisfiedBeforeBatch = deps.stream().allMatch(dep -> dep < start);
            if (!allSatisfiedBeforeBatch) {
                break;
            }
            batch.add(candidate);
        }
        return batch;
    }

    /**
     * Runs the plan's steps in order until all are done. Steps a {@linkplain #nextBatch batch}
     * groups together run concurrently; everything else about step execution — the Think →
     * Act → Observe loop, {@code close_step}, replanning, notes — is unchanged.
     *
     * @return empty when every step finished; otherwise the result that ends the task
     */
    private static Optional<ExecutionResult> executeSteps(
            PlanRun run, PlanTally tally, Progress progress, String replanPolicy, int maxParallel) {
        while (progress.stepIdx < progress.plan.size()) {
            if (PlanRun.cancelled()) {
                return Optional.of(tally.fail("Cancelled"));
            }
            run.checkTimeout();
            if (tally.iterations >= run.maxIterations()) {
                return Optional.of(tally.fail(
                        "Max iterations reached while executing step " + (progress.stepIdx + 1)));
            }

            List<Integer> batch = nextBatch(progress, maxParallel);
            List<StepOutcome> outcomes = batch.size() == 1
                    ? List.of(PlanStepExecutor.executeStep(
                            batch.get(0), progress.plan, progress.results, run, tally))
                    : executeBatch(batch, progress, run, tally);

            if (tally.budgetFailure != null) {
                return Optional.of(tally.budgetFailure);
            }
            if (PlanRun.cancelled()) {
                return Optional.of(tally.fail("Cancelled"));
            }

            // Outcomes are applied in plan order, exactly as a sequential run would: a batch of
            // one takes this loop body once, same as before batching existed, and a batch of
            // several applies stepDone/stepRevised/stepFailed's existing per-step logic to each
            // in turn rather than inventing a batch-level variant of it. The first outcome that
            // ends the task (a failure with no replan left, a cancellation, a budget breach)
            // stops there — later outcomes in the same batch, already computed, are simply not
            // applied, the same way a sequential run never reaches steps after the one that
            // ended it.
            progress.planRewritten = false;
            for (int i = 0; i < batch.size(); i++) {
                StepOutcome outcome = outcomes.get(i);
                Optional<ExecutionResult> ended = switch (outcome) {
                    case StepOutcome.Done done -> stepDone(done, progress);
                    case StepOutcome.Revise revise -> stepRevised(revise, progress, replanPolicy, run, tally);
                    case StepOutcome.Failed failed -> stepFailed(failed, progress, replanPolicy, run, tally);
                };
                if (ended.isPresent()) {
                    return ended;
                }
                if (tally.budgetFailure != null) {
                    return Optional.of(tally.budgetFailure);
                }
                // stepRevised/stepFailed may have replanned, which rewrites progress.plan (and
                // dependsOn) from some index on — the remaining outcomes in this batch were
                // computed against the plan as it stood before that, so they no longer describe
                // steps the (possibly different) revised plan still has at those indices.
                if (progress.planRewritten) {
                    break;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Runs every step in {@code batch} on its own virtual thread, each against a {@link
     * PlanTally} local to that worker — {@code PlanTally} is not thread-safe and steps run one
     * at a time outside of a batch, so concurrent steps must not share one. Bounded by
     * {@code run.deadline()}, mirroring {@code ReactExecutionSupport.dispatchParallel}: a worker
     * still running when the deadline passes is interrupted and abandoned, never joined, so a
     * step blocked in a tool call cannot strand the whole batch past the task's own timeout.
     *
     * <p>Local tallies are merged into {@code tally} by the caller, in {@code batch} order — not
     * completion order — immediately after this returns, so the trace and the accumulated token
     * counts read the same regardless of which worker happened to finish first.
     *
     * @return each step's outcome, in the same order as {@code batch}; a worker that was still
     *         running at the deadline reports {@link StepOutcome.Failed} naming the timeout
     */
    private static List<StepOutcome> executeBatch(
            List<Integer> batch, Progress progress, PlanRun run, PlanTally parentTally) {
        int n = batch.size();
        PlanTally[] localTallies = new PlanTally[n];
        StepOutcome[] localOutcomes = new StepOutcome[n];
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(n);
        List<Thread> workers = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            int idx = i;
            int stepIdx = batch.get(i);
            // Each worker gets its own PlanTally, seeded with the parent's running totals so
            // iteration/token/budget accounting carries over — but notes and steps recorded
            // during this step land only in the local copy until the merge, so two concurrent
            // steps never race on the same list.
            PlanTally local = parentTally.copyForWorker();
            localTallies[idx] = local;
            workers.add(Thread.ofVirtual().start(run.tools().wrapForPropagation(() -> {
                try {
                    localOutcomes[idx] = PlanStepExecutor.executeStep(
                            stepIdx, progress.plan, progress.results, run, local);
                } catch (Exception e) {
                    localOutcomes[idx] = new StepOutcome.Failed(
                            "Unexpected error running step " + (stepIdx + 1) + " in parallel: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            })));
        }

        try {
            long remainingMs = Math.max(0,
                    java.time.Duration.between(java.time.Instant.now(), run.deadline()).toMillis());
            if (!latch.await(remainingMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                log.warn("Parallel step batch {} did not finish before the deadline for task [{}]",
                        batch, run.task().taskId());
                workers.forEach(Thread::interrupt);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            workers.forEach(Thread::interrupt);
            log.warn("Parallel step batch {} interrupted for task [{}]", batch, run.task().taskId());
        }

        // Merge in batch (= plan) order, not completion order: the trace and the usage totals
        // must read the same every run, regardless of which worker happened to finish first.
        for (int i = 0; i < n; i++) {
            if (localOutcomes[i] == null) {
                localOutcomes[i] = new StepOutcome.Failed("step " + (batch.get(i) + 1)
                        + " did not finish before the deadline (parallel batch)");
            }
            parentTally.mergeFrom(localTallies[i]);
        }
        return List.of(localOutcomes);
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
        // The revised tail carries no dependency information of its own (replan() reads the
        // plain goal list, not readSteps) — kept in step with the new plan by truncating to the
        // untouched prefix and padding the rest with "no known dependency", same as an
        // approved-plan rewrite. A replan is rare enough that forgoing parallelism on the
        // rewritten tail costs little, and guessing dependencies for steps nobody declared any
        // for is the one thing worth not doing here.
        List<java.util.Set<Integer>> newDependsOn = new ArrayList<>(progress.dependsOn.subList(0, fromStep));
        newDependsOn.addAll(emptyDependencies(revised.size()));
        progress.dependsOn = newDependsOn;
        progress.planRewritten = true;
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
