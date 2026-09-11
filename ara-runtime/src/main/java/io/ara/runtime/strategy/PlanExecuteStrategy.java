package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.telemetry.TelemetryToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 */
public final class PlanExecuteStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(PlanExecuteStrategy.class);

    private static final int MAX_REPLAN_ATTEMPTS = 2;
    private static final int STEP_RESULT_TRUNCATE_CHARS = 600;

    private static final Pattern STEP_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:\\d+[\\.)]|[-*])\\s+(.+?)\\s*$");

    private static final String PLAN_SUFFIX = """

    Produce a short numbered execution plan.
    Output only a numbered list of concrete steps — no explanations, no prose.
    Do not solve the task yet, only plan.
    """;

    private static final String EXEC_SUFFIX = """

    You are executing one step of a plan.
    - If a tool is needed, output only JSON: {"tool_id":"<id>","arguments":{...}}
    - When the step is complete, write STEP_DONE on the last line.
    - Be concise.
    """;

    /**
     * Used instead of {@link #EXEC_SUFFIX} when the client speaks native provider
     * function-calling ({@code LlmClient.supportsNativeTools()}) — omits the inline
     * JSON tool-call instruction, which would otherwise compete with the structured
     * tool channel the client already receives via {@code LlmCallContext.resolvedTools()}.
     * The step-completion instruction is unrelated to tool-calling and is kept as-is.
     */
    private static final String EXEC_SUFFIX_NATIVE = """

    You are executing one step of a plan.
    - When the step is complete, write STEP_DONE on the last line.
    - Be concise.
    """;

    private static final String SYNTHESIS_SUFFIX = """

    Produce the complete final answer based on the execution results provided.
    Be thorough and self-contained.
    """;

    private static final String REPLAN_SUFFIX = """

    Produce a revised numbered list of steps for the remaining work.
    Output only the numbered list — no explanations.
    """;

    /**
     * Immutable collaborators shared by every phase of one {@link #execute} pass.
     * Replaces the positional parameter lists the phase helpers used to take —
     * {@code executeStep} alone received 17 positional arguments, five of them
     * {@code int}s/arrays, which is the textbook setup for an argument-order bug the
     * compiler cannot catch. Same pattern as {@code ReactStrategy.DispatchContext}.
     */
    private record Run(
            AgentTask task,
            LlmClient llm,
            LlmCallContext ctx,
            ToolRegistry tools,
            List<AraTool> resolvedTools,
            AgentConfig config,
            String systemPrompt,
            String plannerCatalog,
            String stepCatalog,
            Instant deadline,
            int maxIterations,
            int maxStepRounds,
            boolean nativeTools) {
    }

    /**
     * Mutable per-run accumulators: iteration/token tallies plus the execution trace.
     * Replaces the previous {@code int[] iters = {0}} single-element-array idiom — a
     * named object mutated in place says what it is; a one-slot array only says how it
     * was smuggled past Java's by-value parameters.
     */
    private static final class Tally {
        int iterations;
        int promptTokens;
        int outputTokens;
        final List<ExecutionStep> steps = new ArrayList<>();

        void addUsage(LlmCompletion completion) {
            promptTokens += completion.promptTokens();
            outputTokens += completion.outputTokens();
        }
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
        int maxPlanSteps      = pe.maxPlanSteps();
        String replanStrategy = pe.replanPolicy();

        // See ReactStrategy for the rationale: native function-calling clients get
        // structured tool specs attached to the step-execution call context instead of
        // the text catalog/instructions (planning and synthesis never invoke tools
        // either way, so they are left untouched).
        //
        // The catalogs are precomputed once for the whole pass and carried on Run, like
        // systemPrompt: resolvedTools is stable, yet rebuildStepMessages ran
        // ToolCatalogFormatter.format per step round, re-serialising every tool schema.
        List<AraTool> resolvedTools = tools.resolveEnabled(
                config.enabledTools() != null ? config.enabledTools() : List.of());
        boolean nativeTools = llm.supportsNativeTools();
        String plannerCatalog = ToolCatalogFormatter.format(resolvedTools);
        String stepCatalog = nativeTools ? "" : plannerCatalog;

        Run run = new Run(
                task, llm, LlmCallContext.of(config, task), tools,
                resolvedTools,
                config, extractSystemPrompt(memory),
                plannerCatalog, stepCatalog,
                Instant.now().plus(config.executionTimeout()),
                config.maxIterations(), pe.maxStepRoundsPerStep(),
                nativeTools);
        Tally tally = new Tally();

        // ── Phase 1: Planning ──────────────────────────────────────────────────
        if (cancelled()) {
            return fail("Cancelled", tally);
        }
        if (tally.iterations >= run.maxIterations()) {
            return fail("Max iterations reached before planning", tally);
        }
        tally.iterations++;

        LlmCompletion planCompletion;
        try {
            planCompletion = ReactExecutionSupport.completeWithRetry(
                    run.llm(), buildPlanningMessages(run, maxPlanSteps), run.ctx(),
                    run.deadline(), config, run.task().taskId());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return fail("Cancelled", tally);
        } catch (Exception e) {
            return fail("Planning failed: " + ReactExecutionSupport.describeLlmFailure(e), tally);
        }
        checkTimeout(run.deadline(), config);
        tally.addUsage(planCompletion);

        List<String> plan = new ArrayList<>(parsePlanSteps(planCompletion.text()));
        if (plan.isEmpty()) {
            plan.add("Solve the task directly and provide a complete answer.");
        }
        if (plan.size() > maxPlanSteps) {
            log.debug("Plan truncated from {} to {} steps (maxPlanSteps={})",
                    plan.size(), maxPlanSteps, maxPlanSteps);
            plan = new ArrayList<>(plan.subList(0, maxPlanSteps));
        }
        tally.steps.add(ExecutionStep.thought(
                planCompletion.text() != null ? planCompletion.text() : "", tally.iterations));
        log.debug("Plan ({} steps) for task [{}]: {}", plan.size(), task.taskId(), plan);

        // ── Phase 2: Execution ─────────────────────────────────────────────────
        // stepResults: compact key-value store — not appended to working memory.
        // Each step call receives only: system + task + plan summary + previous results summary.
        Map<Integer, String> stepResults = new LinkedHashMap<>();
        int replanAttempts = 0;
        int stepIdx = 0;

        while (stepIdx < plan.size()) {
            if (cancelled()) {
                return fail("Cancelled", tally);
            }
            checkTimeout(run.deadline(), config);
            if (tally.iterations >= run.maxIterations()) {
                return fail("Max iterations reached while executing step " + (stepIdx + 1), tally);
            }

            String result = executeStep(stepIdx, plan, stepResults, run, tally);

            if (cancelled()) {
                return fail("Cancelled", tally);
            }

            if (result != null && !result.isBlank()) {
                stepResults.put(stepIdx, result);
                log.debug("Step {}/{} completed ({} chars)", stepIdx + 1, plan.size(), result.length());
                stepIdx++;
            } else {
                // Step produced no usable result
                String failureDesc = "Step " + (stepIdx + 1) + " of " + plan.size()
                        + " [" + plan.get(stepIdx) + "] produced no result";
                log.warn(failureDesc);

                if ("on_failure".equalsIgnoreCase(replanStrategy)
                        && replanAttempts < MAX_REPLAN_ATTEMPTS
                        && tally.iterations < run.maxIterations()) {
                    replanAttempts++;
                    log.debug("Replanning after step {} failure (attempt {}/{})",
                            stepIdx + 1, replanAttempts, MAX_REPLAN_ATTEMPTS);

                    List<String> revisedSteps = replan(plan, stepResults, stepIdx, failureDesc, run, tally);

                    if (!revisedSteps.isEmpty()) {
                        List<String> newPlan = new ArrayList<>(plan.subList(0, stepIdx));
                        newPlan.addAll(revisedSteps);
                        plan = newPlan;
                        log.debug("Revised plan ({} steps from step {}): {}",
                                revisedSteps.size(), stepIdx + 1, revisedSteps);
                        // Retry current step index with the new plan
                        continue;
                    }
                }
                return fail(failureDesc, tally);
            }
        }

        // ── Phase 3: Synthesis ─────────────────────────────────────────────────
        if (cancelled()) {
            return fail("Cancelled", tally);
        }
        checkTimeout(run.deadline(), config);
        if (tally.iterations >= run.maxIterations()) {
            return fail("Max iterations reached before synthesis", tally);
        }
        tally.iterations++;

        LlmCompletion finalCompletion;
        try {
            finalCompletion = ReactExecutionSupport.completeWithRetry(
                    run.llm(), buildSynthesisMessages(run, plan, stepResults), run.ctx(),
                    run.deadline(), config, run.task().taskId());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return fail("Cancelled", tally);
        } catch (Exception e) {
            return fail("Synthesis failed: " + ReactExecutionSupport.describeLlmFailure(e), tally);
        }
        checkTimeout(run.deadline(), config);
        tally.addUsage(finalCompletion);

        String finalAnswer = finalCompletion.text() != null ? finalCompletion.text().strip() : "";
        if (finalAnswer.isBlank()) {
            // Synthesis returned nothing (context overflow or empty response) —
            // fall back to concatenating step results directly
            log.warn("Synthesis returned empty response, falling back to step results");
            finalAnswer = buildFallbackAnswer(plan, stepResults);
        }
        tally.steps.add(ExecutionStep.finalAnswer(finalAnswer, tally.iterations));
        return ExecutionResult.success(finalAnswer, tally.iterations,
                tally.promptTokens, tally.outputTokens, tally.steps);
    }

    private static ExecutionResult fail(String reason, Tally tally) {
        return ExecutionResult.failure(reason, tally.iterations,
                tally.promptTokens, tally.outputTokens, tally.steps);
    }

    // ── Step execution ─────────────────────────────────────────────────────────

    /**
     * Executes a single plan step in an isolated message context.
     * Returns the step result text, or {@code null} if no usable output was produced.
     */
    private String executeStep(
            int stepIdx, List<String> plan, Map<Integer, String> stepResults, Run run, Tally tally) {

        // Local history for tool call / observation exchanges within this step only.
        // Not carried forward to the next step.
        List<LlmMessage> stepLocalHistory = new ArrayList<>();
        boolean stepDone = false;
        int stepRounds = 0;
        String lastResult = null;

        // Native clients get resolvedTools attached only for step-execution calls — not
        // for planning/synthesis/replan, which never invoke tools by design. Built once
        // per step rather than per round since resolvedTools is stable for the step.
        LlmCallContext stepCtx = run.nativeTools()
                ? run.ctx().withResolvedTools(run.resolvedTools()) : run.ctx();

        while (!stepDone) {
            if (cancelled()) break;   // outer loop returns "Cancelled" on the next boundary check
            checkTimeout(run.deadline(), run.config());
            if (tally.iterations >= run.maxIterations()) break;

            tally.iterations++;
            stepRounds++;

            List<LlmMessage> messages = buildStepMessages(run, plan, stepResults, stepIdx, stepLocalHistory);

            LlmCompletion completion;
            try {
                completion = ReactExecutionSupport.completeWithRetry(
                        run.llm(), messages, stepCtx, run.deadline(), run.config(), run.task().taskId());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.debug("Cancelled during the LLM call on step {}/{}", stepIdx + 1, plan.size());
                return lastResult;
            } catch (Exception e) {
                log.warn("LLM call failed on step {}/{}: {}", stepIdx + 1, plan.size(),
                        ReactExecutionSupport.describeLlmFailure(e));
                return lastResult;
            }
            checkTimeout(run.deadline(), run.config());
            tally.addUsage(completion);
            String text = completion.text() != null ? completion.text() : "";

            // extract() already falls back to inline text parsing when the completion
            // carries no native tool call — no second extractInline() pass needed.
            Optional<ToolCallParser.ToolCallRequest> toolCall = ToolCallParser.extract(completion);

            if (toolCall.isPresent()) {
                dispatchTool(toolCall.get(), text, stepLocalHistory, run, tally);
                continue;
            }

            // No tool call — capture the text as a result candidate
            if (!text.isBlank()) {
                lastResult = text;
                tally.steps.add(ExecutionStep.thought(text, tally.iterations));
            }

            if (text.contains("STEP_DONE")
                    || "stop".equalsIgnoreCase(completion.finishReason())
                    || stepRounds >= run.maxStepRounds()) {
                stepDone = true;
            } else {
                stepLocalHistory.add(new LlmMessage("assistant", text));
            }
        }
        return lastResult;
    }

    /**
     * Dispatches one tool call within a step round: SSE notification, trace steps,
     * telemetry call-id attachment, execution, and the observation exchange appended to
     * {@code stepLocalHistory} — parity with {@code ReactStrategy.dispatchSingle}, which
     * this strategy previously skipped entirely: no {@code tool_call} SSE event ever
     * fired and {@code AgentResponse.steps()} was always empty for {@code plan_execute},
     * despite {@link ExecutionStep}'s contract that traces reach the caller.
     */
    private void dispatchTool(
            ToolCallParser.ToolCallRequest tcr, String completionText,
            List<LlmMessage> stepLocalHistory, Run run, Tally tally) {

        run.task().notifyToolCall(tcr.toolId());
        tally.steps.add(ExecutionStep.toolCall(tcr.toolId(), tcr.argumentJson(), tally.iterations));

        String callId = tcr.toolCallId();
        AgentTask dispatchTask = (callId != null && !callId.isBlank())
                ? run.task().withAttachment(TelemetryToolRegistry.TOOL_CALL_ID_ATTACHMENT_KEY, callId)
                : run.task();
        ToolResult result = run.tools().execute(tcr.toolId(), tcr.argumentJson(), dispatchTask);

        String observation = result.success()
                ? result.output()
                : "Tool [%s] failed — %s".formatted(tcr.toolId(), result.error());
        tally.steps.add(ExecutionStep.observation(observation, tally.iterations));

        if (callId != null && !callId.isBlank()) {
            // Native reconstruction — mirrors ReactStrategy's dispatch: pairs with
            // ToolConversionUtils.toNativeAwareChatMessage in the adapters, so the next
            // round's request carries a proper AiMessage(toolExecutionRequests) +
            // ToolExecutionResultMessage instead of collapsing the exchange into plain
            // text turns that a native provider never asked for.
            stepLocalHistory.add(LlmMessage.assistantToolCall(callId, tcr.toolId(), tcr.argumentJson()));
            stepLocalHistory.add(LlmMessage.tool(callId, tcr.toolId(), observation));
        } else {
            stepLocalHistory.add(new LlmMessage("assistant", completionText));
            stepLocalHistory.add(new LlmMessage("user", "Observation: " + observation));
        }
    }

    // ── Re-planning ────────────────────────────────────────────────────────────

    /**
     * Generates a revised plan for steps starting at {@code failedStepIdx}.
     * Returns an empty list if replanning fails or produces no steps.
     */
    private List<String> replan(
            List<String> originalPlan, Map<Integer, String> stepResults,
            int failedStepIdx, String failureReason, Run run, Tally tally) {

        tally.iterations++;

        StringBuilder sb = new StringBuilder();
        sb.append("Original task: ").append(run.task().input()).append("\n\n");
        sb.append("Plan execution status:\n");
        for (int i = 0; i < originalPlan.size(); i++) {
            if (i < failedStepIdx) {
                sb.append("  %d. ✓ %s%n".formatted(i + 1, originalPlan.get(i)));
            } else if (i == failedStepIdx) {
                sb.append("  %d. ✗ %s — %s%n".formatted(i + 1, originalPlan.get(i), failureReason));
            } else {
                sb.append("  %d. (pending) %s%n".formatted(i + 1, originalPlan.get(i)));
            }
        }
        if (!stepResults.isEmpty()) {
            sb.append("\nCompleted step results:\n");
            stepResults.forEach((idx, result) -> {
                String truncated = result.length() > 300 ? result.substring(0, 300) + "…" : result;
                sb.append("  Step %d: %s%n".formatted(idx + 1, truncated));
            });
        }
        sb.append("\nGenerate a revised plan for the remaining work starting from step ")
           .append(failedStepIdx + 1).append(".");

        List<LlmMessage> messages = List.of(
                new LlmMessage("system", run.systemPrompt() + REPLAN_SUFFIX),
                new LlmMessage("user", sb.toString())
        );

        try {
            LlmCompletion completion = ReactExecutionSupport.completeWithRetry(
                    run.llm(), messages, run.ctx(), run.deadline(), run.config(), run.task().taskId());
            checkTimeout(run.deadline(), run.config());
            tally.addUsage(completion);
            List<String> revised = parsePlanSteps(completion.text());
            log.debug("Replan produced {} step(s)", revised.size());
            return revised;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.debug("Cancelled during replanning");
            return List.of();
        } catch (Exception e) {
            log.warn("Replanning failed: {}", ReactExecutionSupport.describeLlmFailure(e));
            return List.of();
        }
    }

    // ── Message builders ───────────────────────────────────────────────────────

    /**
     * Planning phase: compact prompt asking for a numbered list of steps.
     */
    private List<LlmMessage> buildPlanningMessages(Run run, int maxPlanSteps) {
        return List.of(
                new LlmMessage("system",
                        run.systemPrompt() + run.plannerCatalog() + PLAN_SUFFIX),
                // The planner has to see the attachments too: "summarise this PDF" cannot be
                // broken into steps by a model shown only the words around the document.
                LlmMessage.user(
                        run.task().input() + "\n\n(Produce at most " + maxPlanSteps + " steps.)",
                        run.task().media())
        );
    }

    /**
     * Per-step execution: isolated context containing system prompt, task, compact plan
     * status, compact previous results, and the tool exchange history for this step only.
     *
     * <p>Token usage is O(maxPlanSteps × STEP_RESULT_TRUNCATE_CHARS + stepLocalHistory)
     * regardless of total iterations.
     *
     * <p>When {@code run.nativeTools()} is {@code true} the text tool catalog and the
     * inline JSON tool-call instruction are both omitted — see {@link #EXEC_SUFFIX_NATIVE}.
     */
    private List<LlmMessage> buildStepMessages(
            Run run, List<String> plan, Map<Integer, String> stepResults,
            int currentStepIdx, List<LlmMessage> stepLocalHistory) {

        List<LlmMessage> messages = new ArrayList<>();
        String toolCatalog = run.stepCatalog();
        String execSuffix  = run.nativeTools() ? EXEC_SUFFIX_NATIVE : EXEC_SUFFIX;
        messages.add(new LlmMessage("system", run.systemPrompt() + toolCatalog + execSuffix));
        // Unlike the ReAct family, this strategy does not rebuild the conversation from
        // working memory — it re-serialises the task into a fresh per-step prompt. So the
        // task's attachments have to be re-attached here, or a plan-execute agent would be
        // the one strategy that silently loses them.
        messages.add(LlmMessage.user("Task: " + run.task().input(), run.task().media()));

        // Compact plan overview with execution status markers
        StringBuilder planCtx = new StringBuilder("Execution plan:\n");
        for (int i = 0; i < plan.size(); i++) {
            String marker = i < currentStepIdx ? "✓" : (i == currentStepIdx ? "→" : " ");
            planCtx.append("  %d. [%s] %s%n".formatted(i + 1, marker, plan.get(i)));
        }
        messages.add(new LlmMessage("user", planCtx.toString().stripTrailing()));

        // Compact summaries of completed steps — not full verbatim output
        if (!stepResults.isEmpty()) {
            StringBuilder prev = new StringBuilder("Completed step results:\n");
            stepResults.forEach((idx, result) -> {
                String truncated = result.length() > STEP_RESULT_TRUNCATE_CHARS
                        ? result.substring(0, STEP_RESULT_TRUNCATE_CHARS) + "…"
                        : result;
                prev.append("  Step %d: %s%n".formatted(idx + 1, truncated));
            });
            messages.add(new LlmMessage("user", prev.toString().stripTrailing()));
        }

        // Current step instruction
        messages.add(new LlmMessage("user",
                "Execute step %d/%d: %s".formatted(
                        currentStepIdx + 1, plan.size(), plan.get(currentStepIdx))));

        // Tool call / observation exchanges for this step only
        messages.addAll(stepLocalHistory);
        return messages;
    }

    /**
     * Synthesis phase: compact prompt with task + plan + all step results.
     * Token usage is predictable regardless of how many tool rounds each step used.
     */
    private List<LlmMessage> buildSynthesisMessages(
            Run run, List<String> plan, Map<Integer, String> stepResults) {

        StringBuilder ctx = new StringBuilder();
        ctx.append("Task: ").append(run.task().input()).append("\n\n");
        ctx.append("Execution results:\n");
        for (int i = 0; i < plan.size(); i++) {
            String result = stepResults.getOrDefault(i, "(not executed)");
            ctx.append("Step %d — %s:%n%s%n%n".formatted(i + 1, plan.get(i), result));
        }
        ctx.append("Based on the above, produce the complete final answer.");

        return List.of(
                new LlmMessage("system", run.systemPrompt() + SYNTHESIS_SUFFIX),
                new LlmMessage("user", ctx.toString())
        );
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private String extractSystemPrompt(MemoryManager memory) {
        var entries = memory.workingMemory();
        if (!entries.isEmpty() && "system".equals(entries.get(0).role())) {
            return entries.get(0).content();
        }
        return "";
    }

    private static void checkTimeout(Instant deadline, AgentConfig config) {
        if (Instant.now().isAfter(deadline)) {
            throw new ExecutionTimeoutException(config.executionTimeout());
        }
    }

    /**
     * Cooperative cancellation: {@code AgentInstance.terminate(session)} interrupts the
     * executing thread. Returns {@code true} when the current task should stop.
     */
    private static boolean cancelled() {
        return Thread.currentThread().isInterrupted();
    }

    private String buildFallbackAnswer(List<String> plan, Map<Integer, String> stepResults) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plan.size(); i++) {
            String result = stepResults.get(i);
            if (result != null && !result.isBlank()) {
                sb.append(result).append("\n\n");
            }
        }
        return sb.toString().strip();
    }

    private List<String> parsePlanSteps(String planText) {
        if (planText == null || planText.isBlank()) {
            return List.of();
        }
        List<String> steps = new ArrayList<>();
        Matcher m = STEP_PATTERN.matcher(planText);
        while (m.find()) {
            String step = m.group(1).strip();
            if (!step.isBlank()) {
                steps.add(step);
            }
        }
        return steps;
    }

}
