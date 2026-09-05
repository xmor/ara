package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.agent.StepType;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmConfig;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmRouter;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ReflAct execution strategy — a single ReAct loop with in-place, in-loop self-correction,
 * combining the reasoning/acting cycle of ReAct (Yao et al., 2022 — arXiv:2210.03629)
 * with the verbal self-critique idea of Reflexion (Shinn et al., 2023 —
 * arXiv:2303.11366) at a finer grain.
 *
 * <h2>How this differs from {@link ReflexionStrategy}</h2>
 * <p>{@code ReflexionStrategy} is a decorator: it runs a delegate strategy to
 * completion, and only if the <em>entire</em> attempt fails does it generate a critique,
 * wipe working memory, and restart the whole episode from scratch with the critique
 * injected as a "lessons learned" block. That is macro-level, whole-episode reflection.
 *
 * <p>{@code ReflActStrategy} reflects <em>within</em> one ongoing episode, without ever
 * resetting anything. Two triggers, checked after each iteration:
 * <ul>
 *   <li>A dispatched tool call fails (gated by {@link StrategyConfig.ReflAct#reflectOnToolFailure()}).</li>
 *   <li>{@link StrategyConfig.ReflAct#unproductiveStreak()} consecutive iterations pass
 *       with neither a tool dispatch nor a final answer — the model is "thinking in
 *       circles."</li>
 * </ul>
 * On either trigger, a short side LLM call (optionally routed to a different, stronger
 * model — the "dual-brain" split the ReflAct/Reflexion literature describes) reviews the
 * recent scratchpad and produces a one-shot course-correction, appended to the <em>same</em>
 * working memory as a {@code "user"} turn (same idiom {@link ReactExecutionSupport}
 * already uses for the synthesis nudge) and recorded as a {@link
 * StepType#REFLECTION} step. The very next Think call sees it in
 * context; nothing before it is discarded. Reflection calls are capped at {@link
 * StrategyConfig.ReflAct#maxReflections()} for the whole task and do not consume the
 * main loop's {@code maxIterations} budget (mirroring how {@link ReflexionStrategy} and
 * {@code PlanExecuteStrategy}'s replan step keep meta-level calls off that budget) —
 * their token usage is still folded into the totals.
 *
 * <p>The base decision logic (final-answer vs. tool-call), message building, and the
 * final-iterations synthesis nudge are identical to {@link ReactStrategy}'s and are
 * reused verbatim via {@link ReactExecutionSupport} — this class adds only the
 * reflection triggers layered around that same loop. Reflection is suppressed once the
 * loop enters {@code forceFinal} (the last iteration or two): injecting a fresh
 * course-correction right when the model needs to wrap up would work against the
 * termination guarantee that withholding tools is there to provide.
 */
public final class ReflActStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReflActStrategy.class);

    private static final String REFLECTION_SYSTEM =
            "You are a self-correction module watching an active AI agent's reasoning trace. "
            + "Given a recent excerpt and why it seems stuck, produce ONE short, actionable "
            + "course-correction (1-3 sentences) for its very next step. Do not restate the trace, "
            + "do not apologise, do not repeat the excerpt back.";

    private final LlmRouter reflectionRouter;

    /**
     * Creates a {@code ReflActStrategy} whose reflection calls always use the same model
     * as the main loop — {@link StrategyConfig.ReflAct#reflectionProvider()} has no
     * effect with this constructor. Use {@link #ReflActStrategy(LlmRouter)} to honour it.
     */
    public ReflActStrategy() {
        this(null);
    }

    /**
     * Creates a {@code ReflActStrategy} capable of routing reflection calls to a
     * different LLM provider than the main loop — the "dual-brain" split (a fast/cheap
     * model executes, a stronger one critiques) the resources describe.
     *
     * @param reflectionRouter resolves {@link StrategyConfig.ReflAct#reflectionProvider()}
     *                         to an {@link LlmClient}; {@code null} disables routing
     */
    public ReflActStrategy(LlmRouter reflectionRouter) {
        this.reflectionRouter = reflectionRouter;
    }

    @Override
    public String strategyName() {
        return "reflact";
    }

    @Override
    public ExecutionResult execute(
            AgentTask task,
            LlmClient llm,
            MemoryManager memory,
            ToolRegistry tools,
            AgentConfig config
    ) {
        Objects.requireNonNull(task,   "task must not be null");
        Objects.requireNonNull(llm,    "llm must not be null");
        Objects.requireNonNull(memory, "memory must not be null");
        Objects.requireNonNull(tools,  "tools must not be null");
        Objects.requireNonNull(config, "config must not be null");

        StrategyConfig.ReflAct rc = (config.strategyConfig() instanceof StrategyConfig.ReflAct r)
                ? r : StrategyConfig.ReflAct.defaults();

        int     iterations          = 0;
        int     totalPromptTokens   = 0;
        int     totalOutputTokens   = 0;
        int     reflectionsUsed     = 0;
        int     unproductiveStreak  = 0;
        boolean synthesisModeActive = false;
        final boolean logIo         = config.logLlmIo();
        final int     logIoMaxChars = config.logLlmIoMaxChars();
        final boolean nativeTools   = llm.supportsNativeTools();
        List<ExecutionStep> steps = new ArrayList<>();
        Instant deadline = Instant.now().plus(config.executionTimeout());

        LlmCallContext ctx = LlmCallContext.of(config, task);
        List<AraTool> resolvedTools = tools.resolveEnabled(config.enabledTools());

        log.debug("ReflActStrategy starting for task [{}] maxIterations={} maxReflections={} tools={}",
                task.taskId(), config.maxIterations(), rc.maxReflections(),
                resolvedTools.stream().map(AraTool::toolId).toList());

        while (iterations < config.maxIterations()) {
            if (Thread.currentThread().isInterrupted()) {
                log.debug("Task [{}] cancelled at iteration {} (thread interrupted)", task.taskId(), iterations);
                return ExecutionResult.failure("Cancelled", iterations, totalPromptTokens, totalOutputTokens, steps);
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
            synthesisModeActive = ReactExecutionSupport.maybeInjectSynthesis(
                    memory, config, task, iterations, synthesisModeActive, resolvedTools, nativeTools);

            boolean forceFinal = iterations >= config.maxIterations() - 1;
            List<AraTool> activeTools = forceFinal ? List.of() : resolvedTools;
            List<LlmMessage> messages = ReactExecutionSupport.buildMessages(memory, activeTools, nativeTools);
            LlmCallContext stepCtx = ctx.withResolvedTools(activeTools);

            LlmCompletion completion;
            try {
                completion = ReactExecutionSupport.callLlm(llm, messages, stepCtx, task, deadline, config);
            } catch (ExecutionTimeoutException te) {
                throw te;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();   // restore the flag for our caller
                log.debug("Task [{}] cancelled during the LLM call at iteration {}", task.taskId(), iterations);
                return ExecutionResult.failure("Cancelled", iterations, totalPromptTokens, totalOutputTokens, steps);
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

            ReactExecutionSupport.recordAssistantOutput(memory, steps, completion, output, iterations, task.taskId());
            ReactExecutionSupport.logIterationResult(completion, iterations, config.maxIterations(), task.taskId());

            // forceFinal picks which sealed decision type governs this iteration — see
            // ReactStrategy for why DispatchTools not existing on the forced branch matters.
            // Reflection triggers are suppressed on that branch simply by living in the
            // other branch's switch: there is no DispatchTools/Continue-with-streak case
            // there to attach them to, so "no reflection on the forced-final iteration"
            // no longer needs its own runtime check.
            if (forceFinal) {
                ReactExecutionSupport.ForcedFinalDecision decision =
                        ReactExecutionSupport.decideForcedFinal(output, completion);
                switch (decision) {
                    case ReactExecutionSupport.ForcedFinalDecision.FinalAnswer(String answer) -> {
                        log.debug("Task [{}] reached FINAL_ANSWER in {} iteration(s)", task.taskId(), iterations);
                        steps.add(ExecutionStep.finalAnswer(answer, iterations));
                        return ExecutionResult.success(answer, iterations, totalPromptTokens, totalOutputTokens, steps);
                    }
                    case ReactExecutionSupport.ForcedFinalDecision.Continue ignored ->
                        log.debug("Forced-final iteration: skipping tool dispatch for task [{}]", task.taskId());
                }
            } else {
                ReactExecutionSupport.StepDecision decision = ReactExecutionSupport.decideNormal(
                        output, completion, task, resolvedTools.isEmpty());
                switch (decision) {
                    case ReactExecutionSupport.StepDecision.FinalAnswer(String answer) -> {
                        log.debug("Task [{}] reached FINAL_ANSWER in {} iteration(s)", task.taskId(), iterations);
                        steps.add(ExecutionStep.finalAnswer(answer, iterations));
                        return ExecutionResult.success(answer, iterations, totalPromptTokens, totalOutputTokens, steps);
                    }
                    case ReactExecutionSupport.StepDecision.DispatchTools(List<ToolCallParser.ToolCallRequest> calls) -> {
                        unproductiveStreak = 0;   // an action was taken — the "thinking in circles" trigger does not apply
                        ReactExecutionSupport.DispatchContext dispatchCtx = new ReactExecutionSupport.DispatchContext(
                                tools, memory, steps, task, iterations, deadline, logIo, logIoMaxChars);
                        boolean anyFailed;
                        if (calls.size() == 1) {
                            anyFailed = ReactExecutionSupport.dispatchSingle(calls.get(0), completion.toolCallId(), dispatchCtx);
                        } else {
                            log.debug("Parallel dispatch: {} tool calls for task [{}]", calls.size(), task.taskId());
                            anyFailed = ReactExecutionSupport.dispatchParallel(calls, dispatchCtx);
                        }
                        if (anyFailed && rc.reflectOnToolFailure() && reflectionsUsed < rc.maxReflections()) {
                            var usage = reflect(memory, steps, task, ctx, llm, rc.reflectionProvider(),
                                    "A tool call failed. Diagnose why and suggest what to try instead.", iterations);
                            totalPromptTokens += usage.promptTokens();
                            totalOutputTokens += usage.outputTokens();
                            reflectionsUsed++;
                            ExecutionResult reflectionBudgetExceeded = ReactExecutionSupport.chargeRunBudget(
                                    config, task, usage.promptTokens(), usage.outputTokens(),
                                    iterations, totalPromptTokens, totalOutputTokens, steps);
                            if (reflectionBudgetExceeded != null) {
                                return reflectionBudgetExceeded;
                            }
                        }
                    }
                    case ReactExecutionSupport.StepDecision.Continue ignored -> {
                        unproductiveStreak++;
                        if (unproductiveStreak >= rc.unproductiveStreak() && reflectionsUsed < rc.maxReflections()) {
                            var usage = reflect(memory, steps, task, ctx, llm, rc.reflectionProvider(),
                                    unproductiveStreak + " steps in a row produced neither a tool call nor a final "
                                            + "answer. Diagnose why the approach is stalled and suggest a concrete next action.",
                                    iterations);
                            totalPromptTokens += usage.promptTokens();
                            totalOutputTokens += usage.outputTokens();
                            reflectionsUsed++;
                            unproductiveStreak = 0;
                            ExecutionResult reflectionBudgetExceeded = ReactExecutionSupport.chargeRunBudget(
                                    config, task, usage.promptTokens(), usage.outputTokens(),
                                    iterations, totalPromptTokens, totalOutputTokens, steps);
                            if (reflectionBudgetExceeded != null) {
                                return reflectionBudgetExceeded;
                            }
                        }
                    }
                }
            }
        }

        log.warn("Task [{}] hit maxIterations={} without a final answer ({} reflection(s) used)",
                task.taskId(), config.maxIterations(), reflectionsUsed);
        return ExecutionResult.failure(
                "Max iterations (%d) reached without a final answer".formatted(config.maxIterations()),
                iterations, totalPromptTokens, totalOutputTokens, steps);
    }

    // ── Reflection ─────────────────────────────────────────────────────────────

    private record ReflectionUsage(int promptTokens, int outputTokens) {}

    /**
     * Generates a short course-correction, appends it to working memory as a {@code
     * "user"} turn (no reset — the next Think call simply sees one more message in
     * context), and records it as a {@link StepType#REFLECTION} step.
     * Failures degrade to a generic nudge rather than propagating — a broken reflection
     * call must not abort a task that could otherwise still succeed.
     */
    private ReflectionUsage reflect(
            MemoryManager memory, List<ExecutionStep> steps, AgentTask task, LlmCallContext ctx,
            LlmClient mainLlm, String reflectionProvider, String trigger, int iteration) {

        String scratchpad = recentScratchpad(steps);
        String prompt = "Recent trace:\n" + scratchpad + "\n\nWhy it looks stuck: " + trigger;
        List<LlmMessage> messages = List.of(
                new LlmMessage("system", REFLECTION_SYSTEM),
                new LlmMessage("user", prompt));

        LlmClient reflectionLlm = resolveReflectionLlm(mainLlm, ctx, reflectionProvider, task.taskId());

        String critique;
        int promptTokens = 0;
        int outputTokens = 0;
        try {
            LlmCompletion completion = reflectionLlm.complete(messages, ctx);
            promptTokens = completion.promptTokens();
            outputTokens = completion.outputTokens();
            String text = completion.text();
            critique = (text != null && !text.isBlank()) ? text.strip() : fallbackCritique();
        } catch (Exception e) {
            log.warn("ReflAct: reflection call failed for task [{}]: {}", task.taskId(), e.getMessage());
            critique = fallbackCritique();
        }

        log.debug("ReflAct [iteration={}, task={}]: {}", iteration, task.taskId(), critique);
        memory.appendToWorkingMemory("user", "SELF-REFLECTION: " + critique);
        steps.add(ExecutionStep.reflection(critique, iteration));
        return new ReflectionUsage(promptTokens, outputTokens);
    }

    /**
     * Resolves the {@link LlmClient} for the reflection call: {@code reflectionProvider}
     * routed through {@link #reflectionRouter} when both are available, otherwise {@code
     * fallback} (the main loop's own model) — same fallback chain as {@code
     * ReflexionStrategy.resolveReflectionLlm}.
     */
    private LlmClient resolveReflectionLlm(LlmClient fallback, LlmCallContext ctx,
                                            String reflectionProvider, String taskId) {
        if (reflectionRouter == null || reflectionProvider == null || reflectionProvider.isBlank()) {
            return fallback;
        }
        try {
            return reflectionRouter.select(LlmConfig.of(reflectionProvider), ctx);
        } catch (Exception e) {
            log.warn("ReflAct: failed to resolve reflectionProvider '{}' for task [{}] — "
                    + "falling back to the main loop's model: {}", reflectionProvider, taskId, e.getMessage());
            return fallback;
        }
    }

    private static String fallbackCritique() {
        return "The current approach does not appear to be making progress. Try a different angle.";
    }

    /**
     * Renders the last few steps as a compact excerpt for the reflection prompt — full
     * working memory would grow the reflection call's cost with every iteration; a fixed
     * tail keeps it bounded regardless of how long the episode has run so far.
     */
    private static final int SCRATCHPAD_TAIL_STEPS = 8;

    private static String recentScratchpad(List<ExecutionStep> steps) {
        int from = Math.max(0, steps.size() - SCRATCHPAD_TAIL_STEPS);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < steps.size(); i++) {
            ExecutionStep s = steps.get(i);
            sb.append("- [").append(s.type().wireValue()).append("] ");
            if (s.type() == StepType.TOOL_CALL) {
                sb.append(s.toolId()).append(' ').append(s.arguments());
            } else {
                sb.append(s.content());
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
