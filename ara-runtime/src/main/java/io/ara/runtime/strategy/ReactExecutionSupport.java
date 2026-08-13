package io.ara.runtime.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.common.Budget;
import io.ara.core.common.Money;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.memory.MemoryManager;
import io.ara.core.memory.ToolCallMetadata;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.telemetry.TelemetryToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reasoning-loop machinery shared by every ReAct-shaped strategy — tool dispatch and
 * streaming (used by {@link ReactStrategy}, {@link ReSpActStrategy}, and {@link
 * ReflActStrategy} alike), plus the plain two-branch (final-answer / tool-call) decision
 * logic, message building, and synthesis nudge that {@link ReactStrategy} and {@link
 * ReflActStrategy} share verbatim — {@link ReSpActStrategy} does not use the decision
 * pieces since its three-branch (final-answer / speak / tool-call) decision genuinely
 * differs.
 *
 * <p>Extracted rather than duplicated for the same reason {@link ToolCallParser} and
 * {@link ToolCatalogFormatter} already live here instead of inside a single strategy:
 * this is exactly the machinery where a subtle bug is easiest to introduce and easiest
 * to silently miss in a second copy — the parallel-dispatch interrupt propagation and
 * the streaming-subscription-leak-on-timeout fix (see {@link #dispatchParallel} / {@link
 * #streamAndCollect}) were both real bugs, and {@link ReflActStrategy} choosing to reuse
 * {@link #decideNextStep} rather than re-implement it is what keeps a future ReAct
 * protocol tweak (e.g. a new sentinel format) from silently applying to one strategy and
 * not the other.
 */
final class ReactExecutionSupport {

    private static final Logger log = LoggerFactory.getLogger(ReactExecutionSupport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Appended to the system prompt to instruct the LLM on ReAct output format.
     * Injected at the end of the system prompt section so it never overrides
     * agent-specific instructions.
     */
    static final String REACT_SYSTEM_SUFFIX = """

    You operate in a Reason+Act loop. At each step, reason about what to do next.
    - To invoke a tool, output ONLY a JSON object on a single line: {"tool_id":"<id>","arguments":{...}}
    - For tools with no arguments use an empty object: {"tool_id":"get_current_time","arguments":{}}
    - When you have the final answer, output: Action: FINAL_ANSWER\\nAnswer: <your answer>
    - NEVER output special tokens like <|channel|> or <|constrain|>. Only plain text and JSON.
    Never mix tool calls and final answers in the same response.""";

    /**
     * Always-present core of the synthesis nudge injected into working memory a few
     * iterations before {@code maxIterations} is reached — nudges models that never
     * self-terminate towards a conclusion. See {@link #buildSynthesisPrompt} for the
     * optional clauses appended on top of this.
     */
    static final String SYNTHESIS_CORE =
            "You are in your final iterations. Stop gathering new information and produce "
            + "your final answer now.";

    /**
     * Appended after {@link #SYNTHESIS_CORE} only when the agent actually has a {@code
     * file_write} tool enabled — telling every agent to "call file_write" regardless of
     * whether it has that tool wastes an iteration on a call that cannot succeed, right
     * when few iterations remain.
     */
    private static final String SYNTHESIS_PERSIST_CLAUSE =
            " Before finalising: if you have unsaved research or incomplete documents, call "
            + "file_write NOW to persist all remaining content (use mode=\"append\" to add to "
            + "existing files).";

    /**
     * Appended after the core (and the persist clause, if present) only for clients that
     * don't speak native function-calling — same condition {@link #buildMessages} uses for
     * {@link #REACT_SYSTEM_SUFFIX}. A native-tools client already has a structured signal
     * for "done" and never learned this text sentinel in the first place.
     */
    private static final String SYNTHESIS_SENTINEL_CLAUSE =
            "\nOutput:\n   Action: FINAL_ANSWER\n   Answer: <your final answer>";

    /**
     * Assembles the synthesis nudge from its always-present core plus the clauses that
     * apply to this agent/client — see {@link #SYNTHESIS_PERSIST_CLAUSE} and {@link
     * #SYNTHESIS_SENTINEL_CLAUSE} for when each is included.
     */
    static String buildSynthesisPrompt(boolean includePersistClause, boolean includeSentinelClause) {
        StringBuilder sb = new StringBuilder(SYNTHESIS_CORE);
        if (includePersistClause) {
            sb.append(SYNTHESIS_PERSIST_CLAUSE);
        }
        if (includeSentinelClause) {
            sb.append(SYNTHESIS_SENTINEL_CLAUSE);
        }
        return sb.toString();
    }

    /**
     * Synthesis nudge tuning. The nudge fires once, {@code tail} iterations before the
     * end, where {@code tail = max(SYNTHESIS_MIN_TAIL, maxIterations / SYNTHESIS_TAIL_DIVISOR)}
     * — i.e. proportional to loop length (last ~25%), never fewer than the last 2. Loops
     * shorter than {@link #SYNTHESIS_MIN_ITERATIONS} skip it entirely: there is too little
     * room for a "wrap up now" message to help, and firing it at, say, iteration 2 of 5
     * would just confuse the model.
     */
    private static final int SYNTHESIS_MIN_ITERATIONS = 5;
    private static final int SYNTHESIS_TAIL_DIVISOR   = 4;
    private static final int SYNTHESIS_MIN_TAIL       = 2;

    /** Marker preceding the answer text in a {@code Action: FINAL_ANSWER\nAnswer: <text>} response. */
    private static final String ANSWER_MARKER = "Answer:";

    private ReactExecutionSupport() {}

    // ── Plain ReAct decision logic (shared by ReactStrategy and ReflActStrategy) ───

    /**
     * What to do with the current completion, in priority order:
     * <ol>
     *   <li>{@link FinalAnswer} — the LLM signalled it is done (explicit sentinel,
     *       natural stop, or the forced-final iteration produced plain text).</li>
     *   <li>{@link DispatchTools} — one or more tool calls to run, from either the
     *       LLM adapter's structured tool-call channel (OpenAI function-calling) or
     *       an inline {@code {"tool_id":...}} / {@code <|channel|>} call extracted
     *       from the raw text (for models that don't use native function-calling).</li>
     *   <li>{@link Continue} — intermediate reasoning step, or a forced-final
     *       iteration with no final answer yet; loop again.</li>
     * </ol>
     */
    sealed interface StepDecision {
        record FinalAnswer(String answer) implements StepDecision {}
        record DispatchTools(List<ToolCallParser.ToolCallRequest> calls) implements StepDecision {}
        record Continue() implements StepDecision {}
    }

    /**
     * Decides the next step from the current completion — see {@link StepDecision}
     * for the priority order.
     */
    static StepDecision decideNextStep(
            String output, LlmCompletion completion, boolean forceFinal, AgentTask task, boolean noToolsAvailable) {

        // On the forced-final iteration tools are withheld — any tool-call signal
        // from the LLM is ignored and the output is treated as a final-answer candidate.
        // Same when the agent has no enabled tools at all: extractInline() matches any
        // {"name": ...} JSON object in the text as a fallback heuristic, which collides
        // with tool-less agents (e.g. an AgentBased grader) that legitimately emit JSON
        // containing a "name" field of their own — there is nothing to dispatch either
        // way, so skipping the parse costs nothing and avoids false-positive tool calls.
        Optional<ToolCallParser.ToolCallRequest> inlineCall = (completion.hasToolCall() || forceFinal || noToolsAvailable)
                ? Optional.empty()
                : ToolCallParser.extractInline(output);

        if (!completion.hasToolCall() && inlineCall.isEmpty() && isFinalAnswer(output, completion)) {
            return new StepDecision.FinalAnswer(extractFinalAnswer(output));
        }

        // On the forced-final iteration we never dispatch a tool even if the LLM
        // emitted one — the caller loops and lets isFinalAnswer catch it later
        // (or hits maxIterations).
        if (forceFinal) {
            return new StepDecision.Continue();
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
     * Returns {@code true} when the LLM has indicated that it has a final answer.
     *
     * <p>Two signals are checked:
     * <ol>
     *   <li>The output text contains the sentinel token {@code FINAL_ANSWER}.</li>
     *   <li>The LLM stopped naturally ({@code finishReason == "stop"}) without
     *       requesting a tool call — interpreted as an implicit final answer.</li>
     * </ol>
     */
    static boolean isFinalAnswer(String output, LlmCompletion completion) {
        if (output.contains("FINAL_ANSWER")) {
            return true;
        }
        return "stop".equalsIgnoreCase(completion.finishReason()) && !completion.hasToolCall();
    }

    /**
     * Extracts the answer text from a {@code FINAL_ANSWER} response.
     *
     * <p>Handles two common formats:
     * <ul>
     *   <li>{@code Action: FINAL_ANSWER\nAnswer: <text>}</li>
     *   <li>{@code FINAL_ANSWER <text>} (inline)</li>
     * </ul>
     */
    static String extractFinalAnswer(String output) {
        int answerIdx = output.indexOf(ANSWER_MARKER);
        if (answerIdx >= 0) {
            return output.substring(answerIdx + ANSWER_MARKER.length()).strip();
        }
        int finalIdx = output.indexOf("FINAL_ANSWER");
        if (finalIdx >= 0) {
            return output.substring(finalIdx + "FINAL_ANSWER".length()).strip();
        }
        return output.strip();
    }

    /**
     * Injects the "wrap up now" nudge once, a few iterations before {@code
     * maxIterations} — see the {@code SYNTHESIS_*} constants for how the trigger point
     * scales with loop length. Tools remain available for the iterations that follow
     * this nudge: when {@code resolvedTools} includes {@code file_write}, the persist
     * clause asks the model to save unsaved work before finalising, which would be
     * impossible to honour if tools were withheld at the same time the nudge fires.
     *
     * <p>Injected with role {@code "system"} rather than {@code "user"} — this is a
     * framework-generated instruction, not text from the end user, and agents that
     * treat user turns as untrusted data must not see it labelled as one.
     *
     * @param resolvedTools the agent's enabled tools, used to decide whether the
     *                       file-write persist clause applies to this agent
     * @param nativeTools    whether the LLM client speaks native function-calling —
     *                       gates the text {@code FINAL_ANSWER} sentinel, same condition
     *                       {@link #buildMessages} uses for {@link #REACT_SYSTEM_SUFFIX}
     * @return whether the nudge is now active — either it just fired, or {@code
     *         wasActive} already was; the caller uses this to update its own flag
     */
    static boolean maybeInjectSynthesis(
            MemoryManager memory, AgentConfig config, AgentTask task, int iterations, boolean wasActive,
            List<AraTool> resolvedTools, boolean nativeTools) {

        if (wasActive) {
            return true;
        }
        int synthesisTail = Math.max(SYNTHESIS_MIN_TAIL, config.maxIterations() / SYNTHESIS_TAIL_DIVISOR);
        if (config.maxIterations() < SYNTHESIS_MIN_ITERATIONS
                || iterations < config.maxIterations() - synthesisTail) {
            return false;
        }
        boolean hasFileWrite = resolvedTools.stream().anyMatch(t -> "file_write".equals(t.toolId()));
        memory.appendToWorkingMemory("system", buildSynthesisPrompt(hasFileWrite, !nativeTools));
        log.debug("Synthesis trigger injected at iteration {}/{} for task [{}] (persistClause={}, sentinelClause={})",
                iterations, config.maxIterations(), task.taskId(), hasFileWrite, !nativeTools);
        return true;
    }

    /**
     * Converts working memory to the LLM message list, enhancing the first
     * system message with the list of available tools and the ReAct format
     * instructions.
     *
     * <p>Both are omitted when {@code nativeTools} is {@code true}: the client already
     * receives {@code resolvedTools} as structured provider tool specifications, so the
     * text catalog and the inline {@code {"tool_id":...}}/{@code FINAL_ANSWER}
     * instructions would only duplicate — and can compete against — that channel.
     *
     * <p>The enhancement is applied to the in-flight message list only — the
     * working memory stored in {@link MemoryManager} is never modified here.
     */
    static List<LlmMessage> buildMessages(
            MemoryManager memory, List<io.ara.core.tool.AraTool> resolvedTools, boolean nativeTools) {
        var entries = memory.workingMemory();
        List<LlmMessage> messages = new ArrayList<>(entries.size());
        String toolCatalog = (nativeTools || resolvedTools.isEmpty())
                ? "" : ToolCatalogFormatter.format(resolvedTools);
        String reactSuffix = (nativeTools || resolvedTools.isEmpty()) ? "" : REACT_SYSTEM_SUFFIX;
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            if (i == 0 && "system".equals(e.role())) {
                messages.add(new LlmMessage("system", e.content() + toolCatalog + reactSuffix));
            } else if (e.metadata() instanceof ToolCallMetadata meta
                    && ("tool".equals(e.role()) || "assistant_tool_call".equals(e.role()))) {
                // Propagate toolCallId and toolName for native function-calling reconstruction
                messages.add(new LlmMessage(e.role(), e.content(), meta.callId(), meta.toolName()));
            } else {
                messages.add(new LlmMessage(e.role(), e.content()));
            }
        }
        return messages;
    }

    /** Logs the outcome of one iteration at DEBUG — tool call requested, or not. */
    static void logIterationResult(LlmCompletion completion, int iterations, int maxIterations, String taskId) {
        if (completion.hasToolCall()) {
            String toolName = ToolCallParser.extractToolName(completion.toolCallJson());
            log.debug("Iteration {}/{} — finishReason={} hasToolCall=true tool={} tokens={}",
                    iterations, maxIterations, completion.finishReason(), toolName, completion.totalTokens());
        } else {
            log.debug("Iteration {}/{} — finishReason={} hasToolCall=false tokens={}",
                    iterations, maxIterations, completion.finishReason(), completion.totalTokens());
        }
    }

    // ── LLM call: deadline enforcement and retry ────────────────────────────────

    /**
     * Fires the deadline interrupts raised by {@link #completeWithin}. One shared,
     * daemon-ish virtual-thread scheduler for the whole process rather than one per
     * agent or per call: a watchdog only ever sleeps and then interrupts, so a single
     * timer serves any number of concurrent executions.
     */
    private static final ScheduledExecutorService DEADLINE_WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("ara-llm-deadline").factory());

    /**
     * Attempts for one LLM call: the initial call plus {@value #LLM_MAX_ATTEMPTS} - 1
     * retries. Only {@link LlmException#isRetryable()} failures are retried — see
     * {@link #completeWithRetry}.
     */
    private static final int LLM_MAX_ATTEMPTS = 4;
    /** First backoff delay; doubles per attempt up to {@link #LLM_RETRY_MAX_DELAY}. */
    private static final Duration LLM_RETRY_BASE_DELAY = Duration.ofMillis(500);
    /** Ceiling for a single backoff delay, so a long loop cannot stall on one slow provider. */
    private static final Duration LLM_RETRY_MAX_DELAY = Duration.ofSeconds(8);

    /**
     * Performs one reasoning step's LLM call: streaming when the agent asked for it and
     * the caller supplied a token sink, blocking otherwise. Both paths are bounded by
     * {@code deadline}; the blocking path additionally retries retryable failures.
     *
     * <p><strong>Streaming is never retried.</strong> Tokens already handed to {@link
     * AgentTask#tokenCallback()} cannot be un-sent, so a second attempt would duplicate
     * them in the caller's stream — a worse failure than surfacing the error.
     *
     * @throws ExecutionTimeoutException if {@code deadline} passes before the call returns
     * @throws InterruptedException      if the calling thread is cancelled while waiting
     */
    static LlmCompletion callLlm(
            LlmClient llm, List<LlmMessage> messages, LlmCallContext stepCtx,
            AgentTask task, Instant deadline, AgentConfig config) throws InterruptedException {

        if (config.streamingEnabled() && task.tokenCallback() != null) {
            return streamAndCollect(llm, messages, stepCtx, task, deadline, config);
        }
        return completeWithRetry(llm, messages, stepCtx, deadline, config, task.taskId());
    }

    /**
     * Calls {@link LlmClient#complete} with bounded retries on transient provider failures.
     *
     * <p>Only {@link LlmException}s reporting {@link LlmException#isRetryable()} are retried
     * — rate limits, network blips and 5xx. Everything else propagates on the first attempt:
     * a non-retryable {@code LlmException} (auth, invalid request, context length) will fail
     * identically however many times it is repeated, and a plain {@code RuntimeException}
     * from a client carries no retry semantics at all, so re-issuing it would risk
     * duplicating a side effect the framework knows nothing about.
     *
     * <p>Backoff never borrows time the task does not have: if the next delay would run past
     * {@code deadline}, the last failure is rethrown instead of sleeping into a timeout.
     */
    static LlmCompletion completeWithRetry(
            LlmClient llm, List<LlmMessage> messages, LlmCallContext ctx,
            Instant deadline, AgentConfig config, String taskId) throws InterruptedException {

        for (int attempt = 1; ; attempt++) {
            try {
                return completeWithin(llm, messages, ctx, deadline, config);
            } catch (LlmException e) {
                Duration backoff = backoffFor(attempt);
                if (!e.isRetryable()
                        || attempt >= LLM_MAX_ATTEMPTS
                        || Instant.now().plus(backoff).isAfter(deadline)) {
                    throw e;
                }
                log.warn("LLM call failed for task [{}] (attempt {}/{}, {}): retrying in {} ms — {}",
                        taskId, attempt, LLM_MAX_ATTEMPTS, e.errorType(), backoff.toMillis(), e.getMessage());
                Thread.sleep(backoff);
            }
        }
    }

    /** Exponential backoff for {@code attempt} (1-based), capped at {@link #LLM_RETRY_MAX_DELAY}. */
    private static Duration backoffFor(int attempt) {
        Duration delay = LLM_RETRY_BASE_DELAY.multipliedBy(1L << (attempt - 1));
        return delay.compareTo(LLM_RETRY_MAX_DELAY) > 0 ? LLM_RETRY_MAX_DELAY : delay;
    }

    /**
     * Runs one blocking {@link LlmClient#complete} bounded by {@code deadline}.
     *
     * <p>The call stays on the calling thread — a watchdog interrupts that thread when the
     * deadline passes, rather than the call being handed to a worker. Moving it off-thread
     * would strand the ambient tracing context ({@code Span.makeCurrent()} is thread-local),
     * so the {@code llm.complete} span emitted by {@code InstrumentedLlmClient} would surface
     * as an unrelated root span instead of a child of {@code agent.execute} — and it would
     * leave the abandoned call holding its provider connection with nothing left to cancel it.
     *
     * <p>The {@code done}/{@code expired} handshake, both guarded by {@code gate}, makes the
     * outcome race-free instead of depending on a wall-clock comparison after the fact:
     * whichever side claims {@code gate} first decides. If the calling thread gets there
     * first (call returned or threw before the deadline), it marks {@code done} and the
     * watchdog — finding {@code done} already set — never interrupts at all. If the watchdog
     * gets there first, it marks {@code expired} and interrupts; the calling thread then
     * finds {@code expired} already set once it reclaims {@code gate}, and this method throws
     * {@link ExecutionTimeoutException} unconditionally — <strong>even if {@code llm.complete}
     * had, by then, already returned a normal result or thrown some other exception.</strong>
     * That last part matters: a client that catches the interrupt internally and hands back
     * a completion anyway (some do, treating {@code InterruptedException} as just another
     * error to recover from) must not have that completion mistaken for a real answer to a
     * call this method already gave up on — silently returning it would let a call that
     * timed out one millisecond before the boundary check masquerade as timely, purely
     * depending on how the race landed. Discarding it deterministically here removes that
     * timing dependency instead of leaving it to whichever check happens to run next.
     */
    private static LlmCompletion completeWithin(
            LlmClient llm, List<LlmMessage> messages, LlmCallContext ctx,
            Instant deadline, AgentConfig config) {

        long remainingMs = Duration.between(Instant.now(), deadline).toMillis();
        if (remainingMs <= 0) {
            throw new ExecutionTimeoutException(config.executionTimeout());
        }

        Thread    caller  = Thread.currentThread();
        Object    gate    = new Object();
        boolean[] done    = {false};      // guarded by gate
        boolean[] expired = {false};      // guarded by gate

        ScheduledFuture<?> watchdog = DEADLINE_WATCHDOG.schedule(() -> {
            synchronized (gate) {
                if (done[0]) return;      // call already returned — never interrupt
                expired[0] = true;
                caller.interrupt();
            }
        }, remainingMs, TimeUnit.MILLISECONDS);

        LlmCompletion     result  = null;
        RuntimeException  failure = null;
        try {
            result = llm.complete(messages, ctx);
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            watchdog.cancel(false);
        }

        boolean timedOut;
        synchronized (gate) {
            done[0]  = true;
            timedOut = expired[0];
        }
        if (timedOut) {
            // Absorb any interrupt still pending (from this method's own watchdog, or one
            // the client set again after catching it) so the caller's cancellation check
            // does not misread a deadline as a user-requested terminate().
            Thread.interrupted();
            throw new ExecutionTimeoutException(config.executionTimeout());
        }
        if (failure != null) {
            throw failure;
        }
        return result;
    }

    /**
     * Renders an LLM failure as a task failure reason, keeping the typed detail {@link
     * LlmException} carries. Without this the strategy reports only {@code getMessage()},
     * and the {@code errorType}/{@code provider} that told the runtime whether the failure
     * was even worth retrying never reach the caller — who receives an {@link
     * io.ara.core.agent.AgentResponse}, not the exception.
     */
    static String describeLlmFailure(Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (!(e instanceof LlmException le)) {
            return message;
        }
        String provider = le.provider() != null ? "/" + le.provider() : "";
        return "LLM call failed [" + le.errorType() + provider + "]: " + message;
    }

    // ── Tool dispatch and streaming ─────────────────────────────────────────────

    /**
     * State shared by every tool-dispatch call within one reasoning iteration. Introduced
     * because {@link #dispatchSingle}, {@link #dispatchParallel}, and {@link
     * #recordObservation} were each taking the same 6-7 parameters positionally — the
     * classic setup for an accidental argument-order bug (two adjacent {@code boolean}/
     * {@code int} parameters of the same type are easy to swap without the compiler
     * noticing). {@code recordObservation} does not need {@code tools}/{@code deadline}
     * from this bundle; a context object carrying more than any one consumer uses is
     * normal and simpler than three separate parameter lists.
     */
    record DispatchContext(
            ToolRegistry tools,
            MemoryManager memory,
            List<ExecutionStep> steps,
            AgentTask task,
            int iteration,
            Instant deadline,
            boolean logIo,
            int logIoMaxChars) {
    }

    /**
     * Dispatches a single tool call and appends the observation to memory.
     *
     * @return {@code true} if the tool call failed — callers that need to react to
     *         failure (e.g. {@link ReflActStrategy}'s reflect-on-tool-failure trigger)
     *         get an explicit signal instead of having to parse the formatted
     *         observation text {@link #recordObservation} writes to memory, which would
     *         couple them to wording that is free to change here.
     */
    static boolean dispatchSingle(ToolCallParser.ToolCallRequest tcr, String legacyToolCallId, DispatchContext ctx) {
        log.debug("Dispatching tool [{}] for task [{}]", tcr.toolId(), ctx.task().taskId());
        // Prefer the per-call toolCallId from ToolCallRequest; fall back to the legacy
        // top-level field on LlmCompletion for single-call adapters. Resolved into the
        // request itself so the shared dispatch below needs no single-call special case —
        // it is also what reaches TelemetryToolRegistry's tool.call_id attribute.
        String callId = tcr.toolCallId() != null ? tcr.toolCallId() : legacyToolCallId;
        ToolCallParser.ToolCallRequest resolved = callId != null && !callId.equals(tcr.toolCallId())
                ? new ToolCallParser.ToolCallRequest(tcr.toolId(), tcr.argumentJson(), callId)
                : tcr;
        return dispatchBounded(List.of(resolved), ctx);
    }

    /**
     * Turns a {@link ToolResult} into an observation and appends it to working memory
     * and {@code steps} — the shared tail of both {@link #dispatchSingle} and {@link
     * #dispatchParallel}, which differ only in how the result was obtained (direct
     * call vs. collected from a virtual thread).
     *
     * @return {@code !result.success()}
     */
    private static boolean recordObservation(
            ToolCallParser.ToolCallRequest tcr, String callId, ToolResult result, DispatchContext ctx) {

        String observation = result.success()
                ? result.output()
                : "Tool [%s] failed — %s".formatted(tcr.toolId(), result.error());

        log.debug("Tool [{}] result — success={} output={}",
                tcr.toolId(), result.success(),
                observation.length() > 300 ? observation.substring(0, 300) + "…" : observation);
        if (ctx.logIo()) {
            log.info("TOOL RESULT [{}] success={} output={}",
                    tcr.toolId(), result.success(), truncate(observation, ctx.logIoMaxChars()));
        }

        if (callId != null && !callId.isBlank()) {
            ctx.memory().appendToWorkingMemory("tool", observation, new ToolCallMetadata(callId, tcr.toolId()));
        } else {
            ctx.memory().appendToWorkingMemory("user", "Observation: " + observation);
        }
        ctx.steps().add(ExecutionStep.observation(observation, ctx.iteration()));
        return !result.success();
    }

    /**
     * Dispatches multiple tool calls in parallel using virtual threads (Java 21).
     *
     * <p>All tools run concurrently; results are collected in submission order and
     * appended to working memory once all threads complete. This avoids blocking
     * the loop while waiting for slow tools (e.g. web search + file write).
     *
     * @return {@code true} if any of the dispatched calls failed
     */
    static boolean dispatchParallel(List<ToolCallParser.ToolCallRequest> calls, DispatchContext ctx) {
        return dispatchBounded(calls, ctx);
    }

    /**
     * Runs {@code calls} on virtual threads and collects their results, giving up on any
     * that has not finished by {@code ctx.deadline()}.
     *
     * <p>Used for a lone tool call as much as for a batch. A single call used to run inline
     * on the reasoning thread to save one virtual thread — but that put the most common
     * dispatch shape on the one path with no deadline at all, so a tool that never returned
     * (a hung MCP server joining an unresponsive future, a shell command still streaming
     * output) blocked the whole agent indefinitely, ignoring {@code executionTimeout}.
     * Starting a virtual thread costs microseconds; an unbounded wait costs the session.
     */
    private static boolean dispatchBounded(List<ToolCallParser.ToolCallRequest> calls, DispatchContext ctx) {
        int n = calls.size();
        Map<Integer, ToolResult> results = new ConcurrentHashMap<>(n);
        CountDownLatch latch = new CountDownLatch(n);
        // Retained so the abandonment paths below can interrupt stragglers — without a
        // reference there is no way to signal a tool blocked on I/O, and it would sit on
        // its connection (and any lease-backed transport) until the I/O itself gave up.
        List<Thread> workers = new ArrayList<>(n);
        ToolRegistry tools = ctx.tools();

        for (int i = 0; i < n; i++) {
            final int idx = i;
            final ToolCallParser.ToolCallRequest tcr = calls.get(idx);
            if (ctx.logIo()) {
                log.info("TOOL CALL [{}] args={}", tcr.toolId(), truncate(tcr.argumentJson(), ctx.logIoMaxChars()));
            }
            ctx.task().notifyToolCall(tcr.toolId());
            ctx.steps().add(ExecutionStep.toolCall(tcr.toolId(), tcr.argumentJson(), ctx.iteration()));
            // A derived, per-call task carrying only this call's toolCallId (for
            // TelemetryToolRegistry's tool.call_id attribute) — created here, on the
            // dispatching thread, and handed to the virtual thread below. AgentTask is
            // immutable, so this is just a local value each call gets its own copy of;
            // it does not mutate the shared task the other concurrent calls still see.
            AgentTask dispatchTask = (tcr.toolCallId() != null && !tcr.toolCallId().isBlank())
                    ? ctx.task().withAttachment(TelemetryToolRegistry.TOOL_CALL_ID_ATTACHMENT_KEY, tcr.toolCallId())
                    : ctx.task();
            // wrapForPropagation captures the ambient tracing context (e.g. the current
            // agent.execute span) on THIS thread, before the virtual thread starts — tracing
            // context is thread-local and does not otherwise survive the hop, which would
            // make the tool.execute span below appear as an unrelated root span.
            workers.add(Thread.ofVirtual().start(tools.wrapForPropagation(() -> {
                try {
                    results.put(idx, tools.execute(tcr.toolId(), tcr.argumentJson(), dispatchTask));
                } catch (Exception e) {
                    results.put(idx, ToolResult.failure(tcr.toolId(),
                            "Unexpected error: " + e.getMessage()));
                } finally {
                    latch.countDown();
                }
            })));
        }

        // Bounded wait: tools that hang must not block the loop past the deadline.
        // Unfinished calls fall back to the "result missing" placeholder below; the
        // hung workers themselves are interrupted so a tool blocked on I/O gets the
        // same cooperative unblock signal AgentSession.requestCancel() uses, instead
        // of holding its connection open until the I/O times out on its own.
        try {
            long remainingMs = Math.max(0, Duration.between(Instant.now(), ctx.deadline()).toMillis());
            if (!latch.await(remainingMs, TimeUnit.MILLISECONDS)) {
                log.warn("Tool dispatch did not finish before deadline for task [{}] ({} call(s))",
                        ctx.task().taskId(), n);
                workers.forEach(Thread::interrupt);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();   // preserve the cancel signal for the loop's next check
            workers.forEach(Thread::interrupt);   // propagate it to the in-flight tools too
            log.warn("Tool dispatch interrupted for task [{}]", ctx.task().taskId());
        }

        // Append observations in submission order for deterministic memory layout
        boolean anyFailed = false;
        for (int i = 0; i < n; i++) {
            ToolCallParser.ToolCallRequest tcr = calls.get(i);
            ToolResult result = results.getOrDefault(i,
                    ToolResult.failure(tcr.toolId(), "result missing — thread may have been lost"));
            anyFailed |= recordObservation(tcr, tcr.toolCallId(), result, ctx);
        }
        return anyFailed;
    }

    /**
     * Appends the LLM's output to working memory in the form the next reasoning step
     * (and, for native tool calls, the adapter reconstructing the assistant turn)
     * expects, then records the {@code thought} step. Three shapes, in priority order:
     * multiple native tool calls (serialized so the adapter can reconstruct one AiMessage
     * carrying all of them), a single native tool call (kept as structured metadata for
     * reconstruction), or plain text.
     */
    static void recordAssistantOutput(
            MemoryManager memory, List<ExecutionStep> steps, LlmCompletion completion,
            String output, int iterations, String taskId) {

        if (!completion.toolCalls().isEmpty()) {
            try {
                String callsJson = MAPPER.writeValueAsString(completion.toolCalls().stream()
                        .map(e -> Map.of(
                                "id",   e.toolCallId() != null ? e.toolCallId() : "",
                                "name", e.toolId(),
                                "args", e.argumentJson()))
                        .toList());
                memory.appendToWorkingMemory("assistant_tool_calls", callsJson);
            } catch (Exception ex) {
                log.warn("Failed to serialise parallel tool calls for task [{}]: {}", taskId, ex.getMessage());
                memory.appendToWorkingMemory("assistant", output);
            }
        } else if (completion.hasToolCall() && completion.toolCallId() != null) {
            ToolCallMetadata meta = new ToolCallMetadata(
                    completion.toolCallId(), ToolCallParser.extractToolName(completion.toolCallJson()));
            memory.appendToWorkingMemory("assistant_tool_call",
                    ToolCallParser.extractToolArgs(completion.toolCallJson()), meta);
        } else {
            memory.appendToWorkingMemory("assistant", output);
        }
        steps.add(ExecutionStep.thought(output, iterations));
    }

    /**
     * Returns a failure result if the next LLM call would exceed the configured cost
     * budget, else {@code null} (meaning: proceed as normal). A plain nullable return
     * rather than a richer type: there are exactly two outcomes here (fail now, or
     * nothing to report), not three branches worth naming.
     */
    static ExecutionResult checkBudget(
            AgentConfig config, String taskId, int totalPromptTokens, int totalOutputTokens,
            int iterations, List<ExecutionStep> steps) {

        Budget budget = config.costBudget();
        Money inputRate  = config.costInputPer1kTokens();
        Money outputRate = config.costOutputPer1kTokens();
        if (inputRate.amount().signum() == 0 && outputRate.amount().signum() == 0) {
            return null;
        }
        Money spent = inputRate.multiply(fraction(totalPromptTokens))
                .plus(outputRate.multiply(fraction(totalOutputTokens)));
        Money nextEst = outputRate.multiply(fraction(config.maxTokensPerStep()));
        if (budget.permits(spent.plus(nextEst))) {
            return null;
        }
        log.warn("Task [{}] cost budget exceeded: spent={} nextEst={} limit={}",
                taskId, spent, nextEst, budget);
        return ExecutionResult.failure(
                "Cost budget %s exceeded (spent %s + next est. %s)".formatted(budget, spent, nextEst),
                iterations, totalPromptTokens, totalOutputTokens, steps);
    }

    private static java.math.BigDecimal fraction(int tokens) {
        return java.math.BigDecimal.valueOf(tokens).divide(java.math.BigDecimal.valueOf(1_000));
    }

    /** Truncates a string for INFO-level I/O logging; {@code maxChars <= 0} disables truncation. */
    static String truncate(String s, int maxChars) {
        if (s == null) return "<null>";
        if (maxChars <= 0 || s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "…[+" + (s.length() - maxChars) + "]";
    }

    /** Rough characters-per-token ratio used only to estimate streaming usage (see {@link #streamAndCollect}). */
    private static final int APPROX_CHARS_PER_TOKEN = 4;

    /** Rough token estimate from a character count (~{@value #APPROX_CHARS_PER_TOKEN} chars/token). */
    static int estimateTokensFromChars(int chars) {
        return chars <= 0 ? 0 : Math.max(1, chars / APPROX_CHARS_PER_TOKEN);
    }

    /**
     * Calls {@link LlmClient#stream} and blocks until all tokens have been emitted,
     * forwarding each token to {@link AgentTask#tokenCallback()} for SSE delivery.
     * Returns a synthetic {@link LlmCompletion} built from the accumulated text.
     */
    static LlmCompletion streamAndCollect(
            LlmClient llm,
            List<LlmMessage> messages,
            LlmCallContext ctx,
            AgentTask task,
            Instant deadline,
            AgentConfig config) throws InterruptedException {

        StringBuilder          buf   = new StringBuilder();
        CountDownLatch         latch = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        llm.stream(messages, ctx).subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                subscription.set(s);
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String token) {
                buf.append(token);
                try {
                    task.tokenCallback().accept(token);
                } catch (Exception e) {
                    log.warn("Token callback threw for task [{}]: {}", task.taskId(), e.getMessage());
                }
            }

            @Override public void onError(Throwable t)  { err.set(t); latch.countDown(); }
            @Override public void onComplete()           { latch.countDown(); }
        });

        // Bounded wait: a stream that never completes must not block past the deadline.
        // On timeout (or interrupt) the subscription is cancelled before giving up —
        // without that, the publisher keeps streaming into this dead task indefinitely:
        // the provider connection stays open and the SSE tokenCallback keeps firing for
        // a task the caller has already been told failed.
        long remainingMs = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
        try {
            if (!latch.await(remainingMs, TimeUnit.MILLISECONDS)) {
                cancelQuietly(subscription.get());
                throw new ExecutionTimeoutException(config.executionTimeout());
            }
        } catch (InterruptedException e) {
            cancelQuietly(subscription.get());
            throw e;
        }

        if (err.get() != null) {
            throw new RuntimeException("Streaming LLM call failed: " + err.get().getMessage(), err.get());
        }

        String text = buf.toString();

        // When the stream emits blank text the underlying client used the blocking
        // complete() fallback (LlmClient.super.stream) but only forwarded .text(),
        // silently dropping a native tool-call response. Fall back to complete() so
        // the caller receives the full LlmCompletion with tool-call metadata intact.
        if (text.isBlank()) {
            log.debug("Stream returned blank text — retrying with blocking complete() to recover tool-call metadata");
            return llm.complete(messages, ctx);
        }

        // Streaming responses carry no token usage. Returning 0/0 would silently disable
        // the cost-budget check for every streaming call (budget is derived from token
        // counts), so an agent with streamingEnabled + a tight budget would run to
        // maxIterations/timeout ignoring the budget entirely. Estimate instead: ~4 chars
        // per token over the prompt and the generated text. This is approximate — a
        // provider that returns usage in its final chunk should override this — but it
        // keeps budget enforcement and totalTokens reporting meaningfully non-zero.
        int promptChars = 0;
        for (LlmMessage m : messages) {
            if (m.content() != null) promptChars += m.content().length();
        }
        int estPromptTokens = estimateTokensFromChars(promptChars);
        int estOutputTokens = estimateTokensFromChars(text.length());
        return new LlmCompletion(text, estPromptTokens, estOutputTokens, "stop", null, null, List.of(), true);
    }

    /**
     * Cancels a stream subscription, swallowing any publisher-side failure: this only
     * runs on paths that are already aborting (timeout/interrupt), where a broken
     * {@code cancel()} must not mask the original reason for stopping.
     */
    private static void cancelQuietly(Flow.Subscription subscription) {
        if (subscription == null) return;
        try {
            subscription.cancel();
        } catch (RuntimeException e) {
            log.debug("Stream subscription cancel() threw while aborting", e);
        }
    }
}
