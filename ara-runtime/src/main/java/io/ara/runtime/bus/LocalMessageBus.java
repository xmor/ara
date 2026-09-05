package io.ara.runtime.bus;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.SessionId;
import io.ara.core.auth.ExecutionContext;
import io.ara.core.auth.ScopeSet;
import io.ara.core.bus.AgentMessage;
import io.ara.core.bus.MessageBus;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.runtime.agent.AgentRegistry;
import io.ara.runtime.agent.SessionScoped;
import io.ara.runtime.auth.ScopeVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * In-process {@link MessageBus} implementation for single-node deployments.
 *
 * <h2>Routing</h2>
 * <p>All routing goes through {@link AgentRegistry}. A message to agent {@code B}
 * results in a direct call to {@code B.execute(AgentTask)} — no serialisation,
 * no network hop. The {@code correlationId} carried by the {@link AgentMessage}
 * is forwarded into the {@link AgentTask} so the full delegation chain is visible
 * in logs.
 *
 * <h2>Threading</h2>
 * <ul>
 *   <li>{@link #send} dispatches the execution on a new virtual thread and
 *       returns immediately to the caller.</li>
 *   <li>{@link #request} runs the execution on a virtual thread via
 *       {@link CompletableFuture} and blocks the calling thread until the reply
 *       arrives or the timeout expires. Because the calling thread is itself
 *       a virtual thread (inside a ReAct tool call), this blocking is cheap.</li>
 * </ul>
 *
 * <p>Both dispatch points wrap the spawned work with {@link AraTelemetry#propagate}
 * so a delegated agent's spans still nest under the caller's — tracing context is
 * thread-local and does not otherwise survive the hop onto the new virtual thread.
 *
 * <h2>Cycles</h2>
 * <p>Cyclic delegations (A → B → A) will deadlock if agent A is still in
 * {@code EXECUTING} state when the cycle closes, because {@code execute()} throws
 * {@code IllegalStateException} on a non-IDLE agent.  The deadlock is therefore
 * surfaced immediately as an error rather than silently hanging.
 */
public final class LocalMessageBus implements MessageBus {

    private static final Logger log = LoggerFactory.getLogger(LocalMessageBus.class);

    private final AgentRegistry registry;
    private final AraTelemetry  telemetry;
    private final ApprovalGate  approvalGate;
    private final io.ara.runtime.auth.TemporaryScopeRegistry temporaryScopeRegistry;

    public LocalMessageBus(AgentRegistry registry) {
        this(registry, AraTelemetry.noop());
    }

    public LocalMessageBus(AgentRegistry registry, AraTelemetry telemetry) {
        this(registry, telemetry, null);
    }

    /**
     * @param approvalGate ADR-033 Fase 7 (S4) — checked via {@link ScopeVerifier#checkApproved}
     *                     after the scope check on every dispatch, for a recipient with
     *                     {@code config().requiresApproval() == true}. {@code null} (both
     *                     shorter constructors) keeps today's behavior: no invocation ever
     *                     pauses for human approval, regardless of any agent's flag.
     */
    public LocalMessageBus(AgentRegistry registry, AraTelemetry telemetry, ApprovalGate approvalGate) {
        this(registry, telemetry, approvalGate, null);
    }

    /**
     * @param temporaryScopeRegistry ADR-033 Fase 8 (S5) — the sender's effective scopes for
     *                               every dispatch are widened by {@code
     *                               temporaryScopeRegistry.effectiveTemporaryScopes(message
     *                               .senderId())} before either check runs. {@code null}
     *                               (every shorter constructor) contributes {@link
     *                               ScopeSet#EMPTY} — today's behavior, unchanged.
     */
    public LocalMessageBus(AgentRegistry registry, AraTelemetry telemetry, ApprovalGate approvalGate,
                            io.ara.runtime.auth.TemporaryScopeRegistry temporaryScopeRegistry) {
        this.registry     = Objects.requireNonNull(registry,  "registry must not be null");
        this.telemetry    = Objects.requireNonNull(telemetry, "telemetry must not be null");
        this.approvalGate = approvalGate;
        this.temporaryScopeRegistry = temporaryScopeRegistry;
    }

    // ── MessageBus ────────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Delivery failures (recipient not found, recipient busy) are logged at
     * WARN level and do not propagate to the caller.
     */
    @Override
    public void send(AgentMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        Thread.ofVirtual().start(telemetry.propagate(() -> {
            try {
                deliverAndDiscard(message);
            } catch (Exception e) {
                log.warn("[Bus] Fire-and-forget delivery failed: msg={} to={} reason={}",
                        message.messageId(), message.recipientId(), e.getMessage());
            }
        }));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes on a virtual thread and parks the caller until the reply
     * is ready or the timeout expires.
     */
    @Override
    public AgentMessage request(AgentMessage message, Duration timeout) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        AraAgent recipient = resolve(message.recipientId());
        ExecutionContext ctx = resolveExecutionContext(message, recipient);
        ScopeSet effectiveScopes = resolveEffectiveScopes(message, ctx);
        ScopeVerifier.checkAuthorized(recipient, effectiveScopes);
        ScopeVerifier.checkApproved(recipient, approvalGate, message.senderId(), effectiveScopes);

        log.debug("[Bus] Request from={} to={} correlation={}",
                message.senderId(), message.recipientId(), message.correlationId());

        // A request() caller may give up on this call (timeout or its own interruption)
        // before the recipient finishes — see the catch blocks below, which need a
        // SessionId to actually stop it. message.sessionId() is only set when the
        // delegating caller itself runs inside a real session; fall back to a fresh
        // ephemeral one here so cancellation always has a target, not just delegations
        // that happen to carry a named session.
        SessionId sessionId = message.sessionId() != null ? message.sessionId() : SessionId.ephemeral();
        AgentTask task = toTask(message, sessionId, ctx);

        CompletableFuture<AgentResponse> future = CompletableFuture.supplyAsync(
                () -> recipient.execute(task),
                r -> Thread.ofVirtual().start(telemetry.propagate(r))
        );

        AgentResponse response;
        try {
            response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // future.cancel(true) alone does nothing here: CompletableFuture.cancel()'s
            // mayInterruptIfRunning has no effect for a task submitted via supplyAsync —
            // the JDK does not use interrupts to control it (this is documented behavior,
            // not an oversight). Without an explicit terminate() call the recipient kept
            // running to completion (or its own executionTimeout, up to several more
            // minutes) with nobody left waiting on the result — spending tokens and
            // holding its session for a caller that already gave up.
            future.cancel(true);
            terminateIfScoped(recipient, sessionId);
            throw new RuntimeException(
                    "Request to agent [%s] timed out after %s (correlation=%s)"
                            .formatted(message.recipientId(), timeout, message.correlationId()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Same reasoning as the timeout branch: whatever interrupted us — e.g. our own
            // caller's session being terminated — should cascade to the delegate instead
            // of leaving it running unobserved.
            future.cancel(true);
            terminateIfScoped(recipient, sessionId);
            throw new RuntimeException("Request interrupted", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException(
                    "Request to agent [%s] failed: %s".formatted(message.recipientId(), cause.getMessage()),
                    cause);
        }

        log.debug("[Bus] Reply from={} correlation={} success={}",
                message.recipientId(), message.correlationId(), response.isSuccess());

        return AgentMessage.reply(message, message.recipientId(), response.content());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void deliverAndDiscard(AgentMessage message) {
        AraAgent recipient = resolve(message.recipientId());
        ExecutionContext ctx = resolveExecutionContext(message, recipient);
        ScopeSet effectiveScopes = resolveEffectiveScopes(message, ctx);
        ScopeVerifier.checkAuthorized(recipient, effectiveScopes);
        ScopeVerifier.checkApproved(recipient, approvalGate, message.senderId(), effectiveScopes);
        // Fire-and-forget: nobody waits on this call, so there is nothing to cancel and
        // no need to force a SessionId — message.sessionId() (possibly null) is enough.
        AgentTask task = toTask(message, message.sessionId(), ctx);
        AgentResponse response = recipient.execute(task);
        log.debug("[Bus] Fire-and-forget delivered: to={} success={}",
                message.recipientId(), response.isSuccess());
    }

    /**
     * ADR-033 Fase 5: attenuates the incoming {@link ExecutionContext} — when the message
     * carries one — down to what {@code recipient} itself is granted, exactly the way
     * {@code AgentDelegationTool} already narrows a bare {@link ScopeSet} (ADR-0077 D2/D3),
     * but carrying the subject identity along instead of just a {@code ScopeSet}. {@code
     * null} means "no {@code ExecutionContext} on this message" — every message built
     * before this field existed, and every message a caller still builds with only {@link
     * AgentMessage#withSenderScopes} — and is the signal for both call sites to fall back
     * to {@link AgentMessage#senderScopes()} exactly as they did before this method existed.
     */
    private static ExecutionContext resolveExecutionContext(AgentMessage message, AraAgent recipient) {
        return message.executionContext()
                .map(incoming -> incoming.delegate(
                        recipient.agentId().value(),
                        ScopeSet.of(recipient.config().grantedScopes())))
                .orElse(null);
    }

    /**
     * ADR-033 Fase 8 (S5): the sender's base effective scopes (from {@code ctx} if present,
     * else {@link AgentMessage#senderScopes()} — same fallback as Fase 5), widened by any
     * currently-valid temporary grant on {@link AgentMessage#senderId()}. {@link
     * ScopeSet#union}, never {@code intersect}: a temporary grant only ever adds authority
     * for its own limited time/use-count, it can never take any away. A {@code null}
     * {@link #temporaryScopeRegistry} (every constructor before Fase 8) contributes nothing
     * — identical to today's behavior.
     */
    private ScopeSet resolveEffectiveScopes(AgentMessage message, ExecutionContext ctx) {
        ScopeSet base = ctx != null ? ctx.effectiveScopes() : message.senderScopes();
        if (temporaryScopeRegistry == null) {
            return base;
        }
        return base.union(temporaryScopeRegistry.effectiveTemporaryScopes(message.senderId()));
    }

    /**
     * @param sessionId the session the recipient runs under — pass {@code
     *                   message.sessionId()} directly when there is nothing to cancel
     *                   ({@link #deliverAndDiscard}), or a guaranteed non-null id when the
     *                   caller needs to be able to target this execution later ({@link
     *                   #request}).
     * @param ctx        this hop's {@link ExecutionContext} as resolved by {@link
     *                   #resolveExecutionContext}; {@code null} when the message carried
     *                   none — nothing new is written to the task's {@code RunContext} in
     *                   that case, matching pre-Fase-5 behavior exactly.
     */
    private static AgentTask toTask(AgentMessage message, SessionId sessionId, ExecutionContext ctx) {
        // Seed the recipient's task with the caller's RunContext by reference (ADR-041
        // rev. 2) — not rebuilt from copied maps — so a delegated sub-task runs with the
        // same flow state and authorization as its caller instead of starting empty.
        RunContext runContext = message.runContext();
        if (ctx != null) {
            // So a recipient that itself delegates further (AgentDelegationTool) can read
            // its own current ExecutionContext — subject identity included — the same way
            // it already reads RunContext.SCOPES_KEY, instead of the chain going subject-
            // blind again the moment it crosses a bus hop.
            runContext = runContext.withOpaque(RunContext.EXECUTION_CONTEXT_KEY, ctx);
        }
        return AgentTask.of(message.content(), java.util.Map.of(), message.correlationId(), message.senderId())
                .withRunContext(runContext)
                .withSessionId(sessionId);
    }

    /**
     * Cancels {@code sessionId} on {@code recipient} if it manages per-session lifecycle
     * at all — a recipient with no session concept simply doesn't implement {@link
     * SessionScoped}, and there is nothing to cancel on it.
     */
    private static void terminateIfScoped(AraAgent recipient, SessionId sessionId) {
        if (recipient instanceof SessionScoped scoped) {
            scoped.terminate(sessionId);
        }
    }

    private AraAgent resolve(String recipientId) {
        Optional<AraAgent> found = registry.findById(AgentId.of(recipientId));
        if (found.isEmpty()) {
            throw new NoSuchElementException("Agent [" + recipientId + "] is not registered on this node");
        }
        return found.get();
    }
}