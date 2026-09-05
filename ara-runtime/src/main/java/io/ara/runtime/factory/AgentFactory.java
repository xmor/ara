package io.ara.runtime.factory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentInterceptor;
import io.ara.core.agent.AgentLifecycleManager;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmClientFactory;
import io.ara.core.llm.LlmTransport;
import io.ara.core.mcp.McpClient;
import io.ara.core.media.MediaStore;
import io.ara.core.memory.MemoryManager;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.agent.AgentInstance;
import io.ara.runtime.agent.AgentRegistry;
import io.ara.runtime.agent.ContractEnforcingAgent;
import io.ara.runtime.bus.DelegatingToolRegistry;
import io.ara.runtime.contract.KnowledgeBasePromptShaper;
import io.ara.runtime.interceptor.AgentInterceptorChain;
import io.ara.runtime.strategy.ExecutionPlanner;
import io.ara.runtime.wiring.DefaultResourceRegistry;
import io.ara.runtime.wiring.DefaultWiringFactory;
import io.ara.runtime.wiring.DrainPolicy;
import io.ara.runtime.wiring.McpServerBinding;
import io.ara.runtime.wiring.WiringFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The sole factory responsible for creating and destroying {@link AraAgent} instances.
 *
 * <p>Every {@code AraAgent} is constructed here, wired with all its runtime
 * collaborators, and immediately registered in the {@link AgentRegistry}.
 * This keeps the object graph fully explicit — no reflection, no DI framework,
 * no magic. The wiring is readable by following the constructor calls in this class.
 *
 * <p>{@code AgentFactory} is built once at startup via its {@link Builder} and then
 * lives for the lifetime of the platform node. It is safe to call {@link #create}
 * from multiple threads concurrently.
 *
 * <p><b>When to use {@code AgentFactory} directly vs {@link AraRuntime}:</b>
 * For most use cases — standalone scripts, demos, single-process runtimes — prefer
 * {@code AraRuntime.builder()}: it wires the message bus, planner, and stubs automatically.
 * Use {@code AgentFactory} directly only when you need advanced per-agent wiring that
 * {@code AraRuntime} does not expose, such as a custom {@code toolRegistryFactory} or
 * {@code llmClientFactory} (as done in {@code AraPlatformFactory}).
 *
 * <p>Usage:
 * <pre>{@code
 * AgentFactory factory = AgentFactory.builder()
 *     .llmClient(openAiClient)
 *     .memoryManagerFactory(cfg -> new InMemoryMemoryManager())
 *     .toolRegistry(registry)
 *     .executionPlanner(planner)
 *     .interceptors(List.of(tracingInterceptor, auditInterceptor))
 *     .registry(agentRegistry)
 *     .build();
 *
 * AraAgent agent = factory.create(AgentConfig.defaults()
 *     .agentType("researcher")
 *     .systemPrompt("You are an expert researcher...")
 *     .build());
 * }</pre>
 */
public final class AgentFactory implements AgentLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final String defaultLlmClientId;
    private final LlmClientFactory llmClientFactory;
    private final MediaStore       mediaStore;
    private final Function<AgentConfig, MemoryManager> memoryManagerFactory;
    private final Function<AgentConfig, ToolRegistry>  toolRegistryFactory;
    private final ExecutionPlanner executionPlanner;
    private final List<AgentInterceptor> interceptors;
    private final AgentRegistry registry;
    private final AraTelemetry telemetry;
    private final SessionStore sessionStore;

    /**
     * ADR-0068 D1 — when both are set, every agent this factory registers is wrapped in a
     * {@link io.ara.runtime.trace.TraceEmittingAgent} (outermost decorator) so each
     * execution appends a run trace. Both {@code null} by default: no wrapper, behaviour
     * unchanged.
     */
    private final io.ara.core.trace.TraceStore traceStore;
    private final io.ara.core.trace.BlobStore  traceBlobStore;

    /**
     * Shared, ref-counted, versioned catalog of LLM transports (ADR-039 §3-4), keyed by
     * {@code transportId} for named clients and by content-addressed id for inline
     * overrides. One instance per {@code AgentFactory}, seeded once at construction with
     * every named client from the builder — every agent this factory creates shares it,
     * which is what lets a single {@link #publishLlmTransport} update reach every agent
     * that references that transport, without iterating them.
     */
    private final DefaultResourceRegistry<LlmTransport, LlmClient> llmTransports;

    /**
     * Shared, ref-counted, versioned catalog of MCP connections (ADR-039 §"Il vincolo
     * nuovo: risorse con ciclo di vita"). {@code null} when the builder never registered
     * an MCP server — in that case every agent's tool registry is exactly {@code
     * toolRegistryFactory.apply(config)}, unmanaged, as before ADR-039. Connections are
     * opened lazily (first session to reference that server id) and closed once their
     * ref-count reaches zero after being retired by {@link #publishMcpServer}.
     */
    private final DefaultResourceRegistry<Supplier<McpClient>, McpClient> mcpTransports;
    private final Map<String, McpServerBinding> mcpServers;

    private AgentFactory(Builder builder) {
        this.defaultLlmClientId   = builder.defaultLlmClientId;
        this.llmClientFactory     = builder.llmClientFactory;
        this.memoryManagerFactory = builder.memoryManagerFactory;
        this.toolRegistryFactory  = builder.toolRegistryFactory;
        this.executionPlanner     = builder.executionPlanner;
        this.interceptors         = List.copyOf(builder.interceptors);
        this.registry             = builder.registry;
        this.telemetry            = builder.telemetry;
        this.sessionStore         = builder.sessionStore;
        this.mediaStore           = builder.mediaStore;
        this.traceStore           = builder.traceStore;
        this.traceBlobStore       = builder.traceBlobStore;

        this.llmTransports = new DefaultResourceRegistry<>(
                transport -> {
                    if (this.llmClientFactory == null) {
                        throw new IllegalStateException(
                                "No LlmClientFactory configured to build a transport for modelName='"
                                + transport.modelName() + "'");
                    }
                    return this.llmClientFactory.create(transport);
                },
                client -> { /* LlmClient has no real teardown */ }
        );
        builder.llmClientRegistry.forEach(llmTransports::seed);

        this.mcpServers = Map.copyOf(builder.mcpServers);
        this.mcpTransports = mcpServers.isEmpty()
                ? null
                : DefaultResourceRegistry.forCloseable(Supplier::get);
    }

    /**
     * Creates a new {@link AraAgent} instance from the given configuration and
     * immediately registers it in the {@link AgentRegistry}.
     *
     * <p>Each agent receives its own private {@link MemoryManager} (created via
     * the memory manager factory) and its own {@link io.ara.runtime.lifecycle.AgentStateMachine}. All other
     * collaborators ({@code LlmClient}, {@code ToolRegistry})
     * are shared among all instances because they are stateless and thread-safe.
     *
     * @param config the configuration describing how the agent should behave
     * @return the newly created and registered agent; never {@code null}
     */
    public AraAgent create(AgentConfig config) {
        Objects.requireNonNull(config, "config must not be null");

        // Auto-wire the KB prompt shaper when the config requests it. Any other
        // contract (input/output processors) must be supplied explicitly via
        // create(config, contract) — the contract is orthogonal to config.
        AgentContract kbContract = knowledgeBaseContract(config);
        if (kbContract != null) {
            return create(config, kbContract);
        }

        AraAgent agent = instrumentTracing(buildInstance(config));

        registry.register(agent);
        log.info("Created agent [{}] type=[{}] strategy=[{}]",
                config.agentId().value(), config.agentType(), config.plannerStrategy());
        return agent;
    }

    /**
     * Creates a new {@link AraAgent} wired with the given {@link AgentContract} and
     * registers it in the {@link AgentRegistry}.
     *
     * <p>When {@code contract} is non-null and non-empty, the agent is wrapped in a
     * {@link ContractEnforcingAgent} decorator before registration, so every caller —
     * direct Java or {@link io.ara.core.bus.MessageBus} — passes through the
     * contract. When {@code contract} is {@code null} or {@link AgentContract#isEmpty()},
     * this method is identical to {@link #create(AgentConfig)}.
     *
     * @param config   the agent configuration
     * @param contract the I/O contract; {@code null} is treated as {@link AgentContract#empty()}
     * @return the registered agent (possibly wrapped in a ContractEnforcingAgent)
     */
    public AraAgent create(AgentConfig config, AgentContract contract) {
        Objects.requireNonNull(config, "config must not be null");

        AraAgent toRegister = instrumentTracing(buildAndWrap(config, contract));

        registry.register(toRegister);
        log.info("Created agent [{}] type=[{}] strategy=[{}] contract={}",
                config.agentId().value(), config.agentType(), config.plannerStrategy(),
                contract != null && !contract.isEmpty() ? "yes" : "none");
        return toRegister;
    }

    /**
     * Creates a new {@link AraAgent} from {@code config} and atomically replaces
     * whatever agent currently holds {@code config.agentId()} in the {@link
     * AgentRegistry} — see {@link AgentRegistry#replace}.
     *
     * <p>Unlike {@link #create(AgentConfig)}, a duplicate id never throws
     * {@code IllegalArgumentException}: the previous agent under this id (if any) is
     * simply displaced. This method does not terminate the displaced agent — it is the
     * caller's decision whether to call {@link AraAgent#terminate()} on it immediately
     * (forced cutover) or leave it to finish any in-flight sessions naturally before
     * dropping the last reference to it (graceful replacement, e.g. after a config
     * update from a management UI).
     *
     * @param config the configuration for the replacement agent
     * @return the newly created, now-registered agent; never {@code null}
     */
    public AraAgent replace(AgentConfig config) {
        Objects.requireNonNull(config, "config must not be null");

        AgentContract kbContract = knowledgeBaseContract(config);
        if (kbContract != null) {
            return replace(config, kbContract);
        }

        AraAgent agent = instrumentTracing(buildInstance(config));

        AraAgent previous = registry.replace(agent);
        log.info("Replaced agent [{}] type=[{}] strategy=[{}] (displaced previous agent: {})",
                config.agentId().value(), config.agentType(), config.plannerStrategy(),
                previous != null);
        return agent;
    }

    /**
     * Creates a new {@link AraAgent} wired with {@code contract} and atomically
     * replaces whatever agent currently holds {@code config.agentId()} — see {@link
     * #replace(AgentConfig)}.
     *
     * @param config   the agent configuration
     * @param contract the I/O contract; {@code null} is treated as {@link AgentContract#empty()}
     * @return the newly created, now-registered agent (possibly wrapped in a {@code ContractEnforcingAgent})
     */
    public AraAgent replace(AgentConfig config, AgentContract contract) {
        Objects.requireNonNull(config, "config must not be null");

        AraAgent toRegister = instrumentTracing(buildAndWrap(config, contract));

        AraAgent previous = registry.replace(toRegister);
        log.info("Replaced agent [{}] type=[{}] strategy=[{}] contract={} (displaced previous agent: {})",
                config.agentId().value(), config.agentType(), config.plannerStrategy(),
                contract != null && !contract.isEmpty() ? "yes" : "none",
                previous != null);
        return toRegister;
    }

    /**
     * Assembles a fresh {@link AgentInstance} for {@code config}: a {@link ToolRegistry}
     * built once here (exactly as before ADR-039 — {@link Builder#toolRegistryFactory}'s
     * contract of "invoked once per agent" is preserved), and a {@link WiringFactory}
     * that resolves the LLM transport per session from the shared, ref-counted {@link
     * #llmTransports} registry (ADR-039 §3-4) — the only part of the wiring that varies
     * per session is which registry <em>version</em> gets pinned, not whether the tool
     * registry gets rebuilt.
     */
    private AgentInstance buildInstance(AgentConfig config) {
        Function<SessionId, MemoryManager> sessionMemoryFactory = sid -> memoryManagerFactory.apply(config);
        ToolRegistry toolRegistry = toolRegistryFactory.apply(config);
        WiringFactory wiringFactory = new DefaultWiringFactory(
                llmTransports, defaultLlmClientId, mcpTransports, mcpServers, cfg -> toolRegistry,
                mediaStore);
        AgentInterceptorChain chain = new AgentInterceptorChain(interceptors);
        return new AgentInstance(config, wiringFactory, sessionMemoryFactory, executionPlanner, chain, telemetry, sessionStore);
    }

    /**
     * Builds the auto-wired KB {@link AgentContract} when {@code config} requests one
     * (a knowledge base id set and {@code search_documents} enabled), or {@code null}
     * otherwise. Shared by {@link #create(AgentConfig)} and {@link #replace(AgentConfig)}.
     */
    private AgentContract knowledgeBaseContract(AgentConfig config) {
        boolean hasKb = config.knowledgeBaseId() != null && !config.knowledgeBaseId().isBlank()
                && config.enabledTools().contains("search_documents");
        return hasKb
                ? AgentContract.builder().addPromptShaper(new KnowledgeBasePromptShaper(config)).build()
                : null;
    }

    /**
     * Builds a fresh {@link AgentInstance} for {@code config} and wraps it in a {@link
     * ContractEnforcingAgent} when {@code contract} is non-null and non-empty. Shared by
     * the two-argument {@link #create(AgentConfig, AgentContract)} and {@link
     * #replace(AgentConfig, AgentContract)}.
     */
    private AraAgent buildAndWrap(AgentConfig config, AgentContract contract) {
        boolean enforcing = contract != null && !contract.isEmpty();
        // Validated before anything is built: a rejected configuration must not leave a
        // half-built agent — and its tool registry — behind with no one to dispose of it.
        if (enforcing) {
            rejectUnsatisfiableOutputSchema(config, contract);
        }
        AgentInstance inner = buildInstance(config);
        return enforcing ? new ContractEnforcingAgent(inner, contract) : inner;
    }

    /**
     * ADR-0068 D1 — wraps {@code agent} in a {@link io.ara.runtime.trace.TraceEmittingAgent}
     * (outermost, forwards the marker interfaces like {@code ContractEnforcingAgent}) when
     * a trace store and blob store were both configured; otherwise returns {@code agent}
     * unchanged.
     */
    private AraAgent instrumentTracing(AraAgent agent) {
        return traceStore != null && traceBlobStore != null
                ? new io.ara.runtime.trace.TraceEmittingAgent(agent, traceStore, traceBlobStore)
                : agent;
    }

    /**
     * Rejects the one combination of config and contract that cannot be honoured: an output
     * schema declared on the contract while the agent's LLM profile asks for the <em>native</em>
     * schema path.
     *
     * <p>A structured-output contract reaches the model by exactly one route — {@code
     * OutputFormatEnforcer} appending the schema to the system prompt — and {@code
     * ContractEnforcer} takes that route only when {@code nativeJsonSchema} is {@code false}.
     * When it is {@code true}, the schema is meant to travel as a provider-native
     * {@code response_format}, and <strong>no ARA adapter implements that</strong>: {@code
     * CallParameterUtils} forwards temperature, topP and maxOutputTokens, and nothing reads
     * {@code LlmCallContext.outputJsonSchema()}.
     *
     * <p>So the combination produces a model that was never told about the schema, answering in
     * prose, and an output validator that rejects every answer it gives — a task that fails on
     * every run with a message about a missing field, pointing at the model rather than at the
     * flag that caused it. Failing here instead turns a puzzle into a sentence, at agent
     * creation rather than on the first task.
     *
     * <p>The alternative was to implement the native path in the adapters that support it. It
     * was rejected for now because provider support for {@code response_format: json_schema} is
     * per-<em>endpoint</em> rather than per-provider — the same lesson the media work learned
     * the hard way with {@code file} content parts on OpenAI-compatible gateways — so it needs
     * the same declared-capability treatment, which is a change of its own rather than a
     * by-product of closing this hole.
     *
     * @throws IllegalStateException if the contract's output schema could never reach the model
     */
    private static void rejectUnsatisfiableOutputSchema(AgentConfig config, AgentContract contract) {
        if (contract.outputSchema() == null || !config.nativeJsonSchema()) {
            return;
        }
        throw new IllegalStateException(
                "Agent [" + config.agentId().value() + "] declares an output schema on its "
                        + "AgentContract while its LLM profile sets nativeJsonSchema(true), and no "
                        + "ARA adapter sends a provider-native response_format — so the schema "
                        + "would reach neither the model nor the request, and every answer would "
                        + "fail output validation. Set nativeJsonSchema(false) on the LlmProfile "
                        + "(the default) to have the schema appended to the system prompt instead.");
    }

    /**
     * Publishes a new version of the shared LLM transport {@code transportId}, retiring
     * whatever was current (ADR-039 §4). Every agent that references this id — via a
     * profile's {@code transportId} — sees the new version on its next new session;
     * sessions already alive keep the transport they were pinned to. Equivalent to
     * {@code publishLlmTransport(transportId, spec, DrainPolicy.GRACEFUL)}.
     *
     * @return the new version number
     */
    public long publishLlmTransport(String transportId, LlmTransport spec) {
        return llmTransports.publish(transportId, spec);
    }

    /**
     * Same as {@link #publishLlmTransport(String, LlmTransport)}, but with an explicit
     * {@link DrainPolicy} — use {@link DrainPolicy.Forced} to evict sessions still
     * pinned to the retired version (e.g. a leaked credential that must stop being used
     * immediately), instead of letting them drain naturally.
     *
     * @return the new version number
     */
    public long publishLlmTransport(String transportId, LlmTransport spec, DrainPolicy policy) {
        return llmTransports.publish(transportId, spec, policy);
    }

    /**
     * Publishes a new connector for the MCP server {@code serverId}, retiring whatever
     * was current (ADR-039). Every agent referencing this server id sees the new
     * connection on its next new session; sessions already alive keep the connection
     * they were pinned to, and the retired one is closed once its ref-count reaches
     * zero. Equivalent to {@code publishMcpServer(serverId, newConnector, DrainPolicy.GRACEFUL)}.
     *
     * @throws IllegalStateException if no {@code mcpServer(...)} was ever registered on the builder
     */
    public long publishMcpServer(String serverId, Supplier<McpClient> newConnector) {
        return publishMcpServer(serverId, newConnector, DrainPolicy.GRACEFUL);
    }

    /**
     * Same as {@link #publishMcpServer(String, Supplier)}, but with an explicit {@link
     * DrainPolicy} — use {@link DrainPolicy.Forced} to evict sessions still pinned to
     * the retired connection immediately instead of letting them drain naturally.
     */
    public long publishMcpServer(String serverId, Supplier<McpClient> newConnector, DrainPolicy policy) {
        if (mcpTransports == null) {
            throw new IllegalStateException(
                    "No mcpServer(...) was ever registered on this AgentFactory's builder — "
                    + "nothing to publish for id '" + serverId + "'");
        }
        return mcpTransports.publish(serverId, newConnector, policy);
    }

    /**
     * Terminates the given agent and removes it from the registry. Fulfils {@link
     * AgentLifecycleManager}'s "cleans up its checkpoint" contract as a no-op: {@code
     * AgentFactory} is pure in-memory (see the interface's "Known implementations"),
     * so there is no persisted checkpoint to remove here — only a persistent
     * implementation would have real cleanup to do at this step.
     *
     * @param agent the agent to destroy; must not be {@code null}
     */
    public void destroyPermanently(AraAgent agent) {
        Objects.requireNonNull(agent, "agent must not be null");
        agent.terminate();
        registry.deregister(agent.agentId());
        log.info("Destroyed agent [{}]", agent.agentId().value());
    }

    /**
     * Returns a new {@link Builder} for constructing an {@code AgentFactory}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /**
     * Fluent builder for {@link AgentFactory}.
     *
     * <p>All fields are mandatory. {@link #build()} validates that none are null.
     */
    public static final class Builder {

        private final Map<String, LlmClient> llmClientRegistry = new LinkedHashMap<>();
        private String defaultLlmClientId = "default";
        private LlmClientFactory llmClientFactory;
        private MediaStore       mediaStore = MediaStore.noop();
        private final Map<String, McpServerBinding> mcpServers = new LinkedHashMap<>();
        private Function<AgentConfig, MemoryManager>  memoryManagerFactory;
        private Function<AgentConfig, ToolRegistry>   toolRegistryFactory;
        private ExecutionPlanner  executionPlanner;
        private List<AgentInterceptor> interceptors = List.of();
        private AgentRegistry registry;
        private AraTelemetry telemetry = AraTelemetry.noop();
        private SessionStore sessionStore = SessionStore.noop();
        private io.ara.core.trace.TraceStore traceStore;
        private io.ara.core.trace.BlobStore  traceBlobStore;

        private Builder() {}

        /** Registers a single LLM client as the default (id = {@code "default"}). */
        public Builder llmClient(LlmClient llmClient) {
            this.llmClientRegistry.put("default", Objects.requireNonNull(llmClient));
            return this;
        }

        /** Registers a named LLM client in the registry. */
        public Builder llmClient(String id, LlmClient llmClient) {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(llmClient, "llmClient must not be null");
            this.llmClientRegistry.put(id, llmClient);
            return this;
        }

        /** Sets which registered id is used when the agent's llmProvider is not found. */
        public Builder defaultLlmClient(String id) {
            this.defaultLlmClientId = Objects.requireNonNull(id);
            return this;
        }

        /**
         * Sets where the bytes of a task's media live, so adapters can fetch them when they
         * build the provider request. Defaults to {@link MediaStore#noop()} — no storage, and
         * a clear failure naming the attachment for an agent that receives one anyway.
         */
        public Builder mediaStore(MediaStore mediaStore) {
            this.mediaStore = Objects.requireNonNull(mediaStore, "mediaStore must not be null");
            return this;
        }

        public Builder llmClientFactory(LlmClientFactory llmClientFactory) {
            this.llmClientFactory = llmClientFactory;
            return this;
        }

        /**
         * Registers an MCP server for ADR-039-managed lifecycle: the connection is opened
         * lazily (first session that references {@code id} via {@code
         * AgentConfig.mcpServerIds()}), shared/ref-counted/deduplicated across every agent
         * and session that references it, and closed once retired (via {@link
         * AgentFactory#publishMcpServer}) and no longer leased by any session.
         *
         * <p>{@code ara-runtime} cannot construct an {@code McpAraTool}/{@code
         * McpToolRegistry} itself (module boundary — those live in {@code ara-adapters}),
         * so the caller supplies {@code toolsAdapter} to bridge a leased, already-open
         * {@link McpClient} into {@link AraTool}s, e.g.:
         * <pre>{@code
         * .mcpServer("filesystem-mcp",
         *         () -> McpClientFactory.fromSse("http://localhost:3000/sse"),
         *         client -> {
         *             McpToolRegistry reg = new McpToolRegistry(client);
         *             return reg.getTools().join().stream()
         *                     .map(t -> (AraTool) new McpAraTool(t, reg))
         *                     .toList();
         *         })
         * }</pre>
         *
         * <p>An {@code mcpServerIds()} value with no matching registration here falls
         * through entirely to {@code toolRegistryFactory}/{@code toolRegistry}, unmanaged,
         * exactly as before ADR-039 — registering at least one server here is purely
         * opt-in and never a breaking change for existing callers.
         *
         * @param id           the server id, matched against {@code AgentConfig.mcpServerIds()}
         * @param connector    lazily opens the connection; never invoked until first use
         * @param toolsAdapter converts a leased, open {@link McpClient} into {@link AraTool}s
         */
        public Builder mcpServer(String id, Supplier<McpClient> connector, Function<McpClient, List<AraTool>> toolsAdapter) {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(connector, "connector must not be null");
            Objects.requireNonNull(toolsAdapter, "toolsAdapter must not be null");
            this.mcpServers.put(id, new McpServerBinding(connector, toolsAdapter));
            return this;
        }

        public Builder memoryManagerFactory(Function<AgentConfig, MemoryManager> factory) {
            this.memoryManagerFactory = factory;
            return this;
        }

        /**
         * Sets a shared, config-independent tool registry.
         * Shortcut for {@code toolRegistryFactory(cfg -> registry)}.
         */
        public Builder toolRegistry(ToolRegistry toolRegistry) {
            Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
            this.toolRegistryFactory = cfg -> toolRegistry;
            return this;
        }

        /**
         * Sets a per-agent tool registry factory.
         * Called once per {@link AgentFactory#create} with the agent's config,
         * allowing the registry to be customised per agent (e.g. injecting the
         * agent's own id into {@link DelegatingToolRegistry}).
         */
        public Builder toolRegistryFactory(Function<AgentConfig, ToolRegistry> factory) {
            this.toolRegistryFactory = Objects.requireNonNull(factory, "factory must not be null");
            return this;
        }

        public Builder executionPlanner(ExecutionPlanner executionPlanner) {
            this.executionPlanner = executionPlanner;
            return this;
        }

        public Builder interceptors(List<AgentInterceptor> interceptors) {
            this.interceptors = Objects.requireNonNull(interceptors, "interceptors must not be null");
            return this;
        }

        public Builder registry(AgentRegistry registry) {
            this.registry = registry;
            return this;
        }

        /**
         * Sets the {@link AraTelemetry} used to emit an {@code agent.execute} span per
         * task. Defaults to {@link AraTelemetry#noop()} (zero overhead).
         */
        public Builder telemetry(AraTelemetry telemetry) {
            this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
            return this;
        }

        /**
         * Sets the {@link SessionStore} used to persist {@code RunState} and conversation
         * history beyond a single session's in-process lifetime. Defaults to {@link
         * SessionStore#noop()} (in-memory only, exactly today's behavior).
         */
        public Builder sessionStore(SessionStore sessionStore) {
            this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore must not be null");
            return this;
        }

        /**
         * ADR-0068 D1 — enables automatic trace emission: every agent this factory
         * registers is wrapped so each execution appends a run trace to {@code traceStore},
         * with prompts/outputs content-addressed into {@code blobStore}. Both are required
         * together; not calling this leaves emission off (today's behaviour).
         */
        public Builder traceEmission(io.ara.core.trace.TraceStore traceStore,
                                     io.ara.core.trace.BlobStore blobStore) {
            this.traceStore     = Objects.requireNonNull(traceStore, "traceStore must not be null");
            this.traceBlobStore = Objects.requireNonNull(blobStore, "blobStore must not be null");
            return this;
        }

        /**
         * Builds and returns the {@link AgentFactory}.
         *
         * @return the configured factory
         * @throws IllegalStateException if any mandatory field is null
         */
        public AgentFactory build() {
            if (llmClientRegistry.isEmpty()) {
                throw new IllegalStateException("AgentFactory.Builder: at least one llmClient must be registered");
            }
            requireNonNull(memoryManagerFactory, "memoryManagerFactory");
            requireNonNull(toolRegistryFactory,  "toolRegistry / toolRegistryFactory");
            requireNonNull(executionPlanner,     "executionPlanner");
            requireNonNull(registry,             "registry");
            return new AgentFactory(this);
        }

        private static void requireNonNull(Object value, String fieldName) {
            if (value == null) {
                throw new IllegalStateException(
                        "AgentFactory.Builder: [%s] must not be null".formatted(fieldName));
            }
        }
    }
}
