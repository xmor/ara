package io.ara.runtime.factory;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.core.telemetry.Span;
import io.ara.core.telemetry.SpanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * {@link LlmClient} decorator that implements ordered failover across multiple clients.
 *
 * <p>When an {@link AraTelemetry} is supplied, every chain decision is recorded as a
 * {@code llm.failover} span, which is the only place the chain's outcome is visible as a
 * single fact — the per-candidate {@code llm.complete} spans each report their own call but
 * not that the pool had to walk past the primary to get an answer. Attributes:
 * {@code llm.failover.chain}, {@code llm.failover.streaming}, {@code llm.failover.outcome},
 * {@code llm.failover.attempts}, and {@code llm.failover.served_by} /
 * {@code llm.failover.provider} for who answered. With {@link AraTelemetry#noop()} (the
 * default) nothing is allocated.
 *
 * <p>On each call to {@link #complete}, it tries clients in declaration order.
 * {@link LlmException}s with {@link LlmException#shouldFailover()} {@code false} (e.g.
 * authentication errors, invalid requests) are re-thrown immediately without attempting
 * further fallbacks — the same failure would recur on every candidate in the pool. Failures
 * where another provider could plausibly succeed ({@code shouldFailover()} {@code true}:
 * rate-limits, server errors, network and connection problems) and generic
 * {@link RuntimeException}s advance to the next client in the list.
 * Only when all clients are exhausted is the last exception re-thrown.
 *
 * <p>Failover is deliberately decoupled from {@link LlmException#isRetryable()}: a
 * connection error must not be retried against the <em>same</em> endpoint, but the pool
 * should still advance to a <em>different</em> one. {@code shouldFailover()} is what the
 * pool consults.
 *
 * <p><b>Thread safety:</b> thread-safe. The candidate list, composite id and telemetry are
 * final and immutable; the only mutable state is the volatile {@link #lastSuccessfulProviderId},
 * a hint for diagnostics that is allowed to lose a race. Each call keeps its walk state in
 * locals, and each streaming subscription owns its own {@code FailoverStream}.
 */
public final class FailoverLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(FailoverLlmClient.class);

    private final List<LlmClient> clients;
    private final String          compositeId;
    private volatile String       lastSuccessfulProviderId;
    private final AraTelemetry    telemetry;

    public FailoverLlmClient(List<LlmClient> clients) {
        this(clients, AraTelemetry.noop());
    }

    public FailoverLlmClient(List<LlmClient> clients, AraTelemetry telemetry) {
        Objects.requireNonNull(clients, "clients must not be null");
        Objects.requireNonNull(telemetry, "telemetry must not be null");
        if (clients.isEmpty()) throw new IllegalArgumentException("At least one client required");
        this.clients                  = List.copyOf(clients);
        this.compositeId              = buildCompositeId(clients);
        this.lastSuccessfulProviderId = clients.get(0).providerId();
        this.telemetry                = telemetry;
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        // The span is opened around the whole candidate walk, not per candidate: the enclosing
        // llm.complete spans of the candidates (InstrumentedLlmClient, which wraps each one
        // inside the pool) become its children, so one trace shows both the chain-level outcome
        // and the per-attempt latency underneath it. Only makeCurrent is thread-bound, and
        // complete() runs start to end on the caller's thread, so the nesting is safe here —
        // the streaming path, which cannot make that claim, records events instead.
        Span span = telemetry.spanBuilder("llm.failover")
                .setAttribute("llm.failover.chain", compositeId)
                .setAttribute("llm.failover.streaming", false)
                .startSpan();
        try (var scope = span.makeCurrent()) {
            ChainOutcome outcome = walkChain(messages, context);
            describe(span, outcome);
            if (outcome.completion() != null) return outcome.completion();
            throw rethrow(outcome.failure());
        } finally {
            span.end();
        }
    }

    /**
     * What one pass over the candidate list produced: either a completion, or the failure to
     * rethrow, plus the label that says how the chain ended. Returning it instead of throwing
     * from inside the loop is what lets {@link #complete} describe the whole chain on a single
     * span — the loop has three distinct endings (abort, interrupt, exhaustion) and each needs
     * its own label. The label travels with the outcome rather than being recomputed later,
     * because "was the thread interrupted?" is only a trustworthy answer on the thread that
     * walked the chain.
     */
    private record ChainOutcome(LlmCompletion completion, int attempts, String servedBy,
                                String label, Throwable failure) {

        static ChainOutcome served(LlmCompletion completion, int attempts, String servedBy) {
            return new ChainOutcome(completion, attempts, servedBy,
                    attempts == 1 ? "served_by_primary" : "served_by_fallback", null);
        }

        static ChainOutcome failed(int attempts, String label, Throwable failure) {
            return new ChainOutcome(null, attempts, null, label, failure);
        }
    }

    /**
     * Rethrows the failure the chain ended on, preserving its runtime type: the pool has never
     * wrapped these, so a caller that catches {@link LlmException} to read {@code errorType}
     * must still see one. Only a chain that failed without recording anything — impossible
     * today, every candidate either completed or threw — becomes an {@link IllegalStateException}.
     */
    private static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) return runtimeFailure;
        return new IllegalStateException("Failover chain failed without a recorded cause", failure);
    }

    /**
     * Walks the candidates in order and returns what happened, never throwing — the caller
     * owns the rethrow so it can describe the span first.
     *
     * <p>The three failure labels are kept apart because they call for different responses:
     * an {@code aborted_non_failover} is a misconfiguration to fix, an {@code exhausted} is
     * an outage to wait out, and an {@code interrupted} is a deadline the caller itself set.
     */
    private ChainOutcome walkChain(List<LlmMessage> messages, LlmCallContext context) {
        Throwable lastFailure = null;
        for (int i = 0; i < clients.size(); i++) {
            LlmClient candidate = clients.get(i);
            try {
                LlmCompletion result = candidate.complete(messages, context);
                if (i > 0) {
                    log.info("Failover succeeded with client '{}' (primary failed after {} attempt(s))",
                            candidate.providerId(), i);
                }
                lastSuccessfulProviderId = candidate.providerId();
                return ChainOutcome.served(result, i + 1, candidate.providerId());
            } catch (LlmException ex) {
                if (!ex.shouldFailover()) {
                    log.error("LLM client '{}' returned non-failover error [{}] — aborting failover: {}",
                            candidate.providerId(), ex.errorType(), ex.getMessage());
                    return ChainOutcome.failed(i + 1, "aborted_non_failover", ex);
                }
                lastFailure = ex;
                logCandidateFailure(candidate, ex, i < clients.size() - 1);
            } catch (RuntimeException ex) {
                lastFailure = ex;
                logCandidateFailure(candidate, ex, i < clients.size() - 1);
            }

            // P8/U21bis, 2026-09-23: a deadline watchdog (ReactExecutionSupport
            // .completeWithin, which interrupts the calling thread — this method runs on)
            // firing mid-candidate must stop the failover loop here, not let it march
            // through however many candidates remain, each paying its own full timeout —
            // the "N×timeout amplification" this class had no defense against at all
            // (zero references to interrupt/Thread.currentThread() before this fix). The
            // deadline itself is never a parameter here — same conclusion as U6/U18 for
            // the analogous LlmClient/MemoryManager interfaces: adding one would be a
            // breaking change to LlmCallContext, touching every adapter, for a caller
            // that already has an interrupt-based mechanism to say "stop" with.
            if (Thread.currentThread().isInterrupted()) {
                log.warn("LLM failover loop stopped after client '{}' — calling thread was "
                                + "interrupted (deadline exceeded), not trying the remaining {} candidate(s)",
                        candidate.providerId(), clients.size() - i - 1);
                return ChainOutcome.failed(i + 1, "interrupted", lastFailure);
            }
        }
        return ChainOutcome.failed(clients.size(), "exhausted", lastFailure);
    }

    /**
     * One place for the two failure logs: the last candidate's failure is an exhaustion
     * (the whole pool is down), an earlier one is a switch. The error type is only known for
     * an {@link LlmException} — an unexpected {@link RuntimeException} from an adapter gets
     * logged by its message alone, exactly as before.
     */
    private void logCandidateFailure(LlmClient candidate, Throwable failure, boolean hasNext) {
        String type = failure instanceof LlmException llmFailure ? " [" + llmFailure.errorType() + "]" : "";
        if (hasNext) {
            log.warn("LLM client '{}' failed{} — switching to next fallback. Reason: {}",
                    candidate.providerId(), type, failure.getMessage());
        } else {
            log.error("All {} LLM client(s) failed. Last error from '{}'{}: {}",
                    clients.size(), candidate.providerId(), type, failure.getMessage());
        }
    }

    /**
     * Labels the chain-level span. Every failure is recorded as a span exception, including
     * an unexpected {@link RuntimeException} from an adapter: the label alone says the chain
     * gave up, not why, and the candidate's own {@code llm.complete} span is a sibling of this
     * decision rather than a child of it, so it is not guaranteed to be there to explain it.
     */
    private void describe(Span span, ChainOutcome outcome) {
        span.setAttribute("llm.failover.attempts", (long) outcome.attempts());
        if (outcome.completion() != null) {
            span.setAttribute("llm.failover.outcome", outcome.label())
                    .setAttribute("llm.failover.served_by", outcome.servedBy())
                    .setStatus(SpanStatus.OK);
        } else {
            span.setAttribute("llm.failover.outcome", outcome.label())
                    .setStatus(SpanStatus.ERROR);
            if (outcome.failure() != null) span.recordException(outcome.failure());
        }
    }

    /**
     * Streams tokens with the same ordered failover as {@link #complete}, with one added
     * constraint: <strong>failover only happens before the first token reaches the
     * subscriber.</strong>
     *
     * <p>Once any token has been delivered, switching candidates would replay the response
     * from the beginning and duplicate everything already emitted — and {@code ReactStrategy}
     * deliberately never retries a streaming call for exactly that reason. So a failure after
     * the first {@code onNext} propagates as-is; a failure before it, if
     * {@link LlmException#shouldFailover() failover-able} and a fallback remains,
     * transparently re-subscribes to the next client. Non-failover
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
        /** Subscriptions made so far, incremented on every entry into a candidate. */
        private final AtomicInteger attempts = new AtomicInteger();

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

            // Counted, not derived from idx: idx is the position in the candidate list, while
            // attempts is how many subscriptions this stream actually made — they differ once a
            // candidate fails synchronously in stream() and the next one is entered.
            attempts.incrementAndGet();

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
                        recordDecision("served", candidate, null);
                        downstream.onComplete();
                    }
                }
            });
        }

        private void handleError(Throwable t, int idx, LlmClient candidate, boolean hasNext) {
            if (terminated.get() || cancelled.get()) return;

            boolean nonFailover  = (t instanceof LlmException le) && !le.shouldFailover();
            boolean canFailover  = hasNext && !nonFailover && !delivered.get();

            if (canFailover) {
                log.warn("LLM streaming client '{}' failed{} — switching to next fallback. Reason: {}",
                        candidate.providerId(),
                        (t instanceof LlmException le) ? " [" + le.errorType() + "]" : "",
                        t.getMessage());
                recordDecision("switching", candidate, t);
                current.set(null);
                subscribeTo(idx + 1);
                return;
            }

            if (terminated.compareAndSet(false, true)) {
                if (nonFailover) {
                    log.error("LLM streaming client '{}' returned non-failover error [{}] — "
                            + "aborting failover: {}", candidate.providerId(),
                            ((LlmException) t).errorType(), t.getMessage());
                    recordDecision("aborted_non_failover", candidate, t);
                } else if (delivered.get()) {
                    log.error("LLM streaming client '{}' failed after emitting tokens — not retried "
                            + "(would duplicate the stream): {}", candidate.providerId(), t.getMessage());
                    recordDecision("failed_after_first_token", candidate, t);
                } else {
                    log.error("All {} LLM streaming client(s) failed. Last error from '{}': {}",
                            clients.size(), candidate.providerId(), t.getMessage());
                    recordDecision("exhausted", candidate, t);
                }
                downstream.onError(t);
            }
        }

        /**
         * One short {@code llm.failover} span per decision, started and ended on whatever
         * thread the callback arrived on — which is the point. A streaming failover has no
         * single moment worth wrapping: tokens arrive over time, on the provider's thread, and a
         * scope opened on the subscribing thread cannot be closed there ({@code SpanScope} is
         * thread-bound). Each decision is therefore its own span, and only decisions are
         * recorded — a healthy stream reports one {@code served} span, the counterpart of the
         * single span {@link #complete} emits, plus one per switch.
         */
        private void recordDecision(String outcome, LlmClient candidate, Throwable failure) {
            Span span = telemetry.spanBuilder("llm.failover")
                    .setAttribute("llm.failover.chain", compositeId)
                    .setAttribute("llm.failover.streaming", true)
                    .setAttribute("llm.failover.outcome", outcome)
                    .setAttribute("llm.failover.attempts", (long) attempts.get())
                    .setAttribute("llm.failover.provider", candidate.providerId())
                    .startSpan();
            if (failure != null) {
                span.recordException(failure)
                        .setStatus(SpanStatus.ERROR);
            } else {
                span.setStatus(SpanStatus.OK);
            }
            span.end();
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

    @Override
    public boolean supportsNativeStructuredOutput() {
        return clients.stream().allMatch(LlmClient::supportsNativeStructuredOutput);
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
