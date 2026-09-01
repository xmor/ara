package io.ara.runtime.wiring;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Round-robin {@link LlmClient} decorator over a fixed, already-leased list of clients.
 *
 * <p>Unlike the previous {@code DefaultLlmRouter}'s global {@code AtomicInteger} (shared
 * across every session on the agent), each instance of this class owns its own counter —
 * built once per {@link AgentWiring}, i.e. once per session, so distribution never leaks
 * across sessions (ADR-039 "Impatto sui collaboratori esistenti").
 */
final class RoundRobinLlmClient implements LlmClient {

    private final List<LlmClient> clients;
    private final String compositeId;
    private final AtomicInteger index = new AtomicInteger(0);
    private volatile String lastUsedProviderId;

    RoundRobinLlmClient(List<LlmClient> clients) {
        Objects.requireNonNull(clients, "clients must not be null");
        if (clients.isEmpty()) {
            throw new IllegalArgumentException("At least one client required");
        }
        this.clients = List.copyOf(clients);
        this.compositeId = "round-robin[" + clients.stream()
                .map(LlmClient::providerId)
                .reduce((a, b) -> a + "," + b)
                .orElse("empty") + "]";
        this.lastUsedProviderId = clients.get(0).providerId();
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        LlmClient chosen = clients.get(Math.floorMod(index.getAndIncrement(), clients.size()));
        LlmCompletion result = chosen.complete(messages, context);
        lastUsedProviderId = chosen.providerId();
        return result;
    }

    /**
     * Streams from whichever client the round-robin counter lands on for this call,
     * advancing the counter exactly as {@link #complete} does so the two share one rotation.
     *
     * <p>The chosen client's own {@code stream} is returned directly — its incremental
     * tokens reach the subscriber untouched. No cross-client fallback: round-robin has no
     * fallback semantics, and re-issuing a partly delivered stream on another client would
     * duplicate the tokens already sent.
     */
    @Override
    public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
        LlmClient chosen = clients.get(Math.floorMod(index.getAndIncrement(), clients.size()));
        lastUsedProviderId = chosen.providerId();
        return chosen.stream(messages, context);
    }

    @Override
    public String providerId() {
        return compositeId;
    }

    @Override
    public String lastUsedProviderId() {
        return lastUsedProviderId;
    }

    /** {@code true} only if every candidate supports native tools (same reasoning as {@code FailoverLlmClient}). */
    @Override
    public boolean supportsNativeTools() {
        return clients.stream().allMatch(LlmClient::supportsNativeTools);
    }

    /**
     * The intersection of the candidates' supported media types.
     *
     * <p>Here the intersection matters even more than for failover: the rotation picks a
     * client per call, so claiming the union would make a PDF succeed or fail depending on
     * where the counter happens to be — an intermittent failure, the hardest kind to
     * diagnose. With the intersection the mismatch fails on every call instead of one in N.
     */
    @Override
    public Set<String> supportedMediaTypes() {
        return clients.stream()
                .map(LlmClient::supportedMediaTypes)
                .reduce((a, b) -> a.stream().filter(b::contains).collect(Collectors.toUnmodifiableSet()))
                .orElseGet(Set::of);
    }
}
