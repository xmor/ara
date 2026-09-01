package io.ara.runtime.stubs;

import io.ara.core.agent.AgentConfig;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

/**
 * Test/stub {@link LlmClient} that dispatches to different clients based on
 * {@link AgentConfig#agentType()}.
 *
 * <p>This allows each agent in a multi-agent scenario to have its own
 * independent {@link ScriptedLlmClient} — one shared {@link RoutingLlmClient}
 * instance is wired into {@code AgentFactory} and automatically routes every
 * {@link #complete} call to the right script.
 *
 * <pre>{@code
 * LlmClient llm = RoutingLlmClient.builder()
 *     .route("supervisor", ScriptedLlmClient.script()
 *         .thenToolCall("delegate_task",
 *             """{"agent_id":"writer-0","task":"Scrivi intro su ARA"}""")
 *         .thenFinalAnswer("Il writer ha prodotto l'introduzione.")
 *         .build())
 *     .route("writer", ScriptedLlmClient.script()
 *         .thenFinalAnswer("ARA è un framework per agenti autonomi su JVM.")
 *         .build())
 *     .build();
 * }</pre>
 */
public final class RoutingLlmClient implements LlmClient {

    private final Map<String, LlmClient> routes;
    private final LlmClient              defaultClient;

    private RoutingLlmClient(Map<String, LlmClient> routes, LlmClient defaultClient) {
        this.routes        = Map.copyOf(routes);
        this.defaultClient = Objects.requireNonNull(defaultClient, "defaultClient must not be null");
    }

    /**
     * Routes based on {@link LlmCallContext#agentType()}, which is populated from
     * {@link AgentConfig#agentType()} when the context is built via {@link LlmCallContext#from}.
     */
    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
        String type = context.agentType();
        LlmClient client = (type != null) ? routes.getOrDefault(type, defaultClient) : defaultClient;
        return client.complete(messages, context);
    }

    /** Streams from the same route {@link #complete(List, LlmCallContext)} would pick. */
    @Override
    public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
        String type = context.agentType();
        LlmClient client = (type != null) ? routes.getOrDefault(type, defaultClient) : defaultClient;
        return client.stream(messages, context);
    }

    @Override
    @Deprecated
    public LlmCompletion complete(List<LlmMessage> messages, AgentConfig config) {
        LlmClient client = routes.getOrDefault(config.agentType(), defaultClient);
        return client.complete(messages, LlmCallContext.from(config));
    }

    @Override
    public String providerId() {
        return "routing-stub";
    }

    /** {@code true} only if every routed client (and the default) supports native tools. */
    @Override
    public boolean supportsNativeTools() {
        return routes.values().stream().allMatch(LlmClient::supportsNativeTools)
                && defaultClient.supportsNativeTools();
    }

    /**
     * The intersection across every route <em>and</em> the default — the route is chosen by
     * agent type at call time, so the honest answer is what all of them can do.
     */
    @Override
    public Set<String> supportedMediaTypes() {
        Set<String> shared = defaultClient.supportedMediaTypes();
        for (LlmClient routed : routes.values()) {
            Set<String> other = routed.supportedMediaTypes();
            shared = shared.stream().filter(other::contains).collect(Collectors.toUnmodifiableSet());
        }
        return shared;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {

        private final Map<String, LlmClient> routes = new HashMap<>();
        private LlmClient defaultClient = ScriptedLlmClient.script()
                .thenFinalAnswer("(no route configured for this agent type)")
                .build();

        private Builder() {}

        /**
         * Registers a {@link LlmClient} for the given {@code agentType}.
         *
         * @param agentType the value of {@link AgentConfig#agentType()} to match
         * @param client    the client to use for that agent type
         */
        public Builder route(String agentType, LlmClient client) {
            Objects.requireNonNull(agentType, "agentType must not be null");
            Objects.requireNonNull(client,    "client must not be null");
            routes.put(agentType, client);
            return this;
        }

        /**
         * Sets the fallback client used when no route matches.
         * Defaults to a scripted client that returns a single FINAL_ANSWER.
         */
        public Builder defaultClient(LlmClient client) {
            this.defaultClient = Objects.requireNonNull(client);
            return this;
        }

        public RoutingLlmClient build() {
            return new RoutingLlmClient(routes, defaultClient);
        }
    }
}