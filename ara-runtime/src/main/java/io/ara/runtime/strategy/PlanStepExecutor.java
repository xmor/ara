package io.ara.runtime.strategy;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.telemetry.TelemetryToolRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 2 of {@link PlanExecuteStrategy}: running one step of the plan in a context of its own,
 * and reading how the model closed it.
 *
 * <p>Split out of the strategy so that the part that talks to tools and to the model round after
 * round — the longest and most intricate of the three phases — can be read without the
 * orchestration around it.
 *
 * <p>Stateless: every method is static and takes everything it needs. Thread-safe.
 */
final class PlanStepExecutor {

    private static final Logger log = LoggerFactory.getLogger(PlanStepExecutor.class);

    /** Why a step that ended with nothing usable is reported, unless something more specific is known. */
    private static final String NO_RESULT = "produced no result";

    /** Stored as the result of a step closed with close_step that wrote no text of its own. */
    private static final String NO_WRITTEN_RESULT = "(completed, no written result)";

    private static final int STEP_RESULT_TRUNCATE_CHARS = 600;

    private PlanStepExecutor() {
    }

    // "STEP_DONE" is no longer advertised, but a reply that ends a step with it (or with a plain
    // final answer) is still accepted as a step that finished without notes — see executeStep.
    private static final String EXEC_SUFFIX = """

    You are executing one step of a plan.
    - If a tool is needed, output only JSON: {"tool_id":"<id>","arguments":{...}}
    - When the step is finished, call the close_step tool:
      {"tool_id":"close_step","arguments":{"status":"done","notes":["..."]}}
      status is "done"; "failed" if the step could not be done; or "revise" if it is done but the
      remaining steps need changing. For failed and revise add "reason".
      notes: up to 5 short facts the later steps need and cannot guess — file paths, names you
      chose, decisions — each under 300 characters.
    - Be concise.
    """;

    /**
     * Used instead of {@link #EXEC_SUFFIX} when the client speaks native provider
     * function-calling ({@code LlmClient.supportsNativeTools()}) — omits the inline
     * JSON tool-call instruction, which would otherwise compete with the structured
     * tool channel the client already receives via {@code LlmCallContext.resolvedTools()}
     * (which includes {@code close_step}, so its arguments are described by its own schema).
     */
    private static final String EXEC_SUFFIX_NATIVE = """

    You are executing one step of a plan.
    - When the step is finished, call the close_step tool: status "done"; "failed" if the step
      could not be done; or "revise" if it is done but the remaining steps need changing (add a
      reason for the last two). Put in notes up to 5 short facts the later steps need and cannot
      guess — file paths, names you chose, decisions.
    - Be concise.
    """;

    // ── Step execution ─────────────────────────────────────────────────────────

