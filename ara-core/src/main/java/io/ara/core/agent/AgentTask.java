package io.ara.core.agent;

import io.ara.core.auth.ExecutionContext;
import io.ara.core.llm.LlmExecutionHints;
import io.ara.core.media.MediaRef;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Represents a task submitted to an {@link AraAgent} for execution.
 *
 * <p>A task carries the user's input and an optional {@code correlationId} that links
 * this task to a broader workflow or multi-agent chain. Flow state — prompt variables
 * and opaque objects such as a {@code SecurityContext} — lives in {@link #runContext}
 * (ADR-041 rev. 2), a single value shared by reference across a delegation chain
 * instead of being copied at every hop.
 *
 * <p>{@link #media} is the task's non-textual input: images and documents the model is
 * meant to look at. It is a component of its own, and deliberately <em>not</em> another
 * entry in {@code runContext.opaque()}, because the two carry opposite guarantees:
 * everything in {@code opaque} must never reach a prompt (that is the whole point of
 * keeping a {@code SecurityContext} out of a naive prompt shaper's reach), while
 * everything in {@code media} is LLM-visible by definition. Merging them would destroy
 * the invariant that justifies {@code opaque}.
 *
 * <p>For streaming executions (when {@link AgentConfig#streamingEnabled()} is
 * {@code true}), a {@link #tokenCallback} can be attached to receive each token
 * as the LLM emits it. The gateway uses this to push SSE events to the client.
 * {@code null} means no streaming callback is registered.
 *
 * <p>{@link #toolCallCallback}, when set, is invoked with the tool id each time
 * a tool is about to be dispatched. The gateway emits this as a {@code tool_call}
 * SSE event so the UI can highlight the active tool edge in real time.
 *
 * <p>{@link #speakCallback}, when set, is invoked with the message text each time
 * {@code ReSpActStrategy} emits a {@link StepType#SPEAK} step — a conversational
 * utterance directed at the user that does not end the task the way a final answer
 * does. The gateway can emit this as a {@code speak} SSE event, distinct from both
 * the token stream (partial text of the eventual answer) and the tool-call event
 * (an environment action, not a user-facing utterance).
 *
 * @param taskId           unique identifier for this task; normally assigned by {@link #of},
 *                         but callers that must correlate the task with externally-keyed
 *                         state can override it via {@link #withTaskId}
 * @param input            the raw natural-language or structured input from the caller;
 *                         may be blank only when {@code media} is non-empty
 * @param media            images and documents the model should look at, in the order they
 *                         should be presented; never {@code null} (empty means text-only)
 * @param runContext       flow state of the current request — prompt variables and
 *                         opaque objects, shared by reference across delegation (ADR-041)
 * @param correlationId    optional identifier linking this task to a larger workflow
 * @param requestedBy      identity of the caller (operator id, upstream agent id, etc.)
 * @param createdAt        wall-clock timestamp of task creation
 * @param tokenCallback    optional callback invoked with each streamed token; {@code null}
 *                         when streaming is not requested
 * @param toolCallCallback optional callback invoked with the tool id just before dispatch
 * @param hints            optional per-call LLM execution hints (ADR-017)
 * @param sessionId        optional session identifier; {@code null} means ephemeral
 * @param userId           optional user identifier (ADR-043 rev. 3); {@code null} means
 *                         no cross-session memory for this task — see {@link
 *                         RunContext#userMemory()}
 * @param speakCallback    optional callback invoked with each ReSpAct "speak" message;
 *                         {@code null} when the caller does not want real-time delivery
 *                         of speak steps (they remain visible in {@code
 *                         AgentResponse.steps()} regardless)
 */
public record AgentTask(
        String taskId,
        String input,
        List<MediaRef> media,
        RunContext runContext,
        String correlationId,
        String requestedBy,
        Instant createdAt,
        Consumer<String> tokenCallback,
        Consumer<String> toolCallCallback,
        LlmExecutionHints hints,
        SessionId sessionId,
        UserId userId,
        Consumer<String> speakCallback
) {

    public AgentTask {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(input, "input must not be null");
        media = media != null ? List.copyOf(media) : List.of();
        // "Look at this PDF" with no words at all is the most common shape of a task that
        // attaches a document, so blank input is legal exactly when media carries the
        // request instead. A task with neither still says nothing, and stays rejected.
        if (input.isBlank() && media.isEmpty()) {
            throw new IllegalArgumentException(
                    "AgentTask input must not be blank unless media is present");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        runContext = Objects.requireNonNullElseGet(runContext, RunContext::empty);
    }

    public static AgentTask of(String input) {
        return new AgentTask(
                UUID.randomUUID().toString(),
                input,
                List.of(),
                RunContext.empty(),
                null,
                "system",
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /**
     * Creates a task carrying media alongside (or instead of) text. {@code input} may be
     * blank here — "read this contract" is often the document itself and nothing else.
     */
    public static AgentTask of(String input, List<MediaRef> media) {
        return new AgentTask(
                UUID.randomUUID().toString(),
                input,
                media,
                RunContext.empty(),
                null,
                "system",
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /** Creates a task whose {@link RunContext} carries {@code promptVars} and no opaque values. */
    public static AgentTask of(String input, Map<String, String> promptVars) {
        return new AgentTask(
                UUID.randomUUID().toString(),
                input,
                List.of(),
                new RunContext(promptVars, Map.of(), RunState.noop()),
                null,
                "system",
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /** Creates a task whose {@link RunContext} carries {@code promptVars} and no opaque values. */
    public static AgentTask of(String input, Map<String, String> promptVars,
                               String correlationId, String requestedBy) {
        return new AgentTask(
                UUID.randomUUID().toString(),
                input,
                List.of(),
                new RunContext(promptVars, Map.of(), RunState.noop()),
                correlationId,
                requestedBy,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public static AgentTask ofStreaming(String input, Consumer<String> tokenCallback) {
        Objects.requireNonNull(tokenCallback, "tokenCallback must not be null");
        return new AgentTask(
                UUID.randomUUID().toString(),
                input,
                List.of(),
                RunContext.empty(),
                null,
                "system",
                Instant.now(),
                tokenCallback,
                null,
                null,
                null,
                null,
                null
        );
    }

    /** Returns a copy of this task with the given {@link RunContext}. */
    public AgentTask withRunContext(RunContext newRunContext) {
        return new AgentTask(taskId, input, media, newRunContext, correlationId, requestedBy, createdAt, tokenCallback, toolCallCallback, hints, sessionId, userId, speakCallback);
    }

    /** Returns a copy of this task with an additional prompt variable merged into its {@link RunContext}. */
    public AgentTask withContextEntry(String key, String value) {
        return withRunContext(runContext.withPromptVar(key, value));
    }

    /**
     * Returns a copy of this task with {@code media} replaced by {@code newMedia}, which
     * <em>replaces</em> the existing list rather than appending to it.
     *
     * @throws IllegalArgumentException if {@code newMedia} is empty and {@link #input()} is blank
     */
    public AgentTask withMedia(List<MediaRef> newMedia) {
        return new AgentTask(taskId, input, newMedia, runContext, correlationId, requestedBy, createdAt, tokenCallback, toolCallCallback, hints, sessionId, userId, speakCallback);
    }

    /** Returns a copy of this task with {@code input} replaced by {@code newInput}. */
    public AgentTask withInput(String newInput) {
        Objects.requireNonNull(newInput, "newInput must not be null");
        return new AgentTask(taskId, newInput, media, runContext, correlationId, requestedBy, createdAt, tokenCallback, toolCallCallback, hints, sessionId, userId, speakCallback);
    }

    /** Returns a copy with the given execution hints. */
    public AgentTask withHints(LlmExecutionHints h) {
        return new AgentTask(taskId, input, media, runContext, correlationId, requestedBy, createdAt, tokenCallback, toolCallCallback, h, sessionId, userId, speakCallback);
    }

    /** Returns a copy with an output schema hint (convenience for schema-only hints). */
    public AgentTask withOutputSchema(String schema, boolean strict) {
        LlmExecutionHints base = hints != null ? hints : LlmExecutionHints.empty();
        return withHints(base.withOutputSchema(schema, null, strict));
    }

    /**
     * Returns a copy of this task with {@code taskId} replaced by {@code newTaskId} — the
     * only {@code with*} that overrides an identifier normally assigned once by {@link #of}
     * and never touched again. Needed when a caller must correlate this task with external
     * state keyed by an id it chooses itself (e.g. an eval harness arming a per-task
     * transcript capture before the task exists), rather than the random id {@link #of}
     * would otherwise generate.
     */
    public AgentTask withTaskId(String newTaskId) {
        Objects.requireNonNull(newTaskId, "newTaskId must not be null");
        return new AgentTask(newTaskId, input, media, runContext, correlationId, requestedBy, createdAt, tokenCallback, toolCallCallback, hints, sessionId, userId, speakCallback);
    }

    /** Returns a copy with the given session identifier. */
    public AgentTask withSessionId(SessionId sid) {
        return new AgentTask(taskId, input, media, runContext, correlationId, requestedBy, createdAt, tokenCallback, toolCallCallback, hints, sid, userId, speakCallback);
    }

    /** Returns a copy with the given user identifier (ADR-043 rev. 3). */
    public AgentTask withUserId(UserId uid) {
        return new AgentTask(taskId, input, media, runContext, correlationId, requestedBy, createdAt, tokenCallback, toolCallCallback, hints, sessionId, uid, speakCallback);
    }

    /** Returns a copy with the given tool-call callback. */
    public AgentTask withToolCallCallback(Consumer<String> callback) {
        return new AgentTask(taskId, input, media, runContext, correlationId, requestedBy, createdAt, tokenCallback, callback, hints, sessionId, userId, speakCallback);
    }

    /** Returns a copy with the given ReSpAct speak callback (see {@link #speakCallback}). */
    public AgentTask withSpeakCallback(Consumer<String> callback) {
        return new AgentTask(taskId, input, media, runContext, correlationId, requestedBy, createdAt, tokenCallback, toolCallCallback, hints, sessionId, userId, callback);
    }

    /**
     * Returns a copy of this task with an opaque value added or replaced under {@code key}
     * in its {@link RunContext}. {@code value == null} removes the key instead of
     * inserting a null value (ADR-037/ADR-041).
     */
    public AgentTask withAttachment(String key, Object value) {
        return withRunContext(runContext.withOpaque(key, value));
    }

    /**
     * Returns a copy of this task carrying {@code ctx} in its {@link #runContext} under
     * {@link RunContext#EXECUTION_CONTEXT_KEY} (ADR-033 Fase 6) — used by {@code
     * AraRuntime.executeOnBehalfOf}. Not a separate stored field: {@link #executionContext()}
     * reads back from exactly this key, so the two can never drift apart the way a second,
     * independently-copied field could (e.g. across a later {@link #withRunContext} call).
     */
    public AgentTask withExecutionContext(ExecutionContext ctx) {
        return withRunContext(runContext.withOpaque(RunContext.EXECUTION_CONTEXT_KEY, ctx));
    }

    /**
     * The {@link ExecutionContext} this task carries (ADR-033 Fase 6), if any — derived
     * from {@link #runContext}'s opaque channel, not a stored field; see {@link
     * #withExecutionContext}. {@link Optional#empty()} for every task built before Fase 6
     * existed, and for the ordinary (non-OBO) case.
     */
    public Optional<ExecutionContext> executionContext() {
        return Optional.ofNullable(runContext.opaque(RunContext.EXECUTION_CONTEXT_KEY, ExecutionContext.class));
    }

    /** Notifies the tool-call callback, if set. No-op when null. */
    public void notifyToolCall(String toolId) {
        if (toolCallCallback != null) toolCallCallback.accept(toolId);
    }

    /** Notifies the ReSpAct speak callback, if set. No-op when null. */
    public void notifySpeak(String message) {
        if (speakCallback != null) speakCallback.accept(message);
    }
}
