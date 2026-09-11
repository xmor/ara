package io.ara.adapters.llm;

import java.time.Duration;

/**
 * Shared fluent-builder surface for the LangChain4j-backed, keyed LLM adapters.
 *
 * <p>Three adapters — {@code OpenAiLlmClient}, {@code AnthropicLlmClient},
 * {@code MistralLlmClient} — configure the same eight knobs that the LangChain4j model
 * builders accept (API key, base URL, model, sampling temperature, max tokens, timeout,
 * request/response logging), and until now each declared the field, the fluent setter and
 * the default by hand, three times over. This class holds that copy once; each adapter's
 * nested {@code Builder} extends it, keeps only its own knobs, and sets its own defaults in
 * its constructor.
 *
 * <h2>Which adapters extend this, and why</h2>
 * <p><b>Ollama does not.</b> Its builder needs no API key, no max-tokens and no top-p cap,
 * and its defaults (base URL {@code http://localhost:11434}, five-minute timeout, no
 * temperature) differ on every field that would be inherited. Inheriting knobs a provider
 * does not accept would make {@code .apiKey(...)} on an Ollama client silently do
 * nothing — the same generous-guessing failure mode documented on
 * {@code OpenAiLlmClient.Builder#documentSupport} and {@code OllamaLlmClient.Builder#nativeTools}.
 * <b>{@code ChatJimmyLlmClient} does not either</b>: it is not LangChain4j-backed and its
 * builder is shaped by chatjimmy's own wire format (proxy, no credential). Two standalone
 * builders, three sharing this base, beat one base whose unused knobs sit on two of the
 * five.
 *
 * <h2>Discarded alternative: protected fields read by the client constructors</h2>
 * <p>The most natural first shape was for each {@code LlmClient} constructor to keep
 * reading {@code builder.apiKey} directly, as the pre-refactor code did. That is illegal
 * across packages once the fields move here: the clients implement {@code LlmClient}, they
 * are <em>not</em> subclasses of this class (their nested {@code Builder} is), and Java
 * {@code protected} access from a different package requires the reader to own the
 * implementation of the instance. A public {@link #settings()} snapshot is the least
 * ceremony that keeps a client constructor one expression away from the values.
 *
 * <h2>Defaults</h2>
 * <p>Field defaults are the OpenAI/Mistral profile ({@code temperature 0.7}, {@code 2000}
 * max tokens, {@code 60s} timeout). Adapters that disagree reset the field in their own
 * constructor (Anthropic: {@code 4096} max tokens) rather than duplicating the whole
 * setter block — a one-line override reads more honestly than a shared knobs object holding
 * per-adapter defaults, which would sit as far from the adapter it configures as the fields
 * they replace.
 *
 * @param <B> the concrete builder type, used so every inherited setter returns the
 *            adapter's own {@code Builder} and chaining keeps working
 * @see AbstractLlmClientBuilder.LlmSettings
 */
public abstract class AbstractLlmClientBuilder<B extends AbstractLlmClientBuilder<B>> {

    /** API key, for providers that take one. Null until set. */
    protected String apiKey;
    /** Base URL override (proxies, gateways, self-hosted endpoints). Null = provider default. */
    protected String baseUrl;
    /** Model identifier. Null until set, or given a catalogue default by a subclass constructor. */
    protected String modelName;
    /** Sampling temperature. Defaults to {@code 0.7}. */
    protected Double temperature = 0.7;
    /** Maximum output tokens. Defaults to {@code 2000}. */
    protected Integer maxTokens = 2000;
    /** HTTP request timeout. Defaults to {@code 60s}. */
    protected Duration timeout = Duration.ofSeconds(60);
    /** LangChain4j request logging to SLF4J. Off by default. */
    protected boolean logRequests;
    /** LangChain4j response logging to SLF4J. Off by default. */
    protected boolean logResponses;

    /**
     * Snapshot of the shared settings, consumed by {@code LlmClient} constructors.
     *
     * <p>A single immutable record keeps that read one expression: the constructors that
     * build the LangChain4j models need all eight values together, and getters would either
     * scatter the read over eight calls or (worse) invite a client to read a half-snapshot.
     */
    public record LlmSettings(
            String apiKey,
            String baseUrl,
            String modelName,
            Double temperature,
            Integer maxTokens,
            Duration timeout,
            boolean logRequests,
            boolean logResponses) {}

    /**
     * Returns the current shared settings as a snapshot, defaults applied.
     *
     * <p>Why a snapshot rather than getters, at length: see the class javadoc, "Discarded
     * alternative". In short, the client constructors are in the adapter subpackages and
     * cannot legally read the {@code protected} fields of a builder instance they do not
     * implement.
     *
     * @return the shared fields, with defaults applied
     */
    public final LlmSettings settings() {
        return new LlmSettings(apiKey, baseUrl, modelName, temperature, maxTokens,
                timeout, logRequests, logResponses);
    }

    /** The concrete {@code Builder} for {@code this}, so inherited setters stay fluent. */
    @SuppressWarnings("unchecked")
    protected final B self() {
        return (B) this;
    }

    /** Sets the provider API key (required by the adapters that take one). */
    public B apiKey(String apiKey)       { this.apiKey = apiKey;   return self(); }

    /** Overrides the default API base URL (proxies, gateways, self-hosted endpoints). */
    public B baseUrl(String baseUrl)     { this.baseUrl = baseUrl; return self(); }

    /** Sets the model by string ID (for non-catalogued or preview models). */
    public B modelName(String modelName) { this.modelName = modelName; return self(); }

    /** Sampling temperature. Defaults to {@code 0.7}. */
    public B temperature(double t)       { this.temperature = t;   return self(); }

    /** Maximum output tokens. Defaults to {@code 2000}. */
    public B maxTokens(int maxTokens)    { this.maxTokens = maxTokens; return self(); }

    /** HTTP request timeout. Defaults to {@code 60s}. */
    public B timeout(Duration timeout)   { this.timeout = timeout; return self(); }

    /** Enables LangChain4j request logging to SLF4J. */
    public B logRequests(boolean v)      { this.logRequests = v;   return self(); }

    /** Enables LangChain4j response logging to SLF4J. */
    public B logResponses(boolean v)     { this.logResponses = v;  return self(); }
}