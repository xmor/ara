package io.ara.runtime.wiring;

import io.ara.core.artifact.ArtifactExtractor;
import io.ara.runtime.artifact.ArtifactSink;
import io.ara.core.agent.AgentConfig;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmConfig;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.LlmSelectionPolicy;
import io.ara.core.llm.LlmTransport;
import io.ara.core.mcp.McpClient;
import io.ara.core.media.MediaStore;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.factory.CircuitBreakerLlmClient;
import io.ara.runtime.factory.CircuitStateRegistry;
import io.ara.runtime.factory.FailoverLlmClient;
import io.ara.runtime.llm.LoggingLlmClient;
import io.ara.runtime.llm.MediaResolvingLlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Default {@link WiringFactory}: resolves the LLM transport(s) for a config's profiles
 * from a shared {@link ResourceRegistry}, resolves the selection policy once at build
 * time (ADR-039 §"Forma di design" — {@code LlmSelectionPolicy} used to be re-applied
 * per call in {@code DefaultLlmRouter}; here it becomes a build-time step), and composes
 * the resulting {@link AgentWiring}.
 *
 * <p>Transport resolution mirrors {@code DefaultLlmRouter.resolve}'s three-step order
 * (inline override → registry lookup by {@code transportId} → default client), routed
 * through {@code llmTransports} — keyed by {@link LlmTransport} (ADR-039 §3's "asse A")
 * — so the resolved client becomes a ref-counted, versioned {@link Lease} instead of a
 * live map read (inline overrides) or a permanently-shared instance with no lifecycle
 * at all (named clients).
 */
public final class DefaultWiringFactory implements WiringFactory {

    private final ResourceRegistry<LlmTransport, LlmClient> llmTransports;
    private final String defaultLlmClientId;
    /** {@code null} when no MCP server was ever registered — the MCP step below is then a no-op. */
    private final ResourceRegistry<Supplier<McpClient>, McpClient> mcpTransports;
    private final Map<String, McpServerBinding> mcpServers;
    private final Function<AgentConfig, ToolRegistry> statelessToolRegistryFactory;
    private final MediaStore mediaStore;
    private final AraTelemetry telemetry;
    private final ArtifactSink artifactSink;
    /** Per-endpoint circuit health, keyed by {@code transportId} — see {@link CircuitStateRegistry}. */
    private final CircuitStateRegistry circuitStates;

    /** Convenience constructor for agents with no ARA-managed MCP servers and no media. */
    public DefaultWiringFactory(
            ResourceRegistry<LlmTransport, LlmClient> llmTransports,
            String defaultLlmClientId,
            Function<AgentConfig, ToolRegistry> statelessToolRegistryFactory
    ) {
        this(llmTransports, defaultLlmClientId, null, Map.of(), statelessToolRegistryFactory);
    }

    /**
     * @param mcpTransports shared, ref-counted registry of MCP connections; {@code null}
     *                       if this agent never references an ARA-managed MCP server
     * @param mcpServers     serverId → binding (lazy connector + tools adapter), for the
     *                       ids this agent's {@code config.mcpServerIds()} may reference;
     *                       an id not present here falls through entirely to {@code
     *                       statelessToolRegistryFactory}, unmanaged as before ADR-039
     */
    public DefaultWiringFactory(
            ResourceRegistry<LlmTransport, LlmClient> llmTransports,
            String defaultLlmClientId,
            ResourceRegistry<Supplier<McpClient>, McpClient> mcpTransports,
            Map<String, McpServerBinding> mcpServers,
            Function<AgentConfig, ToolRegistry> statelessToolRegistryFactory
    ) {
        this(llmTransports, defaultLlmClientId, mcpTransports, mcpServers,
                statelessToolRegistryFactory, MediaStore.noop());
    }

    /**
     * @param mediaStore where the bytes of a task's media live, so the adapter can fetch them
     *                    when it builds the provider request. {@link MediaStore#noop()} for a
     *                    deployment that never attaches media.
     */
    public DefaultWiringFactory(
            ResourceRegistry<LlmTransport, LlmClient> llmTransports,
            String defaultLlmClientId,
            ResourceRegistry<Supplier<McpClient>, McpClient> mcpTransports,
            Map<String, McpServerBinding> mcpServers,
            Function<AgentConfig, ToolRegistry> statelessToolRegistryFactory,
            MediaStore mediaStore
    ) {
        this(llmTransports, defaultLlmClientId, mcpTransports, mcpServers,
                statelessToolRegistryFactory, mediaStore, AraTelemetry.noop());
    }

    /**
     * @param telemetry records the failover chain and the per-candidate breaker transitions
     *                  (see {@link FailoverLlmClient} / {@link CircuitBreakerLlmClient}).
     *                  {@link AraTelemetry#noop()} for a deployment that does not trace.
     */
    public DefaultWiringFactory(
            ResourceRegistry<LlmTransport, LlmClient> llmTransports,
            String defaultLlmClientId,
            ResourceRegistry<Supplier<McpClient>, McpClient> mcpTransports,
            Map<String, McpServerBinding> mcpServers,
            Function<AgentConfig, ToolRegistry> statelessToolRegistryFactory,
            MediaStore mediaStore,
            AraTelemetry telemetry
    ) {
        this(llmTransports, defaultLlmClientId, mcpTransports, mcpServers,
                statelessToolRegistryFactory, mediaStore, telemetry, ArtifactExtractor.none());
    }

    /**
     * @param artifactExtractor how the final answer of each agent is split into artifacts that are stored
     *                          in {@code mediaStore}. {@link ArtifactExtractor#none()} (the default of the
     *                          shorter constructors) means no artifacts, and so does a {@code mediaStore}
     *                          that is {@link MediaStore#noop()} — the pair cannot store anything.
     */
    public DefaultWiringFactory(
            ResourceRegistry<LlmTransport, LlmClient> llmTransports,
            String defaultLlmClientId,
            ResourceRegistry<Supplier<McpClient>, McpClient> mcpTransports,
            Map<String, McpServerBinding> mcpServers,
            Function<AgentConfig, ToolRegistry> statelessToolRegistryFactory,
            MediaStore mediaStore,
            AraTelemetry telemetry,
            ArtifactExtractor artifactExtractor
    ) {
        this(llmTransports, defaultLlmClientId, mcpTransports, mcpServers,
                statelessToolRegistryFactory, mediaStore, telemetry, artifactExtractor,
                // A factory built without one gets its own registry, so circuit health is shared by
                // every session this factory wires — the previous behaviour of a breaker per
                // session is never silently restored. AgentFactory passes the runtime-wide
                // registry, which widens the sharing to every agent over the same endpoint.
                new CircuitStateRegistry());
    }

    /**
     * @param circuitStates runtime-wide health of each LLM endpoint, keyed by the same
     *                      {@code transportId} the transport itself is shared by. Passing the
     *                      registry that {@code AgentFactory} owns is what makes an outage cost one
     *                      round of timeouts for the whole runtime instead of one per session.
     */
    public DefaultWiringFactory(
            ResourceRegistry<LlmTransport, LlmClient> llmTransports,
            String defaultLlmClientId,
            ResourceRegistry<Supplier<McpClient>, McpClient> mcpTransports,
            Map<String, McpServerBinding> mcpServers,
            Function<AgentConfig, ToolRegistry> statelessToolRegistryFactory,
            MediaStore mediaStore,
            AraTelemetry telemetry,
            ArtifactExtractor artifactExtractor,
            CircuitStateRegistry circuitStates
    ) {
        this.mediaStore                    = Objects.requireNonNull(mediaStore, "mediaStore must not be null");
        this.artifactSink                  = ArtifactSink.of(artifactExtractor, mediaStore);
        this.llmTransports                = Objects.requireNonNull(llmTransports, "llmTransports must not be null");
        this.defaultLlmClientId            = Objects.requireNonNull(defaultLlmClientId, "defaultLlmClientId must not be null");
        this.mcpTransports                 = mcpTransports;
        this.mcpServers                    = Map.copyOf(Objects.requireNonNull(mcpServers, "mcpServers must not be null"));
        this.statelessToolRegistryFactory  = Objects.requireNonNull(statelessToolRegistryFactory, "statelessToolRegistryFactory must not be null");
        this.telemetry                     = Objects.requireNonNull(telemetry, "telemetry must not be null");
        this.circuitStates                 = Objects.requireNonNull(circuitStates, "circuitStates must not be null");
    }

    @Override
    public AgentWiring build(AgentConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        LlmConfig llmConfig = config.llm();
        LlmSelectionPolicy policy = llmConfig.policy();

        List<LlmProfile> profilesToLease = switch (policy) {
            case FAILOVER, ROUND_ROBIN -> llmConfig.allProfiles();
            default -> List.of(llmConfig.primary());
        };

        List<Lease<?>> acquired = new ArrayList<>();
        try {
            List<LlmClient> resolvedClients = new ArrayList<>(profilesToLease.size());
            // Collected alongside the clients because the circuit breaker below is keyed by it: the
            // health of an endpoint has to be shared by exactly the set of sessions that share its
            // transport, and this is that transport's registry key.
            List<String> transportIds = new ArrayList<>(profilesToLease.size());
            for (LlmProfile profile : profilesToLease) {
                LeasedTransport leased = leaseTransport(profile);
                acquired.add(leased.lease());
                resolvedClients.add(leased.lease().get());
                transportIds.add(leased.transportId());
            }

            LlmClient llm = switch (policy) {
                // Every candidate gets a circuit breaker over the health shared for its endpoint,
                // so an endpoint that keeps failing is skipped on subsequent calls without paying
                // its per-request timeout again and is re-probed by a single trial after a
                // cooldown. The breaker wrapper still lives on the session-pinned wiring
                // (ADR-039), but the state it reads does not: it belongs to the runtime-wide
                // CircuitStateRegistry, keyed by transportId. Keeping the state on the wrapper
                // meant each session held a private opinion about a transport they all share, so
                // an outage cost one round of timeouts per session and every new session re-learnt
                // it from CLOSED — see CircuitState for why health is not session state. The
                // reflection-only router (DefaultLlmRouter) deliberately stays on a plain
                // FailoverLlmClient: it rebuilds the pool per call, and now that health outlives
                // the pool a breaker there would be sound but still pointless, since that path
                // never reuses the wrapper it would attach it to.
                case FAILOVER -> resolvedClients.size() == 1
                        ? resolvedClients.get(0)
                        : new FailoverLlmClient(buildBreakers(resolvedClients, transportIds), telemetry);
                case ROUND_ROBIN -> new RoundRobinLlmClient(resolvedClients);
                default -> resolvedClients.get(0);
            };
            if (llmConfig.logIo()) {
                llm = new LoggingLlmClient(llm, llmConfig.logIoMaxChars());
            }
            // Outermost, and applied here rather than where AraRuntime wraps its registered
            // clients: a client built on demand by an LlmClientFactory (ADR-039 dynamic
            // transports) is created inside the transport registry and never passes through
            // that point, so wrapping there would leave exactly those agents unable to resolve
            // a stored attachment — failing with "no MediaStore is wired" when one was. Every
            // agent's effective client, statically registered or leased, passes through here.
            //
            // Skipped entirely for the no-op store, which is the default: it can never resolve
            // anything, so a resolver backed by it fails exactly as the call context's own
            // default already does. Wrapping anyway would add a layer with no behaviour to it
            // and would change the identity of the leased client that callers can observe.
            if (mediaStore != MediaStore.noop()) {
                llm = new MediaResolvingLlmClient(llm, mediaStore);
            }

            List<AraTool> mcpTools = new ArrayList<>();
            if (mcpTransports != null) {
                for (String serverId : config.mcpServerIds()) {
                    McpServerBinding binding = mcpServers.get(serverId);
                    if (binding == null) continue;   // not ARA-managed — left to statelessToolRegistryFactory
                    Lease<McpClient> mcpLease = mcpTransports.pinOrCreate(serverId, binding::connector);
                    acquired.add(mcpLease);
                    mcpTools.addAll(binding.toolsAdapter().apply(mcpLease.get()));
                }
            }

            ToolRegistry baseRegistry = statelessToolRegistryFactory.apply(config);
            ToolRegistry toolRegistry = mcpTools.isEmpty()
                    ? baseRegistry
                    : new CompositeToolRegistry(mcpTools, baseRegistry);

            return new AgentWiring(config, llm, toolRegistry, List.copyOf(acquired), artifactSink, mediaStore);
        } catch (RuntimeException e) {
            for (Lease<?> lease : acquired) {
                try {
                    lease.close();
                } catch (RuntimeException ignore) {
                    // best-effort rollback — the original failure is what matters
                }
            }
            throw e;
        }
    }

    /**
     * A leased transport together with the registry key it was leased under. The key travels with
     * the lease because the caller needs it for the circuit breaker, and recomputing it there would
     * mean duplicating the three-step resolution below — the id a profile resolves to is only
     * unambiguous at the point the lease is taken.
     */
    private record LeasedTransport(String transportId, Lease<LlmClient> lease) { }

    /** One breaker per candidate, each over the shared health of its own endpoint. */
    private List<LlmClient> buildBreakers(List<LlmClient> clients, List<String> transportIds) {
        List<LlmClient> breakers = new ArrayList<>(clients.size());
        for (int i = 0; i < clients.size(); i++) {
            breakers.add(circuitStates.breakerFor(transportIds.get(i), clients.get(i), telemetry));
        }
        return breakers;
    }

    private LeasedTransport leaseTransport(LlmProfile profile) {
        if (profile.inlineTransport() != null) {
            String id = ContentAddressedTransportId.forInlineTransport(profile.inlineTransport());
            return new LeasedTransport(id, llmTransports.pinOrCreate(id, profile::inlineTransport));
        }

        String transportId = profile.transportId();
        if (transportId != null && !transportId.isBlank()) {
            Long pinnedVersion = profile.pinnedTransportVersion();
            if (pinnedVersion != null) {
                // Explicit opt-in pin (ADR-039 §4): the agent declared "I want vN, not
                // whatever is latest" for reproducibility/rollback — a missing version
                // is a real configuration error, not a case to silently paper over by
                // falling back to the default client like the follow-latest path below.
                //
                // The pinned version is part of the health key: two agents pinning different
                // versions of the same transportId talk to different endpoints, so one being
                // down says nothing about the other.
                return new LeasedTransport(transportId + "@v" + pinnedVersion,
                        llmTransports.pin(transportId, pinnedVersion));
            }
            try {
                return new LeasedTransport(transportId, llmTransports.pin(transportId));
            } catch (IllegalStateException notPublished) {
                // fall through to the default client, mirroring DefaultLlmRouter.resolve() step 3
            }
        }

        try {
            return new LeasedTransport(defaultLlmClientId, llmTransports.pin(defaultLlmClientId));
        } catch (IllegalStateException notPublished) {
            throw new IllegalStateException(
                    "No LlmClient found for transportId='" + transportId
                    + "' and no default registered (defaultClientId='" + defaultLlmClientId + "')");
        }
    }
}
