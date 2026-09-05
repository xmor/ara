package io.ara.core.bus;

import io.ara.core.agent.RunContext;
import io.ara.core.agent.SessionId;
import io.ara.core.auth.ExecutionContext;
import io.ara.core.auth.ScopeSet;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable message envelope exchanged between agents on the same platform node.
 *
 * <p>Carries the textual payload and all metadata needed to route, correlate,
 * and audit agent-to-agent communication:
 *
 * <ul>
 *   <li>{@code messageId}     — unique id for this message</li>
 *   <li>{@code senderId}      — id of the originating agent</li>
 *   <li>{@code recipientId}   — id of the target agent</li>
 *   <li>{@code content}       — the natural-language payload (task or response)</li>
 *   <li>{@code correlationId} — links request and reply; also propagated to the
 *       {@code AgentTask} so the full delegation chain is observable in logs</li>
 *   <li>{@code sentAt}        — wall-clock timestamp of creation</li>
 *   <li>{@code runContext}    — the caller's flow state (ADR-041 rev. 2): prompt
 *       variables and opaque objects such as a {@code SecurityContext}. Carried as a
 *       single reference, not copied into new maps — the recipient's {@code AgentTask}
 *       is seeded with this exact {@code RunContext}, so a delegated sub-task runs with
 *       the same context, and the same authorization, as its caller.</li>
 *   <li>{@code sessionId}     — the session the recipient should run under, if any.
 *       {@code null} means the recipient falls back to an ephemeral session (today's
 *       behavior). Set by {@link io.ara.core.agent.DelegateStateAccess}-aware callers so
 *       a delegate's {@code RunState} and conversation history can persist and be
 *       resumed across repeated delegations, instead of vanishing with each hop.</li>
 *   <li>{@code senderScopes}  — the sender's authorization scopes (ADR-033 Fase 2),
 *       checked by {@code LocalMessageBus} against the recipient's {@code requiredScopes}
 *       before dispatch when no {@code executionContext} is present. Defaults to
 *       {@link ScopeSet#EMPTY} — a message with no declared scopes satisfies only a
 *       recipient that itself requires none, exactly today's unrestricted behavior.</li>
 *   <li>{@code executionContext} — the full {@link ExecutionContext} (ADR-033 Fase 5),
 *       superseding {@code senderScopes} wherever it is present: it carries a subject
 *       identity across the whole delegation chain (on-behalf-of, ADR-033 Fase 6), not
 *       just a single hop's {@link ScopeSet}. {@code LocalMessageBus} prefers this field
 *       when set and falls back to {@code senderScopes} otherwise — every message built
 *       before this field existed keeps behaving exactly as it did under Fase 2.</li>
 * </ul>
 *
 * <p>{@code runContext} is {@link RunContext#empty()} for replies: the answer flows
 * back, no caller state needs to travel with it. Replies likewise carry no
 * {@code sessionId} — a reply is a return value, not a task to route.
 *
 * <p>Factory methods:
 * <ul>
 *   <li>{@link #of(String, String, String)} — outbound request, no caller state</li>
 *   <li>{@link #of(String, String, String, RunContext)} — outbound request carrying
 *       the caller's {@code RunContext}</li>
 *   <li>{@link #of(String, String, String, RunContext, SessionId)} — outbound request
 *       carrying the caller's {@code RunContext} and a session for the recipient</li>
 *   <li>{@link #reply} — creates a reply that preserves the original
 *       {@code correlationId}</li>
 * </ul>
 */
public record AgentMessage(
        String  messageId,
        String  senderId,
        String  recipientId,
        String  content,
        String  correlationId,
        Instant sentAt,
        RunContext runContext,
        SessionId sessionId,
        ScopeSet senderScopes,
        Optional<ExecutionContext> executionContext
) {

    public AgentMessage {
        Objects.requireNonNull(messageId,     "messageId must not be null");
        Objects.requireNonNull(senderId,      "senderId must not be null");
        Objects.requireNonNull(recipientId,   "recipientId must not be null");
        Objects.requireNonNull(content,       "content must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(sentAt,        "sentAt must not be null");
        if (content.isBlank()) throw new IllegalArgumentException("content must not be blank");
        runContext = Objects.requireNonNullElseGet(runContext, RunContext::empty);
        senderScopes = Objects.requireNonNullElse(senderScopes, ScopeSet.EMPTY);
        executionContext = Objects.requireNonNullElse(executionContext, Optional.empty());
    }

    /** Backwards-compatible constructor (ADR-033 Fase 2): {@code senderScopes} defaults to {@link ScopeSet#EMPTY}, no {@code executionContext}. */
    public AgentMessage(String messageId, String senderId, String recipientId, String content,
                        String correlationId, Instant sentAt, RunContext runContext, SessionId sessionId,
                        ScopeSet senderScopes) {
        this(messageId, senderId, recipientId, content, correlationId, sentAt, runContext, sessionId,
                senderScopes, Optional.empty());
    }

    /** Backwards-compatible constructor (ADR-033 Fase 2): {@code senderScopes} defaults to {@link ScopeSet#EMPTY}. */
    public AgentMessage(String messageId, String senderId, String recipientId, String content,
                        String correlationId, Instant sentAt, RunContext runContext, SessionId sessionId) {
        this(messageId, senderId, recipientId, content, correlationId, sentAt, runContext, sessionId, ScopeSet.EMPTY);
    }

    /** Returns a copy of this message with {@link #senderScopes} replaced (ADR-033 Fase 2). */
    public AgentMessage withSenderScopes(ScopeSet senderScopes) {
        return new AgentMessage(messageId, senderId, recipientId, content, correlationId, sentAt,
                runContext, sessionId, Objects.requireNonNull(senderScopes, "senderScopes must not be null"),
                executionContext);
    }

    /**
     * Returns a copy of this message carrying {@code ctx} (ADR-033 Fase 5) — the richer,
     * subject-aware replacement for {@link #withSenderScopes}. When both are set,
     * {@code LocalMessageBus} prefers this one.
     */
    public AgentMessage withExecutionContext(ExecutionContext ctx) {
        return new AgentMessage(messageId, senderId, recipientId, content, correlationId, sentAt,
                runContext, sessionId, senderScopes,
                Optional.ofNullable(ctx));
    }

    /**
     * Creates a new outbound request message with a fresh message id and
     * correlation id, carrying no caller state.
     *
     * @param senderId    the originating agent's id
     * @param recipientId the target agent's id
     * @param content     the payload
     * @return a ready-to-send {@code AgentMessage}
     */
    public static AgentMessage of(String senderId, String recipientId, String content) {
        return of(senderId, recipientId, content, RunContext.empty());
    }

    /**
     * Creates a new outbound request message carrying the caller's {@link RunContext}
     * so it survives the delegation hop and reaches the recipient agent's task —
     * see the record-level notes.
     *
     * @param senderId    the originating agent's id
     * @param recipientId the target agent's id
     * @param content     the payload
     * @param runContext  the caller's flow state to propagate
     * @return a ready-to-send {@code AgentMessage}
     */
    public static AgentMessage of(String senderId, String recipientId, String content,
                                  RunContext runContext) {
        return of(senderId, recipientId, content, runContext, null);
    }

    /**
     * Creates a new outbound request message carrying the caller's {@link RunContext}
     * and a {@link SessionId} for the recipient to run under.
     *
     * @param senderId    the originating agent's id
     * @param recipientId the target agent's id
     * @param content     the payload
     * @param runContext  the caller's flow state to propagate
     * @param sessionId   the session the recipient should run under; {@code null} means
     *                    the recipient falls back to an ephemeral session
     * @return a ready-to-send {@code AgentMessage}
     */
    public static AgentMessage of(String senderId, String recipientId, String content,
                                  RunContext runContext, SessionId sessionId) {
        return new AgentMessage(
                UUID.randomUUID().toString(),
                senderId,
                recipientId,
                content,
                UUID.randomUUID().toString(),
                Instant.now(),
                runContext,
                sessionId
        );
    }

    /**
     * Creates a reply that preserves the original message's {@code correlationId}.
     *
     * @param original  the message being replied to
     * @param senderId  the replying agent's id (typically the original recipient)
     * @param content   the reply payload
     * @return a reply {@code AgentMessage}
     */
    public static AgentMessage reply(AgentMessage original, String senderId, String content) {
        return new AgentMessage(
                UUID.randomUUID().toString(),
                senderId,
                original.senderId(),
                content,
                original.correlationId(),
                Instant.now(),
                RunContext.empty(),
                null
        );
    }
}
