package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentFuture;
import io.ara.core.agent.MemoryScope;
import io.ara.core.agent.AgentInterceptor;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AraAgents;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.CheckpointStore;
import io.ara.core.agent.SessionStore;
import io.ara.core.auth.ExecutionContext;
import io.ara.core.auth.ScopeSet;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmClientFactory;
import io.ara.core.llm.LlmTransport;
import io.ara.core.llm.LlmRouter;
import io.ara.core.mcp.McpClient;
import io.ara.core.memory.EmbeddingClient;
import io.ara.core.memory.MemoryManager;
import io.ara.core.memory.SemanticStore;
import io.ara.core.memory.TokenCounter;
import io.ara.core.provider.AgentProvider;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.agent.AgentRegistry;
import io.ara.core.agent.ConversationTurn;
import io.ara.runtime.agent.InstanceContextStore;
import io.ara.runtime.agent.Reconfigurable;
import io.ara.runtime.agent.SessionHistoryAware;
import io.ara.runtime.agent.SessionScoped;
import io.ara.runtime.auth.AuthorizationService;
import io.ara.runtime.auth.TemporaryScopeRegistry;
import io.ara.runtime.bus.AgentDelegationTool;
import io.ara.runtime.bus.LocalMessageBus;
import io.ara.runtime.config.AraRuntimeConfig;
import io.ara.runtime.factory.AgentFactory;
import io.ara.runtime.factory.DefaultLlmRouter;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.media.MediaStore;
import io.ara.runtime.llm.InstrumentedLlmClient;
import io.ara.runtime.memory.SlidingWindowMemoryManager;
import io.ara.core.retriever.Retriever;
import io.ara.core.retriever.RetrieverRouter;
import io.ara.runtime.scheduler.AgentScheduler;
import io.ara.runtime.scheduler.ScheduleExecutionListener;
import io.ara.runtime.telemetry.TelemetryToolRegistry;
import io.ara.runtime.wiring.AggregatingToolRegistry;
import io.ara.runtime.wiring.DrainPolicy;
import io.ara.runtime.wiring.McpServerBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Top-level entry point for the ARA agent runtime.
 *
 * <p>{@code AraRuntime} is the single class a user needs to start ARA in
 * standalone mode. It wires the internal object graph (factory, registry,
 * planner, message bus) and exposes a minimal API for creating agents and
 * querying the registry.
 *
 * <h2>Quick start — in-memory / no LLM</h2>
 * <pre>{@code
 * AraRuntime runtime = AraRuntime.builder()
 *     .llmClient(ScriptedLlmClient.script()
 *         .thenFinalAnswer("Done!")
 *         .build())
 *     .build();
 *
 * AraAgent agent = runtime.createAgent(AgentConfig.defaults()
 *     .agentType("demo")
 *     .build());
 *
 * runtime.start();
 * // ... run tasks ...
 * runtime.stop();
 * }</pre>
 *
 * <h2>Load configuration from {@code ara.yml}</h2>
 * <pre>{@code
 * AraRuntime runtime = AraRuntime.fromYaml(llmClient);
 * }</pre>
 *
 * <h2>Register agents up-front via {@link AgentProvider}</h2>
 * <pre>{@code
 * AraRuntime runtime = AraRuntime.builder()
 *     .llmClient(llmClient)
 *     .agentProvider(ExplicitAgentProvider.of(config1, config2))
 *     .build();
 *
 * runtime.start();  // creates all agents from the provider
 * }</pre>
 */
