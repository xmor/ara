package io.ara.core.llm;

import io.ara.core.agent.AgentConfig;

import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Abstraction over any LLM provider (OpenAI, Anthropic, Gemini, Ollama, …).
 *
 * <p>The runtime only ever speaks to this interface — concrete adapters live in
 * {@code ara-llm-providers} and are wired by {@code AgentFactory}.
 *
 * <h2>Migration from AgentConfig to LlmCallContext (ADR-017)</h2>
 * <p>The primary method is now {@link #complete(List, LlmCallContext)} which accepts
 * per-call parameters (output schema, temperature override, stop sequences, seed).
 * Implementations must override this method. The {@link #complete(List, AgentConfig)}
 * overload is deprecated and bridges automatically to the new primary method.
 */
public interface LlmClient {

    /**
     * Sends messages to the LLM with per-call parameters and blocks until completion.
     *
     * <p>This is the primary method. Implementations must override this.
     * The context carries per-call parameters (output schema, temperature override, etc.)
     * in addition to the base configuration copied from {@link AgentConfig}.
     *
     * @param messages the conversation history, oldest first
     * @param context  per-call parameters built from AgentConfig + LlmExecutionHints
     * @return the LLM's completion
     * @throws LlmException if the LLM call fails; check {@link LlmException#isRetryable()} to decide whether to retry
     */
    LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException;

    /**
     * @deprecated Use {@link #complete(List, LlmCallContext)}. This method bridges
     *             to the new primary by wrapping config in a {@link LlmCallContext}.
     *             Per-call features (output schema, temperature override, stop sequences,
     *             seed) are not available through this path.
     */
    @Deprecated(forRemoval = false)
    default LlmCompletion complete(List<LlmMessage> messages, AgentConfig config) {
        return complete(messages, LlmCallContext.from(config));
    }

    /**
     * Streams tokens from the LLM with per-call parameters.
     *
     * <p>The default implementation delegates to {@link #complete(List, LlmCallContext)}
     * and emits the full response as a single item. Implementations that support native
     * streaming (e.g. {@code Lc4jLlmClient}) override this method.
     *
     * <p><b>P0/U6, 2026-09-22:</b> {@code request(n)} runs {@link #complete} on a dedicated
     * virtual thread rather than the calling thread. The caller here is typically {@code
     * ReactExecutionSupport.streamAndCollect} (in {@code ara-runtime}, which depends on this
     * module — not the other way around, so that class's own bounded-wait-plus-retry
     * machinery, {@code completeWithRetry}, cannot be called from here directly): it {@code
     * subscribe()}s and then bounds its wait for {@code onComplete}/{@code onError} with its
     * own deadline. That bound only ever applies to time spent <em>after</em> {@code
     * subscribe()} returns — a {@code request(n)} that blocked synchronously inside {@code
     * complete()} (the previous behaviour here) ran before the bounded wait even started,
     * silently bypassing it entirely for every client that relies on this default rather than
     * overriding {@link #stream}. Handing the call to a worker thread lets {@code
     * subscribe()} return immediately, so the deadline enforced downstream actually applies;
     * {@link Flow.Subscription#cancel()} interrupts that worker, mirroring {@code
     * completeWithin}'s own interrupt-based cancellation — best-effort, same as there, since
     * nothing here can force an uncooperative {@link #complete} implementation to return
     * early. Guarded to run {@link #complete} at most once even if {@code request} is called
     * more than once, since this publisher only ever emits a single item.
     */
    default Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private final AtomicBoolean started = new AtomicBoolean();
            private volatile Thread worker;

            @Override
            public void request(long n) {
                if (!started.compareAndSet(false, true)) return;   // single-item publisher — only the first request() runs anything
                worker = Thread.ofVirtual().start(() -> {
                    try {
                        String text = complete(messages, context).text();
                        subscriber.onNext(text);
                        subscriber.onComplete();
                    } catch (Exception e) {
                        subscriber.onError(e);
                    }
                });
            }

            @Override
            public void cancel() {
                Thread w = worker;
                if (w != null) w.interrupt();
            }
        });
    }

    /**
     * @deprecated Use {@link #stream(List, LlmCallContext)}.
     */
    @Deprecated(forRemoval = false)
    default Flow.Publisher<String> stream(List<LlmMessage> messages, AgentConfig config) {
        return stream(messages, LlmCallContext.from(config));
    }

    /**
     * Returns the provider identifier string (e.g. {@code "langchain4j-gpt-4o"}).
     *
     * @return a non-null, non-blank provider id
     */
    String providerId();

    /**
     * Returns the provider id of the client that handled the most recent {@link #complete} call.
     *
     * <p>For simple clients this is identical to {@link #providerId()}. Composite clients
     * (e.g. failover) override this to return the id of whichever delegate actually
     * responded, giving callers visibility into which model was used at runtime.
     *
     * @return provider id of the last successful delegate; never null
     */
    default String lastUsedProviderId() {
        return providerId();
    }

    /**
     * Whether this client sends native provider tool/function-calling
     * ({@link LlmCallContext#resolvedTools()} → structured {@code toolSpecifications}
     * on the outgoing request) and reports invocations back via {@link
     * LlmCompletion#hasToolCall()}/{@link LlmCompletion#toolCalls()}.
     *
     * <p>Strategies ({@code ReactStrategy}, {@code PlanExecuteStrategy}) use this to
     * decide whether to also inject the text-based tool catalog and inline
     * {@code {"tool_id":...}} / {@code FINAL_ANSWER} instructions into the system
     * prompt: when {@code true}, that text scaffolding is redundant with (and can
     * actively compete against) the structured channel, so it is omitted.
     *
     * <p>Defaults to {@code false} — the safe default for any client that only
     * supports prompt-based tool routing (e.g. most local/Ollama models). Adapters
     * that do speak native function-calling (OpenAI, Anthropic) override this to
     * {@code true}. Decorators <strong>must</strong> delegate to the wrapped
     * client(s) rather than inherit this default, or they will silently mask the
     * capability of whatever they wrap.
     *
     * @return {@code true} if native tool/function-calling is used for this client
     */
    default boolean supportsNativeTools() {
        return false;
    }

    /**
     * Whether this client can enforce an output JSON Schema natively — sending it as a
     * provider {@code response_format: json_schema} on the request rather than relying on
     * the schema being appended to the system prompt.
     *
     * <p>This is the capability behind {@link LlmProfile#nativeJsonSchema()}: when an agent
     * sets {@code nativeJsonSchema(true)} and declares an output schema, the schema travels
     * on {@link LlmCallContext#outputJsonSchema()} and the adapter is expected to put it in
     * the request's {@code response_format}. A client that returns {@code false} here cannot
     * honour that request and must reject it with a non-retryable {@link LlmException}
     * rather than silently answer in prose — the same contract as an unsupported media type.
     *
     * <p><b>Support is per-endpoint, not per-vendor.</b> The structured-output API is an
     * OpenAI extension that hosted OpenAI, Azure OpenAI and a few gateways implement, while
     * many OpenAI-compatible proxies reject the {@code response_format} field outright. So an
     * adapter pointed at an arbitrary {@code baseUrl} must not claim this on the strength of
     * speaking the OpenAI wire format — exactly the lesson {@link #supportedMediaTypes()}
     * learned with {@code file} content parts.
     *
     * <p>Defaults to {@code false} — the safe answer for any client with no native structured
     * output. Adapters that have it override this (OpenAI, per endpoint). Decorators
     * <strong>must</strong> delegate to the wrapped client(s) rather than inherit this
     * default, or they will silently mask the capability of whatever they wrap; a composite
     * over several clients reports the <em>intersection</em>, since it cannot promise what a
     * candidate it might pick lacks.
     *
     * @return {@code true} if this client sends a native {@code response_format: json_schema}
     */
    default boolean supportsNativeStructuredOutput() {
        return false;
    }

    /**
     * The MIME types from {@code MediaTypes.allowed()} that this client can actually send to
     * its provider.
     *
     * <p>Provider media support is per-type, not a boolean: Ollama takes images but has no
     * PDF path at all, while OpenAI, Anthropic and Mistral take both. A client must declare
     * only what it really forwards.
     *
     * <p><b>A media type absent from this set is a hard error, never a downgrade.</b> When a
     * call carries media this client does not support, the adapter must raise a
     * {@link LlmException} with {@link LlmException#shouldFailover()} {@code false}
     * naming the type and the provider, before the request goes out. It must not strip
     * the attachment, must not fall back to text, and must not log a warning and
     * continue: doing so produces a confident, well-formed answer about a document the
     * model never saw — indistinguishable from a real answer to anyone reading the
     * response, with the only trace in a log nobody reads in production. A
     * {@code shouldFailover() false} classification stops {@code FailoverLlmClient} from
     * quietly trying the next candidate, which would turn one such answer into an
     * ordinary-looking success.
     *
     * <p>Defaults to an empty set — text only, the safe answer for a leaf client that has
     * no media path. Decorators must not inherit this default: extend {@code
     * DelegatingLlmClient} so the delegate's set is forwarded, or a decorator silently
     * masks the capability of whatever it wraps. Composites over several clients report the
     * <em>intersection</em>, since they cannot promise what any candidate they might pick
     * lacks.
     *
     * @return the supported MIME types; never {@code null}, empty means text-only
     */
    default java.util.Set<String> supportedMediaTypes() {
        return java.util.Set.of();
    }
}