    /**
     * Executes a single plan step in an isolated message context and reports how it ended:
     * {@link StepOutcome.Done} with the result text, or {@link StepOutcome.Failed} when no
     * usable output was produced.
     */
    static StepOutcome executeStep(
            int stepIdx, List<String> plan, Map<Integer, String> stepResults, PlanRun run, PlanTally tally) {

        // Local history for tool call / observation exchanges within this step only.
        // Not carried forward to the next step.
        List<LlmMessage> stepLocalHistory = new ArrayList<>();
        boolean stepDone = false;
        int stepRounds = 0;
        String lastResult = null;
        String endedBecause = NO_RESULT;

        // Native clients get resolvedTools attached only for step-execution calls — not
        // for planning/synthesis/replan, which never invoke tools by design. Built once
        // per step rather than per round since resolvedTools is stable for the step.
        LlmCallContext stepCtx = run.nativeTools()
                ? run.ctx().withResolvedTools(run.stepTools()) : run.ctx();

        // The step's instruction prefix — system prompt, task, plan overview, completed-step
        // summaries, current step instruction — is invariant for every round of this step
        // (plan, step index and stepResults are fixed here), so it is built once and each
        // round appends only the tool exchanges this step has accumulated. Rebuilding it
        // per round re-serialised the plan overview and the step summaries for a string
        // that never changed.
        List<LlmMessage> stepPrefix = buildStepPrefix(run, plan, stepResults, tally.notes, stepIdx);

        while (!stepDone) {
            if (PlanRun.cancelled()) break;   // outer loop returns "Cancelled" on the next boundary check
            run.checkTimeout();
            if (tally.iterations >= run.maxIterations()) break;
            // The per-step round limit is checked here, before the round starts, and not only in
            // the "no tool call" branch below: a step whose every round calls a tool never reaches
            // that branch, so it used to run until the whole task's maxIterations was spent,
            // starving every later step. Stopping here means the step ends with whatever text it
            // produced so far (null when it only called tools, which the caller treats as a failed
            // step — the same outcome as any other step that yields nothing usable).
            if (stepRounds >= run.maxStepRounds()) {
                endedBecause = "reached the limit of " + run.maxStepRounds()
                        + " rounds per step (maxStepRoundsPerStep) without finishing";
                break;
            }

            tally.iterations++;
            stepRounds++;

            List<LlmMessage> messages = new ArrayList<>(stepPrefix);
            messages.addAll(stepLocalHistory);

            LlmCompletion completion;
            try {
                completion = ReactExecutionSupport.completeWithRetry(
                        run.llm(), messages, stepCtx, run.deadline(), run.config(), run.task().taskId());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.debug("Cancelled during the LLM call on step {}/{}", stepIdx + 1, plan.size());
                return StepOutcome.of(lastResult, endedBecause);
            } catch (Exception e) {
                log.warn("LLM call failed on step {}/{}: {}", stepIdx + 1, plan.size(),
                        ReactExecutionSupport.describeLlmFailure(e));
                return StepOutcome.of(lastResult, endedBecause);
            }
            run.checkTimeout();
            tally.addUsage(completion);
            ExecutionResult budgetExceeded = ReactExecutionSupport.checkBudget(
                    run.config(), run.task().taskId(),
                    tally.promptTokens, tally.outputTokens, tally.iterations, tally.steps);
            if (budgetExceeded == null) {
                budgetExceeded = ReactExecutionSupport.chargeRunBudget(
                        run.config(), run.task(), completion.promptTokens(), completion.outputTokens(),
                        tally.iterations, tally.promptTokens, tally.outputTokens, tally.steps);
            }
            if (budgetExceeded != null) {
                tally.budgetFailure = budgetExceeded;   // caller returns it
                return StepOutcome.of(lastResult, endedBecause);
            }
            String text = completion.text() != null ? completion.text() : "";

            // A turn that includes close_step is about closing the step, not about running a
            // tool: it is handled apart so that every other turn keeps the exact path it had.
            List<ToolCallParser.ToolCallRequest> calls = ToolCallParser.extractAll(completion);
            if (calls.stream().anyMatch(CloseStep::isCall)) {
                StepOutcome closed;
                try {
                    closed = closeStep(calls, text, lastResult, stepLocalHistory, run, tally);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.debug("Cancelled while closing step {}/{}", stepIdx + 1, plan.size());
                    return StepOutcome.of(lastResult, endedBecause);
                }
                if (closed != null) {
                    return closed;
                }
                continue;   // refused: the correction is in the history and the step stays open
            }

            // extract() already falls back to inline text parsing when the completion
            // carries no native tool call — no second extractInline() pass needed.
            Optional<ToolCallParser.ToolCallRequest> toolCall = ToolCallParser.extract(completion);

            if (toolCall.isPresent()) {
                try {
                    dispatchTool(toolCall.get(), text, stepLocalHistory, run, tally);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.debug("Cancelled during tool dispatch on step {}/{}", stepIdx + 1, plan.size());
                    return StepOutcome.of(lastResult, endedBecause);
                }
                continue;
            }

            // No tool call — capture the text as a result candidate
            if (ReactExecutionSupport.shouldRecordReasoning(run.config(), completion)) {
                tally.record(ExecutionStep.reasoning(completion.reasoning(), tally.iterations));
            }
            if (!text.isBlank()) {
                lastResult = text;
                tally.record(ExecutionStep.thought(text, tally.iterations));
            }

            if (text.contains("STEP_DONE")
                    || "stop".equalsIgnoreCase(completion.finishReason())
                    || stepRounds >= run.maxStepRounds()) {
                stepDone = true;
            } else {
                stepLocalHistory.add(new LlmMessage("assistant", text));
            }
        }
        return StepOutcome.of(lastResult, endedBecause);
    }

    /**
     * Handles a turn in which the model called {@code close_step}. Whatever else it asked for in
     * the same turn runs first, in the order given, so that a closing call listed ahead of its
     * siblings cannot make their effects vanish; then the step is closed.
     *
     * @return how the step ended, or {@code null} when the call was refused (bad status, a
     *         missing reason, too many or too long notes) — the correction is then in the history
     *         and the step stays open for another round
     */
    private static StepOutcome closeStep(
            List<ToolCallParser.ToolCallRequest> calls, String text, String lastResult,
            List<LlmMessage> stepLocalHistory, PlanRun run, PlanTally tally) throws InterruptedException {

        ToolCallParser.ToolCallRequest closing = null;
        for (ToolCallParser.ToolCallRequest call : calls) {
            if (!CloseStep.isCall(call)) {
                dispatchTool(call, text, stepLocalHistory, run, tally);
            } else if (closing == null) {
                closing = call;
            }
        }
        if (!text.isBlank()) {
            tally.record(ExecutionStep.thought(text, tally.iterations));
        }
        run.task().notifyToolCall(closing.toolId(), closing.argumentJson());
        tally.record(ExecutionStep.toolCall(closing.toolId(), closing.argumentJson(), tally.iterations));

        CloseStep.Parsed parsed = CloseStep.parse(closing.argumentJson());
        String observation = switch (parsed) {
            case CloseStep.Parsed.Refused refused -> refused.correction();
            case CloseStep.Parsed.Accepted accepted -> "Step closed (" + accepted.closing().status() + ").";
        };
        tally.record(ExecutionStep.observation(observation, tally.iterations));
        appendExchange(stepLocalHistory, closing, text, observation);

        return switch (parsed) {
            case CloseStep.Parsed.Refused refused -> null;
            case CloseStep.Parsed.Accepted accepted ->
                    outcomeOf(accepted.closing(), !text.isBlank() ? text : lastResult, tally);
        };
    }

