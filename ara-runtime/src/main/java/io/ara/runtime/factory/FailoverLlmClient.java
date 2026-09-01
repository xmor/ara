package io.ara.runtime.factory;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * {@link LlmClient} decorator that implements ordered failover across multiple clients.
 *
 * <p>On each call to {@link #complete}, it tries clients in declaration order.
 * {@link LlmException}s with {@link LlmException#isRetryable()} {@code false} (e.g.
 * authentication errors, invalid requests) are re-thrown immediately without attempting
 * further fallbacks. Retryable errors (rate-limits, network, 5xx) and generic
 * {@link RuntimeException}s advance to the next client in the list.
 * Only when all clients are exhausted is the last exception re-thrown.
 */
public final class FailoverLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(FailoverLlmClient.class);

    private final List<LlmClient> clients;
    private final String          compositeId;
    private volatile String       lastSuccessfulProviderId;

    public FailoverLlmClient(List<LlmClient> clients) {
        Objects.requireNonNull(clients, "clients must not be null");
        if (clients.isEmpty()) throw new IllegalArgumentException("At least one client required");
        this.clients                  = List.copyOf(clients);
        this.compositeId              = buildCompositeId(clients);
        this.lastSuccessfulProviderId = clients.get(0).providerId();
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        LlmException     lastLlmFailure = null;
        RuntimeException lastFailure    = null;

        for (int i = 0; i < clients.size(); i++) {
            LlmClient candidate = clients.get(i);
            try {
                LlmCompletion result = candidate.complete(messages, context);
                if (i > 0) {
                    log.info("Failover succeeded with client '{}' (primary failed after {} attempt(s))",
                            candidate.providerId(), i);
                }
                lastSuccessfulProviderId = candidate.providerId();
                return result;
            } catch (LlmException ex) {
                if (!ex.isRetryable()) {
                    log.error("LLM client '{}' returned non-retryable error [{}] — aborting failover: {}",
                            candidate.providerId(), ex.errorType(), ex.getMessage());
                    throw ex;
                }
                lastLlmFailure = ex;
                boolean hasNext = i < clients.size() - 1;
                if (hasNext) {
                    log.warn("LLM client '{}' failed [{}] — switching to next fallback. Reason: {}",
                            candidate.providerId(), ex.errorType(), ex.getMessage());
                } else {
                    log.error("All {} LLM client(s) failed. Last error from '{}' [{}]: {}",
                            clients.size(), candidate.providerId(), ex.errorType(), ex.getMessage());
                }
            } catch (RuntimeException ex) {
                lastFailure = ex;
                boolean hasNext = i < clients.size() - 1;
                if (hasNext) {
                    log.warn("LLM client '{}' failed — switching to next fallback. Reason: {}",
                            candidate.providerId(), ex.getMessage());
                } else {
                    log.error("All {} LLM client(s) failed. Last error from '{}': {}",
                            clients.size(), candidate.providerId(), ex.getMessage());
                }
            }
        }

        if (lastLlmFailure != null) throw lastLlmFailure;
        throw lastFailure;
    }

    /**
     * Streams tokens with the same ordered failover as {@link #complete}, with one added
     * constraint: <strong>failover only happens before the first token reaches the
     * subscriber.</strong>
     *
     * <p>Once any token has been delivered, switching candidates would replay the response
     * from the beginning and duplicate everything already emitted — and {@code ReactStrategy}
     * deliberately never retries a streaming call for exactly that reason. So a failure after
     * the first {@code onNext} propagates as-is; a failure before it, if retryable and a
     * fallback remains, transparently re-subscribes to the next client. Non-retryable
     * {@link LlmException}s abort immediately, as in {@link #complete}.
     *
     * <p>Demand is not honoured ({@code request(n)} is a no-op): the provider pushes
     * server-sent events at its own pace and the only consumer in the runtime
     * ({@code ReactExecutionSupport.streamAndCollect}) requests {@code Long.MAX_VALUE}
     * up front — mirroring {@code TokenStreamPublisher}.
     */
    @Override
    public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
        return downstream -> new FailoverStream(messages, context, downstream).start();
    }

    /**
     * Drives one {@link #stream} subscription across the candidate list. Not static: it
     * updates {@link #lastSuccessfulProviderId} on the enclosing client when a candidate
     * produces its first token.
     */
    private final class FailoverStream {

        private final List<LlmMessage>               messages;
        private final LlmCallContext                 context;
        private final Flow.Subscriber<? super String> downstream;

        private final AtomicBoolean delivered  = new AtomicBoolean(false);
        private final AtomicBoolean terminated = new AtomicBoolean(false);
        private final AtomicBoolean cancelled  = new AtomicBoolean(false);
        private final AtomicReference<Flow.Subscription> current = new AtomicReference<>();

        FailoverStream(List<LlmMessage> messages, LlmCallContext context,
                       Flow.Subscriber<? super String> downstream) {
            this.messages   = messages;
            this.context    = context;
            this.downstream = downstream;
        }

        void start() {
            downstream.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { /* push-based — see stream() javadoc */ }
                @Override public void cancel() {
                    cancelled.set(true);
                    Flow.Subscription s = current.get();
                    if (s != null) s.cancel();
                }
            });
            subscribeTo(0);
        }

        private void subscribeTo(int idx) {
            if (cancelled.get() || terminated.get()) return;

            LlmClient candidate = clients.get(idx);
            boolean   hasNext   = idx < clients.size() - 1;

            Flow.Publisher<String> publisher;
            try {
                publisher = candidate.stream(messages, context);
            } catch (RuntimeException e) {
                handleError(e, idx, candidate, hasNext);
                return;
            }

            publisher.subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription s) {
                    current.set(s);
                    if (cancelled.get()) { s.cancel(); return; }
                    s.request(Long.MAX_VALUE);
                }

                @Override public void onNext(String token) {
                    if (terminated.get() || cancelled.get()) return;
                    if (delivered.compareAndSet(false, true)) {
                        lastSuccessfulProviderId = candidate.providerId();
                        if (idx > 0) {
                            log.info("Failover streaming succeeded with client '{}' "
                                    + "(primary failed after {} attempt(s))", candidate.providerId(), idx);
                        }
                    }
                    downstream.onNext(token);
                }

                @Override public void onError(Throwable t) {
                    handleError(t, idx, candidate, hasNext);
                }

                @Override public void onComplete() {
                    if (terminated.compareAndSet(false, true)) {
                        lastSuccessfulProviderId = candidate.providerId();
                        downstream.onComplete();
                    }
                }
            });
        }

        private void handleError(Throwable t, int idx, LlmClient candidate, boolean hasNext) {
            if (terminated.get() || cancelled.get()) return;

            boolean nonRetryable = (t instanceof LlmException le) && !le.isRetryable();
            boolean canFailover  = hasNext && !nonRetryable && !delivered.get();

            if (canFailover) {
                log.warn("LLM streaming client '{}' failed{} — switching to next fallback. Reason: {}",
                        candidate.providerId(),
                        (t instanceof LlmException le) ? " [" + le.errorType() + "]" : "",
                        t.getMessage());
                current.set(null);
                subscribeTo(idx + 1);
                return;
            }

            if (terminated.compareAndSet(false, true)) {
                if (nonRetryable) {
                    log.error("LLM streaming client '{}' returned non-retryable error [{}] — "
                            + "aborting failover: {}", candidate.providerId(),
                            ((LlmException) t).errorType(), t.getMessage());
                } else if (delivered.get()) {
                    log.error("LLM streaming client '{}' failed after emitting tokens — not retried "
                            + "(would duplicate the stream): {}", candidate.providerId(), t.getMessage());
                } else {
                    log.error("All {} LLM streaming client(s) failed. Last error from '{}': {}",
                            clients.size(), candidate.providerId(), t.getMessage());
                }
                downstream.onError(t);
            }
        }
    }

    @Override
    public String providerId() {
        return compositeId;
    }

    @Override
    public String lastUsedProviderId() {
        return lastSuccessfulProviderId;
    }

    /**
     * {@code true} only if every candidate supports native tools — a fallback that
     * doesn't would otherwise silently lose tool-calling ability whenever failover
     * picks it, since text-based scaffolding would have already been omitted.
     */
    @Override
    public boolean supportsNativeTools() {
        return clients.stream().allMatch(LlmClient::supportsNativeTools);
    }

    /**
     * The intersection of the candidates' supported media types — the pool can only promise
     * what every client it might fall back to can deliver.
     *
     * <p>Claiming the union instead would mean a call with a PDF succeeds or fails depending
     * on which candidate happened to answer. Reporting the intersection makes the mismatch
     * a non-retryable failure raised by the first candidate, which aborts the failover
     * rather than letting a text-only fallback answer about a document it never received.
     * Excluding media-incapable candidates from the rotation instead would give better
     * availability, but it is a determinism improvement, not a correctness one — the
     * intersection already rules out the wrong answer — and it is not done here.
     */
    @Override
    public Set<String> supportedMediaTypes() {
        return clients.stream()
                .map(LlmClient::supportedMediaTypes)
                .reduce((a, b) -> a.stream().filter(b::contains).collect(Collectors.toUnmodifiableSet()))
                .orElseGet(Set::of);
    }

    private static String buildCompositeId(List<LlmClient> clients) {
        return "failover[" + clients.stream()
                .map(LlmClient::providerId)
                .reduce((a, b) -> a + "→" + b)
                .orElse("empty") + "]";
    }
}
