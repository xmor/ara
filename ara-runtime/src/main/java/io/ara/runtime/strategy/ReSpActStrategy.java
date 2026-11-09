package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * ReactExecutionSupport#REACT_SYSTEM_SUFFIX}, and like it free of any model's control tokens
     * (see there for why).
     */
    static final String RESPACT_SYSTEM_SUFFIX = """

    You operate in a Reason+Speak+Act loop. At each step, reason about what to do next,
    then choose exactly one of three actions:
    - To invoke a tool, output ONLY a JSON object on a single line: {"tool_id":"<id>","arguments":{...}}
    - To say something to the user WITHOUT ending the task (ask a clarifying question, \
    give a status update, share a partial result), output: Action: SPEAK\\nMessage: <your message>
    - When the task is fully complete and needs no reply, output: Action: FINAL_ANSWER\\nAnswer: <your answer>
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
    public boolean resumable() {
        return true;
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

        return new ReSpActLoop(task, llm, memory, tools, config).run();
    }

    /**
     * The ReSpAct-specific pieces over the shared {@link ReActLoop}: the three-branch
     * decision (final answer / speak / tool call) and its sentinel parsing. The synthesis
     * nudge and per-iteration logging are opted out of, matching this strategy's
     * pre-refactor behaviour.
     */
    private final class ReSpActLoop extends ReActLoop {

        ReSpActLoop(AgentTask task, LlmClient llm, MemoryManager memory, ToolRegistry tools, AgentConfig config) {
            super(task, llm, memory, tools, config);
        }

        @Override
        String systemSuffix() {
            return RESPACT_SYSTEM_SUFFIX;
        }

        @Override
        boolean injectSynthesis() {
            return false;
        }

        @Override
        boolean logIterations() {
            return false;
        }

        @Override
        void logStart() {
            if (log.isDebugEnabled()) {
                log.debug("ReSpActStrategy starting for task [{}] maxIterations={} tools={}",
                        task.taskId(), config.maxIterations(),
                        resolvedTools.stream().map(AraTool::toolId).toList());
            }
        }

        @Override
        ExecutionResult decide(String output, LlmCompletion completion, boolean forceFinal, int iteration) {
            // forceFinal picks which sealed decision type governs this iteration — on the
            // forced branch DispatchTools does not exist as a case (same rationale as
            // ReactStrategy.ForcedFinalDecision), so a tool dispatch here is a compile
            // error rather than a rule enforced by remembering to check a flag.
            if (forceFinal) {
                ForcedFinalDecision decision = decideForcedFinal(output, completion);
                switch (decision) {
                    case ForcedFinalDecision.FinalAnswer(String answer) -> {
                        log.debug("Task [{}] reached FINAL_ANSWER in {} iteration(s)", task.taskId(), iteration);
                        RunEvents.record(task, steps, ExecutionStep.finalAnswer(answer, iteration));
                        return ExecutionResult.success(answer, iteration, totalPromptTokens, totalOutputTokens, steps);
                    }
                    case ForcedFinalDecision.Speak(String message) -> {
                        log.debug("Task [{}] spoke to the user in {} iteration(s)", task.taskId(), iteration);
                        RunEvents.record(task, steps, ExecutionStep.speak(message, iteration));
                        task.notifySpeak(message);
                        return ExecutionResult.success(message, iteration, totalPromptTokens, totalOutputTokens, steps);
                    }
                    case ForcedFinalDecision.Continue ignored ->
                        log.debug("Forced-final iteration: skipping tool dispatch for task [{}]", task.taskId());
                }
            } else {
                StepDecision decision = decideNormal(output, completion, task, resolvedTools.isEmpty());
                switch (decision) {
                    case StepDecision.FinalAnswer(String answer) -> {
                        log.debug("Task [{}] reached FINAL_ANSWER in {} iteration(s)", task.taskId(), iteration);
                        RunEvents.record(task, steps, ExecutionStep.finalAnswer(answer, iteration));
                        return ExecutionResult.success(answer, iteration, totalPromptTokens, totalOutputTokens, steps);
                    }
                    case StepDecision.Speak(String message) -> {
                        log.debug("Task [{}] spoke to the user in {} iteration(s)", task.taskId(), iteration);
                        RunEvents.record(task, steps, ExecutionStep.speak(message, iteration));
                        task.notifySpeak(message);
                        return ExecutionResult.success(message, iteration, totalPromptTokens, totalOutputTokens, steps);
                    }
                    case StepDecision.DispatchTools(List<ToolCallParser.ToolCallRequest> calls) ->
                        dispatch(calls, completion, iteration);
                    case StepDecision.Continue ignored -> {
                        // No terminal signal yet — an intermediate reasoning step. Loop again.
                    }
                }
            }
            return null;
        }

        @Override
        ExecutionResult onMaxIterations() {
            log.warn("Task [{}] hit maxIterations={} without a final answer or speak turn",
                    task.taskId(), config.maxIterations());
            return ExecutionResult.failure(
                    "Max iterations (%d) reached without a final answer or speak turn".formatted(config.maxIterations()),
                    iterations, totalPromptTokens, totalOutputTokens, steps);
        }
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
}