    /**
     * Turns an accepted closing into the step's outcome and files its notes for the steps after
     * it. The result is, in order: what the model put in {@code result}, the text it wrote, or a
     * placeholder — a step that acted through tools and said nothing is still a finished step.
     */
    private static StepOutcome outcomeOf(CloseStep.Closing closing, String writtenText, PlanTally tally) {
        tally.notes.addAll(closing.notes());
        String result = closing.result() != null ? closing.result()
                : (writtenText != null && !writtenText.isBlank() ? writtenText : NO_WRITTEN_RESULT);
        return switch (closing.status()) {
            case DONE -> new StepOutcome.Done(result);
            case FAILED -> new StepOutcome.Failed("reported that it failed: " + closing.reason());
            case REVISE -> new StepOutcome.Revise(result, closing.reason());
        };
    }

    /**
     * Dispatches one tool call within a step round: SSE notification, trace steps,
     * telemetry call-id attachment, execution, and the observation exchange appended to
     * {@code stepLocalHistory} — parity with {@code ReactStrategy.dispatchSingle}, which
     * this strategy previously skipped entirely: no {@code tool_call} SSE event ever
     * fired and {@code AgentResponse.steps()} was always empty for {@code plan_execute},
     * despite {@link ExecutionStep}'s contract that traces reach the caller.
     *
     * <p><b>P0/U1-U2, 2026-09-22:</b> the tool call itself now runs through {@link
     * ReactExecutionSupport#runBounded} instead of inline on this (the reasoning) thread —
     * previously the only dispatch path in the codebase with no deadline and no watchdog at
     * all, so a hung tool (a stuck MCP server, a shell command still streaming) blocked this
     * step, and by extension the whole task, forever, ignoring {@code executionTimeout} — the
     * step loop's own {@code checkTimeout} boundary check is only ever reached between rounds,
     * never while a round's own tool call is still in flight.
     *
     * @throws InterruptedException      if the calling thread is cancelled while the tool call is in flight
     * @throws ExecutionTimeoutException if {@code run.deadline()} passes before the tool call returns
     */
    private static void dispatchTool(
            ToolCallParser.ToolCallRequest tcr, String completionText,
            List<LlmMessage> stepLocalHistory, PlanRun run, PlanTally tally) throws InterruptedException {

        run.task().notifyToolCall(tcr.toolId(), tcr.argumentJson());
        tally.record(ExecutionStep.toolCall(tcr.toolId(), tcr.argumentJson(), tally.iterations));

        String callId = tcr.toolCallId();
        AgentTask dispatchTask = (callId != null && !callId.isBlank())
                ? run.task().withAttachment(TelemetryToolRegistry.TOOL_CALL_ID_ATTACHMENT_KEY, callId)
                : run.task();
        ToolResult result = ReactExecutionSupport.runBounded(
                run.tools(),
                () -> run.tools().execute(tcr.toolId(), tcr.argumentJson(), dispatchTask),
                run.deadline(), run.config().executionTimeout());

        String observation = result.success()
                ? result.output()
                : "Tool [%s] failed — %s".formatted(tcr.toolId(), result.error());
        tally.record(ExecutionStep.observation(result, tally.iterations));
        appendExchange(stepLocalHistory, tcr, completionText, observation);
    }

    /**
     * Appends one call and its observation to a step's local history, as the native
     * function-calling pair when the call has an id and as plain text turns otherwise.
     */
    private static void appendExchange(
            List<LlmMessage> stepLocalHistory, ToolCallParser.ToolCallRequest tcr,
            String completionText, String observation) {
        String callId = tcr.toolCallId();
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

    /**
     * Per-step execution: the invariant instruction prefix for every round of one step —
     * isolated context containing system prompt, task, compact plan status, compact
     * previous results, and the current step instruction. The caller appends that step's
     * own tool exchange history to a copy of this list on each round.
     *
     * <p>Token usage is O(maxPlanSteps × STEP_RESULT_TRUNCATE_CHARS + stepLocalHistory)
     * regardless of total iterations.
     *
     * <p>When {@code run.nativeTools()} is {@code true} the text tool catalog and the
     * inline JSON tool-call instruction are both omitted — see {@link #EXEC_SUFFIX_NATIVE}.
     */
    private static List<LlmMessage> buildStepPrefix(
            PlanRun run, List<String> plan, Map<Integer, String> stepResults, List<String> notes,
            int currentStepIdx) {

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

        // Notes are exact facts (a path, a name), so — unlike the results above — they are
        // handed over whole, never shortened.
        String notesBlock = CloseStep.formatNotes(notes);
        if (notesBlock != null) {
            messages.add(new LlmMessage("user", notesBlock));
        }

        // Current step instruction
        messages.add(new LlmMessage("user",
                "Execute step %d/%d: %s".formatted(
                        currentStepIdx + 1, plan.size(), plan.get(currentStepIdx))));
        return messages;
    }

}