public final class AraRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AraRuntime.class);

    private final AraRuntimeConfig config;
    private final AgentFactory     factory;
    private final AgentRegistry    registry;
    private final AgentProvider    agentProvider;
    private final AgentScheduler   scheduler;
    private final InstanceContextStore instanceContextStore;
    private final ApprovalGate     approvalGate;
    private final TemporaryScopeRegistry temporaryScopeRegistry;
    private final AuthorizationService authorizationService;
    private final RuntimeLifecycle lifecycle;
    private final Map<String, LlmClient> llmClients;
    private final ToolRegistry     toolRegistry;
    private final Map<String, Retriever> retrievers;
    /**
     * The shared agent behind {@link #ask}/{@link #askText}, created on first use. Volatile
     * so the common read is lock-free; {@link #defaultAgent()} guards the create with
     * double-checked locking. Cleared by {@link #stop()}, because that call destroys every
     * registered agent, this one included.
     */
    private volatile AraAgent defaultAgent;

    AraRuntime(
            AraRuntimeConfig config,
            AgentFactory factory,
            AgentRegistry registry,
            RuntimeLifecycle lifecycle,
            AgentProvider agentProvider,
            AgentScheduler scheduler,
            InstanceContextStore instanceContextStore,
            ApprovalGate approvalGate,
            io.ara.runtime.auth.TemporaryScopeRegistry temporaryScopeRegistry,
            io.ara.core.auth.AbacPolicyEngine abacPolicyEngine,
            Map<String, LlmClient> llmClients,
            ToolRegistry toolRegistry,
            Map<String, Retriever> retrievers) {
        this.config        = config;
        this.factory       = factory;
        this.registry      = registry;
        this.agentProvider = agentProvider;
        this.scheduler     = scheduler;
        this.instanceContextStore = instanceContextStore;
        this.approvalGate  = approvalGate;
        this.temporaryScopeRegistry = temporaryScopeRegistry;
        this.authorizationService = new io.ara.runtime.auth.AuthorizationService(abacPolicyEngine);
        this.lifecycle = lifecycle;
        this.llmClients    = llmClients;
        this.toolRegistry  = toolRegistry;
        this.retrievers    = retrievers;
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts the runtime: creates all agents supplied by the {@link AgentProvider}
     * (if any) and marks the runtime as running.
     *
     * <p>Agents are created synchronously. If the provider throws, startup fails fast.
     * Provider-driven startup is additionally bounded by {@link
     * AraRuntimeConfig#startupTimeoutSec()}: once the deadline elapses while the
     * provider supplies configs or {@code factory.create} produces an agent, start()
     * aborts with an {@link IllegalStateException}. The bound is cooperative — it
     * catches slow-but-returning work, it cannot preempt a call that blocks forever.
     */
    public void start() {
        lifecycle.getLock().lock();
        try {
            if (lifecycle.isStarted()) return;
            log.info("AraRuntime [{}]{} starting", config.name(), identitySuffix());
            lifecycle.start();

            if (agentProvider != null) {
                Instant deadline = Instant.now().plusSeconds(config.startupTimeoutSec());
                for (AgentConfig cfg : agentProvider.configs()) {
                    ensureWithinStartupDeadline(deadline);
                    factory.create(cfg);
                    ensureWithinStartupDeadline(deadline);
                    log.info("  + agent [{}] type=[{}]", cfg.agentId().value(), cfg.agentType());
                }
            }

            scheduler.start();
            log.info("AraRuntime [{}] started — {} agent(s) registered{}",
                    config.name(), registry.count(), identitySuffix());
        } finally {
            lifecycle.getLock().unlock();
        }
    }

    /** Non-blank {@link AraRuntimeConfig#description()} rendered as a log suffix for runtime identity. */
    private String identitySuffix() {
        String description = config.description();
        return description.isBlank() ? "" : " — " + description;
    }

    /** Fails startup loudly once the configured startup timeout has elapsed. */
    private void ensureWithinStartupDeadline(Instant deadline) {
        if (Instant.now().isAfter(deadline)) {
            throw new IllegalStateException(
                    "AraRuntime [%s] startup exceeded the configured startupTimeoutSec=%ss while creating provider agents"
                            .formatted(config.name(), config.startupTimeoutSec()));
        }
    }

    /**
     * Stops the runtime: terminates all registered agents and cleans up their
     * checkpoints.
     *
     * <p>Best-effort and fail-safe: a failure while destroying one agent is logged and
     * does not abort the shutdown — the remaining agents are still destroyed and the
     * executor is always shut down (in a {@code finally}), so the runtime can never be
     * left in a half-stopped "zombie" state (running, but scheduler/executor already
     * gone). The executor is drained for up to {@link AraRuntimeConfig#shutdownTimeoutSec()}
     * seconds before {@code shutdownNow()} forces it.
     *
     * <p><b>N1/U25, 2026-09-22:</b> {@code factory.destroyPermanently} below closes each
     * agent's real MCP connections, and {@code lifecycle.stop()} blocks on {@code
     * awaitTermination} for up to {@code shutdownTimeoutSec} seconds — both while holding
     * {@code lifecycle.getLock()}, the single lock shared by every other lifecycle operation
     * ({@link #createAgent}, {@link #destroyAgent}, {@link #submit}'s slow path...). See
     * {@code RuntimeLifecycle}'s own class javadoc for why this lock is a {@link
     * java.util.concurrent.locks.ReentrantLock}, not a monitor (a virtual thread blocked here
     * would otherwise pin its carrier for this whole method on JDK 21) — deliberately without
     * also shrinking this critical section to a snapshot (that would let a concurrent {@link
     * #start()} race this method's own destroy loop for agent ids the two could share).
     */
    public void stop() {
        lifecycle.getLock().lock();
        try {
            if (!lifecycle.isStarted()) return;
            log.info("AraRuntime [{}] stopping", config.name());
            // This call destroys every registered agent below, the default one included, so
            // drop the reference now rather than hand the next ask() a destroyed instance.
            defaultAgent = null;
            try {
                try {
                    scheduler.stop();
                } catch (RuntimeException e) {
                    log.error("AraRuntime [{}] scheduler.stop() failed — continuing shutdown",
                            config.name(), e);
                }
                registry.all().forEach(agent -> {
                    try {
                        factory.destroyPermanently(agent);
                        instanceContextStore.clear(agent.agentId());
                    } catch (RuntimeException e) {
                        log.error("AraRuntime [{}] failed to destroy agent [{}] during shutdown — continuing",
                                config.name(), agent.agentId().value(), e);
                    }
                });
            } finally {
                // P4/U14: close AgentFactory's own llmTransports/mcpTransports schedulers —
                // separate from, and after, the per-agent destroy loop above, which already
                // tears down each agent's *leased* LLM/MCP resources via retire/release.
                try {
                    factory.close();
                } catch (RuntimeException e) {
                    log.error("AraRuntime [{}] factory.close() failed — continuing shutdown",
                            config.name(), e);
                }
                lifecycle.stop();
                log.info("AraRuntime [{}] stopped", config.name());
            }
        } finally {
            lifecycle.getLock().unlock();
        }
    }

    /**
     * Alias for {@link #stop()}, so the runtime can be scoped with
     * try-with-resources:
     * <pre>{@code
     * try (AraRuntime runtime = AraRuntime.builder().llmClient(llm).build()) {
     *     runtime.createAgent(config).execute(task);
     * } // stop() runs here — agents terminated, executor drained
     * }</pre>
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * First-use convenience behind {@link #createAgent}, {@link #replaceAgent} and
     * {@link #submit}: transparently starts a runtime that was never started, but
     * refuses to resurrect one that was explicitly stopped — {@code stop()} destroyed
     * its agents and their checkpoints, so silently re-running the {@link
     * AgentProvider} would be surprising. Restart explicitly via {@link #start()}.
     *
     * <p>Must be called while holding {@code lifecycle.getLock()}.
     */
    private void autoStart() {
        lifecycle.autoStart();   // throws when stopped, no-op when already STARTED
    }

    // ── agent management ──────────────────────────────────────────────────────

    /**
     * Creates a new agent from the given config and registers it in the registry.
     * Starts the runtime automatically if it was never started; throws
     * {@link IllegalStateException} if the runtime was stopped — restart it
     * explicitly via {@link #start()} first.
     */
    public AraAgent createAgent(AgentConfig config) {
        lifecycle.getLock().lock();
        try {
            autoStart();
            return factory.create(config);
        } finally {
            lifecycle.getLock().unlock();
        }
    }

    /**
     * Creates a new agent wired with the given {@link AgentContract} and registers it.
     * Same lifecycle rules as {@link #createAgent(AgentConfig)}.
     */
    public AraAgent createAgent(AgentConfig config, AgentContract contract) {
        lifecycle.getLock().lock();
        try {
            autoStart();
            return factory.create(config, contract);
        } finally {
            lifecycle.getLock().unlock();
        }
    }

    /**
     * Runs {@code prompt} on a shared default agent and returns the full response — the
     * shortest path from a runtime to an answer, for scripts, tests and the first ten
     * minutes with ARA.
     *
     * <p>The default agent is {@link AgentConfig#defaults()} with no overrides, created on
     * first use and reused by every later call on this runtime, so several prompts cost one
     * agent rather than one each. It is deliberately unremarkable: the moment the agent
     * needs a role, a system prompt, tools or a specific model, create your own with
     * {@link #createAgent(AgentConfig)} and call {@link AraAgents#ask} on it directly. If
     * the default agent has left the registry (a {@link #stop()}, or an explicit {@link
     * #destroyAgent(AraAgent)}), the next call transparently creates a fresh one.
     *
     * @throws NullPointerException  if {@code prompt} is {@code null}
     * @throws IllegalStateException if the runtime was explicitly stopped (see {@link #createAgent})
     */
    public AgentResponse ask(String prompt) {
        return AraAgents.ask(defaultAgent(), prompt);
    }

    /**
     * As {@link #ask(String)}, returning only the answer text — the empty string when the
     * run failed, matching {@link AraAgents#askText}.
     */
    public String askText(String prompt) {
        return AraAgents.askText(defaultAgent(), prompt);
    }

    /**
     * The shared agent behind {@link #ask}/{@link #askText}. Double-checked locking on the
     * volatile {@link #defaultAgent} field keeps the common case lock-free — the runtime is
     * used from many threads. No lock is held across {@code createAgent}, which takes the
     * lifecycle lock itself; a second thread that races the first simply observes the
     * winning agent and discards its own.
     */
    private AraAgent defaultAgent() {
        AraAgent agent = defaultAgent;
        if (agent != null && registry.findById(agent.agentId()).isPresent()) {
            return agent;
        }
        synchronized (this) {
            agent = defaultAgent;
            if (agent == null || registry.findById(agent.agentId()).isEmpty()) {
                agent = createAgent(AgentConfig.defaults().build());
                defaultAgent = agent;
            }
            return agent;
        }
    }

    /**
     * Executes {@code task} on {@code agent} on behalf of {@code userId} (ADR-033 Fase 6,
     * S2/S6) — Delegation, not Impersonation: the call's {@link ExecutionContext#effectiveScopes()}
     * is the intersection of {@code agent}'s own granted scopes and {@code userScopes}, so
     * the agent can never exceed the user it claims to act for, nor the user exceed what
     * the agent itself is trusted to wield. The context travels with {@code task} into
     * {@code RunContext.EXECUTION_CONTEXT_KEY} (see {@link AgentTask#withExecutionContext})
     * — read there today by {@code AgentDelegationTool} if {@code agent} itself delegates
     * further, attenuating again at each hop exactly as ADR-033 Fase 5 already does for a
     * machine-to-machine chain.
     *
     * <p>Since ADR-033 §3.3's task-scoped wiring, the same channels scope this execution
     * directly, at both enforcement points the way the tool catalog is scoped: the
     * delegation pre-check (a {@code delegate_task} call from {@code agent} resolves its
     * recipients against the effective scopes this context carries) and the tool registry
     * (the catalog filtered per task). Peer visibility — {@code AgentRegistry.viewFor(AgentTask)}
     * — now reads the same source, so a caller with narrower scopes sees and can reach
     * fewer peers, without rebuilding any wiring.
     *
     * <p>What is still fixed per agent: everything else {@code AgentInstance} builds at
     * {@code createAgent} time from {@code agent.config()} — not fresh per call from
     * this method's {@code userScopes}.
     */
    public AgentResponse executeOnBehalfOf(AraAgent agent, AgentTask task, String userId, ScopeSet userScopes) {
        Objects.requireNonNull(agent,  "agent must not be null");
        Objects.requireNonNull(task,   "task must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        ExecutionContext ctx = ExecutionContext.ofUserDelegation(
                agent.agentId().value(),
                ScopeSet.of(agent.config().grantedScopes()),
                userId,
                userScopes);
        return agent.execute(task.withExecutionContext(ctx));
    }

    /**
     * Creates a new agent from {@code config} and atomically replaces whatever agent
     * currently holds {@code config.agentId()} — see {@code AgentRegistry.replace}.
     *
     * <p>Unlike {@link #createAgent(AgentConfig)}, a duplicate id never throws: the
     * previous agent under this id (if any) is simply displaced, not terminated. It
     * keeps running any in-flight sessions exactly as before this call — the caller
     * decides whether to let it drain naturally (drop the last reference once it's
     * done) or call {@link AraAgent#terminate()} on it immediately for a forced
     * cutover. Typical use: swapping in an agent whose config changed in a management
     * UI without dropping work already in progress against the old one.
     */
    public AraAgent replaceAgent(AgentConfig config) {
        lifecycle.getLock().lock();
        try {
            autoStart();
            return factory.replace(config);
        } finally {
            lifecycle.getLock().unlock();
        }
    }

    /**
     * Creates a new agent wired with the given {@link AgentContract} and atomically
     * replaces whatever agent currently holds {@code config.agentId()} — see {@link
     * #replaceAgent(AgentConfig)}.
     */
    public AraAgent replaceAgent(AgentConfig config, AgentContract contract) {
        lifecycle.getLock().lock();
        try {
            autoStart();
            return factory.replace(config, contract);
        } finally {
            lifecycle.getLock().unlock();
        }
    }

    /**
     * Applies {@code update} to the current config of the agent registered under {@code id}
     * and hot-swaps it in place (ADR-039) — no new instance, no lost sessions. A task
     * already in flight always finishes with the configuration it started with; only
     * sessions created after this call observe the updated one.
     *
     * <p>Serialized on the same lock as {@link #createAgent}/{@link
     * #replaceAgent}: an administrative operation, not a hot-path one, so process-wide
     * serialization against other lifecycle operations is an acceptable trade-off for
     * the simplicity of reusing the existing lock.
     *
     * @param id     the id of the agent to reconfigure; must be currently registered
     * @param update pure function computing the new config from the current one
     * @throws IllegalArgumentException     if no agent is registered under {@code id}
     * @throws UnsupportedOperationException if the registered agent does not support hot reconfiguration
     */
    public void reconfigureAgent(AgentId id, UnaryOperator<AgentConfig> update) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(update, "update must not be null");
        lifecycle.getLock().lock();
        try {
            autoStart();
            AraAgent agent = registry.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No agent registered with id [" + id.value() + "]"));
            if (!(agent instanceof Reconfigurable reconfigurable)) {
                throw new UnsupportedOperationException(
                        "Agent [" + id.value() + "] does not support hot reconfiguration");
            }
            reconfigurable.reconfigure(update.apply(agent.config()));
        } finally {
            lifecycle.getLock().unlock();
        }
    }

    /**
     * Publishes a new version of the shared LLM transport {@code transportId} —
     * administration of shared infrastructure, distinct from {@link #reconfigureAgent}.
     * Every agent that references this id sees the new version on its next new
     * session; sessions already alive keep the transport they were pinned to and
     * finish naturally — the correct behavior for a routine rollout, not a bug.
     * Use {@link #forceUpdateSharedModel} instead when in-flight sessions must be
     * cut over immediately (e.g. a leaked credential).
     */
    public void updateSharedModel(String transportId, LlmTransport spec) {
        Objects.requireNonNull(transportId, "transportId must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        factory.publishLlmTransport(transportId, spec, DrainPolicy.GRACEFUL);
    }

    /**
     * Same as {@link #updateSharedModel(String, LlmTransport)}, but forcibly cancels
     * sessions still pinned to the retired transport instead of letting them drain
     * naturally. Reserved for security-sensitive updates (e.g. a leaked credential,
     * an endpoint being decommissioned right now).
     */
    public void forceUpdateSharedModel(String transportId, LlmTransport spec) {
        Objects.requireNonNull(transportId, "transportId must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        factory.publishLlmTransport(transportId, spec, new DrainPolicy.Forced(null));
    }

    /**
     * Publishes a new connector for the shared MCP server {@code serverId}. Requires
     * at least one {@code mcpServer(...)} to have been registered on the {@link
     * AgentFactory.Builder} this runtime was built with. Sessions already alive keep
     * the connector they were pinned to and finish naturally; use {@link
     * #forceUpdateSharedMcpServer} to cut them over immediately instead.
     */
    public void updateSharedMcpServer(String serverId, Supplier<McpClient> newConnector) {
        Objects.requireNonNull(serverId, "serverId must not be null");
        Objects.requireNonNull(newConnector, "newConnector must not be null");
        factory.publishMcpServer(serverId, newConnector, DrainPolicy.GRACEFUL);
    }

    /**
     * Same as {@link #updateSharedMcpServer(String, Supplier)}, but forcibly cancels
     * sessions still pinned to the retired connector instead of letting them drain
     * naturally.
     */
    public void forceUpdateSharedMcpServer(String serverId, Supplier<McpClient> newConnector) {
        Objects.requireNonNull(serverId, "serverId must not be null");
        Objects.requireNonNull(newConnector, "newConnector must not be null");
        factory.publishMcpServer(serverId, newConnector, new DrainPolicy.Forced(null));
    }

    /** {@code true} between a {@link #start()} and the next {@link #stop()}. */
    public boolean isRunning() { return lifecycle.isStarted(); }

    /**
     * Terminates a single agent, cleans up its checkpoints, and removes it from
     * the registry. Use this for fine-grained lifecycle control; {@link #stop()}
     * destroys all agents at once.
     *
     * <p>Thread-safe and idempotent: if the agent has already been destroyed
     * (e.g. by a concurrent {@link #stop()} or a duplicate call), this method
     * is a no-op. Returns silently if the runtime was explicitly stopped — at
     * that point all agents are already gone.
     *
     * @param agent the agent to destroy; must be currently registered
     */
    // N1/U25, 2026-09-22: factory.destroyPermanently below closes the agent's real MCP
    // connections while holding lifecycle.getLock() — see RuntimeLifecycle's class javadoc
    // for why that lock is a ReentrantLock, not a monitor (a virtual thread blocked here
    // would otherwise pin its carrier on JDK 21, and every other lifecycle caller sharing
    // this lock would stall behind it, not just other destroyAgent() calls).
    public void destroyAgent(AraAgent agent) {
        Objects.requireNonNull(agent, "agent must not be null");

        lifecycle.getLock().lock();
        try {
            // Guard: runtime already stopped → all agents were destroyed by stop()
            if (lifecycle.isStopped()) {
                log.debug("AraRuntime [{}] already stopped — destroyAgent([{}]) is a no-op",
                        config.name(), agent.agentId().value());
                return;
            }

            // Idempotency: agent may have been removed by a concurrent destroyAgent()
            // or by stop() racing on another thread before we acquired the lock
            if (registry.findById(agent.agentId()).isEmpty()) {
                log.debug("AraRuntime [{}] agent [{}] not in registry — already destroyed, skipping",
                        config.name(), agent.agentId().value());
                return;
            }

            // Actual destruction — mutually exclusive with stop() via lifecycleLock
            try {
                factory.destroyPermanently(agent);
            } finally {
                // Always clean up instance context, even if destroyPermanently throws
                instanceContextStore.clear(agent.agentId());
            }

            log.info("AraRuntime [{}] agent [{}] destroyed",
                    config.name(), agent.agentId().value());
        } finally {
            lifecycle.getLock().unlock();
        }
    }

    /**
     * Looks up a registered agent by its id.
     *
     * @param agentId the agent's unique identifier
     * @return the agent, or empty if not found
     */
    public Optional<AraAgent> agent(AgentId agentId) {
        return registry.findById(agentId);
    }

    /** Returns all currently registered agents. */
    public Collection<AraAgent> agents() {
        return registry.all();
    }

    /**
     * Returns the recorded conversation turns for {@code sessionId} on {@code agentId},
     * oldest-first — empty if the agent isn't registered, doesn't implement
     * {@link SessionHistoryAware}, or no session with that id currently exists (never
     * used, or already evicted by the session TTL sweeper). Works both for raw
     * {@link io.ara.runtime.agent.AgentInstance}s and for agents wrapped in a
     * {@code ContractEnforcingAgent} (i.e. created with a non-empty contract).
     *
     * <p>Read-only: never creates a session as a side effect, unlike {@code execute()}.
     */
    public List<ConversationTurn> conversationHistory(AgentId agentId, SessionId sessionId) {
        return registry.findById(agentId)
                .filter(a -> a instanceof SessionHistoryAware)
                .map(a -> ((SessionHistoryAware) a).conversationHistory(sessionId))
                .orElseGet(List::of);
    }

    /**
     * Cancels the in-flight task on a single {@code sessionId} of {@code agentId}, without
     * terminating the agent or affecting other sessions (ADR-016) — a no-op if the agent
     * isn't registered or doesn't manage sessions ({@link SessionScoped}).
     */
    public void terminateSession(AgentId agentId, SessionId sessionId) {
        sessionScoped(agentId).ifPresent(a -> a.terminate(sessionId));
    }

    /**
     * Immediately removes {@code sessionId} from {@code agentId} — a no-op if the agent
     * isn't registered or doesn't manage sessions ({@link SessionScoped}).
     */
    public void invalidateSession(AgentId agentId, SessionId sessionId) {
        sessionScoped(agentId).ifPresent(a -> a.invalidateSession(sessionId));
    }

    /**
     * Kill switch (ADR-0069 D4): requests cooperative cancellation of the in-flight task
     * on <em>every</em> session of <em>every</em> registered agent. Agents and sessions
     * stay alive — only running tasks stop at their next iteration boundary, and new tasks
     * submitted afterwards run normally. A no-op for agents that don't manage sessions
     * ({@link SessionScoped}).
     */
    public void emergencyStopAll() {
        registry.all().stream()
                .filter(a -> a instanceof SessionScoped)
                .forEach(a -> ((SessionScoped) a).cancelAllSessions());
    }

    /**
     * Per-agent kill switch (ADR-0069 D4): as {@link #emergencyStopAll()} but scoped to
     * {@code agentId}. Sibling agents keep running — the same per-session isolation as
     * {@link #terminateSession}. A no-op if the agent isn't registered or doesn't manage
     * sessions ({@link SessionScoped}).
     */
    public void emergencyStop(AgentId agentId) {
        sessionScoped(agentId).ifPresent(SessionScoped::cancelAllSessions);
    }

    /**
     * Returns how many sessions are currently held open for {@code agentId} — {@code 0}
     * if the agent isn't registered or doesn't manage sessions ({@link SessionScoped}).
     */
    public int activeSessionCount(AgentId agentId) {
        return sessionScoped(agentId).map(SessionScoped::activeSessionCount).orElse(0);
    }

    /** The registered agent under {@code agentId} narrowed to {@link SessionScoped}, or empty if not applicable. */
    private Optional<SessionScoped> sessionScoped(AgentId agentId) {
        return registry.findById(agentId)
                .filter(a -> a instanceof SessionScoped)
                .map(a -> (SessionScoped) a);
    }

    // ── accessors ─────────────────────────────────────────────────────────────

    /** Returns the {@link AgentRegistry}. */
    public AgentRegistry registry()  { return registry; }

    /**
     * Returns every {@link LlmClient} registered on this runtime, keyed by the transport
     * id it was registered under (via {@link Builder#llmClient(String, LlmClient)} —
     * {@code "default"} for the single-arg {@link Builder#llmClient(LlmClient)}).
     *
     * <p>Each client is the same, telemetry-instrumented instance {@link LlmRouter}
     * resolves calls against — not a separate snapshot — so {@code providerId()} and
     * behavior match exactly what agents actually call. Intended for discovery/listing
     * surfaces (e.g. an AgentOS-compat {@code GET /registry?resource_type=model} route)
     * that need the full catalog of configured models, not a per-call resolution.
     *
     * @return an immutable map from transport id to client; never {@code null}, never empty
     *         (a runtime cannot be built with zero registered clients — the invariant is
     *         enforced at build time by the package-private {@code RuntimeAssembler})
     */
    public Map<String, LlmClient> llmClients() { return llmClients; }

    /**
     * Returns a {@link ToolRegistry} suitable for discovery/listing surfaces (e.g. an
     * AgentOS-compat {@code GET /registry?resource_type=tool} route) — never the registry
     * actually wired into any single agent's tool dispatch, since that depends on which
     * of the two mutually exclusive builder options was used:
     *
     * <ul>
     *   <li>{@link Builder#toolRegistry(ToolRegistry)} — the single shared instance,
     *       returned as-is. Whether {@link ToolRegistry#all()} reports anything depends
     *       on whether it (and its chain of decorators) implements {@code all()}.
     *   <li>{@link Builder#toolRegistryFactory(java.util.function.Function)} — different
     *       agents can be wired to genuinely different registries, so there is no single
     *       instance to point at. This returns an {@link AggregatingToolRegistry} backed
     *       by the live, growing set of per-agent registries the factory has produced —
     *       {@code all()} is the union of all of them, deduplicated by tool id, and
     *       reflects agents created after this accessor was first called.
     *   <li>Neither set — {@link ToolRegistry#empty()}.
     * </ul>
     *
     * @return a {@link ToolRegistry} for enumeration purposes; never {@code null}
     */
    public ToolRegistry toolRegistry() { return toolRegistry; }

    /**
     * Returns every {@link Retriever} registered on this runtime, keyed by the id it was
     * registered under (via {@link Builder#retriever(String, Retriever)} — {@code
     * "default"} for the single-arg {@link Builder#retriever(Retriever)}).
     *
     * <p>ARA's closest equivalent to agno's "knowledge" concept: a {@code Retriever}
     * encapsulates a full retrieval pipeline (embedding, nearest-neighbour search,
     * ranked passages), the same role agno's {@code Knowledge} class plays. Unlike
     * {@link AraTool}, {@code Retriever} exposes no self-describing name/description —
     * only the registration id and the runtime class are available for discovery
     * surfaces (e.g. an AgentOS-compat {@code GET /registry?resource_type=knowledge}
     * route).
     *
     * @return an immutable map from registration id to retriever; never {@code null},
     *         empty if none were registered
     */
    public Map<String, Retriever> retrievers() { return retrievers; }

    /** Returns the {@link AgentScheduler} for registering and managing agent schedules. */
    public AgentScheduler scheduler() { return scheduler; }

    /** Returns the active {@link AraRuntimeConfig}. */
    public AraRuntimeConfig config() { return config; }

    /**
     * Returns the shared virtual-thread executor for async agent execution.
     * Pass this to {@link io.ara.core.agent.AraAgents#executeAsync} for fan-out
     * and pipeline chains. The executor is initialised on {@link #start()} and
     * replaced with a fresh one on every restart.
     *
     * <p>Prefer {@link #submit} where possible: it validates the lifecycle phase
     * atomically, whereas a reference obtained here goes stale after {@link #stop()}
     * (the executor is shut down and rejects new tasks).
     *
     * @return the shared executor; may be {@code null} before {@link #start()} is called
     */
    public Executor executor() { return lifecycle.getAgentExecutor(); }

    /**
     * Returns the shared {@link InstanceContextStore} (ADR-036) — one entry per agent,
     * readable live by {@code PromptShaper}s and {@code AraTool} instances alike, and
     * updatable at any time without recreating the agent. Created automatically (empty)
     * if not supplied via {@link Builder#instanceContextStore(InstanceContextStore)}.
     * Entries are cleared automatically when their agent is destroyed via {@link
     * #destroyAgent(AraAgent)} or {@link #stop()}.
     */
    public InstanceContextStore instanceContextStore() { return instanceContextStore; }

    /** Exposed for tests (same package) and diagnostics; see {@link AgentFactory#close()}. */
    AgentFactory factory() { return factory; }

    /**
     * Returns the {@link ApprovalGate} configured on this runtime, or {@code null} if
     * no gate was supplied via {@link Builder#approvalGate(ApprovalGate)}.
     *
     * <p>When non-null, agents whose {@link AgentConfig#humanApprovalRequired()} is
     * {@code true} will route every tool call through this gate before dispatch. External
     * surfaces (e.g. an HTTP gateway) can list and resolve pending requests via
     * {@link ApprovalGate#getPendingRequests()} and {@link ApprovalGate#submit}.
     */
    public ApprovalGate approvalGate() { return approvalGate; }

    /**
     * ADR-033 Fase 2b — the {@link io.ara.runtime.auth.AuthorizationService} for this
     * runtime. Never {@code null}: without {@link Builder#abacPolicies}, it still runs
     * the scope check (Fasi 1-9) via {@link io.ara.runtime.auth.AuthorizationService#authorize},
     * just with {@link io.ara.runtime.auth.AuthorizationService#abacEnabled()} {@code false}
     * and the ABAC half a no-op. Not consulted automatically by anything in this runtime
     * (see that class's own Javadoc for why) — a caller invokes it explicitly.
     */
    public io.ara.runtime.auth.AuthorizationService authorizationService() { return authorizationService; }

    /**
     * Grants {@code agentId} a temporary, task-scoped set of scopes (ADR-033 Fase 8, S5) —
     * on top of whatever it already holds via {@link AgentConfig#grantedScopes()}, never in
     * place of it ({@link io.ara.core.auth.ScopeSet#union}, not {@code intersect}). The next
     * time this agent is the recipient of a {@link LocalMessageBus} dispatch, the caller's
     * effective scopes are widened by this grant for as long as it stays {@link
     * io.ara.core.auth.ScopeGrant#isValid()} — each such dispatch also consumes one use.
     *
     * @param agentId  the agent this grant applies to
     * @param scopes   the scopes it contributes
     * @param ttl      time limit; {@code null} means no time limit (use-count only)
     * @param maxUses  invocations before exhaustion; {@code -1} means unlimited
     * @param reason   human-readable justification, kept for audit on the returned {@link
     *                 io.ara.core.auth.ScopeGrant}
     * @return the {@link io.ara.core.auth.ScopeGrant} that was registered
     */
    public io.ara.core.auth.ScopeGrant grantTemporaryScope(
            AgentId agentId, io.ara.core.auth.ScopeSet scopes, Duration ttl, int maxUses, String reason) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(scopes,  "scopes must not be null");
        java.time.Instant expiresAt = ttl != null ? java.time.Instant.now().plus(ttl) : null;
        io.ara.core.auth.ScopeGrant grant = new io.ara.core.auth.ScopeGrant(
                scopes, expiresAt, maxUses, "system", reason == null ? "" : reason);
        temporaryScopeRegistry.grant(agentId.value(), grant);
        return grant;
    }

    /**
     * Submits a task for asynchronous execution on the runtime's shared
     * virtual-thread executor, returning immediately with an {@link AgentFuture}.
     *
     * <p>Tasks submitted via this method are tracked by the quiescence tracker —
     * call {@link #awaitQuiescence(Duration)} to block until all of them complete.
     * Tasks submitted directly via {@code AraAgent.executeAsync} with a raw
     * executor reference are NOT tracked.
     */
    public AgentFuture submit(AraAgent agent, AgentTask task) {
        // Fast path: volatile read, lock-free when already STARTED. On the slow path
        // (NEW or stopped) the lifecycle decision is taken atomically against a
        // concurrent stop() — see RuntimeLifecycle#getAgentExecutorOrAutoStart.
        Executor executor = lifecycle.getAgentExecutor();
        if (!lifecycle.isStarted()) {
            executor = lifecycle.getAgentExecutorOrAutoStart();
        }

        // Register BEFORE handing off — prevents a race where the task
        // completes before we even start tracking it
        lifecycle.taskStarted();
        try {
            return submitTracked(agent, task, executor);
        } catch (RejectedExecutionException e) {
            // The fast path's volatile executor read went stale: a concurrent stop() had
            // already begun draining when we handed the task over, and the virtual-thread-
            // per-task executor refuses new submissions once shut down. Resolve the race
            // through the lock — the way the pre-optimization submit always did — so the
            // outcome is exactly the old one: blocked until stop() finishes, then the
            // IllegalStateException autoStart() raises for a stopped runtime. The executor
            // never rejects for any other reason, so this retry cannot loop.
            lifecycle.taskFinished();
            executor = lifecycle.getAgentExecutorOrAutoStart();
            lifecycle.taskStarted();
            try {
                return submitTracked(agent, task, executor);
            } catch (RuntimeException f) {
                lifecycle.taskFinished();
                throw f;
            }
        } catch (RuntimeException e) {
            // Task was never actually submitted (no completion will ever fire)
            lifecycle.taskFinished();
            throw e;
        }
    }

    /** Submits {@code task} on {@code executor} and ties quiescence tracking to the future's completion. */
    private AgentFuture submitTracked(AraAgent agent, AgentTask task, Executor executor) {
        AgentFuture future = io.ara.core.agent.AraAgents.executeAsync(agent, task, executor);
        // Release on completion (success OR failure)
        future.async().whenComplete((response, error) ->
                lifecycle.taskFinished());
        return future;
    }

    /**
     * Blocks until all tasks submitted via {@link #submit} have completed,
     * or the timeout elapses. Does NOT stop the runtime — the executor
     * remains active and new tasks can be submitted immediately after.
     *
     * <p>Typical use in tests:
     * <pre>{@code
     * runtime.submit(agent, task);
     * assertTrue(runtime.awaitQuiescence(Duration.ofSeconds(10)));
     * // now assert on results
     * }</pre>
     *
     * <p>Typical use in graceful shutdown:
     * <pre>{@code
     * // stop accepting new work
     * server.stopAcceptingRequests();
     * // drain in-flight agent tasks
     * if (!runtime.awaitQuiescence(Duration.ofSeconds(30))) {
     *     log.warn("Timed out waiting for agent tasks — forcing stop");
     * }
     * runtime.stop();
     * }</pre>
     *
     * @param timeout maximum time to wait
     * @return true if all tasks completed, false if the timeout elapsed first
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public boolean awaitQuiescence(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative: " + timeout);
        }
        return lifecycle.awaitQuiescence(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * Returns the number of tasks currently in-flight (submitted via
     * {@link #submit} but not yet completed). Useful for health-check
     * endpoints and diagnostics.
     */
    public int inFlightTaskCount() {
        return lifecycle.inFlightCount();
    }


    // ── factory methods ───────────────────────────────────────────────────────

    /**
     * Loads {@code ara.yml} from the default search path and creates a runtime
     * with the given LLM client and in-memory stubs for all other dependencies.
     */
    public static AraRuntime fromYaml(LlmClient llmClient) {
        return builder()
                .runtimeConfig(AraRuntimeConfig.fromYaml())
                .llmClient(llmClient)
                .build();
    }

    /** Returns a new {@link Builder}. */
    public static Builder builder() {
        return new Builder();
    }

    // ── builder ───────────────────────────────────────────────────────────────

    /**
     * Fluent builder for {@link AraRuntime}.
     *
     * <p>Only {@link #llmClient} is mandatory. All other fields default to
     * in-memory stubs, making it trivial to get started with no infrastructure.
     */
    public static final class Builder {

        // Fields are package-private rather than private so RuntimeAssembler (same package)
        // can build the object graph from them. Keeping the assembly logic out of this class
        // leaves it focused on the fluent configuration surface; the fields are still only
        // reachable within io.ara.runtime.
        final java.util.Map<String, LlmClient> namedClients = new java.util.LinkedHashMap<>();
        String defaultClientId = "default";
        final java.util.Map<String, Retriever> namedRetrievers = new java.util.LinkedHashMap<>();
        String defaultRetrieverId;
        RetrieverRouter retrieverRouter;
        Function<AgentConfig, MemoryManager> memoryManagerFactory;
        /** ADR-0091 D3 — session-scoped memory factory; see {@link #memoryManagerScopeFactory}. */
        Function<MemoryScope, MemoryManager> memoryManagerScopeFactory;
        EmbeddingClient embeddingClient;
        SemanticStore   semanticStore;
        TokenCounter    tokenCounter;
        ToolRegistry toolRegistry;
        Function<AgentConfig, ToolRegistry> toolRegistryFactory;
        InstanceContextStore instanceContextStore;
        AraTelemetry telemetry = AraTelemetry.noop();
        SessionStore sessionStore = SessionStore.noop();
        CheckpointStore checkpointStore = CheckpointStore.noop();
        Consumer<AgentResponse> resumedRunListener = response -> { };
        io.ara.core.trace.TraceStore traceStore;
        io.ara.core.trace.BlobStore  traceBlobStore;
        java.util.function.Function<io.ara.core.agent.AgentConfig, String> traceSpecHash;
        MediaStore   mediaStore   = MediaStore.noop();
        io.ara.core.artifact.ArtifactExtractor artifactExtractor = io.ara.core.artifact.ArtifactExtractor.none();
        ApprovalGate approvalGate;
        io.ara.runtime.auth.TemporaryScopeRegistry temporaryScopeRegistry =
                new io.ara.runtime.auth.InMemoryTemporaryScopeRegistry();
        io.ara.core.auth.AbacPolicyEngine abacPolicyEngine;
        Duration delegationTimeout = Duration.ofSeconds(AgentDelegationTool.DEFAULT_TIMEOUT_SEC);
        AgentProvider agentProvider;
        AraRuntimeConfig runtimeConfig;
        List<AgentInterceptor>    interceptors     = List.of();
        final List<ExecutionStrategy> extraStrategies = new java.util.ArrayList<>();
        ScheduleExecutionListener scheduleExecutionListener;
        LlmClientFactory          llmClientFactory;
        LlmRouter                 reflectionRouter;
        final java.util.Map<String, McpServerBinding> mcpServers = new java.util.LinkedHashMap<>();

        private Builder() {}

        /**
         * Registers an MCP server for ADR-039-managed lifecycle (lazy connection, shared
         * and ref-counted across every agent/session referencing it, closed once retired
         * and unleased) — see {@link AgentFactory.Builder#mcpServer} for the full
         * contract and an example {@code toolsAdapter}. Purely opt-in: an {@code
         * AgentConfig.mcpServerIds()} value with no matching registration here falls
         * through entirely to {@link #toolRegistry}/{@link #toolRegistryFactory}.
         */
        public Builder mcpServer(String id, Supplier<McpClient> connector,
                                  Function<McpClient, List<AraTool>> toolsAdapter) {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(connector, "connector must not be null");
            Objects.requireNonNull(toolsAdapter, "toolsAdapter must not be null");
            this.mcpServers.put(id, new McpServerBinding(connector, toolsAdapter));
            return this;
        }

        /**
         * Registers additional {@link ExecutionStrategy} implementations beyond the
         * built-in {@code react}, {@code plan_execute}, and {@code reflexion} strategies.
         * Call multiple times or pass a list to register several strategies at once.
         *
         * @param strategies the extra strategies to register
         */
        public Builder extraStrategies(ExecutionStrategy... strategies) {
            java.util.Collections.addAll(this.extraStrategies, strategies);
            return this;
        }

        /**
         * Registers a single LLM client under the id {@code "default"} and makes it the
         * default, regardless of what was registered before. Like every builder setter,
         * the last call wins: a later {@link #defaultLlmClient(String)} (or another call
         * to this method) overrides it.
         */
        public Builder llmClient(LlmClient llmClient) {
            this.namedClients.put("default", Objects.requireNonNull(llmClient));
            this.defaultClientId = "default";
            return this;
        }

        /** Registers a named LLM client. The first registered name is used as default. */
        public Builder llmClient(String id, LlmClient llmClient) {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(llmClient, "llmClient must not be null");
            if (namedClients.isEmpty()) defaultClientId = id;
            this.namedClients.put(id, llmClient);
            return this;
        }

        /** Sets which registered id acts as the fallback when llmProvider is not found. */
        public Builder defaultLlmClient(String id) {
            this.defaultClientId = Objects.requireNonNull(id);
            return this;
        }

        /**
         * Provides a factory for creating {@link LlmClient} instances on-demand from
         * inline {@link LlmTransport} overrides (ADR-039 §3). Required to use the
         * inline-override path in {@code DefaultLlmRouter}/{@code DefaultWiringFactory}.
         */
        public Builder llmClientFactory(LlmClientFactory factory) {
            this.llmClientFactory = Objects.requireNonNull(factory);
            return this;
        }

        /**
         * Overrides the router used for meta-level reflection calls (Reflexion / ReflAct),
         * which may route the critique to a different provider than the main loop's own
         * model. This router does <em>not</em> govern the main LLM resolution — that happens
         * per session in the wiring factory, customized via {@link #llmClient}/{@link
         * #llmClientFactory} — so the name is deliberately narrow rather than a misleading
         * {@code llmRouter}. Unset means a {@code DefaultLlmRouter} over the registered
         * clients.
         */
        public Builder reflectionRouter(LlmRouter reflectionRouter) {
            this.reflectionRouter = Objects.requireNonNull(reflectionRouter, "reflectionRouter must not be null");
            return this;
        }

        /**
         * Registers a single {@link Retriever} under the id {@code "default"} and makes it
         * the default, regardless of what was registered before. Like every builder setter,
         * the last call wins: a later {@link #defaultRetriever(String)} (or another call to
         * this method) overrides it.
         *
         * <p>When at least one retriever is registered, the planner automatically registers
         * the {@code "rag+react"}, {@code "rag+respact"}, {@code "rag+plan_execute"} and
         * {@code "rag+reflact"} strategies, backed by a {@code RetrieverRouter} over all
         * registered retrievers (ADR-030 pattern applied to retrieval) — each agent selects
         * which retriever to use via {@code AgentConfig.retrieverId()}.
         */
        public Builder retriever(Retriever retriever) {
            return retriever("default", retriever);
        }

        /**
         * Registers a named {@link Retriever}. The first registered name becomes the default;
         * override it explicitly with {@link #defaultRetriever(String)}.
         *
         * @param id        the id agents select via {@code AgentConfig.retrieverId()}
         * @param retriever the retriever to register under {@code id}
         */
        public Builder retriever(String id, Retriever retriever) {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(retriever, "retriever must not be null");
            if (namedRetrievers.isEmpty()) defaultRetrieverId = id;
            this.namedRetrievers.put(id, retriever);
            return this;
        }

        /** Sets which registered id acts as the fallback when {@code retrieverId} is not found. */
        public Builder defaultRetriever(String id) {
            this.defaultRetrieverId = Objects.requireNonNull(id);
            return this;
        }

        /**
         * Overrides retriever routing for the RAG-augmented strategies with a
         * caller-supplied {@link RetrieverRouter}. Providing this also registers the
         * {@code "rag+*"} strategies even with no named retriever, since the router is the
         * thing that resolves them. Mutually exclusive with {@link #retriever(Retriever)}/
         * {@link #retriever(String, Retriever)}: a custom router supersedes the named map,
         * so supplying both is refused at build time rather than silently ignoring one.
         */
        public Builder retrieverRouter(RetrieverRouter retrieverRouter) {
            this.retrieverRouter = Objects.requireNonNull(retrieverRouter, "retrieverRouter must not be null");
            return this;
        }

        public Builder memoryManagerFactory(Function<AgentConfig, MemoryManager> factory) {
            this.memoryManagerFactory = factory;
            return this;
        }

        /**
         * ADR-0091 D3 — memory factory over {@link MemoryScope}: the agent config <em>and</em>
         * the session id each manager will serve, so a memory implementation that scopes its
         * events per session can learn the session ({@code AgentFactory} used to discard it).
         * Mutually exclusive with {@link #memoryManagerFactory(Function)}: supplying both is
         * refused at build time, like {@code toolRegistry}/{@code toolRegistryFactory}, rather
         * than silently ignoring one.
         */
        public Builder memoryManagerScopeFactory(Function<MemoryScope, MemoryManager> factory) {
            this.memoryManagerScopeFactory = Objects.requireNonNull(factory, "factory must not be null");
            return this;
        }

        /**
         * Embedding backend for the default working-memory manager's episodic offload and
         * recall (ADR-0078 D3/D4) — used only when {@link #memoryManagerFactory} is never
         * called, so the built-in {@link SlidingWindowMemoryManager} default can offload
         * evicted entries and recall them later. Ignored when a {@link #semanticStore} is
         * not also set (both are required for offload/recall to activate).
         */
        public Builder embeddingClient(EmbeddingClient embeddingClient) {
            this.embeddingClient = embeddingClient;
            return this;
        }

        /**
         * Episodic store for the default working-memory manager's offload/recall — see
         * {@link #embeddingClient(EmbeddingClient)}. Ignored when an {@code embeddingClient}
         * is not also set.
         */
        public Builder semanticStore(SemanticStore semanticStore) {
            this.semanticStore = semanticStore;
            return this;
        }

        /**
         * Token counter for the default working-memory manager's budget accounting
         * (ADR-0087) — used only when {@link #memoryManagerFactory} is never called, same
         * scope as {@link #embeddingClient}/{@link #semanticStore}. Defaults to {@code null},
         * which keeps the built-in {@link SlidingWindowMemoryManager}'s historic
         * {@code chars / 4} estimate ({@link TokenCounter#approximate()}); pass a real
         * tokenizer (e.g. {@code JtokkitTokenCounter} in {@code ara-adapters}) to make
         * eviction decisions on an actual token count instead of an approximation.
         */
        public Builder tokenCounter(TokenCounter tokenCounter) {
            this.tokenCounter = tokenCounter;
            return this;
        }

        /**
         * Convenience: sets a shared (config-independent) tool registry.
         *
         * <p>Mutually exclusive with {@link #toolRegistryFactory(Function)} — use that
         * instead when different agents need different tool instances (e.g. tools built
         * with private per-agent parameters from an {@link InstanceContextStore}, ADR-036).
         */
        public Builder toolRegistry(ToolRegistry toolRegistry) {
            this.toolRegistry = toolRegistry;
            return this;
        }

        /**
         * Sets a per-agent tool registry factory, invoked once per agent at creation time
         * with that agent's {@link AgentConfig} (ADR-036). Enables per-agent tool
         * instances — e.g. tools constructed with private parameters read from {@link
         * #instanceContextStore(InstanceContextStore)} that are never exposed to the LLM.
         * The returned registry is still wrapped per-agent to add {@code delegate_task}
         * support, exactly like the shared-registry path. Mutually exclusive with {@link
         * #toolRegistry(ToolRegistry)} — {@link #build()} throws if both are set.
         */
        public Builder toolRegistryFactory(Function<AgentConfig, ToolRegistry> factory) {
            this.toolRegistryFactory = Objects.requireNonNull(factory, "factory must not be null");
            return this;
        }

        /**
         * Sets the {@link InstanceContextStore} shared across every agent created by this
         * runtime (ADR-036). If not called, {@link #build()} creates an empty one
         * automatically, retrievable via {@link AraRuntime#instanceContextStore()}.
         */
        public Builder instanceContextStore(InstanceContextStore store) {
            this.instanceContextStore = Objects.requireNonNull(store, "store must not be null");
            return this;
        }

        /**
         * Sets the {@link AraTelemetry} used to emit OpenTelemetry spans:
         * {@code llm.complete} (one per LLM call, via {@link InstrumentedLlmClient}),
         * {@code agent.execute} (one per task), and {@code tool.execute} (one per tool
         * dispatch, via {@link TelemetryToolRegistry}). Defaults to {@link
         * AraTelemetry#noop()} (zero overhead) — build a real one with {@code
         * OtelTelemetryFactory} in {@code ara-adapters} to activate tracing.
         */
        public Builder telemetry(AraTelemetry telemetry) {
            this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
            return this;
        }

        /**
         * Sets the {@link SessionStore} used to persist {@code RunState} and conversation
         * history beyond a single session's in-process lifetime. Defaults to {@link
         * SessionStore#noop()} (in-memory only, exactly today's behavior) — build a real
         * one (or use {@link SessionStore#inMemory()} for process-local resumption
         * testing) to activate persistence.
         */
        public Builder sessionStore(SessionStore sessionStore) {
            this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore must not be null");
            return this;
        }

        /**
         * Sets the {@link CheckpointStore} that receives a snapshot of each run at every
         * iteration boundary (ADR-048 D2), for the ReAct-family strategies. Defaults to
         * {@link CheckpointStore#noop()} — no snapshots, exactly today's behavior. A run's
         * checkpoint is removed when the run ends; this step only writes it, resuming from it
         * is not wired yet.
         */
        public Builder checkpointStore(CheckpointStore checkpointStore) {
            this.checkpointStore = Objects.requireNonNull(checkpointStore, "checkpointStore must not be null");
            return this;
        }

        /**
         * Receives the response of every run the runtime continues after an approval was
         * answered (ADR-048 D2). Whoever called {@code execute} on the original run already got a
         * response saying it was waiting; this is how the outcome reaches the application — to
         * store it, notify someone, or answer a webhook. Defaults to doing nothing (the outcome
         * is logged). Called on the thread that resumed the run, so it must not block for long.
         */
        public Builder resumedRuns(Consumer<AgentResponse> listener) {
            this.resumedRunListener = Objects.requireNonNull(listener, "listener must not be null");
            return this;
        }

        /**
         * ADR-0068 D1 — enables automatic execution-trace emission: every agent created by
         * this runtime appends a run trace to {@code traceStore} after each execution, with
         * prompts and outputs content-addressed into {@code blobStore}. Both are required
         * together; not calling this leaves emission off (today's behaviour). Workflow
         * runs still emit via {@code WorkflowTraceProjection.emit(...)} explicitly.
         */
        public Builder traceEmission(io.ara.core.trace.TraceStore traceStore,
                                     io.ara.core.trace.BlobStore blobStore) {
            this.traceStore     = Objects.requireNonNull(traceStore, "traceStore must not be null");
            this.traceBlobStore = Objects.requireNonNull(blobStore, "blobStore must not be null");
            return this;
        }

        /**
         * Stamps every span the runtime's trace emission writes with the spec hash this function
         * returns for the agent's config, so a run can be joined to the spec that produced it.
         * Has no effect unless {@link #traceEmission} is also set.
         */
        public Builder traceSpecHash(java.util.function.Function<io.ara.core.agent.AgentConfig, String> specHashOf) {
            this.traceSpecHash = Objects.requireNonNull(specHashOf, "specHashOf must not be null");
            return this;
        }

        /**
         * Sets the {@link MediaStore} holding the bytes of any media attached to a task, so
         * the adapter can fetch them when it builds the provider request. Defaults to {@link
         * MediaStore#noop()}, which stores nothing — invisible to a deployment that never
         * attaches media, and a clear failure naming the attachment for one that does without
         * wiring a store.
         *
         * <p>Runtime-wide rather than per-agent on purpose: two agents in one delegation chain
         * must agree on where a document lives, or a {@code MediaRef} handed from one to the
         * other resolves for the first and not the second. {@link MediaStore#inMemory()} is a
         * process-local reference implementation; a deployment that keeps media beyond one JVM
         * needs a real backend, and owns the retention policy for it.
         */
        public Builder mediaStore(MediaStore mediaStore) {
            this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore must not be null");
            return this;
        }

        /**
         * Sets how the final answer of every agent is split into artifacts — a code block, a document — that
         * the response then carries as {@link io.ara.core.media.MediaRef}s into the {@link MediaStore}
         * (see {@code AgentResponse#artifacts()}). Defaults to {@link io.ara.core.artifact.ArtifactExtractor#none()}:
         * no artifacts, a response identical to what it was before they existed. It has no effect while the
         * store is {@link MediaStore#noop()}, which cannot hold them; {@link
         * io.ara.runtime.artifact.FencedBlockArtifactExtractor} is the one the runtime ships.
         *
         * <p>Runtime-wide rather than per-agent for the same reason as the store: two agents in one delegation
         * chain must agree on what a part is, or an artifact handed from one to the other means two things.
         */
        public Builder artifactExtractor(io.ara.core.artifact.ArtifactExtractor artifactExtractor) {
            this.artifactExtractor = Objects.requireNonNull(artifactExtractor, "artifactExtractor must not be null");
            return this;
        }

        /**
         * Sets the {@link ApprovalGate} used to route sensitive tool calls through a
         * human-in-the-loop approval flow. Optional — if not set, the HITL feature is
         * disabled entirely (even when {@link AgentConfig#humanApprovalRequired()} is
         * {@code true} on individual agents).
         *
         * <p>When set, agents with {@code humanApprovalRequired(true)} will block on the
         * gate's {@link ApprovalGate#requestApproval} before every tool dispatch, parking
         * cheaply on a virtual thread until a decision arrives or the request times out.
         *
         * <p>The same gate instance should be shared with any external surface (HTTP
         * gateway, CLI, webhook) that lists and resolves pending approvals.
         *
         * @param approvalGate the gate; never {@code null}
         * @see ApprovalGate
         */
        public Builder approvalGate(ApprovalGate approvalGate) {
            this.approvalGate = Objects.requireNonNull(approvalGate, "approvalGate must not be null");
            return this;
        }

        /**
         * ADR-033 Fase 8 (S5) — store backing {@link #grantTemporaryScope}. Defaults to a
         * fresh {@link io.ara.runtime.auth.InMemoryTemporaryScopeRegistry}, so {@code
         * grantTemporaryScope} always works without extra wiring; override only to share
         * one registry across multiple {@code AraRuntime} instances, or to persist grants
         * beyond process lifetime.
         */
        public Builder temporaryScopeRegistry(io.ara.runtime.auth.TemporaryScopeRegistry v) {
            this.temporaryScopeRegistry = Objects.requireNonNull(v, "temporaryScopeRegistry must not be null");
            return this;
        }

        /**
         * ADR-033 Fase 2b (S8, Livello 1b) — opt-in ABAC layer, on top of the scope check
         * (Fasi 1-9) that always applies. Not configured (the default) means {@link
         * #authorizationService()} carries no {@code AbacPolicyEngine} and behaves
         * identically to every phase before Fase 2b — this builds the engine and exposes
         * it via {@code AraRuntime.authorizationService()} for a caller to use explicitly;
         * see {@link io.ara.runtime.auth.AuthorizationService}'s own Javadoc for why it is
         * not silently auto-wired into every dispatch.
         *
         * <pre>{@code
         * .abacPolicies(policies -> policies
         *     .add("clearance", ClearancePolicy.standard())
         *     .combineWith(CompositeAbacPolicyEngine.CombiningAlgorithm.DENY_OVERRIDES))
         * }</pre>
         */
        public Builder abacPolicies(java.util.function.Consumer<io.ara.runtime.auth.AbacPoliciesBuilder> configure) {
            Objects.requireNonNull(configure, "configure must not be null");
            io.ara.runtime.auth.AbacPoliciesBuilder b = new io.ara.runtime.auth.AbacPoliciesBuilder();
            configure.accept(b);
            this.abacPolicyEngine = b.build();
            return this;
        }

        /**
         * Sets how long {@code delegate_task} waits for the target agent's reply before
         * failing the delegation, for every agent this runtime creates. Defaults to
         * {@value AgentDelegationTool#DEFAULT_TIMEOUT_SEC} seconds ({@link
         * AgentDelegationTool#DEFAULT_TIMEOUT_SEC}) — raise it when delegate agents
         * routinely need longer than that to finish (long reports, many-step sub-tasks),
         * since a delegation that times out is aborted server-side too: the target
         * agent's in-flight execution is cancelled, not merely abandoned by the caller.
         */
        public Builder delegationTimeout(Duration delegationTimeout) {
            this.delegationTimeout = Objects.requireNonNull(delegationTimeout, "delegationTimeout must not be null");
            return this;
        }

        /**
         * Sets the provider that supplies agent definitions at {@link AraRuntime#start()}.
         * If not set, no agents are created automatically — use
         * {@link AraRuntime#createAgent(AgentConfig)} instead.
         */
        public Builder agentProvider(AgentProvider agentProvider) {
            this.agentProvider = agentProvider;
            return this;
        }

        /**
         * Overrides the runtime configuration. If not called, defaults are used.
         * Use {@link AraRuntimeConfig#fromYaml()} to load from {@code ara.yml}.
         */
        public Builder runtimeConfig(AraRuntimeConfig runtimeConfig) {
            this.runtimeConfig = runtimeConfig;
            return this;
        }

        /**
         * Sets the interceptor chain applied to every agent execution step.
         * If not called, no interceptors are active.
         */
        public Builder interceptors(List<AgentInterceptor> interceptors) {
            this.interceptors = Objects.requireNonNull(interceptors);
            return this;
        }

        /**
         * Attaches an optional {@link ScheduleExecutionListener} to the scheduler created
         * by the resulting runtime: it observes every scheduled fire and its outcome (see
         * the listener contract — callbacks are best-effort, exceptions from them never
         * propagate). {@code null} (the default) disables notifications. Has no effect on
         * schedules registered manually against a scheduler obtained some other way.
         */
        public Builder scheduleExecutionListener(ScheduleExecutionListener listener) {
            this.scheduleExecutionListener = Objects.requireNonNull(listener, "listener must not be null");
            return this;
        }

        public AraRuntime build() {
            // Configuration is validated and the object graph assembled by RuntimeAssembler,
            // keeping this class a pure fluent configuration surface.
            return new RuntimeAssembler(this).assemble();
        }
    }
}
