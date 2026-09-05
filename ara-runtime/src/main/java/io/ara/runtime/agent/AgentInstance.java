package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentExecutionContext;
import io.ara.core.agent.AgentInterceptor;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.ConversationTurn;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.agent.RunState;
import io.ara.core.agent.SessionBusyPolicy;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;
import io.ara.core.agent.UserId;
import io.ara.core.common.AgentId;
import io.ara.core.common.Money;
import io.ara.core.media.MediaRef;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmRouter;
import io.ara.core.memory.MemoryManager;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.core.telemetry.Span;
import io.ara.core.telemetry.SpanStatus;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.interceptor.AgentInterceptorChain;
import io.ara.runtime.interceptor.InterceptingLlmClient;
import io.ara.runtime.interceptor.InterceptingToolRegistry;
import io.ara.runtime.strategy.ExecutionPlanner;
import io.ara.runtime.wiring.AgentWiring;
import io.ara.runtime.wiring.Versioned;
import io.ara.runtime.wiring.WiringFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The concrete, executable unit of intelligence in the ARA platform.
 *
 * <p>{@code AgentInstance} implements {@link AraAgent} and orchestrates the full
 * execution lifecycle for a single task. State machine and working memory are
 * per-session (ADR-016): concurrent clients on the same agent use isolated
 * {@link AgentSession}s and never interfere.
 *
 * <p>Since ADR-039, the agent's config is a {@link Versioned} pointer, hot-swappable
 * via {@link #reconfigure(AgentConfig)}: each session pins its own {@link AgentWiring}
 * (config + resolved LLM client + tool registry) at creation time and keeps it for its
 * entire lifetime — a task already in flight always finishes with the config it started
 * with; only sessions created after a {@code reconfigure} observe the new one.
 *
 * <p>Instances are created exclusively by {@link io.ara.runtime.factory.AgentFactory} and are stateless
 * with respect to execution. All fields are final or thread-safe.
 *
 * <p><strong>No annotations are used.</strong> All dependencies are injected
 * explicitly via the constructor.
 */
public final class AgentInstance implements AraAgent, SessionHistoryAware, RunStateAware, UserMemoryAware,
                                            Reconfigurable, SessionScoped {

    private static final Logger log = LoggerFactory.getLogger(AgentInstance.class);

    /** Reserved context key for contract-enforced system prompt override. */
    public static final String CTX_SYSTEM_PROMPT = "__ara_system_prompt__";

    /** Upper bound on entries pulled back per turn by {@link MemoryManager#recallRelevant} (ADR-0086). */
    private static final int RECALL_MAX_RESULTS = 3;

    private final Versioned<AgentConfig> versionedConfig;
    /** Permanent shutdown flag (set by {@link #terminate()} / destroy). Poisons the whole agent. */
    private final AtomicBoolean closed;

    private final ExecutionPlanner executionPlanner;
    private final AgentInterceptorChain interceptorChain;
    private final SessionManager sessionManager;
    private final AraTelemetry telemetry;
    /** Backs cross-session {@code userMemory} (ADR-043 rev. 3) — resolved fresh per {@code execute()}, not cached on any {@link AgentSession}, since it must outlive any single one. */
    private final SessionStore sessionStore;

    /**
     * Creates a new {@code AgentInstance} with static (non-hot-swappable) wiring.
     *
     * @param memoryFactory factory producing a fresh {@link MemoryManager} per session
     * @deprecated retained for direct-construction call sites (tests, examples) that
     *             predate ADR-039's per-session wiring/lease model. Every session built
     *             through this constructor shares the same {@code llmClient}/{@code
     *             toolRegistry} with no lease, no registry, no dedup — equivalent to the
     *             pre-ADR-039 behavior. {@link io.ara.runtime.factory.AgentFactory} uses the {@link
     *             WiringFactory}-based constructor instead.
     */
    @Deprecated(forRemoval = false)
    public AgentInstance(
            AgentConfig config,
            LlmClient llmClient,
            Function<SessionId, MemoryManager> memoryFactory,
            ToolRegistry toolRegistry,
            ExecutionPlanner executionPlanner,
            AgentInterceptorChain interceptorChain
    ) {
        this(config, legacyWiring(llmClient, null, toolRegistry), memoryFactory,
                executionPlanner, interceptorChain, AraTelemetry.noop(), SessionStore.noop());
    }

    /**
     * Constructor used by {@link io.ara.runtime.factory.AgentFactory} when a {@link LlmRouter} is available.
     * @deprecated see {@link #AgentInstance(AgentConfig, LlmClient, Function, ToolRegistry, ExecutionPlanner, AgentInterceptorChain)}.
     */
    @Deprecated(forRemoval = false)
    public AgentInstance(
            AgentConfig config,
            LlmClient llmClient,
            LlmRouter llmRouter,
            Function<SessionId, MemoryManager> memoryFactory,
            ToolRegistry toolRegistry,
            ExecutionPlanner executionPlanner,
            AgentInterceptorChain interceptorChain
    ) {
        this(config, legacyWiring(llmClient, llmRouter, toolRegistry), memoryFactory,
                executionPlanner, interceptorChain, AraTelemetry.noop(), SessionStore.noop());
    }

    /**
     * Constructor used by {@link io.ara.runtime.factory.AgentFactory} with an explicit {@link AraTelemetry}.
     * @deprecated see {@link #AgentInstance(AgentConfig, LlmClient, Function, ToolRegistry, ExecutionPlanner, AgentInterceptorChain)}.
     */
    @Deprecated(forRemoval = false)
    public AgentInstance(
            AgentConfig config,
            LlmClient llmClient,
            LlmRouter llmRouter,
            Function<SessionId, MemoryManager> memoryFactory,
            ToolRegistry toolRegistry,
            ExecutionPlanner executionPlanner,
            AgentInterceptorChain interceptorChain,
            AraTelemetry telemetry
    ) {
        this(config, legacyWiring(llmClient, llmRouter, toolRegistry), memoryFactory,
                executionPlanner, interceptorChain, telemetry, SessionStore.noop());
    }

    /**
     * Canonical constructor (ADR-039): each session's {@link AgentWiring} — resolved LLM
     * client, tool registry, and any leases they hold — is built by {@code wiringFactory}
     * from whatever {@link AgentConfig} is current when the session is created.
     */
    public AgentInstance(
            AgentConfig config,
            WiringFactory wiringFactory,
            Function<SessionId, MemoryManager> memoryFactory,
            ExecutionPlanner executionPlanner,
            AgentInterceptorChain interceptorChain,
            AraTelemetry telemetry,
            SessionStore sessionStore
    ) {
        this.versionedConfig   = new Versioned<>(Objects.requireNonNull(config, "config must not be null"));
        this.executionPlanner  = Objects.requireNonNull(executionPlanner, "executionPlanner must not be null");
        this.interceptorChain  = Objects.requireNonNull(interceptorChain, "interceptorChain must not be null");
        this.telemetry         = Objects.requireNonNull(telemetry,        "telemetry must not be null");
        Objects.requireNonNull(wiringFactory, "wiringFactory must not be null");
        Objects.requireNonNull(memoryFactory, "memoryFactory must not be null");
        this.sessionStore   = Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        // Idle TTL comes from the config the agent is born with. Not re-read on
        // reconfigure(): the sweeper is agent-level infrastructure, outside the
        // per-session wiring that ADR-039 hot-swaps.
        this.sessionManager = new SessionManager(memoryFactory, wiringFactory, sessionStore, config.sessionTtl());
        this.closed         = new AtomicBoolean(false);
    }

    /**
     * Builds a {@link WiringFactory} that ignores config changes and always returns the
     * same static {@code llmClient}/{@code toolRegistry} with no leases — the behavior of
     * every deprecated constructor above, preserved unchanged.
     */
    private static WiringFactory legacyWiring(LlmClient llmClient, LlmRouter llmRouter, ToolRegistry toolRegistry) {
        if (llmClient == null && llmRouter == null) {
            throw new IllegalArgumentException("Either llmClient or llmRouter must be provided");
        }
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        return config -> {
            LlmClient effectiveLlm = llmRouter != null
                    ? llmRouter.select(config.llm(), LlmCallContext.from(config))
                    : llmClient;
            return new AgentWiring(config, effectiveLlm, toolRegistry, List.of());
        };
    }

    // ── AraAgent API ──────────────────────────────────────────────────────────

    @Override
    public AgentId agentId() {
        return versionedConfig.current().agentId();
    }

    @Override
    public AgentConfig config() {
        return versionedConfig.current();
    }

    /**
     * Applies {@code newConfig} in place (ADR-039): swaps the current-config pointer
     * atomically. Sessions already alive keep the {@link AgentWiring} they were pinned
     * with; only sessions created after this call see {@code newConfig}.
     */
    @Override
    public void reconfigure(AgentConfig newConfig) {
        Objects.requireNonNull(newConfig, "newConfig must not be null");
        AgentId currentId = versionedConfig.current().agentId();
        if (!newConfig.agentId().equals(currentId)) {
            throw new IllegalArgumentException("reconfigure cannot change agentId: "
                    + currentId.value() + " -> " + newConfig.agentId().value());
        }
        versionedConfig.swap(ignored -> newConfig);
        log.info("Agent [{}] reconfigured — new sessions will use the updated config", currentId.value());
    }

    /**
     * Returns {@link AgentState#IDLE} as a safe default. State is per-session (ADR-016);
     * this method reflects the agent definition level, not a specific session.
     */
    @Override
    public AgentState currentState() {
        return AgentState.IDLE;
    }

    @Override
    public AgentResponse execute(AgentTask task) {
        Objects.requireNonNull(task, "task must not be null");

        if (closed.get()) {
            return AgentResponse.failure(task.taskId(), agentId(),
                    "Agent terminated", Duration.ZERO);
        }

        SessionId sessionId = task.sessionId() != null
                ? task.sessionId()
                : SessionId.ephemeral();

        // Read the versioned config exactly once. A brand-new session pins its wiring
        // from this single snapshot; an already-live session ignores it entirely and
        // keeps the wiring it was born with — either way the whole task below reads
        // from one snapshot, never a torn mix of old/new (ADR-039 "lettura coerente").
        AgentSession session = sessionManager.getOrCreate(sessionId, versionedConfig.current());

        // Bind this session's shared RunState onto the task, but only if it doesn't
        // already carry one: a fresh top-level call should see its session's
        // accumulated state, but a task that arrived via delegate_task already has
        // SHARED/OVERLAY/ISOLATED state attached by AgentDelegationTool and must not
        // have it clobbered here.
        AgentTask effectiveTask = task.runContext().state() == RunState.noop()
                ? task.withRunContext(task.runContext().withState(session.runState()))
                : task;

        // Bind cross-session userMemory (ADR-043 rev. 3), resolved fresh from
        // sessionStore rather than cached on AgentSession: unlike session-scoped state,
        // userMemory must outlive any single session, so there is no per-session object
        // to cache it on. Same noop()-guard as state, above.
        if (effectiveTask.userId() != null && effectiveTask.runContext().userMemory() == RunState.noop()) {
            RunState userMemory = RunState.persisting(sessionStore, userMemoryKey(effectiveTask.userId()));
            effectiveTask = effectiveTask.withRunContext(effectiveTask.runContext().withUserMemory(userMemory));
        }

        AgentConfig sessionConfig = session.wiring().config();
        String effectiveSystemPrompt = effectiveTask.runContext().promptVar(CTX_SYSTEM_PROMPT, sessionConfig.systemPrompt());

        return executeUnderSessionLock(effectiveTask, session, sessionConfig, effectiveSystemPrompt);
    }

    private AgentResponse executeUnderSessionLock(AgentTask task, AgentSession session,
                                                    AgentConfig sessionConfig, String effectiveSystemPrompt) {
        // Per-session concurrency policy (ADR-016). Different sessions never contend
        // here because each has its own lock; only same-session tasks do.
        ReentrantLock lock = session.executionLock();
        if (sessionConfig.sessionBusyPolicy() == SessionBusyPolicy.ENQUEUE) {
            try {
                // Interruptible on purpose: an uninterruptible lock() leaves a caller with
                // no way out if the in-flight task wedges — not even executor shutdown or
                // Future.cancel(true) can unpark it, so the queue grows without bound.
                lock.lockInterruptibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();   // preserve the flag for our caller
                return AgentResponse.failure(task.taskId(), agentId(),
                        "Interrupted while queued for session '" + session.sessionId().value() + "'",
                        Duration.ZERO);
            }
        } else if (!lock.tryLock()) {          // REJECT: fail fast when the session is busy
            return sessionBusyFailure(task.taskId(), session);
        }
        try {
            // Cancellation requested while queued (ENQUEUE) or just before start → skip.
            if (session.isCancelRequested()) {
                session.clearCancel();
                return handleEarlyTermination(task.taskId(), session, Instant.now());
            }
            session.setRunningThread(Thread.currentThread());
            try {
                return executeWithTelemetry(task, session, effectiveSystemPrompt);
            } finally {
                session.setRunningThread(null);
                session.clearCancel();
                Thread.interrupted();   // clear any interrupt from a cancel; keep the thread reusable
            }
        } finally {
            lock.unlock();
        }
    }

    private AgentResponse executeWithTelemetry(AgentTask task, AgentSession session, String effectiveSystemPrompt) {
        AgentConfig config = session.wiring().config();
        Span span = telemetry.spanBuilder("agent.execute")
                .setAttribute("agent.id", agentId().value())
                .setAttribute("agent.type", config.agentType())
                .setAttribute("session.id", session.sessionId().value())
                .setAttribute("task.id", task.taskId())
                .startSpan();
        try (var scope = span.makeCurrent()) {
            AgentResponse response = executeTask(task, session, effectiveSystemPrompt);
            span.setAttribute("agent.success", response.isSuccess())
                .setAttribute("agent.iterations", (long) response.iterationsUsed())
                .setAttribute("agent.tokens_total", (long) response.totalTokens())
                .setStatus(response.isSuccess() ? SpanStatus.OK : SpanStatus.ERROR);
            if (!response.isSuccess()) {
                span.setAttribute("agent.failure_kind", FailureKind.classify(response.failureReason()).name());
            }
            return response;
        } catch (RuntimeException e) {
            span.recordException(e).setStatus(SpanStatus.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }

    private AgentResponse executeTask(AgentTask task, AgentSession session, String effectiveSystemPrompt) {
        Instant startedAt = Instant.now();
        AgentConfig config = session.wiring().config();
        log.info("Agent [{}] starting task [{}] session [{}]",
                agentId().value(), task.taskId(), session.sessionId().value());

        try {
            session.stateMachine().requireState(AgentState.IDLE);
        } catch (IllegalStateException e) {
            // The execution lock already serialises same-session tasks, so reaching here
            // means the state machine was moved from outside the lock (e.g. a concurrent
            // terminate). Logged rather than swallowed: the cause is the only signal.
            log.debug("Agent [{}] session [{}] not IDLE at task start",
                    agentId().value(), session.sessionId().value(), e);
            return sessionBusyFailure(task.taskId(), session);
        }

        session.stateMachine().transitionTo(AgentState.PLANNING);
        AgentExecutionContext planCtx = buildContext(task.taskId(), session, 0, 0);
        interceptorChain.before(planCtx, "Planning");

        ExecutionStrategy strategy = executionPlanner.select(config);
        log.debug("Agent [{}] selected strategy [{}]", agentId().value(), strategy.strategyName());

        interceptorChain.after(planCtx, "Planning", strategy.strategyName());

        session.stateMachine().transitionTo(AgentState.EXECUTING);

        // Declared outside the try so the actual per-call client (resolved once when the
        // session's wiring was built) is available to resolvedLlmProviderId() even on the
        // success/failure paths below.
        LlmClient effectiveLlm = null;
        try {
            if (closed.get() || session.isCancelRequested()) {
                return handleEarlyTermination(task.taskId(), session, startedAt);
            }

            AgentExecutionContext execCtx = buildContext(task.taskId(), session, 0, 0);
            interceptorChain.before(execCtx, "Executing");

            MemoryManager memoryManager = session.memoryManager();
            seedWorkingMemory(memoryManager, config, session, effectiveSystemPrompt, task);

            effectiveLlm = session.wiring().llm();
            ToolRegistry toolRegistry = session.wiring().toolRegistry();
            // One supplier shared by both decorators instead of two identical lambdas:
            // it is invoked on every LLM call and every tool dispatch, so it belongs to
            // the hot path.
            Supplier<AgentExecutionContext> contextSupplier =
                    () -> buildContext(task.taskId(), session, 0, 0);
            // Wrapped, not the raw session-pinned client/registry: gives interceptors
            // per-iteration Think/ToolCall visibility (see InterceptingLlmClient /
            // InterceptingToolRegistry) without any strategy needing to know about the
            // interceptor chain — same pattern as the OTel decorators in AraRuntime.
            LlmClient interceptedLlm = new InterceptingLlmClient(
                    effectiveLlm, interceptorChain, contextSupplier);
            ToolRegistry interceptedTools = new InterceptingToolRegistry(
                    toolRegistry, interceptorChain, contextSupplier);
            ExecutionResult result = strategy.execute(task, interceptedLlm, memoryManager, interceptedTools, config);

            Duration elapsed = Duration.between(startedAt, Instant.now());

            if (!result.isSuccess()) {
                return handleFailure(task.taskId(), session, result, elapsed, effectiveLlm);
            } else {
                // Record the turn before resetting working memory
                if (config.maxConversationTurns() > 0) {
                    session.conversationHistory().addTurn(task.input(), result.output(), task.media());
                }
                return handleSuccess(task, session, result, elapsed, effectiveLlm);
            }

        } catch (ExecutionTimeoutException e) {
            log.warn("Agent [{}] timed out after {}s for task [{}]",
                    agentId().value(), e.timeout().toSeconds(), task.taskId());
            Duration elapsed = Duration.between(startedAt, Instant.now());
            AgentExecutionContext timeoutCtx = buildContext(task.taskId(), session, 0, 0);
            interceptorChain.onTimeout(timeoutCtx, "Executing", e.timeout());
            resetSessionAfterFailure(session);
            return AgentResponse.failure(task.taskId(), agentId(), e.getMessage(), elapsed);
        } catch (Exception e) {
            log.error("Agent [{}] threw an unexpected exception during task [{}]",
                    agentId().value(), task.taskId(), e);
            Duration elapsed = Duration.between(startedAt, Instant.now());
            return handleUnexpectedError(task.taskId(), session, e, elapsed);
        }
    }

    /**
     * Seeds working memory for a fresh execution: system prompt, then up to
     * {@code config.maxConversationTurns()} replayed turns for multi-turn continuity,
     * then the new task input — with this task's attachments, and only this task's.
     *
     * <p>Replayed turns are text: their attachments are named, not re-sent. Re-attaching them
     * would mean paying for every document again on every subsequent turn of the session, for
     * a model that has already been shown it and already answered about it. Naming them keeps
     * the history readable — a turn that was about a contract does not come back as a turn
     * about nothing — while the reference stays in {@code ConversationTurn} for anyone who
     * needs to fetch the file again.
     */
    private void seedWorkingMemory(MemoryManager memoryManager, AgentConfig config,
                                    AgentSession session, String effectiveSystemPrompt, AgentTask task) {
        memoryManager.appendToWorkingMemory("system", effectiveSystemPrompt);
        int maxTurns = config.maxConversationTurns();
        if (maxTurns > 0) {
            for (var turn : session.conversationHistory().recentTurns(maxTurns)) {
                memoryManager.appendToWorkingMemory("user",      replayText(turn));
                memoryManager.appendToWorkingMemory("assistant", turn.assistantOutput());
            }
        }
        memoryManager.appendToWorkingMemory("user", task.input(), task.media());
        // ADR-0086: pull back whatever this agent's offload tier holds that is relevant to
        // this task — a no-op for a manager with no offload tier (the default). A small,
        // fixed cap: this is a floor under the task's own context, not a second retrieval
        // budget for the caller to tune.
        memoryManager.recallRelevant(task.input(), RECALL_MAX_RESULTS);
    }

    /**
     * The text of a replayed turn: what the user said, plus a note naming whatever they
     * attached. A media-only turn would otherwise replay as an empty user message, which
     * loses the fact that the exchange happened at all.
     */
    private static String replayText(ConversationTurn turn) {
        if (turn.media().isEmpty()) return turn.userInput();
        String names = turn.media().stream()
                .map(MediaRef::name)
                .collect(java.util.stream.Collectors.joining(", "));
        String note = "[attached earlier in this conversation: " + names + "]";
        return turn.userInput().isBlank() ? note : turn.userInput() + "\n" + note;
    }

    @Override
    public void terminate() {
        if (!closed.compareAndSet(false, true)) return;   // idempotent
        log.info("Agent [{}] termination requested", agentId().value());
        // Cancel any in-flight task across all sessions, then release session resources
        // (including each session's AgentWiring leases — see SessionManager.shutdown()).
        for (AgentSession s : sessionManager.activeSessions()) {
            s.requestCancel();
        }
        sessionManager.shutdown();
    }

    /**
     * Cancels the in-flight task on a single session without terminating the agent.
     * Other sessions — and future tasks on this same session — keep working (ADR-016).
     */
    @Override
    public void terminate(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        sessionManager.find(sessionId).ifPresentOrElse(
                s -> {
                    log.info("Agent [{}] cancelling session [{}]", agentId().value(), sessionId.value());
                    s.requestCancel();
                },
                () -> log.debug("Agent [{}] terminate(session): no active session [{}]",
                        agentId().value(), sessionId.value()));
    }

    /** Returns a snapshot of all active sessions for this agent. */
    public List<Map<String, Object>> activeSessions() {
        return sessionManager.listActive();
    }

    /**
     * Cancels the in-flight task on every session of this agent, keeping the agent alive
     * (ADR-0069 D4). Same loop as {@link #terminate()} without {@link SessionManager#shutdown()}.
     */
    @Override
    public void cancelAllSessions() {
        for (AgentSession s : sessionManager.activeSessions()) {
            s.requestCancel();
        }
    }

    /**
     * Returns how many sessions are currently held open for this agent (ADR-016) — O(1),
     * with no intermediate collection. Neither {@link #activeSessions()} (which builds a
     * {@code Map} per session: turn count, last-accessed time, TTL remaining) nor {@code
     * sessionManager.activeSessions()} (which materialises a {@code List} of every live
     * session) is used, since a caller after the count alone would throw all of it away.
     */
    @Override
    public int activeSessionCount() {
        return sessionManager.activeSessionCount();
    }

    /** Invalidates (removes) the session with the given id. */
    @Override
    public void invalidateSession(SessionId sessionId) {
        sessionManager.invalidate(sessionId);
    }

    /**
     * Returns the recorded conversation turns for {@code sessionId}, oldest-first — empty if
     * no session with that id currently exists (never used, or already evicted by the TTL
     * sweeper). Read-only: unlike {@link #execute}, this never creates a session as a side
     * effect, so calling it for an unknown id does not spawn an empty one.
     */
    @Override
    public List<ConversationTurn> conversationHistory(SessionId sessionId) {
        return sessionManager.find(sessionId)
                .map(AgentSession::conversationHistory)
                .map(ConversationHistory::allTurns)
                .orElseGet(List::of);
    }

    /**
     * Returns a snapshot of {@code sessionId}'s shared {@link RunState} — empty if no
     * session with that id currently exists. Read-only, same contract as {@link
     * #conversationHistory(SessionId)}: never creates a session as a side effect.
     */
    @Override
    public Map<String, Object> sessionState(SessionId sessionId) {
        return sessionManager.find(sessionId)
                .map(AgentSession::runState)
                .map(RunState::snapshot)
                .orElseGet(Map::of);
    }

    /**
     * Returns a snapshot of {@code userId}'s cross-session memory, or an empty map if
     * nothing has ever been written for it. Read-only, and — unlike {@link
     * #sessionState}/{@link #conversationHistory} — has no in-process session to look up:
     * it reads straight from {@link #sessionStore} under {@code userId}'s derived key.
     */
    @Override
    public Map<String, Object> userMemory(UserId userId) {
        return sessionStore.loadState(userMemoryKey(userId));
    }

    /**
     * Derives the {@link SessionId} under which {@code userId}'s cross-session memory is
     * stored in {@link #sessionStore} — reusing {@code SessionStore}/{@code
     * RunState.persisting} rather than a dedicated store, same technique as {@code
     * AgentDelegationTool.delegateSessionId} (ADR-043 rev. 2).
     */
    private static SessionId userMemoryKey(UserId userId) {
        return SessionId.of("user::" + userId.value());
    }

    /** Shuts down the session manager, releasing all session resources. */
    public void shutdown() {
        sessionManager.shutdown();
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private AgentResponse handleSuccess(AgentTask task, AgentSession session,
                                         ExecutionResult result, Duration elapsed, LlmClient usedLlm) {
        String taskId = task.taskId();
        AgentConfig config = session.wiring().config();
        session.stateMachine().transitionTo(AgentState.DONE);
        AgentExecutionContext doneCtx = buildContext(taskId, session, result.iterationsDone(), result.tokensUsed());
        interceptorChain.after(doneCtx, "Executing", result.output());

        AgentResponse response = AgentResponse.success(
                taskId, agentId(), result.output(),
                result.iterationsDone(), result.promptTokens(), result.outputTokens(),
                estimateCost(result.promptTokens(), result.outputTokens(), config), elapsed, result.steps())
                .withLlmProvider(resolvedLlmProviderId(usedLlm, config));
        // ADR-0086: called before clearWorkingMemory() so an implementation that reacts to
        // a finished turn (e.g. consolidation) still sees this turn's full window.
        session.memoryManager().onTurnCompleted(task, response);
        session.memoryManager().clearWorkingMemory();
        session.stateMachine().transitionTo(AgentState.IDLE);

        log.info("Agent [{}] completed task [{}] in {} iteration(s), {} tokens",
                agentId().value(), taskId, result.iterationsDone(), result.tokensUsed());

        return response;
    }

    private AgentResponse handleFailure(String taskId, AgentSession session,
                                         ExecutionResult result, Duration elapsed, LlmClient usedLlm) {
        session.stateMachine().transitionTo(AgentState.FAILED);
        String reason = result.failureReasonOpt().orElse("Unknown failure");
        AgentExecutionContext failCtx = buildContext(taskId, session, result.iterationsDone(), result.tokensUsed());
        dispatchFailureEvent(failCtx, "Executing", reason);
        session.memoryManager().clearWorkingMemory();
        session.stateMachine().transitionTo(AgentState.IDLE);

        log.warn("Agent [{}] failed task [{}]: {}", agentId().value(), taskId, reason);
        // result.output() is "" for every strategy that has nothing safe to show on
        // failure (the common case), so this is a no-op for them; a strategy that
        // attaches a genuine partial output (see ExecutionResult.failure(reason,
        // partialOutput, ...), used by PipelineStrategy) has it reach the caller instead
        // of being silently dropped here.
        AgentConfig config = session.wiring().config();
        Money cost = estimateCost(result.promptTokens(), result.outputTokens(), config);
        return AgentResponse.failure(taskId, agentId(), reason, elapsed,
                result.iterationsDone(), result.promptTokens(), result.outputTokens(), result.steps())
                .withContent(result.output())
                .withCost(cost)
                .withLlmProvider(resolvedLlmProviderId(usedLlm, config));
    }

    /**
     * Routes a strategy failure to the interceptor hook matching its {@link FailureKind}:
     * budget/cancellation are expected, policy-driven stops with their own typed hook,
     * everything else (session busy, max iterations, genuinely unexpected reasons) still
     * goes through the generic {@link AgentInterceptor#onError}.
     */
    private void dispatchFailureEvent(AgentExecutionContext context, String stepName, String reason) {
        switch (FailureKind.classify(reason)) {
            case BUDGET_EXCEEDED -> interceptorChain.onBudgetExceeded(context, stepName, reason);
            case CANCELLED       -> interceptorChain.onCancelled(context, stepName);
            case SESSION_BUSY, TIMEOUT, MAX_ITERATIONS, UNEXPECTED_ERROR, OTHER ->
                    interceptorChain.onError(context, stepName, new RuntimeException(reason));
        }
    }

    /** The single source of the "session busy" failure reason — {@link #classifyFailure} keys off its prefix. */
    private AgentResponse sessionBusyFailure(String taskId, AgentSession session) {
        return AgentResponse.failure(taskId, agentId(),
                "Session busy: another request is already being processed on session '"
                        + session.sessionId().value() + "'", Duration.ZERO);
    }

    private AgentResponse handleUnexpectedError(String taskId, AgentSession session,
                                                  Exception e, Duration elapsed) {
        String reason = "Unexpected error: " + e.getMessage();
        AgentExecutionContext errCtx = buildContext(taskId, session, 0, 0);
        interceptorChain.onError(errCtx, "Executing", e);
        resetSessionAfterFailure(session);
        return AgentResponse.failure(taskId, agentId(), reason, elapsed);
    }

    private AgentResponse handleEarlyTermination(String taskId, AgentSession session, Instant startedAt) {
        log.warn("Agent [{}] termination detected before execution started for task [{}]",
                agentId().value(), taskId);
        interceptorChain.onCancelled(buildContext(taskId, session, 0, 0), "Executing");
        resetSessionAfterFailure(session);
        return AgentResponse.failure(taskId, agentId(), "Agent terminated before execution",
                Duration.between(startedAt, Instant.now()));
    }

    /**
     * Best-effort reset to a clean, idle session after any failure path. Wrapped in a
     * swallow-all: a concurrent terminate() or cancel may have already moved the state
     * machine, and failing to reset here would be worse than a silent no-op — the session
     * must always end up available for the next task.
     */
    private void resetSessionAfterFailure(AgentSession session) {
        quietly(session, "transition to FAILED", () -> session.stateMachine().transitionTo(AgentState.FAILED));
        quietly(session, "clear working memory", () -> session.memoryManager().clearWorkingMemory());
        quietly(session, "transition to IDLE",   () -> session.stateMachine().transitionTo(AgentState.IDLE));
    }

    /**
     * Runs a best-effort cleanup step, logging rather than discarding any failure — an
     * empty {@code catch} here erases the only evidence that a session was left in an
     * unexpected state.
     */
    private void quietly(AgentSession session, String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.debug("Agent [{}] session [{}]: best-effort reset step '{}' failed",
                    agentId().value(), session.sessionId().value(), what, e);
        }
    }

    /**
     * Returns the effective LLM provider id: registry alias + "/" + actual client used.
     *
     * @param usedLlm the client actually resolved for this call — the session's pinned
     *                {@link AgentWiring#llm()}
     * @param config  the session's pinned config (never the instance's live config: an
     *                older session must report the alias it was actually built with)
     */
    private String resolvedLlmProviderId(LlmClient usedLlm, AgentConfig config) {
        String alias    = config.llmProvider();
        String clientId = usedLlm != null ? usedLlm.lastUsedProviderId() : null;
        if (alias != null && clientId != null) return alias + "/" + clientId;
        if (alias    != null) return alias;
        if (clientId != null) return clientId;
        return null;
    }

    private AgentExecutionContext buildContext(String taskId, AgentSession session,
                                               int iterations, int tokens) {
        return new AgentExecutionContext(
                agentId(),
                taskId,
                session.stateMachine().current(),
                iterations,
                session.memoryManager().workingMemory(),
                tokens,
                Instant.now());
    }

    /**
     * Estimates cost in USD using per-provider rates from {@link AgentConfig}, applied to
     * the actual prompt/output split — not a blended average — now that {@link
     * ExecutionResult} tracks them separately.
     * When both input and output rates are zero (default), returns 0.0 (cost tracking disabled).
     */
    private Money estimateCost(int promptTokens, int outputTokens, AgentConfig config) {
        Money inputRate  = config.costInputPer1kTokens();
        Money outputRate = config.costOutputPer1kTokens();
        if (inputRate.amount().signum() == 0 && outputRate.amount().signum() == 0) {
            return Money.zero(config.costCurrency());
        }
        return inputRate.multiply(fraction(promptTokens)).plus(outputRate.multiply(fraction(outputTokens)));
    }

    private static java.math.BigDecimal fraction(int tokens) {
        return java.math.BigDecimal.valueOf(tokens).divide(java.math.BigDecimal.valueOf(1_000));
    }
}
