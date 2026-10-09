package io.ara.runtime.factory;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.core.telemetry.Span;
import io.ara.core.telemetry.SpanStatus;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link LlmClient} decorator that adds a per-candidate circuit breaker to ordered failover.
 *
 * <p>It wraps a <em>single</em> client and is meant to be composed underneath
 * {@link FailoverLlmClient}: the wiring for {@code LlmSelectionPolicy.FAILOVER} wraps every
 * candidate in its own breaker, so the pool's ordering, logging and streaming
 * before-first-token failover all keep working unchanged while each candidate gets its own
 * health state. {@link FailoverLlmClient} remains the orchestrator; this class only decides
 * whether <em>its</em> candidate is worth trying on a given call.
 *
 * <h2>State machine</h2>
 * <ul>
 *   <li><b>CLOSED</b> — normal operation: every call reaches the delegate. A
 *       failover-able failure (network, 5xx, rate limit — {@link LlmException#shouldFailover()})
 *       increments a counter; on the configured threshold the circuit opens. A success resets
 *       the counter.</li>
 *   <li><b>OPEN</b> — the candidate is skipped: calls fast-fail with a failover-able
 *       {@link LlmException} before ever touching the delegate, so the enclosing pool advances
 *       to the next candidate without paying this endpoint's connect/read timeout. That is the
 *       whole point: during an outage you wait for the timeout once to open the circuit, not on
 *       every request.</li>
 *   <li><b>HALF_OPEN</b> — after {@code cooldown} has elapsed since the circuit opened, the
 *       <em>next</em> call is allowed through as a single trial. Success closes the circuit;
 *       a failover-able failure reopens it for another cooldown. While a trial is in flight,
 *       concurrent calls fast-fail as if OPEN (only one probe at a time).</li>
 * </ul>
 *
 * <p>A <em>non-failover</em> failure (401, invalid request, content filter) never counts and
 * never opens the circuit: it would recur on every candidate in the pool, so it is rethrown
 * untouched for the pool's abort semantics to decide ({@code FailoverLlmClient.complete}
 * already stops the whole chain on it). Opening the circuit for it would only route a
 * misconfiguration around to another provider that fails identically.
 *
 * <p>Health is derived from real traffic, never from artificial probes: the whole point of a
 * passive breaker is that it costs zero extra API calls (no probe thread hammering the
 * endpoint it is trying to protect) and that the trial travels through the ordinary
 * {@link #complete}/{@link #stream} path, so it observes exactly what a production call
 * observes. A separate watchdog thread that actively pings the candidates was considered and
 * rejected: probes bill like real calls, can themselves trip the rate limit the breaker is
 * meant to absorb, and can mark an endpoint down on a probe-specific time-out that real calls
 * would have survived — the passive model reads the evidence the traffic already produced.
 *
 * <p>Sharing the state per endpoint is also what keeps the passive model sufficient. The lazy
 * {@code OPEN -> HALF_OPEN} transition fires on the first call that arrives after the cooldown, and
 * with one circuit per endpoint that first call is drawn from the aggregate traffic of every
 * session and agent using it, not from one conversation's own. A proactive timer that flipped the
 * state the moment the cooldown expired was considered and rejected on that basis: with shared
 * state it anticipates a transition that the next real call already makes promptly, and it would
 * have to hold a reference to the circuit across the idle period the session sweeper is free to
 * tear down — complexity and a lifecycle to manage for a window the traffic already covers.
 *
 * <p>Configuration is deliberately fixed to sensible defaults ({@value #DEFAULT_FAILURE_THRESHOLD}
 * consecutive failures, {@value #DEFAULT_COOLDOWN_SECONDS}s cooldown): the values matter far
 * less than the mechanism, and every knob is another contract to test and defend (the wiring
 * in {@code DefaultWiringFactory} applies this with no parameters).
 *
 * <p>With an {@link AraTelemetry} supplied, each state transition is recorded as a
 * {@code llm.circuit} span carrying {@code llm.provider}, {@code llm.circuit.outcome}
 * ({@code opened} / {@code half_open} / {@code closed}), {@code llm.circuit.from},
 * {@code llm.circuit.failure_threshold} and {@code llm.circuit.cooldown_ms}. Transitions
 * only, never the skips they cause: an open circuit is hit once per request for as long as the
 * outage lasts, and a span per skip would bury the transitions that actually explain the
 * behaviour. {@link AraTelemetry#noop()} (the default) allocates nothing.
 *
 * <p><b>Where the state lives.</b> This wrapper is stateless: the circuit itself is a
 * {@link CircuitState}, either private to this instance (the public constructors, for a caller
 * that wants a standalone breaker) or shared per endpoint by the wiring's
 * {@link CircuitStateRegistry}. Sharing is what the runtime uses, because the wrapper is pinned to
 * a session while the transport underneath is shared by all of them: a private state made every
 * session re-learn an outage with its own full round of timeouts. See {@link CircuitState} for the
 * reasoning and for the sharing key.
 *
 * <p><b>Thread safety:</b> thread-safe. All fields are final and the machine's transitions are
 * compound compare-and-sets inside {@link CircuitState}; a transition's span is emitted by
 * whichever thread won that CAS, which is the thread whose transition it describes.
 */
public final class CircuitBreakerLlmClient implements LlmClient {

    /** Consecutive failover-able failures that open the circuit. */
    public static final int DEFAULT_FAILURE_THRESHOLD = 3;
    /** How long the circuit stays open before a single trial is allowed. */
    public static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(30);
    /** Mirror of {@link #DEFAULT_COOLDOWN} as a literal, so the javadoc can render it with {@code {@value}}. */
    static final int DEFAULT_COOLDOWN_SECONDS = 30;

    private final LlmClient            delegate;
    private final AraTelemetry         telemetry;
    /**
     * The endpoint's health. Private to this wrapper when built through the public constructors —
     * the standalone behaviour every direct caller already had — and shared across sessions when
     * the wiring passes one in from {@link CircuitStateRegistry}, which is how an outage is learned
     * once per endpoint instead of once per session. See {@link CircuitState} for why health does
     * not belong to the session-pinned wrapper.
     */
    private final CircuitState         circuit;

    public CircuitBreakerLlmClient(LlmClient delegate) {
        this(delegate, DEFAULT_FAILURE_THRESHOLD, DEFAULT_COOLDOWN, Clock.systemUTC(), AraTelemetry.noop());
    }

    public CircuitBreakerLlmClient(LlmClient delegate, int failureThreshold, Duration cooldown) {
        this(delegate, failureThreshold, cooldown, Clock.systemUTC(), AraTelemetry.noop());
    }

    public CircuitBreakerLlmClient(LlmClient delegate, AraTelemetry telemetry) {
        this(delegate, DEFAULT_FAILURE_THRESHOLD, DEFAULT_COOLDOWN, Clock.systemUTC(), telemetry);
    }

    public CircuitBreakerLlmClient(LlmClient delegate, int failureThreshold, Duration cooldown, AraTelemetry telemetry) {
        this(delegate, failureThreshold, cooldown, Clock.systemUTC(), telemetry);
    }

    /** Package-visible so tests can freeze/advance the clock and hold the trial threads. */
    CircuitBreakerLlmClient(LlmClient delegate, int failureThreshold, Duration cooldown, Clock clock) {
        this(delegate, failureThreshold, cooldown, clock, AraTelemetry.noop());
    }

    CircuitBreakerLlmClient(LlmClient delegate, int failureThreshold, Duration cooldown,
                            Clock clock, AraTelemetry telemetry) {
        this(delegate, new CircuitState(failureThreshold, cooldown, clock), telemetry);
    }

    /**
     * Wraps {@code delegate} over a circuit state owned by someone else — the wiring's
     * {@link CircuitStateRegistry}, so that every session wrapping this endpoint consults the same
     * health. The threshold, cooldown and clock travel with {@code circuit}, so all sharers of an
     * endpoint agree on them by construction rather than by each caller passing matching values.
     */
    CircuitBreakerLlmClient(LlmClient delegate, CircuitState circuit, AraTelemetry telemetry) {
        this.delegate  = Objects.requireNonNull(delegate, "delegate must not be null");
        this.circuit   = Objects.requireNonNull(circuit, "circuit must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        allowTrialIfCooldownElapsed();
        try {
            LlmCompletion result = delegate.complete(messages, context);
            onSuccess();
            return result;
        } catch (RuntimeException ex) {
            onFailure(ex);
            throw ex;
        }
    }

    @Override
    public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
        // The trial is claimed here, at assembly time, not inside subscribe(): FailoverLlmClient
        // drives streaming failover by catching the fast-fail thrown from candidate.stream(...)
        // (see FailoverLlmClient.FailoverStream.subscribeTo), so an OPEN breaker must throw from
        // this method for the pool to skip it. Moving the claim into the subscription would hide
        // the skip from the orchestrator and break ordered failover.
        allowTrialIfCooldownElapsed();
        Flow.Publisher<String> inner = delegate.stream(messages, context);
        // Guards the single trial verdict. onComplete, onError and a downstream cancel race to
        // resolve it, and only the winner touches the breaker state. Without this, a stream that
        // emits a token and then never sends a terminal signal (a hung SSE connection) would strand
        // the breaker in HALF_OPEN forever — fast-failing a since-recovered endpoint on every later
        // call, with no timeout to recover it. The caller's cancel (its own deadline, e.g.
        // ReactExecutionSupport) now counts as a failed trial and reopens the circuit.
        AtomicBoolean verdictRendered = new AtomicBoolean(false);
        return subscriber -> inner.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) { subscription.request(n); }
                    @Override public void cancel() {
                        if (verdictRendered.compareAndSet(false, true)) reopenAbandonedTrial();
                        subscription.cancel();
                    }
                });
            }
            @Override public void onNext(String item) { subscriber.onNext(item); }
            @Override public void onError(Throwable error) {
                // A mid-stream failure counts exactly like a blocking one: the endpoint proved
                // flaky, and opening the circuit makes the next request skip it. FailoverLlmClient
                // will not re-route this particular stream past the first token, but the next call
                // pays the lesson instead of this one.
                if (verdictRendered.compareAndSet(false, true)) onFailure(error);
                subscriber.onError(error);
            }
            @Override public void onComplete() {
                if (verdictRendered.compareAndSet(false, true)) onSuccess();
                subscriber.onComplete();
            }
        });
    }

    /**
     * CLOSED: proceed. OPEN with cooldown not yet elapsed, or HALF_OPEN with a trial already in
     * flight: throw fast-fail so the enclosing pool advances without paying this candidate's
     * timeout. OPEN with cooldown elapsed: claim the single trial and proceed.
     */
    private void allowTrialIfCooldownElapsed() {
        switch (circuit.beforeCall()) {
            case PROCEED       -> { }
            case SKIP          -> throw fastFail();
            case TRIAL_CLAIMED -> record("half_open", State.OPEN, null);
        }
    }

    private void onSuccess() {
        // Only the recovery close is worth a span: a success while already CLOSED is the
        // normal path and would emit one span per request forever.
        circuit.onSuccess().ifPresent(previous -> record("closed", previous, null));
    }

    private void onFailure(Throwable ex) {
        circuit.onFailure(ex).ifPresent(previous -> record("opened", previous, null));
    }

    /**
     * Reopens the circuit when a streaming trial is abandoned (the downstream cancelled before any
     * terminal signal). A trial that already resolved — a completion that closed it, or an error
     * that reopened it — is left alone, so a normal "cancel after onComplete" never disturbs a
     * healthy circuit.
     */
    private void reopenAbandonedTrial() {
        circuit.onTrialAbandoned().ifPresent(previous -> record("opened", previous, null));
    }

    /**
     * Records one {@code llm.circuit} span for a state transition. Deliberately not recorded
     * for every fast-fail skip: during an outage that would emit one span per request, which
     * is exactly the volume this change is meant to avoid — the {@code opened} transition and
     * the pool's own {@code llm.failover} span already say the candidate is being skipped.
     */
    private void record(String outcome, State from, Throwable cause) {
        Span span = telemetry.spanBuilder("llm.circuit")
                .setAttribute("llm.provider", delegate.providerId())
                .setAttribute("llm.circuit.outcome", outcome)
                .setAttribute("llm.circuit.from", from.name())
                .setAttribute("llm.circuit.failure_threshold", (long) circuit.failureThreshold())
                .setAttribute("llm.circuit.cooldown_ms", circuit.cooldown().toMillis())
                .startSpan();
        if (cause != null) {
            span.recordException(cause).setStatus(SpanStatus.ERROR);
        } else {
            span.setStatus(SpanStatus.OK);
        }
        span.end();
    }

    private LlmException fastFail() {
        return LlmException.connectionError(delegate.providerId(),
                "circuit open — skipping '" + delegate.providerId() + "' after "
                        + circuit.failureThreshold() + " consecutive failures (cooldown "
                        + circuit.cooldown() + ")",
                null);
    }

    @Override
    public String providerId() {
        return delegate.providerId();
    }

    @Override
    public String lastUsedProviderId() {
        return delegate.lastUsedProviderId();
    }

    @Override
    public boolean supportsNativeTools() {
        // Must forward the delegate's capability: a breaker that inherited the default false
        // would quietly strip native tool-calling whenever FAILOVER wraps a capable client
        // (see LlmClient.supportsNativeTools javadoc).
        return delegate.supportsNativeTools();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        // Forward the delegate's capability for the same reason as supportsNativeTools above.
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public Set<String> supportedMediaTypes() {
        return delegate.supportedMediaTypes();
    }

    /**
     * Lifecycle of a single candidate's breaker.
     * <ul>
     *   <li>CLOSED: normal operation, call the delegate.</li>
     *   <li>OPEN: skip the delegate until the cooldown has elapsed.</li>
     *   <li>HALF_OPEN: a single trial call is in flight (or was just claimed); all others skip.</li>
     * </ul>
     */
    enum State { CLOSED, OPEN, HALF_OPEN }

    /** Exposed for tests (same package) and diagnostics; {@link State#CLOSED} when never tripped. */
    State state() {
        return circuit.state();
    }

    @Override
    public String toString() {
        return "circuit-breaker[" + delegate.providerId() + ":" + circuit.state() + "]";
    }
}