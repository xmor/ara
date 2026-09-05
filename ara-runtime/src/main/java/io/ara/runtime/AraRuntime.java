package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentFuture;
import io.ara.core.agent.AgentInterceptor;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
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
import io.ara.core.memory.MemoryConfig;
import io.ara.core.memory.MemoryManager;
import io.ara.core.memory.SemanticStore;
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
import io.ara.runtime.bus.AgentDelegationTool;
import io.ara.runtime.bus.DelegatingToolRegistry;
import io.ara.runtime.bus.LocalMessageBus;
import io.ara.runtime.config.AraRuntimeConfig;
import io.ara.runtime.factory.AgentFactory;
import io.ara.runtime.factory.DefaultLlmRouter;
import io.ara.runtime.factory.DefaultRetrieverRouter;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.media.MediaStore;
import io.ara.runtime.llm.InstrumentedLlmClient;
import io.ara.runtime.memory.EvictionPolicy;
import io.ara.runtime.memory.SlidingWindowMemoryManager;
import io.ara.runtime.strategy.ExecutionPlanner;
import io.ara.core.retriever.Retriever;
import io.ara.core.retriever.RetrieverRouter;
import io.ara.runtime.strategy.PlanExecuteStrategy;
import io.ara.runtime.strategy.ReflexionStrategy;
import io.ara.runtime.strategy.RetrievalAugmentedStrategy;
import io.ara.runtime.strategy.ReactStrategy;
import io.ara.runtime.strategy.ReSpActStrategy;
import io.ara.runtime.strategy.ReflActStrategy;
import io.ara.runtime.scheduler.AgentScheduler;
import io.ara.runtime.scheduler.LocalAgentScheduler;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.hitl.ApprovalToolRegistry;
import io.ara.runtime.telemetry.TelemetryToolRegistry;
import io.ara.runtime.wiring.AggregatingToolRegistry;
import io.ara.runtime.wiring.DrainPolicy;
import io.ara.runtime.wiring.McpServerBinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
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

    /**
     * Lifecycle phases: {@code NEW → STARTED ⇄ STOPPED}. Explicit restart via
     * {@link #start()} is supported (a fresh executor is provisioned); only the
     * <em>implicit</em> auto-start performed by {@link #createAgent} / {@link #submit}
     * is limited to the {@code NEW} phase — see {@link #autoStart()}.
     */
    private enum Lifecycle { NEW, STARTED, STOPPED }

    private final AraRuntimeConfig config;
    private final AgentFactory     factory;
    private final AgentRegistry    registry;
    private final AgentProvider    agentProvider;
    private final AgentScheduler   scheduler;
    private final InstanceContextStore instanceContextStore;
    private final ApprovalGate     approvalGate;
    private final io.ara.runtime.auth.TemporaryScopeRegistry temporaryScopeRegistry;
    private final QuiescenceTracker quiescenceTracker = new QuiescenceTracker();
    private final Map<String, LlmClient> llmClients;
    private final ToolRegistry     toolRegistry;
    private final Map<String, Retriever> retrievers;

    /**
     * Serializes lifecycle transitions and agent creation/submission, so a
     * {@link #stop()} can't race a concurrent {@link #createAgent} (which would leak an
     * agent into a stopped runtime) or a {@link #submit} (which would hand the task to
     * an executor that is being shut down).
     */
    private final Object lifecycleLock = new Object();
    private volatile Lifecycle lifecycle = Lifecycle.NEW;
    private volatile Executor agentExecutor;

    private AraRuntime(
            AraRuntimeConfig config,
            AgentFactory factory,
            AgentRegistry registry,
            AgentProvider agentProvider,
            AgentScheduler scheduler,
            InstanceContextStore instanceContextStore,
            ApprovalGate approvalGate,
            io.ara.runtime.auth.TemporaryScopeRegistry temporaryScopeRegistry,
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
     */
    public void start() {
        synchronized (lifecycleLock) {
            if (lifecycle == Lifecycle.STARTED) return;
            log.info("AraRuntime [{}] starting", config.name());
            this.agentExecutor = Executors.newVirtualThreadPerTaskExecutor();

            if (agentProvider != null) {
                for (AgentConfig cfg : agentProvider.configs()) {
                    factory.create(cfg);
                    log.info("  + agent [{}] type=[{}]", cfg.agentId().value(), cfg.agentType());
                }
            }

            scheduler.start();
            lifecycle = Lifecycle.STARTED;
            log.info("AraRuntime [{}] started — {} agent(s) registered",
                    config.name(), registry.count());
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
     */
    public void stop() {
        synchronized (lifecycleLock) {
            if (lifecycle != Lifecycle.STARTED) return;
            log.info("AraRuntime [{}] stopping", config.name());
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
                shutdownExecutor();
                lifecycle = Lifecycle.STOPPED;
                log.info("AraRuntime [{}] stopped", config.name());
            }
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
     * <p>Must be called while holding {@link #lifecycleLock}.
     */
    private void autoStart() {
        if (lifecycle == Lifecycle.STOPPED) {
            throw new IllegalStateException("AraRuntime [" + config.name()
                    + "] has been stopped — call start() to restart it explicitly"
                    + " before creating agents or submitting tasks");
        }
        start();   // no-op when already STARTED
    }

    /**
     * Shuts the shared executor down gracefully, waiting up to
     * {@link AraRuntimeConfig#shutdownTimeoutSec()} seconds for in-flight tasks to
     * finish before forcing it.
     */
    private void shutdownExecutor() {
        if (!(agentExecutor instanceof ExecutorService es)) return;
        long timeoutSec = config.shutdownTimeoutSec();
        es.shutdown();
        try {
            if (!es.awaitTermination(timeoutSec, TimeUnit.SECONDS)) {
                log.warn("AraRuntime [{}] executor did not drain within {}s — forcing shutdownNow",
                        config.name(), timeoutSec);
                es.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            es.shutdownNow();
        }
    }

    // ── agent management ──────────────────────────────────────────────────────

    /**
     * Creates a new agent from the given config and registers it in the registry.
     * Starts the runtime automatically if it was never started; throws
     * {@link IllegalStateException} if the runtime was stopped — restart it
     * explicitly via {@link #start()} first.
     */
    public AraAgent createAgent(AgentConfig config) {
        synchronized (lifecycleLock) {
            autoStart();
            return factory.create(config);
        }
    }

    /**
     * Creates a new agent wired with the given {@link AgentContract} and registers it.
     * Same lifecycle rules as {@link #createAgent(AgentConfig)}.
     */
    public AraAgent createAgent(AgentConfig config, AgentContract contract) {
        synchronized (lifecycleLock) {
            autoStart();
            return factory.create(config, contract);
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
     * <p>Does not otherwise change how {@code agent} resolves its tools or peer visibility
     * for this call — narrowing what the *delegation chain* downstream of this call can do
     * (Fase 5, already wired), not yet what {@code agent} itself can invoke directly for
     * this specific execution (Fase 5.4/6.3, not implemented: {@code AgentInstance} still
     * builds its {@code AgentView}/tool registry once, at {@code createAgent} time, from
     * {@code agent.config()} — not fresh per call from this method's {@code userScopes}).
     *
     * @param agent      the agent to run; must already be created via {@link #createAgent}
     * @param task       the task to execute
     * @param userId     the user this call is made on behalf of
     * @param userScopes the user's own scopes
     * @return the agent's response, exactly as {@link AraAgent#execute(AgentTask)} would return it
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
        synchronized (lifecycleLock) {
            autoStart();
            return factory.replace(config);
        }
    }

    /**
     * Creates a new agent wired with the given {@link AgentContract} and atomically
     * replaces whatever agent currently holds {@code config.agentId()} — see {@link
     * #replaceAgent(AgentConfig)}.
     */
    public AraAgent replaceAgent(AgentConfig config, AgentContract contract) {
        synchronized (lifecycleLock) {
            autoStart();
            return factory.replace(config, contract);
        }
    }

    /**
     * Applies {@code update} to the current config of the agent registered under {@code id}
     * and hot-swaps it in place (ADR-039) — no new instance, no lost sessions. A task
     * already in flight always finishes with the configuration it started with; only
     * sessions created after this call observe the updated one.
     *
     * <p>Serialized on the same {@link #lifecycleLock} as {@link #createAgent}/{@link
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
        synchronized (lifecycleLock) {
            autoStart();
            AraAgent agent = registry.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No agent registered with id [" + id.value() + "]"));
            if (!(agent instanceof Reconfigurable reconfigurable)) {
                throw new UnsupportedOperationException(
                        "Agent [" + id.value() + "] does not support hot reconfiguration");
            }
            reconfigurable.reconfigure(update.apply(agent.config()));
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
    public boolean isRunning() { return lifecycle == Lifecycle.STARTED; }

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
    public void destroyAgent(AraAgent agent) {
        Objects.requireNonNull(agent, "agent must not be null");

        synchronized (lifecycleLock) {
            // Guard: runtime already stopped → all agents were destroyed by stop()
            if (lifecycle == Lifecycle.STOPPED) {
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
        registry.findById(agentId)
                .filter(a -> a instanceof SessionScoped)
                .ifPresent(a -> ((SessionScoped) a).terminate(sessionId));
    }

    /**
     * Immediately removes {@code sessionId} from {@code agentId} — a no-op if the agent
     * isn't registered or doesn't manage sessions ({@link SessionScoped}).
     */
    public void invalidateSession(AgentId agentId, SessionId sessionId) {
        registry.findById(agentId)
                .filter(a -> a instanceof SessionScoped)
                .ifPresent(a -> ((SessionScoped) a).invalidateSession(sessionId));
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
        registry.findById(agentId)
                .filter(a -> a instanceof SessionScoped)
                .ifPresent(a -> ((SessionScoped) a).cancelAllSessions());
    }

    /**
     * Returns how many sessions are currently held open for {@code agentId} — {@code 0}
     * if the agent isn't registered or doesn't manage sessions ({@link SessionScoped}).
     */
    public int activeSessionCount(AgentId agentId) {
        return registry.findById(agentId)
                .filter(a -> a instanceof SessionScoped)
                .map(a -> ((SessionScoped) a).activeSessionCount())
                .orElse(0);
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
     *         (a runtime cannot be built with zero registered clients — see
     *         {@link Builder#validate()})
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
    public Executor executor() { return agentExecutor; }

    /**
     * Returns the shared {@link InstanceContextStore} (ADR-036) — one entry per agent,
     * readable live by {@code PromptShaper}s and {@code AraTool} instances alike, and
     * updatable at any time without recreating the agent. Created automatically (empty)
     * if not supplied via {@link Builder#instanceContextStore(InstanceContextStore)}.
     * Entries are cleared automatically when their agent is destroyed via {@link
     * #destroyAgent(AraAgent)} or {@link #stop()}.
     */
    public InstanceContextStore instanceContextStore() { return instanceContextStore; }

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
        Executor executor;
        synchronized (lifecycleLock) {
            autoStart();
            executor = agentExecutor;
        }

        // Register BEFORE handing off — prevents a race where the task
        // completes before we even start tracking it
        quiescenceTracker.taskStarted();
        try {
            AgentFuture future = io.ara.core.agent.AraAgents.executeAsync(agent, task, executor);

            // Release on completion (success OR failure)
            future.async().whenComplete((response, error) ->
                    quiescenceTracker.taskFinished());
            return future;

        } catch (RuntimeException e) {
            // Task was never actually submitted (e.g. RejectedExecutionException)
            quiescenceTracker.taskFinished();
            throw e;
        }
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
        return quiescenceTracker.awaitQuiescence(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * Returns the number of tasks currently in-flight (submitted via
     * {@link #submit} but not yet completed). Useful for health-check
     * endpoints and diagnostics.
     */
    public int inFlightTaskCount() {
        return quiescenceTracker.inFlightCount();
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

        private final java.util.Map<String, LlmClient> namedClients = new java.util.LinkedHashMap<>();
        private String defaultClientId = "default";
        private final java.util.Map<String, Retriever> namedRetrievers = new java.util.LinkedHashMap<>();
        private String defaultRetrieverId;
        private Function<AgentConfig, MemoryManager> memoryManagerFactory;
        private EmbeddingClient embeddingClient;
        private SemanticStore   semanticStore;
        private ToolRegistry toolRegistry;
        private Function<AgentConfig, ToolRegistry> toolRegistryFactory;
        private InstanceContextStore instanceContextStore;
        private AraTelemetry telemetry = AraTelemetry.noop();
        private SessionStore sessionStore = SessionStore.noop();
        private io.ara.core.trace.TraceStore traceStore;
        private io.ara.core.trace.BlobStore  traceBlobStore;
        private MediaStore   mediaStore   = MediaStore.noop();
        private ApprovalGate approvalGate;
        private io.ara.runtime.auth.TemporaryScopeRegistry temporaryScopeRegistry =
                new io.ara.runtime.auth.InMemoryTemporaryScopeRegistry();
        private Duration delegationTimeout = Duration.ofSeconds(AgentDelegationTool.DEFAULT_TIMEOUT_SEC);
        private AgentProvider agentProvider;
        private AraRuntimeConfig runtimeConfig;
        private List<AgentInterceptor>    interceptors     = List.of();
        private final List<ExecutionStrategy> extraStrategies = new java.util.ArrayList<>();
        private LlmClientFactory          llmClientFactory;
        private final java.util.Map<String, McpServerBinding> mcpServers = new java.util.LinkedHashMap<>();

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

        public Builder memoryManagerFactory(Function<AgentConfig, MemoryManager> factory) {
            this.memoryManagerFactory = factory;
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

        public AraRuntime build() {
            validate();

            AraRuntimeConfig cfg = runtimeConfig != null
                    ? runtimeConfig
                    : AraRuntimeConfig.defaults();
            InstanceContextStore ctxStore = instanceContextStore != null
                    ? instanceContextStore
                    : new InstanceContextStore();

            AgentRegistry   registry   = new AgentRegistry();
            // ADR-0086: a builder that never calls memoryManagerFactory(...) still gets a
            // real working memory — SlidingWindowMemoryManager wired from AgentConfig.memory()
            // — instead of the unlimited InMemoryMemoryManager, whenever a token budget is
            // actually configured. Budget 0 (the record's own default) reproduces exactly
            // today's unlimited behaviour, so nothing changes for a config that never asked
            // for a limit.
            Function<AgentConfig, MemoryManager> memFactory = memoryManagerFactory != null
                    ? memoryManagerFactory
                    : agentCfg -> defaultMemoryManager(agentCfg, registry);
            // ADR-033 Fase 7: the same ApprovalGate that gates a tool's own outgoing calls
            // (ApprovalToolRegistry, below) also gates delegation INTO an agent that opted
            // in via requiresApproval() — one Builder.approvalGate(...) call now covers
            // both surfaces. null (the default) reproduces pre-Fase-7 behavior exactly.
            LocalMessageBus messageBus = new LocalMessageBus(registry, telemetry, approvalGate, temporaryScopeRegistry);

            // Built once here (rather than inline in buildAgentFactory) so the very same
            // instrumented clients back both AgentFactory's registry and the router
            // ReflexionStrategy uses to resolve StrategyConfig.Reflexion#reflectionProvider()
            // — no separate, uninstrumented path for reflection calls.
            Map<String, LlmClient> instrumentedClients = instrumentClients();
            ExecutionPlanner planner = buildExecutionPlanner(instrumentedClients);
            // Populated by resolvePerAgentToolRegistry()'s wrapper every time the factory
            // produces a registry for a newly created agent — the accumulator backing
            // discoveryRegistry() below when toolRegistryFactory is in play.
            Map<String, ToolRegistry> perAgentRegistries = new java.util.concurrent.ConcurrentHashMap<>();
            Function<AgentConfig, ToolRegistry> perAgentToolRegistry = resolvePerAgentToolRegistry(perAgentRegistries);

            AgentFactory agentFactory = buildAgentFactory(
                    instrumentedClients, planner, perAgentToolRegistry, messageBus, memFactory, registry);

            AgentScheduler scheduler = new LocalAgentScheduler(registry);
            return new AraRuntime(cfg, agentFactory, registry, agentProvider, scheduler, ctxStore,
                    approvalGate, temporaryScopeRegistry,
                    Map.copyOf(instrumentedClients), discoveryRegistry(perAgentRegistries),
                    Map.copyOf(namedRetrievers));
        }

        /**
         * The default {@link MemoryManager} for an agent whose builder never called
         * {@link #memoryManagerFactory}: {@link InMemoryMemoryManager} (today's behaviour,
         * unlimited window) when {@code agentCfg.memory().workingMemoryTokenBudget()} is 0,
         * or a fully wired {@link SlidingWindowMemoryManager} otherwise (ADR-0086). The
         * summariser agent is resolved by id from {@code registry} on every call rather than
         * once, since it may not be registered yet the first time an agent that names it is
         * created — {@code registry} is mutated in place by {@code create(...)} after
         * {@code build()} returns.
         */
        private MemoryManager defaultMemoryManager(AgentConfig agentCfg, AgentRegistry registry) {
            MemoryConfig memory = agentCfg.memory();
            if (memory.workingMemoryTokenBudget() <= 0) {
                return new InMemoryMemoryManager();
            }
            AraAgent summarizer = null;
            String summarizerId = memory.contextSummarizerAgentId();
            if (summarizerId != null && !summarizerId.isBlank()) {
                summarizer = registry.findById(AgentId.of(summarizerId)).orElse(null);
            }
            return new SlidingWindowMemoryManager(
                    memory.workingMemoryTokenBudget(),
                    EvictionPolicy.from(memory.workingMemoryEviction()),
                    summarizer, semanticStore, embeddingClient, agentCfg.agentId().value());
        }

        /** Fails fast on configurations {@link #build()} could not wire correctly. */
        private void validate() {
            if (namedClients.isEmpty()) {
                throw new IllegalStateException(
                        "AraRuntime.Builder: at least one llmClient must be registered");
            }
            if (!namedClients.containsKey(defaultClientId)) {
                throw new IllegalStateException(
                        "AraRuntime.Builder: default LLM client '" + defaultClientId
                                + "' is not among the registered clients " + namedClients.keySet()
                                + " — register it via llmClient(id, client) or fix defaultLlmClient(id)");
            }
            if (!namedRetrievers.isEmpty() && defaultRetrieverId != null
                    && !namedRetrievers.containsKey(defaultRetrieverId)) {
                throw new IllegalStateException(
                        "AraRuntime.Builder: default retriever '" + defaultRetrieverId
                                + "' is not among the registered retrievers " + namedRetrievers.keySet()
                                + " — register it via retriever(id, retriever) or fix defaultRetriever(id)");
            }
            if (toolRegistry != null && toolRegistryFactory != null) {
                throw new IllegalStateException(
                        "AraRuntime.Builder: set either toolRegistry(...) or toolRegistryFactory(...), not both");
            }
        }

        /** Wraps every registered client so every LLM call — including reflection — is instrumented. */
        private Map<String, LlmClient> instrumentClients() {
            Map<String, LlmClient> instrumentedClients = new java.util.LinkedHashMap<>();
            namedClients.forEach((id, client) ->
                    instrumentedClients.put(id, new InstrumentedLlmClient(client, telemetry)));
            return instrumentedClients;
        }

        /**
         * Resolves the tool-registry-per-agent function from whichever of the two mutually
         * exclusive options was set. When {@link #toolRegistryFactory} is in play, wraps it
         * so every invocation (one per agent, in {@code AgentFactory}) also records its
         * result into {@code perAgentRegistries} — the accumulator {@link
         * #discoveryRegistry} later reads from, since the factory itself is invoked deep
         * inside {@code AgentFactory}, out of {@code AraRuntime}'s direct reach otherwise.
         */
        private Function<AgentConfig, ToolRegistry> resolvePerAgentToolRegistry(
                Map<String, ToolRegistry> perAgentRegistries) {
            if (toolRegistryFactory != null) {
                return agentCfg -> {
                    ToolRegistry resolved = toolRegistryFactory.apply(agentCfg);
                    perAgentRegistries.put(agentCfg.agentId().value(), resolved);
                    return resolved;
                };
            }
            ToolRegistry baseRegistry = toolRegistry != null ? toolRegistry : ToolRegistry.empty();
            return agentCfg -> baseRegistry;
        }

        /**
         * Builds the {@link ToolRegistry} exposed via {@link AraRuntime#toolRegistry()} for
         * discovery purposes — see that method's javadoc for the three cases.
         */
        private ToolRegistry discoveryRegistry(Map<String, ToolRegistry> perAgentRegistries) {
            if (toolRegistry != null) return toolRegistry;
            if (toolRegistryFactory != null) return new AggregatingToolRegistry(perAgentRegistries);
            return ToolRegistry.empty();
        }

        /** Registers the built-in strategies (react, respact, plan_execute, reflexion, reflact), RAG variants, and any extras. */
        private ExecutionPlanner buildExecutionPlanner(Map<String, LlmClient> instrumentedClients) {
            LlmRouter reflectionRouter =
                    new DefaultLlmRouter(instrumentedClients, defaultClientId, llmClientFactory);

            ReactStrategy       reactStrategy     = new ReactStrategy();
            ReSpActStrategy     respactStrategy   = new ReSpActStrategy();
            PlanExecuteStrategy planStrategy      = new PlanExecuteStrategy();
            ReflexionStrategy   reflexionStrategy = new ReflexionStrategy(reactStrategy, reflectionRouter);
            // Same reflectionRouter as ReflexionStrategy — both support routing the
            // critique call to a different provider than the main loop's own model.
            ReflActStrategy     reflactStrategy   = new ReflActStrategy(reflectionRouter);

            ExecutionPlanner.Builder plannerBuilder = ExecutionPlanner.builder()
                    .register(reactStrategy)
                    .register(respactStrategy)
                    .register(planStrategy)
                    .register(reflexionStrategy)
                    .register(reflactStrategy);

            if (!namedRetrievers.isEmpty()) {
                RetrieverRouter retrieverRouter = new DefaultRetrieverRouter(namedRetrievers, defaultRetrieverId);
                plannerBuilder.register(RetrievalAugmentedStrategy.wrap(reactStrategy,   retrieverRouter));
                plannerBuilder.register(RetrievalAugmentedStrategy.wrap(respactStrategy, retrieverRouter));
                plannerBuilder.register(RetrievalAugmentedStrategy.wrap(planStrategy,    retrieverRouter));
                plannerBuilder.register(RetrievalAugmentedStrategy.wrap(reflactStrategy, retrieverRouter));
            }

            extraStrategies.forEach(plannerBuilder::register);
            return plannerBuilder.build();
        }

        /** Assembles the {@link AgentFactory}: LLM clients, MCP servers, tool registry, and cross-cutting concerns. */
        private AgentFactory buildAgentFactory(
                Map<String, LlmClient> instrumentedClients,
                ExecutionPlanner planner,
                Function<AgentConfig, ToolRegistry> perAgentToolRegistry,
                LocalMessageBus messageBus,
                Function<AgentConfig, MemoryManager> memFactory,
                AgentRegistry registry) {

            AgentFactory.Builder factoryBuilder = AgentFactory.builder()
                    .defaultLlmClient(defaultClientId);
            // Reuses instrumentedClients (built once in build()) — one wrapped instance per
            // registered client, not two. InstrumentedLlmClient adds no overhead beyond an
            // interface dispatch when telemetry is AraTelemetry.noop().
            instrumentedClients.forEach(factoryBuilder::llmClient);
            if (llmClientFactory != null) factoryBuilder.llmClientFactory(llmClientFactory);
            mcpServers.forEach((id, binding) ->
                    factoryBuilder.mcpServer(id, binding.connector(), binding.toolsAdapter()));
            if (traceStore != null) factoryBuilder.traceEmission(traceStore, traceBlobStore);   // ADR-0068 D1

            return factoryBuilder
                    .toolRegistryFactory(agentCfg -> {
                        ToolRegistry base = new DelegatingToolRegistry(
                                perAgentToolRegistry.apply(agentCfg), messageBus, agentCfg.agentId().value(),
                                delegationTimeout, agentCfg.delegateStateAccess(), sessionStore);
                        // ADR-0067 D6: insert the approval decorator whenever a gate is
                        // configured, and let it decide per call whether a gate is needed
                        // (agent flag OR the tool's own ToolSpec.approvalRequired()) — so a
                        // high-risk tool is gated even when the agent's flag is false.
                        ToolRegistry withApproval = approvalGate != null
                                ? new ApprovalToolRegistry(base, approvalGate, agentCfg)
                                : base;
                        return new TelemetryToolRegistry(withApproval, telemetry);
                    })
                    .memoryManagerFactory(memFactory)
                    .executionPlanner(planner)
                    .telemetry(telemetry)
                    .sessionStore(sessionStore)
                    .mediaStore(mediaStore)
                    .interceptors(interceptors)
                    .registry(registry)
                    .build();
        }
    }
}
