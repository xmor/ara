package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.memory.MemoryManager;
import io.ara.core.memory.ToolCallMetadata;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ReSpAct (Reasoning + Speaking + Acting) execution strategy — Verma et al., 2024
 * (arXiv:2411.00927, "ReSpAct: Harmonizing Reasoning, Speaking, and Acting Towards
 * Building Large Language Model-Based Conversational AI Agents").
 *
 * <p>Extends the ReAct loop ({@link ReactStrategy}) with a third action type alongside
 * <em>reason</em> (silent thoughts) and <em>act</em> (tool calls): <strong>speak</strong>
 * — a conversational utterance directed at the user (a clarifying question, a status
 * update, a partial result) that, unlike a final answer, does not close out the task.
 * Every iteration of the loop follows the same Think → decide cycle as {@link
 * ReactStrategy}, but the decision now has three terminal branches instead of two.
 *
 * <h2>Mapping onto ARA's turn model</h2>
 * <p>The paper's reference setup uses a live "user simulator" that replies to every
 * speak action within the same episode — i.e. the agent's loop pauses and resumes
 * mid-execution. ARA's {@code AraAgent.execute(AgentTask)} is synchronous and
 * one-task-per-call; there is no mechanism for a strategy to suspend mid-loop and be
 * resumed later with a reply spliced into the same execution. This strategy adapts the
 * paper's model to that constraint the same way a real chat turn already works in ARA:
 * <strong>a SPEAK action ends the current {@code execute()} call</strong>, exactly like
 * a final answer does — the strategy returns {@link ExecutionResult#success}, {@code
 * AgentInstance} records it into {@code ConversationHistory} as an assistant turn, and
 * the user's reply (if any) arrives as the next {@link AgentTask} submitted on the same
 * {@link io.ara.core.agent.SessionId} — replayed into working memory exactly like any
 * other turn. Multiple Thought → Act (tool call) rounds can still happen within a single
 * {@code execute()} call before the loop terminates via either branch; SPEAK and
 * FINAL_ANSWER differ only in what they signal to the caller, not in how they stop the
 * loop.
 *
 * <p>The two are distinguished for callers via {@link ExecutionStep#type()}: the last
 * step in {@link ExecutionResult#steps()} (equivalently, {@code
 * AgentResponse.steps()}) is {@link io.ara.core.agent.StepType#SPEAK} for a speak-ended
 * turn and {@link io.ara.core.agent.StepType#FINAL_ANSWER} for a genuinely completed
 * task — a caller (gateway/UI) that wants to keep the composer open and await a reply
 * checks exactly that. {@link AgentTask#speakCallback()} additionally delivers the speak
 * message in real time, the same way {@link AgentTask#toolCallCallback()} delivers tool
 * dispatch notifications.
 *
 * <h2>Protocol</h2>
 * <p>Mirrors {@link ReactStrategy}'s sentinel format, adding a third action:
 * <pre>{@code
 * Action: SPEAK
 * Message: <text directed at the user>
 * }</pre>
 * A completion is resolved in this priority order (see {@link #decideNormal}):
 * a requested tool call, then an explicit {@code Action: FINAL_ANSWER} sentinel, then an
 * explicit {@code Action: SPEAK} sentinel, then — for a model that stops generating
 * without any sentinel and without a tool call — an <em>implicit SPEAK</em>, not an
 * implicit final answer as in plain ReAct. This is the one behavioural divergence from
 * {@link ReactStrategy}, deliberately: a conversational agent's default "I produced text
 * and have nothing more to do right now" state is "I said something to the user," not
 * "this entire task is permanently closed" — the latter should be an affirmative signal
 * from the model, not the fallback for every model that does not reliably emit sentinels.
 *
 * <p>A single completion cannot both speak and dispatch a tool in this v1 — the
 * per-iteration decision is exclusive, same as {@link ReactStrategy}'s {@code
 * StepDecision}; a model that emits both a tool call and {@code Action: SPEAK} text in
 * the same response has the tool call win, exactly mirroring how {@link ReactStrategy}
 * already prioritises a tool-call signal over final-answer text in the same completion.
 */
public final class ReSpActStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReSpActStrategy.class);

    /**
     * Appended to the system prompt to instruct the LLM on the ReSpAct output format.
     * Injected at the end of the system prompt section so it never overrides
     * agent-specific instructions — same placement rule as {@link
     * ReactExecutionSupport#REACT_SYSTEM_SUFFIX}.
     */
    static final String RESPACT_SYSTEM_SUFFIX = """

    You operate in a Reason+Speak+Act loop. At each step, reason about what to do next,
    then choose exactly one of three actions:
    - To invoke a tool, output ONLY a JSON object on a single line: {"tool_id":"<id>","arguments":{...}}
    - To say something to the user WITHOUT ending the task (ask a clarifying question, \
    give a status update, share a partial result), output: Action: SPEAK\\nMessage: <your message>
    - When the task is fully complete and needs no reply, output: Action: FINAL_ANSWER\\nAnswer: <your answer>
    - NEVER output special tokens like <|channel|> or <|constrain|>. Only plain text and JSON.
    Never combine two of these actions in the same response.""";

    /** Marker preceding the answer text in a {@code Action: FINAL_ANSWER\nAnswer: <text>} response. */
    private static final String ANSWER_MARKER = "Answer:";
    /** Marker preceding the message text in a {@code Action: SPEAK\nMessage: <text>} response. */
    private static final String MESSAGE_MARKER = "Message:";
    private static final String FINAL_ANSWER_SENTINEL = "FINAL_ANSWER";
    private static final String SPEAK_SENTINEL = "Action: SPEAK";

    @Override
    public String strategyName() {
        return "respact";
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

        int     iterations        = 0;
        int     totalPromptTokens = 0;
        int     totalOutputTokens = 0;
        final boolean logIo         = config.logLlmIo();
        final int     logIoMaxChars = config.logLlmIoMaxChars();
        final boolean nativeTools   = llm.supportsNativeTools();
        List<ExecutionStep> steps = new ArrayList<>();
        Instant deadline = Instant.now().plus(config.executionTimeout());

        LlmCallContext ctx = LlmCallContext.of(config, task);
        List<AraTool> resolvedTools = tools.resolveEnabled(config.enabledTools());

        log.debug("ReSpActStrategy starting for task [{}] maxIterations={} tools={}",
                task.taskId(), config.maxIterations(),
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

            // Hard stop: on the final iteration(s) withhold tools so the model cannot
            // emit a structured tool call and MUST produce plain text — decideForcedFinal's
            // implicit-SPEAK fallback then guarantees termination even for a model that
            // never emits either sentinel. Same rationale as ReactStrategy's forceFinal.
            boolean forceFinal = iterations >= config.maxIterations() - 1;
            List<AraTool> activeTools = forceFinal ? List.of() : resolvedTools;
            List<LlmMessage> messages = buildMessages(memory, activeTools, nativeTools);
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

            // forceFinal picks which sealed decision type governs this iteration — on the
            // forced branch DispatchTools does not exist as a case (same rationale as
            // ReactStrategy.ForcedFinalDecision), so a tool dispatch here is a compile
            // error rather than a rule enforced by remembering to check a flag.
            if (forceFinal) {
                ForcedFinalDecision decision = decideForcedFinal(output, completion);
                switch (decision) {
                    case ForcedFinalDecision.FinalAnswer(String answer) -> {
                        log.debug("Task [{}] reached FINAL_ANSWER in {} iteration(s)", task.taskId(), iterations);
                        steps.add(ExecutionStep.finalAnswer(answer, iterations));
                        return ExecutionResult.success(answer, iterations, totalPromptTokens, totalOutputTokens, steps);
                    }
                    case ForcedFinalDecision.Speak(String message) -> {
                        log.debug("Task [{}] spoke to the user in {} iteration(s)", task.taskId(), iterations);
                        steps.add(ExecutionStep.speak(message, iterations));
                        task.notifySpeak(message);
                        return ExecutionResult.success(message, iterations, totalPromptTokens, totalOutputTokens, steps);
                    }
                    case ForcedFinalDecision.Continue ignored ->
                        log.debug("Forced-final iteration: skipping tool dispatch for task [{}]", task.taskId());
                }
            } else {
                StepDecision decision = decideNormal(output, completion, task, resolvedTools.isEmpty());
                switch (decision) {
                    case StepDecision.FinalAnswer(String answer) -> {
                        log.debug("Task [{}] reached FINAL_ANSWER in {} iteration(s)", task.taskId(), iterations);
                        steps.add(ExecutionStep.finalAnswer(answer, iterations));
                        return ExecutionResult.success(answer, iterations, totalPromptTokens, totalOutputTokens, steps);
                    }
                    case StepDecision.Speak(String message) -> {
                        log.debug("Task [{}] spoke to the user in {} iteration(s)", task.taskId(), iterations);
                        steps.add(ExecutionStep.speak(message, iterations));
                        task.notifySpeak(message);
                        return ExecutionResult.success(message, iterations, totalPromptTokens, totalOutputTokens, steps);
                    }
                    case StepDecision.DispatchTools(List<ToolCallParser.ToolCallRequest> calls) -> {
                        ReactExecutionSupport.DispatchContext dispatchCtx = new ReactExecutionSupport.DispatchContext(
                                tools, memory, steps, task, iterations, deadline, logIo, logIoMaxChars);
                        if (calls.size() == 1) {
                            ReactExecutionSupport.dispatchSingle(calls.get(0), completion.toolCallId(), dispatchCtx);
                        } else {
                            log.debug("Parallel dispatch: {} tool calls for task [{}]", calls.size(), task.taskId());
                            ReactExecutionSupport.dispatchParallel(calls, dispatchCtx);
                        }
                    }
                    case StepDecision.Continue ignored -> {
                        // No terminal signal yet — an intermediate reasoning step. Loop again.
                    }
                }
            }
        }

        log.warn("Task [{}] hit maxIterations={} without a final answer or speak turn",
                task.taskId(), config.maxIterations());
        return ExecutionResult.failure(
                "Max iterations (%d) reached without a final answer or speak turn".formatted(config.maxIterations()),
                iterations, totalPromptTokens, totalOutputTokens, steps);
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * What to do with the current completion on a <strong>normal</strong> (non-forced)
     * iteration, in priority order:
     * <ol>
     *   <li>{@link DispatchTools} — a tool call was requested (native or inline);
     *       highest priority, same as {@link ReactStrategy}.</li>
     *   <li>{@link FinalAnswer} — the LLM explicitly signalled the task is done.</li>
     *   <li>{@link Speak} — the LLM explicitly said something to the user, or (the one
     *       divergence from plain ReAct) stopped naturally with no sentinel and no tool
     *       call — treated as an implicit speak turn, not an implicit final answer;
     *       see the class Javadoc for why.</li>
     *   <li>{@link Continue} — no terminal signal yet; loop again.</li>
     * </ol>
     *
     * <p>See {@link ForcedFinalDecision} for the forced-final-iteration counterpart,
     * whose type has no {@code DispatchTools} case — the compiler, not a runtime check,
     * is what keeps a forced-final iteration from ever dispatching a tool.
     */
    private sealed interface StepDecision {
        record FinalAnswer(String answer) implements StepDecision {}
        record Speak(String message) implements StepDecision {}
        record DispatchTools(List<ToolCallParser.ToolCallRequest> calls) implements StepDecision {}
        record Continue() implements StepDecision {}
    }

    /**
     * What to do with the current completion on the <strong>forced-final</strong>
     * iteration — tools were withheld from the LLM for this call, so there is nothing to
     * dispatch: a terminal signal (final answer or speak), or the caller loops again.
     */
    private sealed interface ForcedFinalDecision {
        record FinalAnswer(String answer) implements ForcedFinalDecision {}
        record Speak(String message) implements ForcedFinalDecision {}
        record Continue() implements ForcedFinalDecision {}
    }

    /**
     * Decides the next step on a normal iteration — see {@link StepDecision} for the
     * priority order. Use {@link #decideForcedFinal} once tools have been withheld for
     * the forced-final iteration(s) instead.
     */
    private StepDecision decideNormal(
            String output, LlmCompletion completion, AgentTask task, boolean noToolsAvailable) {

        // When the agent has no tools at all, skip the inline-JSON tool-call parse:
        // extractInline()'s {"name":...} heuristic would otherwise collide with a
        // tool-less agent's own legitimate JSON output.
        Optional<ToolCallParser.ToolCallRequest> inlineCall = (completion.hasToolCall() || noToolsAvailable)
                ? Optional.empty()
                : ToolCallParser.extractInline(output);

        boolean hasTerminalText = !completion.hasToolCall() && inlineCall.isEmpty();

        if (hasTerminalText && isFinalAnswer(output)) {
            return new StepDecision.FinalAnswer(extractAfterMarker(output, FINAL_ANSWER_SENTINEL, ANSWER_MARKER));
        }
        if (hasTerminalText && isSpeak(output, completion)) {
            return new StepDecision.Speak(extractAfterMarker(output, SPEAK_SENTINEL, MESSAGE_MARKER));
        }

        List<ToolCallParser.ToolCallRequest> allCalls = new ArrayList<>();
        if (completion.hasToolCall()) {
            allCalls.addAll(ToolCallParser.extractAll(completion));
        } else if (inlineCall.isPresent()) {
            allCalls.add(inlineCall.get());
            log.debug("Inline tool call detected for task [{}]: tool={}", task.taskId(), allCalls.get(0).toolId());
        }

        return allCalls.isEmpty() ? new StepDecision.Continue() : new StepDecision.DispatchTools(allCalls);
    }

    /**
     * Decides the next step on the forced-final iteration. Tools were withheld from the
     * LLM for this call, so any tool-call signal it emits anyway is ignored outright —
     * unlike {@link #decideNormal}, there is no inline-JSON parse to run and no {@code
     * DispatchTools} case to return it as.
     */
    private ForcedFinalDecision decideForcedFinal(String output, LlmCompletion completion) {
        boolean hasTerminalText = !completion.hasToolCall();

        if (hasTerminalText && isFinalAnswer(output)) {
            return new ForcedFinalDecision.FinalAnswer(extractAfterMarker(output, FINAL_ANSWER_SENTINEL, ANSWER_MARKER));
        }
        if (hasTerminalText && isSpeak(output, completion)) {
            return new ForcedFinalDecision.Speak(extractAfterMarker(output, SPEAK_SENTINEL, MESSAGE_MARKER));
        }
        return new ForcedFinalDecision.Continue();
    }

    /** Returns {@code true} when the output carries the explicit {@code FINAL_ANSWER} sentinel. */
    private boolean isFinalAnswer(String output) {
        return output.contains(FINAL_ANSWER_SENTINEL);
    }

    /**
     * Returns {@code true} when the LLM has said something to the user without closing
     * the task: either the explicit {@code Action: SPEAK} sentinel, or — the ReSpAct-
     * specific divergence from {@link ReactExecutionSupport#isFinalAnswer} — a natural stop
     * ({@code finishReason == "stop"}) with no tool call and no {@code FINAL_ANSWER}
     * sentinel either (already ruled out by the caller before this is reached).
     */
    private boolean isSpeak(String output, LlmCompletion completion) {
        if (output.contains(SPEAK_SENTINEL)) {
            return true;
        }
        return "stop".equalsIgnoreCase(completion.finishReason()) && !completion.hasToolCall();
    }

    /**
     * Extracts the text following {@code fieldMarker} (e.g. {@code "Answer:"} or
     * {@code "Message:"}); falls back to the text following {@code sentinel} itself when
     * the field marker is absent, then to the whole output — mirrors {@link
     * ReactExecutionSupport#extractFinalAnswer}'s two-format tolerance ({@code Action: X\nField:
     * <text>} vs. bare {@code X <text>}).
     */
    private String extractAfterMarker(String output, String sentinel, String fieldMarker) {
        int fieldIdx = output.indexOf(fieldMarker);
        if (fieldIdx >= 0) {
            return output.substring(fieldIdx + fieldMarker.length()).strip();
        }
        int sentinelIdx = output.indexOf(sentinel);
        if (sentinelIdx >= 0) {
            return output.substring(sentinelIdx + sentinel.length()).strip();
        }
        return output.strip();
    }

    /**
     * Converts working memory to the LLM message list, enhancing the first system
     * message with the tool catalog and the ReSpAct format instructions — same
     * structure as {@link ReactExecutionSupport#buildMessages}, with {@link
     * #RESPACT_SYSTEM_SUFFIX} in place of {@code REACT_SYSTEM_SUFFIX}.
     */
    private List<LlmMessage> buildMessages(
            MemoryManager memory, List<AraTool> resolvedTools, boolean nativeTools) {
        var entries = memory.workingMemory();
        List<LlmMessage> messages = new ArrayList<>(entries.size());
        String toolCatalog = (nativeTools || resolvedTools.isEmpty())
                ? "" : ToolCatalogFormatter.format(resolvedTools);
        String respactSuffix = (nativeTools || resolvedTools.isEmpty()) ? "" : RESPACT_SYSTEM_SUFFIX;
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            if (i == 0 && "system".equals(e.role())) {
                messages.add(new LlmMessage("system", e.content() + toolCatalog + respactSuffix));
            } else if (e.metadata() instanceof ToolCallMetadata meta
                    && ("tool".equals(e.role()) || "assistant_tool_call".equals(e.role()))) {
                messages.add(new LlmMessage(e.role(), e.content(), meta.callId(), meta.toolName()));
            } else {
                // Entry media rides along: this is the only path from the task's attachments
                // to the outgoing request, and the adapter is what turns the references into
                // provider content. A text-only entry produces exactly the message it did
                // before media existed.
                messages.add(new LlmMessage(e.role(), e.content(), null, null, e.media()));
            }
        }
        return messages;
    }
}
