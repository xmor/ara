package io.ara.runtime.llm;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Flow;

/**
 * Base class for every {@link LlmClient} decorator that wraps exactly one delegate.
 *
 * <h2>Why this exists</h2>
 * <p>Capability methods on {@link LlmClient} ({@code supportsNativeTools()}, and any that
 * follow it) have a safe <em>leaf</em> default — "no, I don't do that". For a decorator
 * that default is not safe at all: it is a lie about whatever it wraps. A decorator that
 * forgets to forward one silently reports "no native tools" over an OpenAI client, and the
 * strategy above it responds by injecting a text tool catalog that competes with the
 * structured channel — a failure with no exception and no log line anywhere.
 *
 * <p>Before this class each decorator hand-copied that forwarding, which works right up
 * until a new capability method is added: every decorator then has to be found and edited,
 * and the one that is missed fails exactly as described. Here, forwarding is inherited.
 * A new capability gets one implementation in this class and every single-wrap decorator
 * reports it correctly without being touched.
 *
 * <p><b>Scope.</b> Single-wrap decorators only. Composites over a <em>list</em> of clients
 * ({@code FailoverLlmClient}, {@code RoundRobinLlmClient}, {@code RoutingLlmClient}) cannot
 * inherit from here: for them a capability is a property of the whole pool, and the honest
 * answer is the intersection of the delegates' — a client the pool might pick that lacks
 * the capability makes the pool unable to promise it. Leaf stubs that wrap nothing
 * ({@code ScriptedLlmClient}, {@code NoopLlmClient}) are correct with the interface
 * defaults and must not extend this either.
 *
 * <p>The deprecated {@code complete(List, AgentConfig)} overload is deliberately
 * <em>not</em> overridden: the interface default bridges it to {@code this.complete(...)},
 * so it goes through the subclass's decoration. Forwarding it straight to the delegate
 * would quietly skip it.
 */
public abstract class DelegatingLlmClient implements LlmClient {

    /** The wrapped client. Visible to subclasses, which decorate calls around it. */
    protected final LlmClient delegate;

    protected DelegatingLlmClient(LlmClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        return delegate.complete(messages, context);
    }

    @Override
    public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
        return delegate.stream(messages, context);
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
        return delegate.supportsNativeTools();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public Set<String> supportedMediaTypes() {
        return delegate.supportedMediaTypes();
    }
}
