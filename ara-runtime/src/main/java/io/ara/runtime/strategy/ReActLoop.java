package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.agent.PendingApproval;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.agent.RunCheckpoint;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.bus.BackgroundTasks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared skeleton of a ReAct-shaped reasoning loop, reused by {@link ReactStrategy},
 * {@link ReflActStrategy} and {@link ReSpActStrategy}.
 *
 * <p>Every strategy in the family repeats the same per-iteration invariants: cooperative
 * cancellation, the single task deadline, the cost/run budget checks, token accounting,
 * the one-shot synthesis nudge, and an LLM call bounded by that deadline. Keeping that
 * skeleton in each strategy meant a fix to any one of them (the interrupt restore, the
 * deadline re-check after the call, the budget charge) had to be copied correctly three
 * times — the same class of bug {@link ReactExecutionSupport} was extracted to avoid for
 * the dispatch/decision pieces. This class owns the skeleton; a subclass supplies only
 * what genuinely varies:
 *
 * <ul>
 *   <li>{@link #systemSuffix()} — the format instructions appended to the system prompt;</li>
 *   <li>{@link #decide} — the post-LLM decision and its side effects (tool dispatch via
 *       {@link #dispatch}, reflection, speak), returning a result to stop or {@code null}
 *       to loop again;</li>
 *   <li>{@link #onMaxIterations()} — the strategy-specific give-up message;</li>
 *   <li>the two optional behaviours {@link #injectSynthesis()} and {@link #logIterations()},
 *       which {@link ReSpActStrategy} deliberately disables.</li>
 * </ul>
 *
 * <p>State that the hooks need to read or mutate (iteration/token counters, the step
 * trace, the resolved tools) lives on this class as fields rather than being threaded
 * through each hook's parameter list.
 */
abstract class ReActLoop {

    private static final Logger log = LoggerFactory.getLogger(ReActLoop.class);

    final AgentTask     task;
    final LlmClient     llm;
    final MemoryManager memory;
    final ToolRegistry  tools;
    final AgentConfig   config;
    final List<AraTool> resolvedTools;
    final boolean       nativeTools;
    final boolean       logIo;
    final int           logIoMaxChars;
    final Instant       deadline;
    final LlmCallContext ctx;
    final LlmCallContext stepCtx;
    final LlmCallContext forcedCtx;
    private final RunCheckpointer checkpointer;   // null when the run is not checkpointed
    private ExecutionResult suspension;           // set by dispatch() when the turn stopped for an approval
    private final BackgroundTasks backgroundTasks;   // null unless the agent may delegate in the background
    private boolean finalAnswerHeld;                 // the run already waited once for its background tasks

    private final String toolCatalog;
    // The message list grows incrementally across iterations — see MessageBuffer.
    private final ReactExecutionSupport.MessageBuffer messageBuffer = new ReactExecutionSupport.MessageBuffer();

    final List<ExecutionStep> steps = new ArrayList<>();
    int     iterations;
    int     totalPromptTokens;
    int     totalOutputTokens;
    boolean synthesisModeActive;

    ReActLoop(AgentTask task, LlmClient llm, MemoryManager memory, ToolRegistry tools, AgentConfig config) {
        // The registry rides in the task's context, which is where the tools look for it. It is
        // made here, per run, and only for an agent that opted in: a run that never starts a
        // background task pays nothing and sees nothing of the feature.
        this.backgroundTasks = config.asyncDelegation() != null ? new BackgroundTasks(config.asyncDelegation()) : null;
        this.task          = backgroundTasks != null ? backgroundTasks.attachTo(task) : task;
        this.llm           = llm;
        this.memory        = memory;
        this.tools         = tools;
        this.config        = config;
        this.logIo         = config.logLlmIo();
        this.logIoMaxChars = config.logLlmIoMaxChars();
        this.nativeTools   = llm.supportsNativeTools();
        this.deadline      = Instant.now().plus(config.executionTimeout());
        this.ctx           = LlmCallContext.of(config, task);
        this.checkpointer  = RunCheckpointer.from(task);

        // Resolve tools once — the list is stable for the lifetime of this execution.
        // Resolved against the effective task (`this.task`), not the raw parameter: with
        // background delegation configured, BackgroundTasks.attachTo may wrap it, and the
        // scope filter must read the channels of the task actually being executed.
        this.resolvedTools = tools.resolveEnabled(config.enabledTools(), this.task);
        // stepCtx only ever differs by which tools are exposed: the full set on normal
        // iterations, an empty set on forced-final ones. All other fields are fixed for
        // the task, so precompute the two variants once instead of copying every field
        // per loop turn.
        this.stepCtx       = ctx.withResolvedTools(resolvedTools);
        this.forcedCtx     = ctx.withResolvedTools(List.of());
        // Same hoisting: the text catalog only varies by which tools are exposed, so
        // precompute the string once instead of re-serialising every schema per iteration.
        this.toolCatalog   = ReactExecutionSupport.toolCatalog(resolvedTools, nativeTools);
        task.resumeCheckpoint().ifPresent(this::resumeFrom);
    }

    /**
     * Continues a saved run: the counters and trace pick up where the checkpoint left them, so
     * budgets, the iteration cap and the final response account for the work already done. The
     * working memory was already restored by the caller.
     *
     * <p>The synthesis nudge is a message in that memory, so whether it was already injected is
     * decided by the iteration count alone — the nudge fires at the start of the first iteration
     * past the threshold, and a checkpoint is only taken at the end of one.
     */
    private void resumeFrom(RunCheckpoint checkpoint) {
        iterations        = checkpoint.iteration();
        totalPromptTokens = checkpoint.promptTokens();
        totalOutputTokens = checkpoint.outputTokens();
        steps.addAll(checkpoint.steps());
        synthesisModeActive = ReactExecutionSupport.synthesisDue(config, iterations);
    }

    /**
     * Runs the loop to completion, success or failure, and then stops whatever the run left
     * running in the background. In a {@code finally}, so that every way a run can end — an
     * answer, a failure, a deadline, a budget, a cancellation — takes its children with it: a
     * background task that outlived the run that started it would spend tokens for nobody.
     */
    final ExecutionResult run() {
        try {
            return runLoop();
        } finally {
            stopBackgroundTasks();
        }
    }

    private ExecutionResult runLoop() {
        logStart();
        task.resumeCheckpoint().map(RunCheckpoint::pendingApproval).ifPresent(this::completePendingTurn);
        while (iterations < config.maxIterations()) {
            // Cooperative cancellation: AgentInstance.terminate(session) interrupts this thread.
            if (Thread.currentThread().isInterrupted()) {
                log.debug("Task [{}] cancelled at iteration {} (thread interrupted)", task.taskId(), iterations);
                return cancelled();
            }
            if (Instant.now().isAfter(deadline)) {
                throw new ExecutionTimeoutException(config.executionTimeout());
            }

            ExecutionResult budgetExceeded = ReactExecutionSupport.checkBudget(
                    config, task.taskId(), totalPromptTokens, totalOutputTokens, iterations, steps);
            if (budgetExceeded != null) {
                return budgetExceeded;
            }

            iterations++;
            if (injectSynthesis()) {
                synthesisModeActive = ReactExecutionSupport.maybeInjectSynthesis(
                        memory, config, task, iterations, synthesisModeActive, resolvedTools, nativeTools);
            }

            deliverBackgroundResults();

            // Hard stop: on the final iteration(s) withhold tools so the model cannot
            // emit a structured tool call and MUST produce plain text, which the forced
            // decision catches. This guarantees termination even for models that never
            // self-terminate.
            boolean forceFinal = iterations >= config.maxIterations() - 1;
            List<LlmMessage> messages = messageBuffer.build(memory, forceFinal ? "" : toolCatalog, systemSuffix());
            LlmCallContext iterationCtx = forceFinal ? forcedCtx : stepCtx;

            LlmCompletion completion;
            try {
                completion = ReactExecutionSupport.callLlm(llm, messages, iterationCtx, task, deadline, config);
            } catch (ExecutionTimeoutException te) {
                throw te;   // preserve timeout semantics for AgentInstance
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();   // restore the flag for our caller
                log.debug("Task [{}] cancelled during the LLM call at iteration {}", task.taskId(), iterations);
                return cancelled();
            } catch (Exception e) {
                log.warn("LLM call failed on iteration {} for task [{}]: {}",
                        iterations, task.taskId(), e.getMessage());
                log.debug("LLM exception detail", e);
                return ExecutionResult.failure(ReactExecutionSupport.describeLlmFailure(e),
                        iterations, totalPromptTokens, totalOutputTokens, steps);
            }

            if (Instant.now().isAfter(deadline)) {
                throw new ExecutionTimeoutException(config.executionTimeout());
            }

            totalPromptTokens += completion.promptTokens();
            totalOutputTokens += completion.outputTokens();
            String output = completion.text();

            ExecutionResult runBudgetExceeded = ReactExecutionSupport.chargeRunBudget(
                    config, task, completion.promptTokens(), completion.outputTokens(),
                    iterations, totalPromptTokens, totalOutputTokens, steps);
            if (runBudgetExceeded != null) {
                return runBudgetExceeded;
            }

            ReactExecutionSupport.recordAssistantOutput(memory, steps, completion, output, iterations, task, config);
            if (logIterations()) {
                ReactExecutionSupport.logIterationResult(completion, iterations, config.maxIterations(), task.taskId());
            }

            ExecutionResult decision = decide(output, completion, forceFinal, iterations);
            if (decision != null) {
                if (holdFinalAnswer(decision)) {
                    checkpointIteration();
                    continue;
                }
                return decision;
            }
            // dispatch() runs inside decide(), whose subclasses ignore what it returns about
            // suspension; the field is how the loop learns the turn stopped for an approval.
            if (suspension != null) {
                return suspension;
            }
            checkpointIteration();
        }
        return onMaxIterations();
    }

    // ── Background tasks ──────────────────────────────────────────────────────

    /**
     * Hands the model what finished while it was thinking, as messages in its working memory,
     * and marks the start of the turn for {@code await_tasks}. Pushed here, before the model
     * call, so the model never has to ask whether something finished.
     */
    private void deliverBackgroundResults() {
        if (backgroundTasks == null) {
            return;
        }
        backgroundTasks.deliverFinished().forEach(message -> memory.appendToWorkingMemory("user", message));
        backgroundTasks.beginTurn();
    }

    /**
     * Keeps a final answer from ending a run that still has background tasks: the run waits for
     * them (up to its deadline), puts their results in front of the model and lets it answer once
     * more, with them. Only once per run, and only if the iteration budget has a turn left — a
     * second answer the loop has no room to take would turn a good answer into "max iterations".
     *
     * <p>The answer already in hand is kept when waiting brings nothing to answer with — the
     * tasks did not finish, or the deadline passed — and is then the run's answer: another turn
     * would only run into the deadline and lose a good answer. The tasks still open are stopped
     * by {@link #run}.
     *
     * <p>Known cost: an answer already streamed to the user is followed by a second one.
     *
     * @return {@code true} when the answer was held and the loop should go on
     */
    private boolean holdFinalAnswer(ExecutionResult decision) {
        if (backgroundTasks == null || finalAnswerHeld || !decision.isSuccess()
                || iterations >= config.maxIterations() || !backgroundTasks.hasTasks()) {
            return false;
        }
        finalAnswerHeld = true;
        log.debug("Task [{}] answered with background tasks open: waiting for them", task.taskId());
        List<String> results = backgroundTasks.awaitAllAndDeliver(deadline);
        if (results.isEmpty() || Instant.now().isAfter(deadline)) {
            return false;
        }
        results.forEach(message -> memory.appendToWorkingMemory("user", message));
        memory.appendToWorkingMemory("user", backgroundTasks.hasTasks()
                ? "[automated] Some of your background tasks did not finish in time and will be stopped. "
                        + "Give your final answer with what you have."
                : "[automated] Your background tasks have finished. Give your final answer using their results.");
        return true;
    }

    /**
     * Before the run is saved for an approval: results that already arrived go into the
     * working memory (and so into the checkpoint), and what is still running is stopped, with
     * a note the resumed run will read. A future in flight cannot be saved, and pretending
     * otherwise would promise the model a result that cannot arrive.
     */
    private void settleBackgroundTasksForSuspension() {
        if (backgroundTasks == null) {
            return;
        }
        backgroundTasks.deliverFinished().forEach(message -> memory.appendToWorkingMemory("user", message));
        List<String> stopped = backgroundTasks.cancelAll();
        if (!stopped.isEmpty()) {
            memory.appendToWorkingMemory("user", "[automated] These background tasks were cancelled because the run "
                    + "stopped to wait for an approval: " + String.join(", ", stopped)
                    + ". Start them again if you still need them.");
        }
    }

    private void stopBackgroundTasks() {
        if (backgroundTasks == null) {
            return;
        }
        List<String> stopped = backgroundTasks.cancelAll();
        if (!stopped.isEmpty() && log.isInfoEnabled()) {
            log.info("Task [{}] ended with background tasks still running; stopped: {}", task.taskId(), stopped);
        }
    }

    /**
     * Dispatches the calls of a {@link ReactExecutionSupport.StepDecision.DispatchTools}
     * decision on the shared {@link ReactExecutionSupport#dispatchSingle} /
     * {@link ReactExecutionSupport#dispatchParallel} path.
     *
     * @return {@code true} if any dispatched call failed — the signal
     *         {@link ReflActStrategy}'s reflect-on-tool-failure trigger reacts to
     */
    final boolean dispatch(List<ToolCallParser.ToolCallRequest> calls, LlmCompletion completion, int iteration) {
        if (suspendForApproval(calls, completion)) {
            return false;   // nothing ran, so nothing failed
        }
        ReActSupport.DispatchContext dispatchCtx = new ReActSupport.DispatchContext(
                tools, memory, steps, task, iteration, deadline, logIo, logIoMaxChars);
        if (calls.size() == 1) {
            // Single tool call — same deadline-bounded dispatch, plus the legacy
            // top-level toolCallId fallback batches never need. A turn being completed after
            // an approval has no completion: its call ids were resolved when it was suspended.
            return ReactExecutionSupport.dispatchSingle(calls.get(0),
                    completion != null ? completion.toolCallId() : null, dispatchCtx);
        }
        // Multiple tool calls — dispatch in parallel via virtual threads.
        log.debug("Parallel dispatch: {} tool calls for task [{}]", calls.size(), task.taskId());
        return ReactExecutionSupport.dispatchParallel(calls, dispatchCtx);
    }

    /**
     * Stops the run before this turn's calls if one of them needs a human's approval, saving the
     * run so it can be continued once that is decided (ADR-048 D2). Asked <em>before</em> any
     * call is dispatched, so the turn is either run whole or not at all: a suspension after
     * some of a parallel batch had already executed would run those calls again on resume.
     *
     * <p>Does nothing, and the caller dispatches as before — with the gate blocking its thread
     * for the answer — when the run cannot be resumed (no store, or a strategy that does not
     * resume) or when the checkpoint could not be written.
     *
     * @return {@code true} when the turn was suspended; {@link #suspension} then holds the result
     */
    private boolean suspendForApproval(List<ToolCallParser.ToolCallRequest> calls, LlmCompletion completion) {
        if (checkpointer == null || !checkpointer.canSuspend()) {
            return false;
        }
        // A request for every call that needs one, opened together: the model asked for the
        // turn as a whole, and so does the operator get to see it.
        List<PendingApproval.ToolCall> pendingCalls = new ArrayList<>(calls.size());
        boolean anyNeedsApproval = false;
        for (ToolCallParser.ToolCallRequest call : calls) {
            ApprovalRequest request = tools.approvalRequestFor(call.toolId(), call.argumentJson(), task).orElse(null);
            anyNeedsApproval |= request != null;
            // The id a single call is known by may be only on the completion (the legacy
            // shape); resolve it now, the resumed turn has no completion to read it from.
            String callId = call.toolCallId() != null ? call.toolCallId()
                    : calls.size() == 1 && completion != null ? completion.toolCallId() : null;
            pendingCalls.add(new PendingApproval.ToolCall(call.toolId(), call.argumentJson(), callId, request));
        }
        return anyNeedsApproval && suspendBefore(new PendingApproval(pendingCalls));
    }

    private boolean suspendBefore(PendingApproval pending) {
        settleBackgroundTasksForSuspension();
        boolean saved = checkpointer.saveSuspended(iterations, memory.workingMemory(), steps,
                totalPromptTokens, totalOutputTokens, pending);
        if (!saved) {
            return false;
        }
        log.info("Task [{}] suspended at iteration {} awaiting {} approval(s)",
                task.taskId(), iterations, pending.requests().size());
        suspension = ExecutionResult.awaitingApproval(pending.requests(), iterations,
                totalPromptTokens, totalOutputTokens, steps);
        return true;
    }

    /**
     * Completes the turn a resumed run was suspended in: its calls run as one batch, each
     * answered by the decision the operator gave (see {@code ApprovalVerdicts}), before the loop
     * goes on to the next iteration. The turn's own iteration number is kept, so budgets and the
     * iteration cap count it once.
     *
     * <p>Only the plain dispatch runs here: a strategy's reaction to a failed call (ReflAct's
     * self-correction) is not applied to this one turn.
     */
    private void completePendingTurn(PendingApproval pending) {
        List<ToolCallParser.ToolCallRequest> calls = pending.calls().stream()
                .map(call -> new ToolCallParser.ToolCallRequest(call.toolId(), call.argumentJson(), call.toolCallId()))
                .toList();
        dispatch(calls, null, iterations);
        checkpointIteration();
    }

    /**
     * Persists the run at the boundary of a finished iteration (ADR-048 D2) — the point where
     * the next turn is about to start, so a resumed run continues from the next iteration with
     * nothing half-done. Does nothing when the run is not checkpointed, which is the default.
     */
    private void checkpointIteration() {
        if (checkpointer != null) {
            checkpointer.save(iterations, memory.workingMemory(), steps, totalPromptTokens, totalOutputTokens);
        }
    }

    /** The strategy-neutral "Cancelled" failure, carrying the counters accumulated so far. */
    final ExecutionResult cancelled() {
        return ExecutionResult.failure("Cancelled", iterations, totalPromptTokens, totalOutputTokens, steps);
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    /** The format-instruction suffix appended to the system prompt on normal iterations. */
    abstract String systemSuffix();

    /**
     * Handles the current completion. Returns a non-null result to end the run, or
     * {@code null} to continue looping.
     *
     * @param forceFinal {@code true} on the last iteration(s), when tools were withheld
     */
    abstract ExecutionResult decide(String output, LlmCompletion completion, boolean forceFinal, int iteration);

    /** The strategy-specific failure produced when the loop exhausts {@code maxIterations}. */
    abstract ExecutionResult onMaxIterations();

    /** Logs the strategy's start banner. Overridden where the banner carries extra state. */
    void logStart() {}

    /** Whether the one-shot synthesis nudge applies — {@link ReSpActStrategy} opts out. */
    boolean injectSynthesis() { return true; }

    /** Whether to log each iteration's outcome — {@link ReSpActStrategy} opts out. */
    boolean logIterations() { return true; }
}
