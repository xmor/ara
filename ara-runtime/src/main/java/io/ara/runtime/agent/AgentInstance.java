package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.ToolExchange;
import io.ara.core.agent.AgentExecutionContext;
import io.ara.core.agent.AgentInterceptor;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.CheckpointStore;
import io.ara.core.agent.RunCheckpoint;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.ConversationTurn;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.agent.RunContext;
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
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmRouter;
import io.ara.core.memory.MemoryManager;
import io.ara.core.memory.ToolCallMetadata;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.core.telemetry.Span;
import io.ara.core.telemetry.SpanStatus;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.interceptor.AgentInterceptorChain;
import io.ara.runtime.interceptor.InterceptingLlmClient;
import io.ara.runtime.interceptor.InterceptingToolRegistry;
import io.ara.runtime.strategy.ExecutionPlanner;
import io.ara.runtime.strategy.MeteringStrategy;
import io.ara.runtime.hitl.ApprovalVerdicts;
import io.ara.runtime.strategy.RunCheckpointer;
import io.ara.runtime.strategy.RunEvents;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
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
    private final CheckpointStore checkpointStore;
    private final Consumer<RunCheckpoint> suspensionListener;

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
        this(config, wiringFactory, memoryFactory, executionPlanner, interceptorChain, telemetry,
                sessionStore, CheckpointStore.noop());
    }

    /**
     * As the canonical constructor above, plus the {@link CheckpointStore} that receives a
     * snapshot of every run at each iteration boundary (ADR-048 D2). The 7-argument form keeps
     * {@link CheckpointStore#noop()}, so existing callers behave exactly as before.
     */
    public AgentInstance(
            AgentConfig config,
            WiringFactory wiringFactory,
            Function<SessionId, MemoryManager> memoryFactory,
            ExecutionPlanner executionPlanner,
            AgentInterceptorChain interceptorChain,
            AraTelemetry telemetry,
            SessionStore sessionStore,
            CheckpointStore checkpointStore
    ) {
        this(config, wiringFactory, memoryFactory, executionPlanner, interceptorChain, telemetry,
                sessionStore, checkpointStore, checkpoint -> { });
    }

    /**
     * As the 8-argument form, plus {@code suspensionListener}, told of every run that stops to
     * wait for an approval, with the checkpoint it can be resumed from (ADR-048 D2). It is what
     * connects a suspended run to the approval gate; without one a suspended run is only a
     * checkpoint nobody is watching.
     */
    public AgentInstance(
            AgentConfig config,
            WiringFactory wiringFactory,
            Function<SessionId, MemoryManager> memoryFactory,
            ExecutionPlanner executionPlanner,
            AgentInterceptorChain interceptorChain,
            AraTelemetry telemetry,
            SessionStore sessionStore,
            CheckpointStore checkpointStore,
            Consumer<RunCheckpoint> suspensionListener
    ) {
        this.suspensionListener = Objects.requireNonNull(suspensionListener, "suspensionListener must not be null");
        this.versionedConfig   = new Versioned<>(Objects.requireNonNull(config, "config must not be null"));
        this.executionPlanner  = Objects.requireNonNull(executionPlanner, "executionPlanner must not be null");
        this.interceptorChain  = Objects.requireNonNull(interceptorChain, "interceptorChain must not be null");
        this.telemetry         = Objects.requireNonNull(telemetry,        "telemetry must not be null");
        Objects.requireNonNull(wiringFactory, "wiringFactory must not be null");
        Objects.requireNonNull(memoryFactory, "memoryFactory must not be null");
        this.sessionStore   = Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore must not be null");
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

        // A task with an event listener gets exactly one RunStarted and one RunFinished, however
        // the run ends: the refusals below ("Agent terminated", a busy session) and an exception
        // are runs too, and a page waiting for RunFinished must not hang on them. Doing it here,
        // around the whole body, covers every strategy, pipeline and workflow without touching them.
        RunEvents events = RunEvents.forRun(task, agentId().value());
        if (events == null) {
            return executeRun(task);
        }
        events.started();
        AgentResponse response;
        try {
            response = executeRun(events.attachTo(task));
        } catch (RuntimeException | Error e) {
            events.failed(e);
            throw e;
        }
        events.finished(response);
        return response;
    }

    private AgentResponse executeRun(AgentTask task) {
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

        // P5/U18bis, 2026-09-23: re-check `closed` immediately after getOrCreate, before
        // `session` is used for anything else. `terminate()` can run concurrently between
        // the check above and the getOrCreate call: it flips `closed` and drains every
        // session sessionManager.shutdown() can see at that moment — a session this call
        // creates strictly afterward is invisible to that drain (never in its snapshot).
        // runStrategy() independently re-checks `closed` right before the strategy would
        // actually run, so this is not what stops the task from executing against a
        // terminated agent (that was already covered) — it is what stops the session
        // itself from leaking: without it, this orphaned session stays registered with its
        // wiring open forever, since terminate() is idempotent (no second drain ever
        // comes) and execute()'s very first check now rejects every later call before it
        // can reach getOrCreate again. invalidate() here is safe even if terminate() also
        // raced this exact session into its own drain first — the second teardown of the
        // same key is a documented no-op (SessionManager's own single-owner removal rule).
        if (closed.get()) {
            sessionManager.invalidate(sessionId);
            return AgentResponse.failure(task.taskId(), agentId(),
                    "Agent terminated", Duration.ZERO);
        }

        AgentConfig sessionConfig = session.wiring().config();

        // Three independent seeding decisions, applied left to right. Same shape in all
        // three: attach the channel only when the task doesn't already carry it, never
        // overwrite what an earlier layer put there. Each has its own guard and its own
        // reason, so each gets its own method rather than a shared "bind if absent" helper
        // — the guards differ enough that a generic version would hide exactly the part
        // a reader needs to check.
        AgentTask effectiveTask = withSessionState(task, session);
        effectiveTask = withUserMemory(effectiveTask);
        effectiveTask = withOwnScopes(effectiveTask, sessionConfig);

        String effectiveSystemPrompt = effectiveTask.runContext().promptVar(CTX_SYSTEM_PROMPT, sessionConfig.systemPrompt());

        return executeUnderSessionLock(effectiveTask, session, sessionConfig, effectiveSystemPrompt);
    }

    /**
     * Binds this session's shared {@link RunState} onto the task, but only if the task
     * doesn't already carry one: a fresh top-level call should see its session's
     * accumulated state, but a task that arrived via {@code delegate_task} already has
     * SHARED/OVERLAY/ISOLATED state attached by {@code AgentDelegationTool} and must not
     * have it clobbered here.
     *
     * <p>Side effect: none beyond the returned task; the session's own state object is
     * read, never replaced.
     */
    private AgentTask withSessionState(AgentTask task, AgentSession session) {
        return task.runContext().state() == RunState.noop()
                ? task.withRunContext(task.runContext().withState(session.runState()))
                : task;
    }

    /**
     * Binds cross-session user memory (ADR-043 rev. 3), resolved fresh from
     * {@code sessionStore} rather than cached on the {@code AgentSession}: unlike
     * session-scoped state, user memory must outlive any single session, so there is no
     * per-session object to cache it on. Same noop-guard as {@link #withSessionState}.
     *
     * <p>Skipped when the task carries no {@link UserId} — there is no key to resolve
     * from, and an anonymous caller has no cross-session memory by definition.
     *
     * <p>Side effect: none beyond the returned task; reads through
     * {@code sessionStore} on every call rather than memoizing the handle.
     */
    private AgentTask withUserMemory(AgentTask task) {
        if (task.userId() == null || task.runContext().userMemory() != RunState.noop()) {
            return task;
        }
        RunState userMemory = RunState.persisting(sessionStore, userMemoryKey(task.userId()));
        return task.withRunContext(task.runContext().withUserMemory(userMemory));
    }

    /**
     * Seeds this agent's OWN declared scope ceiling as the task's starting authorization
     * context — the upstream mechanism whose absence was ADR-0077 D2's declared external
     * blocker ("senza grantedScopes popolato da qualche meccanismo di
     * configurazione/autenticazione a monte, incoming è sempre null"), now that ADR-033
     * Fasi 1-9 give it somewhere real to flow to.
     *
     * <p>Applies ONLY to a genuinely fresh, top-level task that carries neither the
     * scope-set channel nor an execution context. A task delivered via
     * {@code AgentDelegationTool}/{@code LocalMessageBus} already carries an attenuated
     * incoming scope from a prior hop, and one built via
     * {@code AraRuntime.executeOnBehalfOf} already carries the OBO subject: overwriting
     * either with this agent's own (wider) ceiling would defeat the whole attenuation
     * chain, so those two are the reason this is a guard and not a default.
     *
     * <p>An agent with no {@code grantedScopes} configured — still the overwhelming
     * default — seeds nothing, which is why this is inert for the unconfigured case.
     *
     * <p>Side effect: none beyond the returned task; the ceiling is read from the
     * session's config, not cached here.
     */
    private AgentTask withOwnScopes(AgentTask task, AgentConfig sessionConfig) {
        if (sessionConfig.grantedScopes().isEmpty()
                || task.runContext().opaque(RunContext.SCOPES_KEY, io.ara.core.auth.ScopeSet.class) != null
                || !task.executionContext().isEmpty()) {
            return task;
        }
        io.ara.core.auth.ScopeSet ownScopes = io.ara.core.auth.ScopeSet.of(sessionConfig.grantedScopes());
        return task
                .withRunContext(task.runContext().withOpaque(RunContext.SCOPES_KEY, ownScopes))
                .withExecutionContext(io.ara.core.auth.ExecutionContext.ofAgent(agentId().value(), ownScopes));
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

        // With no interceptors registered (the default), every before/after notification and
        // both Intercepting* decorators are pure hot-path overhead: the chain loops over
        // nothing, and the decorators would still build a fresh AgentExecutionContext on
        // every LLM call and every tool dispatch via contextSupplier. Read the count once and
        // share it across the Planning phase and the Executing dispatch, each of which skips
        // building a snapshot nobody will read when the chain is empty.
        boolean hasInterceptors = interceptorChain.size() > 0;

        session.stateMachine().transitionTo(AgentState.PLANNING);
        ExecutionStrategy strategy = selectStrategy(task.taskId(), session, config, hasInterceptors);

        session.stateMachine().transitionTo(AgentState.EXECUTING);
        try {
            return runStrategy(task, session, config, effectiveSystemPrompt, strategy,
                    hasInterceptors, startedAt);
        } catch (ExecutionTimeoutException e) {
            log.warn("Agent [{}] timed out after {}s for task [{}]",
                    agentId().value(), e.timeout().toSeconds(), task.taskId());
            Duration elapsed = Duration.between(startedAt, Instant.now());
            resetSessionAfterFailure(session,
                    () -> interceptorChain.onTimeout(context(task.taskId(), session, 0, 0), "Executing", e.timeout()));
            return AgentResponse.failure(task.taskId(), agentId(), e.getMessage(), elapsed);
        } catch (Exception e) {
            log.error("Agent [{}] threw an unexpected exception during task [{}]",
                    agentId().value(), task.taskId(), e);
            return handleUnexpectedError(task.taskId(), session, e,
                    Duration.between(startedAt, Instant.now()));
        }
    }

    /**
     * Planning phase: resolves the strategy and, when interceptors are registered, brackets
     * the selection with the {@code "Planning"} hooks. The context snapshot is built only when
     * a hook will read it — with no interceptors the before/after calls are no-ops and the
     * allocation would be pure overhead (the same reasoning as the Executing hot path).
     */
    private ExecutionStrategy selectStrategy(String taskId, AgentSession session,
                                             AgentConfig config, boolean hasInterceptors) {
        AgentExecutionContext planCtx = hasInterceptors ? context(taskId, session, 0, 0) : null;
        if (hasInterceptors) {
            interceptorChain.before(planCtx, "Planning");
        }
        ExecutionStrategy strategy = executionPlanner.select(config);
        log.debug("Agent [{}] selected strategy [{}]", agentId().value(), strategy.strategyName());
        if (hasInterceptors) {
            interceptorChain.after(planCtx, "Planning", strategy.strategyName());
        }
        // Metering is the runtime's, not the config's: a no-op unless the task carries a
        // SpendMeter, and named like the strategy it wraps, so nothing that reads the name
        // (logs, spans, SpecLineage's hash) can tell it is there.
        return MeteringStrategy.around(strategy);
    }

    /**
     * Executing phase: bails out if cancellation arrived while queued, seeds working memory
     * for this turn, runs the strategy against the session's pinned collaborators, and turns
     * its {@link ExecutionResult} into a response.
     */
    private AgentResponse runStrategy(AgentTask task, AgentSession session, AgentConfig config,
                                      String effectiveSystemPrompt, ExecutionStrategy strategy,
                                      boolean hasInterceptors, Instant startedAt) {
        if (closed.get() || session.isCancelRequested()) {
            return handleEarlyTermination(task.taskId(), session, startedAt);
        }

        Dispatched dispatched = dispatchCollaborators(task.taskId(), session, hasInterceptors);
        MemoryManager memoryManager = session.memoryManager();
        Optional<RunCheckpoint> resumed = task.resumeCheckpoint();
        if (resumed.isPresent() && !strategy.resumable()) {
            return handleFailure(task.taskId(), session, ExecutionResult.failure(
                    "Strategy '" + strategy.strategyName() + "' cannot resume a checkpointed run", 0, 0, 0),
                    Duration.between(startedAt, Instant.now()), dispatched.llm());
        }
        if (resumed.isPresent()) {
            restoreWorkingMemory(memoryManager, resumed.get());
            task = withVerdicts(task, resumed.get());
        } else {
            seedWorkingMemory(memoryManager, config, session, effectiveSystemPrompt, task,
                    dispatched.llm().supportsNativeTools());
        }

        ExecutionResult result = executeCheckpointed(task, session, config, strategy, dispatched, memoryManager);

        Duration elapsed = Duration.between(startedAt, Instant.now());
        if (result.isAwaitingApproval()) {
            return handleSuspended(task, session, result, elapsed, dispatched.llm());
        }
        if (!result.isSuccess()) {
            return handleFailure(task.taskId(), session, result, elapsed, dispatched.llm());
        }
        // Record the turn before resetting working memory
        if (config.maxConversationTurns() > 0) {
            List<ToolExchange> toolCalls = config.replayToolCalls()
                    ? ToolCallHistory.record(result.steps(), dispatched.tools())
                    : List.of();
            session.conversationHistory().addTurn(task.input(), result.output(), task.media(), toolCalls);
        }
        return handleSuccess(task, session, result, elapsed, dispatched.llm());
    }

    /**
     * Runs the strategy with a {@link RunCheckpointer} attached to the task, and removes the
     * run's checkpoint once the strategy has returned or thrown: a run that ended, whatever
     * the outcome, has nothing left to resume.
     *
     * <p>With the default no-op store nothing is attached: a checkpointer would copy the
     * working memory at every iteration only to hand it to a store that discards it. The
     * identity check on {@link CheckpointStore#noop()} is the cheapest way to skip that cost
     * without adding a method to the SPI just to ask "do you persist?".
     */
    private ExecutionResult executeCheckpointed(AgentTask task, AgentSession session, AgentConfig config,
                                                ExecutionStrategy strategy, Dispatched dispatched,
                                                MemoryManager memoryManager) {
        if (checkpointStore == CheckpointStore.noop()) {
            return strategy.execute(task, dispatched.llm(), memoryManager, dispatched.tools(), config);
        }
        RunCheckpointer checkpointer = new RunCheckpointer(checkpointStore, session.sessionId(),
                task.taskId(), task.input(), strategy.resumable(),
                (promptTokens, outputTokens) -> estimateCost(promptTokens, outputTokens, config));
        ExecutionResult result = null;
        try {
            result = strategy.execute(checkpointer.attachTo(task), dispatched.llm(), memoryManager,
                    dispatched.tools(), config);
            return result;
        } finally {
            // A run that stopped to wait for an approval is the one run whose checkpoint must
            // outlive this call: it is what the decision resumes from.
            if (result == null || !result.isAwaitingApproval()) {
                checkpointer.clear();
            }
        }
    }

    /**
     * The LLM client and tool registry this execution runs against: the session's pinned
     * pair — already OTel-instrumented by the wiring — or, when interceptors are registered,
     * decorators wrapping them so interceptors get per-iteration Think/ToolCall visibility
     * without any strategy needing to know about the interceptor chain. Same pattern as the
     * OTel decorators in {@code AraRuntime}.
     */
    private Dispatched dispatchCollaborators(String taskId, AgentSession session, boolean hasInterceptors) {
        LlmClient llm = session.wiring().llm();
        ToolRegistry tools = session.wiring().toolRegistry();
        if (!hasInterceptors) {
            return new Dispatched(llm, tools);
        }
        // One supplier shared by both decorators instead of two identical lambdas: it is
        // invoked on every LLM call and every tool dispatch, so it belongs to the hot path.
        Supplier<AgentExecutionContext> contextSupplier = () -> context(taskId, session, 0, 0);
        interceptorChain.before(contextSupplier.get(), "Executing");
        return new Dispatched(
                new InterceptingLlmClient(llm, interceptorChain, contextSupplier),
                new InterceptingToolRegistry(tools, interceptorChain, contextSupplier));
    }

    /** The collaborators a single strategy execution runs against. */
    private record Dispatched(LlmClient llm, ToolRegistry tools) {}

    /**
     * For a run resumed after an approval, carries the decisions on the task so the gate
     * applies them instead of asking again. A checkpoint taken at an ordinary iteration
     * boundary waits for nothing and the task is returned as it is.
     */
    private static AgentTask withVerdicts(AgentTask task, RunCheckpoint checkpoint) {
        return checkpoint.pendingApproval() == null
                ? task
                : ApprovalVerdicts.of(task.taskId(), checkpoint.pendingApproval(), task.resumeDecisions())
                        .attachTo(task);
    }

    /**
     * Rebuilds the working memory a checkpoint was taken from, in place of {@link
     * #seedWorkingMemory}: the system prompt, the replayed turns and the task input are already
     * in it, together with everything the run added since. Anything the session's memory holds
     * now is dropped first — a resumed run continues the saved conversation, not one grafted
     * onto whatever the session did meanwhile.
     *
     * <p>A tool-call entry gets its {@link ToolCallMetadata} back, because adapters rebuild
     * native function-calling turns from it; without it a resumed run would send the model a
     * tool result that no call precedes.
     */
    private void restoreWorkingMemory(MemoryManager memoryManager, RunCheckpoint checkpoint) {
        memoryManager.clearWorkingMemory();
        for (LlmMessage message : checkpoint.workingMemory()) {
            boolean isToolCallEntry = "tool".equals(message.role()) || "assistant_tool_call".equals(message.role());
            if (isToolCallEntry && message.toolCallId() != null && message.toolName() != null) {
                memoryManager.appendToWorkingMemory(message.role(), message.content(),
                        new ToolCallMetadata(message.toolCallId(), message.toolName()));
            } else if (message.hasMedia()) {
                memoryManager.appendToWorkingMemory(message.role(), message.content(), message.media());
            } else {
                memoryManager.appendToWorkingMemory(message.role(), message.content());
            }
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
    private void seedWorkingMemory(MemoryManager memoryManager, AgentConfig config, AgentSession session,
                                    String effectiveSystemPrompt, AgentTask task, boolean nativeTools) {
        memoryManager.appendToWorkingMemory("system", effectiveSystemPrompt);
        int maxTurns = config.maxConversationTurns();
        if (maxTurns > 0) {
            List<ConversationTurn> recent = session.conversationHistory().recentTurns(maxTurns);
            for (int i = 0; i < recent.size(); i++) {
                ConversationTurn turn = recent.get(i);
                memoryManager.appendToWorkingMemory("user", replayText(turn));
                // the calls go between the question and the answer, where the run made them
                if (config.replayToolCalls()) {
                    ToolCallHistory.replay(memoryManager, turn, i, i == recent.size() - 1, nativeTools);
                }
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
        AgentExecutionContext doneCtx = context(taskId, session, result.iterationsDone(), result.tokensUsed());
        interceptorChain.after(doneCtx, "Executing", result.output());

        AgentResponse answered = AgentResponse.success(
                taskId, agentId(), result.output(),
                result.iterationsDone(), result.promptTokens(), result.outputTokens(),
                costOf(result, config), elapsed, result.steps())
                .withLlmProvider(resolvedLlmProviderId(usedLlm, config));
        // Only a completed task has artifacts, and only the final answer is split: a failure keeps its
        // partial text and no references, since nothing was finished enough to be handed on.
        List<MediaRef> artifacts = session.wiring().artifactSink().emit(answered.content());
        AgentResponse response = artifacts.isEmpty() ? answered : answered.withArtifacts(artifacts);
        // ADR-0086: called before clearWorkingMemory() so an implementation that reacts to
        // a finished turn (e.g. consolidation) still sees this turn's full window.
        session.memoryManager().onTurnCompleted(task, response);
        session.memoryManager().clearWorkingMemory();
        session.stateMachine().transitionTo(AgentState.IDLE);

        log.info("Agent [{}] completed task [{}] in {} iteration(s), {} tokens",
                agentId().value(), taskId, result.iterationsDone(), result.tokensUsed());

        return response;
    }

    /**
     * The run stopped before a tool call a human must approve, and its checkpoint holds
     * everything needed to continue it. The session is released like after any finished turn —
     * working memory cleared, state back to {@link AgentState#IDLE} — so nothing stays locked
     * while nobody has answered; the conversation history is not extended, since the turn has
     * no answer yet. No {@code after} interceptor hook fires for the same reason: the turn is
     * not over, it will be reported when it is.
     */
    private AgentResponse handleSuspended(AgentTask task, AgentSession session, ExecutionResult result,
                                          Duration elapsed, LlmClient usedLlm) {
        AgentConfig config = session.wiring().config();
        session.stateMachine().transitionTo(AgentState.AWAITING_APPROVAL);
        session.memoryManager().clearWorkingMemory();
        session.stateMachine().transitionTo(AgentState.IDLE);

        log.info("Agent [{}] suspended task [{}] after {} iteration(s), awaiting approval requestIds={}",
                agentId().value(), task.taskId(), result.iterationsDone(), result.awaitingApproval().stream().map(ApprovalRequest::requestId).toList());

        announceSuspension(session, task.taskId());

        return AgentResponse.awaitingApproval(task.taskId(), agentId(), result.awaitingApproval(),
                        result.iterationsDone(), result.promptTokens(), result.outputTokens(),
                        costOf(result, config), elapsed, result.steps())
                .withLlmProvider(resolvedLlmProviderId(usedLlm, config));
    }

    /**
     * Tells the listener a run was suspended, handing it the checkpoint as the store holds it —
     * the durable truth, the same one a restart would read. Called after the session is already
     * free, because the listener may let an operator answer at once and the resumed run needs
     * that session. A listener that fails must not fail the suspension: the checkpoint is saved
     * and a restart will find it.
     */
    private void announceSuspension(AgentSession session, String taskId) {
        try {
            Optional<RunCheckpoint> checkpoint = checkpointStore.loadLatest(session.sessionId(), taskId);
            if (checkpoint.isEmpty()) {
                log.error("Task [{}] was suspended but its checkpoint is not in the store", taskId);
                return;
            }
            suspensionListener.accept(checkpoint.get());
        } catch (RuntimeException e) {
            log.error("Announcing the suspension of task [{}] failed: {}", taskId, e.getMessage(), e);
        }
    }

    private AgentResponse handleFailure(String taskId, AgentSession session,
                                         ExecutionResult result, Duration elapsed, LlmClient usedLlm) {
        String reason = result.failureReasonOpt().orElse("Unknown failure");
        resetSessionAfterFailure(session,
                () -> dispatchFailureEvent(context(taskId, session, result.iterationsDone(), result.tokensUsed()),
                        "Executing", reason));

        log.warn("Agent [{}] failed task [{}]: {}", agentId().value(), taskId, reason);
        // result.output() is "" for every strategy that has nothing safe to show on
        // failure (the common case), so this is a no-op for them; a strategy that
        // attaches a genuine partial output (see ExecutionResult.failure(reason,
        // partialOutput, ...), used by PipelineStrategy) has it reach the caller instead
        // of being silently dropped here.
        AgentConfig config = session.wiring().config();
        Money cost = costOf(result, config);
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
            case SESSION_BUSY, TIMEOUT, MAX_ITERATIONS, UNEXPECTED_ERROR, OTHER,
                 CONTRACT_REQUEST_VIOLATION, CONTRACT_OUTPUT_VIOLATION ->   // never from classify(reason); listed so a future typed caller is not silently dropped
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
        resetSessionAfterFailure(session,
                () -> interceptorChain.onError(context(taskId, session, 0, 0), "Executing", e));
        return AgentResponse.failure(taskId, agentId(), reason, elapsed);
    }

    private AgentResponse handleEarlyTermination(String taskId, AgentSession session, Instant startedAt) {
        log.warn("Agent [{}] termination detected before execution started for task [{}]",
                agentId().value(), taskId);
        resetSessionAfterFailure(session,
                () -> interceptorChain.onCancelled(context(taskId, session, 0, 0), "Executing"));
        return AgentResponse.failure(taskId, agentId(), "Agent terminated before execution",
                Duration.between(startedAt, Instant.now()));
    }

    /**
     * Best-effort reset to a clean, idle session after any failure path. {@code failureNotice}
     * runs once the session has been marked {@link AgentState#FAILED} — so a hook reading the
     * context sees the failure state — but before working memory is cleared, so it still sees
     * this turn's window. Every step, including the notice, is wrapped in a swallow-all: a
     * concurrent terminate()/cancel may have already moved the state machine, an interceptor
     * may itself throw, and failing to reset here would be worse than a silent no-op — the
     * session must always end up available for the next task.
     */
    private void resetSessionAfterFailure(AgentSession session, Runnable failureNotice) {
        quietly(session, "transition to FAILED",    () -> session.stateMachine().transitionTo(AgentState.FAILED));
        quietly(session, "dispatch failure notice", failureNotice);
        quietly(session, "clear working memory",    () -> session.memoryManager().clearWorkingMemory());
        quietly(session, "transition to IDLE",      () -> session.stateMachine().transitionTo(AgentState.IDLE));
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

    /**
     * Builds the checkpoint snapshot an {@link AgentInterceptor} hook receives for the
     * current session: the live working-memory window and lifecycle state at the instant of
     * the call. Each hook invocation gets its own snapshot (memory and state evolve between
     * calls), so this is a factory, not a cached value.
     */
    private AgentExecutionContext context(String taskId, AgentSession session,
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
     * The cost to report for {@code result}: what the strategy measured itself when it did
     * (a workflow sums the real cost of the agents it ran, each on its own model and rate),
     * otherwise the token counts priced with this agent's own rates.
     */
    private Money costOf(ExecutionResult result, AgentConfig config) {
        return result.cost() != null
                ? result.cost()
                : estimateCost(result.promptTokens(), result.outputTokens(), config);
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

    /** Divisor for the per-1k token rate, hoisted so this hot path allocates only the result. */
    private static final java.math.BigDecimal ONE_THOUSAND = java.math.BigDecimal.valueOf(1_000);

    private static java.math.BigDecimal fraction(int tokens) {
        return java.math.BigDecimal.valueOf(tokens).divide(ONE_THOUSAND);
    }
}
