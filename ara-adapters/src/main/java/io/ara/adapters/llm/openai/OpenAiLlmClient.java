package io.ara.adapters.llm.openai;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.ara.adapters.llm.AbstractLangChain4jLlmClient;
import io.ara.adapters.llm.AbstractLlmClientBuilder;
import io.ara.adapters.llm.AbstractLlmClientBuilder.LlmSettings;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmException;
import io.ara.core.media.MediaTypes;
import io.ara.core.media.MediaTypes.MediaKind;

/**
 * {@link LlmClient} adapter for the <a href="https://platform.openai.com/">OpenAI</a> API,
 * backed by <a href="https://github.com/langchain4j/langchain4j">LangChain4j</a>.
 *
 * <p>Compatible with any OpenAI-compatible endpoint (Azure OpenAI, LM Studio, Groq, Together AI, …)
 * by overriding the base URL via {@link Builder#baseUrl(String)}.
 *
 * <pre>{@code
 * LlmClient gpt4o = OpenAiLlmClient.builder()
 *     .apiKey(System.getenv("OPENAI_API_KEY"))
 *     .modelName("gpt-oss-20b")
 *     .build();
 *
 * AraRuntime runtime = AraRuntime.builder()
 *     .llmClient("gpt-4o", gpt4o)
 *     .build();
 * }</pre>
 *
 * <h2>Function calling</h2>
 * <p>When {@link LlmCallContext} carries resolved tools, they are converted to LangChain4j
 * {@link ToolSpecification} objects and forwarded to the OpenAI tool-calls API.
 *
 * <h2>OpenAI-compatible endpoints</h2>
 * <pre>{@code
 * // Groq example
 * LlmClient groq = OpenAiLlmClient.builder()
 *     .apiKey(System.getenv("GROQ_API_KEY"))
 *     .baseUrl("https://api.groq.com/openai/v1")
 *     .modelName("llama3-70b-8192")
 *     .build();
 * }</pre>
 *
 * @see LlmClient
 */
public class OpenAiLlmClient extends AbstractLangChain4jLlmClient {

    private static final String PROVIDER = "openai";

    private final OpenAiChatModel chatModel;
    // Lazily initialize the streaming model to avoid unnecessary resource allocation.
    private volatile OpenAiStreamingChatModel streamingModel;
    private final String modelName;
    private final String apiKey;
    private final String baseUrl;
    private final Double defaultTemperature;
    private final Double defaultTopP;
    private final Integer defaultMaxTokens;
    private final Duration timeout;
    private final boolean logRequests;
    private final boolean logResponses;
    private final Map<String, String> customHeaders;
    private final boolean documentSupport;
    /**
     * Whether this endpoint accepts a native {@code response_format: json_schema}.
     *
     * <p>Derived the same way as {@link #documentSupport}, and for the same reason: structured
     * output is an OpenAI API extension, hosted OpenAI (and Azure) implement it, but an arbitrary
     * OpenAI-compatible gateway often rejects the {@code response_format} field. So the default is
     * {@code true} only when no custom {@link Builder#baseUrl(String)} is set; behind a base URL
     * it is {@code false} unless the caller opts in with {@link Builder#structuredOutputSupport(boolean)}.
     * See {@link #supportsNativeStructuredOutput()}.
     */
    private final boolean structuredOutputSupport;
    /**
     * Whether a native output schema is sent with OpenAI's {@code strict: true}, which is what
     * turns the schema from strong guidance into a guarantee: only in strict mode does OpenAI
     * constrain decoding so the answer cannot violate the schema.
     *
     * <p>Opt-in rather than on by default, because strict mode restricts what the schema may
     * contain — every property must be listed in {@code required}, {@code additionalProperties}
     * must be {@code false}, and several JSON Schema keywords are unsupported. A perfectly legal
     * draft-07 schema that breaks one of those rules is rejected by the API, so defaulting to
     * {@code true} would turn working agents into 400s. {@code null}/{@code false} keeps the
     * previous wire shape ({@code strict: false}).
     */
    private final Boolean strictJsonSchema;
    /**
     * Forces HTTP/1.1 on <em>both</em> the blocking and the streaming model's underlying JDK
     * {@code HttpClient}.
     *
     * <p>It addresses two distinct failures of OpenAI-compatible endpoints, which is why it
     * must cover both call paths rather than streaming alone:
     * <ul>
     *   <li><b>SSE buffering (streaming only).</b> Some gateways use HTTP/2 multiplexing that
     *       buffers SSE data-frames server-side and delivers them all at once at the end of the
     *       response instead of flushing each token as it arrives. {@code HTTP_1_1} disables
     *       multiplexing so each {@code data:} frame is flushed immediately. See
     *       https://github.com/langchain4j/langchain4j/issues/3682.</li>
     *   <li><b>Upgrade rejection (every request, blocking included).</b> A vLLM served through
     *       uvicorn's {@code httptools} path mistakes the HTTP/2 h2c upgrade handshake the JDK
     *       client may attempt for a WebSocket handshake, rejects it, logs
     *       {@code "Unsupported upgrade request."} and drops the request body — so even a plain
     *       {@code complete()} call fails. Pinning HTTP/1.1 never sends the upgrade header.</li>
     * </ul>
     *
     * <p>Because the second failure hits the blocking path too, the flag is applied to
     * {@code chatModel} as well as the streaming model — see {@link #applyCustomHttpClientIfNeeded}.
     */
    private final boolean forceHttp1;
    /**
     * Bounds the TCP/TLS handshake, independently of {@link #timeout}. {@code null} (the default)
     * leaves the JDK's own behaviour in place, which is what every build did before this knob
     * existed: langchain4j applies its single {@code timeout} to the whole call, so an
     * unreachable endpoint is only given up on after the full request budget.
     *
     * <p>Set it short when this client is a candidate in a failover pool: a dead endpoint is then
     * recognised in seconds rather than costing the whole request timeout on every call, while a
     * provider that is merely slow still gets {@link #timeout} to answer in.
     */
    private final Duration connectTimeout;
    /**
     * Precomputed once: the media capability is a pure function of {@link #documentSupport},
     * which never changes after construction, so rebuilding the set on every request only
     * allocates and streams the vocabulary needlessly.
     */
    private final Set<String> supportedMediaTypes;

    // ── Model catalogue ───────────────────────────────────────────────────────

    /**
     * Enumeration of OpenAI models commonly used through this adapter.
     *
     * <p>Only hosted-OpenAI models belong here; an OpenAI-<em>compatible</em> endpoint
     * (Azure, Groq, LM Studio, Together AI, a corporate gateway) serves its own catalogue, so
     * for those keep using {@link Builder#modelName(String)} with the id the endpoint expects.
     *
     * <p>The {@code o}-series are reasoning models: they reject {@code temperature} values
     * other than the default and expect {@code max_completion_tokens} rather than
     * {@code max_tokens}. Point this adapter at one only if you are prepared to leave
     * {@link Builder#temperature(double)} unset — see the class notes on OpenAI-compatible
     * endpoints.
     *
     * <p>Model ids and context windows are from the
     * <a href="https://platform.openai.com/docs/models">OpenAI models overview</a>
     * (last verified: 2026-10). Context window is the combined input+output token budget.
     */
    public enum Models {
        // GPT-5 family
        GPT_5           ("gpt-5",            400_000),
        GPT_5_MINI      ("gpt-5-mini",       400_000),
        GPT_5_NANO      ("gpt-5-nano",       400_000),
        // GPT-4.1 family (1M-token context)
        GPT_4_1         ("gpt-4.1",        1_000_000),
        GPT_4_1_MINI    ("gpt-4.1-mini",   1_000_000),
        GPT_4_1_NANO    ("gpt-4.1-nano",   1_000_000),
        // GPT-4o family
        GPT_4O          ("gpt-4o",           128_000),
        GPT_4O_MINI     ("gpt-4o-mini",      128_000),
        // o-series reasoning models (see enum javadoc on temperature / max tokens)
        O3              ("o3",               200_000),
        O3_MINI         ("o3-mini",          200_000),
        O1              ("o1",               200_000),
        O1_MINI         ("o1-mini",          128_000);

        /** OpenAI model identifier string. */
        public final String id;
        /** Maximum context window in tokens (combined input + output). */
        public final int contextWindow;

        Models(String id, int contextWindow) {
            this.id            = id;
            this.contextWindow = contextWindow;
        }
    }

    private OpenAiLlmClient(Builder builder) {
        LlmSettings s = builder.settings();
        this.modelName = s.modelName();
        this.apiKey = s.apiKey();
        this.baseUrl = s.baseUrl();
        this.defaultTemperature = s.temperature();
        this.defaultTopP = builder.topP;
        this.defaultMaxTokens = s.maxTokens();
        this.timeout = s.timeout();
        this.logRequests = s.logRequests();
        this.logResponses = s.logResponses();
        this.customHeaders = builder.customHeaders;
        // Unset by the caller ⇒ derive it: hosted OpenAI (no custom base URL) accepts `file`
        // parts, an arbitrary OpenAI-compatible endpoint usually does not. See
        // supportedMediaTypes().
        this.documentSupport = builder.documentSupport != null
                ? builder.documentSupport
                : (s.baseUrl() == null || s.baseUrl().isBlank());
        // Same per-endpoint derivation as documentSupport: hosted OpenAI accepts
        // response_format: json_schema, an arbitrary OpenAI-compatible gateway usually does
        // not. See supportsNativeStructuredOutput().
        this.structuredOutputSupport = builder.structuredOutputSupport != null
                ? builder.structuredOutputSupport
                : (s.baseUrl() == null || s.baseUrl().isBlank());
        this.strictJsonSchema = builder.strictJsonSchema;
        this.forceHttp1 = builder.forceHttp1;
        this.connectTimeout = builder.connectTimeout;
        this.supportedMediaTypes = documentSupport
                ? MediaTypes.ofKinds(MediaKind.IMAGE, MediaKind.DOCUMENT, MediaKind.TEXT)
                : MediaTypes.ofKinds(MediaKind.IMAGE, MediaKind.TEXT);

        this.chatModel = applyCustomHttpClientIfNeeded(OpenAiChatModel.builder()
                .apiKey(s.apiKey())
                .baseUrl(s.baseUrl())
                .modelName(s.modelName())
                .temperature(s.temperature())
                .topP(builder.topP)
                .maxTokens(s.maxTokens())
                .returnThinking(true)   // parse-only: reads the reasoning a server returns; asks for nothing
                .strictJsonSchema(builder.strictJsonSchema)   // null ⇒ langchain4j's default (strict: false)
                .timeout(s.timeout())
                .customHeaders(customHeaders)
                .logRequests(s.logRequests())
                .logResponses(s.logResponses())
                .maxRetries(0))
                .build();
    }

    /**
     * Installs a customised JDK HTTP client on the blocking chat-model builder when this client
     * needs one — {@link #forceHttp1} and/or {@link #connectTimeout} — and returns the builder for
     * chaining. A no-op when neither is set, so the default build is byte-for-byte what it was
     * before either flag existed.
     *
     * <p>Shared with {@link #getStreamingModel()} through {@link #jdkClientBuilder()}: both the
     * blocking and the streaming model must apply the same transport settings — not just the
     * SSE-buffering {@code forceHttp1} documents, but the {@code Connection: Upgrade} rejection a
     * uvicorn/{@code httptools} vLLM applies to <em>every</em> request, {@code complete()}
     * included. See the {@link #forceHttp1} field javadoc.
     */
    private OpenAiChatModel.OpenAiChatModelBuilder applyCustomHttpClientIfNeeded(
            OpenAiChatModel.OpenAiChatModelBuilder builder) {
        if (needsCustomHttpClient()) builder.httpClientBuilder(jdkClientBuilder());
        return builder;
    }

    /** Whether any transport-level override requires building our own JDK client. */
    private boolean needsCustomHttpClient() {
        return forceHttp1 || connectTimeout != null;
    }

    /**
     * The JDK HTTP client for this adapter, carrying whichever transport overrides are set.
     *
     * <p>Both overrides must travel on the <em>same</em> builder: langchain4j takes a single
     * {@code httpClientBuilder}, so installing one per concern would mean the second silently
     * replacing the first.
     *
     * <p>{@code HTTP_1_1} pinning removes the h2c upgrade handshake the JDK client would otherwise
     * attempt — the handshake a uvicorn/{@code httptools} vLLM mistakes for a WebSocket upgrade,
     * rejecting it and dropping the request body.
     *
     * <p>{@link #connectTimeout}, when set, bounds the TCP/TLS handshake independently of the
     * request timeout. That separation is the point: langchain4j's single {@code timeout} is
     * applied to the whole call, so an unreachable endpoint costs the <em>full</em> request budget
     * before a failover pool can move on — 30s of dead wait per request for a host that will never
     * answer. A short connect timeout fails that case fast while leaving a slow-but-alive provider
     * its full time to respond.
     */
    private java.net.http.HttpClient.Builder jdkHttpClientBuilder() {
        java.net.http.HttpClient.Builder jdkBuilder = java.net.http.HttpClient.newBuilder();
        if (forceHttp1) jdkBuilder.version(java.net.http.HttpClient.Version.HTTP_1_1);
        if (connectTimeout != null) jdkBuilder.connectTimeout(connectTimeout);
        return jdkBuilder;
    }

    /** {@link #jdkHttpClientBuilder()} wrapped for langchain4j. */
    private HttpClientBuilder jdkClientBuilder() {
        JdkHttpClientBuilder jdk = JdkHttpClient.builder().httpClientBuilder(jdkHttpClientBuilder());
        if (connectTimeout == null) return jdk;
        jdk.connectTimeout(connectTimeout);
        return new PinnedConnectTimeoutBuilder(jdk, connectTimeout);
    }

    /**
     * Keeps {@link #connectTimeout} from being overwritten by the request timeout.
     *
     * <p>Necessary because langchain4j resolves the two from the same value and lets the model's
     * win: {@code OpenAiChatModel} always calls {@code .connectTimeout(getOrDefault(timeout,
     * ofSeconds(15)))} on the supplied builder, and {@code DefaultOpenAiClient} then prefers that
     * over whatever the builder already carried
     * ({@code getOrDefault(builder.connectTimeout, httpClientBuilder.connectTimeout())}). A
     * connect timeout set on the JDK client underneath is therefore never consulted — verified
     * against langchain4j 1.16.1, and the reason setting it there alone silently kept the full
     * 30s handshake wait.
     *
     * <p>So this decorator accepts every other setting and ignores exactly one: an attempt to
     * reset the connect timeout. {@code readTimeout} passes through untouched, which is what
     * keeps the request budget the caller configured — the point of the knob is to separate the
     * two, not to shorten the time a slow-but-alive provider is given to answer.
     */
    private static final class PinnedConnectTimeoutBuilder implements HttpClientBuilder {

        private final JdkHttpClientBuilder delegate;
        private final Duration             pinned;

        PinnedConnectTimeoutBuilder(JdkHttpClientBuilder delegate, Duration pinned) {
            this.delegate = delegate;
            this.pinned   = pinned;
        }

        @Override
        public Duration connectTimeout() {
            return pinned;
        }

        /** Deliberately ignored — see the class javadoc. */
        @Override
        public HttpClientBuilder connectTimeout(Duration ignored) {
            return this;
        }

        @Override
        public Duration readTimeout() {
            return delegate.readTimeout();
        }

        @Override
        public HttpClientBuilder readTimeout(Duration readTimeout) {
            delegate.readTimeout(readTimeout);
            return this;
        }

        @Override
        public dev.langchain4j.http.client.HttpClient build() {
            return delegate.build();
        }
    }

    /**
     * OpenAI takes an effort level per request ({@code reasoning_effort}); it has no token budget.
     * Returning the reasoning needs nothing per call: the models above are built to read it whenever the
     * server sends it, and the agent's {@code returnReasoning} decides whether a run records it.
     */
    @Override
    protected ChatRequestParameters reasoningParameters(LlmCallContext context) {
        if (context.thinkingBudgetTokens() != null) {
            throw unsupportedReasoningOption("thinkingBudgetTokens",
                    "OpenAI sets how hard a model reasons with reasoningEffort, not with a token budget");
        }
        OpenAiChatRequestParameters.Builder parameters = OpenAiChatRequestParameters.builder();
        if (context.reasoningEffort() != null) {
            parameters.reasoningEffort(context.reasoningEffort().wireValue());
        }
        return parameters.build();
    }

    @Override
    public String providerId() {
        return PROVIDER + "-" + modelName;
    }

    /** OpenAI's function-calling is sent natively — see the shared pipeline's response mapping in {@link AbstractLangChain4jLlmClient}. */
    @Override
    public boolean supportsNativeTools() {
        return true;
    }

    /**
     * Whether this endpoint accepts a native {@code response_format: json_schema}. Derived per
     * endpoint — hosted OpenAI yes, a custom {@link Builder#baseUrl(String)} no unless opted in
     * via {@link Builder#structuredOutputSupport(boolean)} — see the {@link #structuredOutputSupport}
     * field and {@link io.ara.core.llm.LlmClient#supportsNativeStructuredOutput()}.
     */
    @Override
    public boolean supportsNativeStructuredOutput() {
        return structuredOutputSupport;
    }

    /**
     * The agent's output schema as an OpenAI {@code response_format: json_schema}. The schema
     * string travels verbatim via {@link dev.langchain4j.model.chat.request.json.JsonRawSchema},
     * so a draft-07 schema with {@code pattern}, bounds, {@code additionalProperties} etc. reaches
     * the provider exactly as the contract declared it, with no lossy round-trip through a typed
     * model. Called only when {@link #supportsNativeStructuredOutput()} is {@code true} and a
     * schema is present (see {@link AbstractLangChain4jLlmClient#applyResponseFormat}).
     */
    @Override
    protected ResponseFormat buildResponseFormat(LlmCallContext context) {
        String name = context.outputSchemaName() != null ? context.outputSchemaName() : "output";
        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder()
                        .name(name)
                        .rootElement(JsonRawSchema.from(context.outputJsonSchema()))
                        .build())
                .build();
    }

    /**
     * Images as image parts and text files inlined as text — always; PDFs as {@code file}
     * parts only when this client talks to an endpoint known to accept them.
     *
     * <h4>Why documents are conditional</h4>
     * <p>Media support is not a property of "OpenAI" but of the <em>endpoint</em>. This adapter
     * exists to be pointed at any OpenAI-compatible API (Azure, Groq, LM Studio, vLLM, a
     * corporate gateway), and while essentially all of them accept the {@code image_url} part,
     * many reject the {@code file} part that a PDF becomes — typically with an opaque
     * {@code "Unknown part type: file"} 400 from the proxy. Claiming document support there
     * would defeat the whole point of declaring capabilities: instead of a clear ARA failure
     * naming the type and the provider <em>before</em> the request goes out, the caller gets a
     * provider error they have to reverse-engineer.
     *
     * <p>So the default is derived from configuration rather than assumed: no {@link
     * Builder#baseUrl(String)} means hosted OpenAI, which does accept documents; a custom base
     * URL means an endpoint whose document support is unknown, and unknown is treated as
     * unsupported. A caller who knows better opts in with
     * {@link Builder#documentSupport(boolean)} — the same shape as
     * {@code OllamaLlmClient.nativeTools(boolean)}, and for the same reason: the adapter cannot
     * discover this, and guessing generously is what produces the confusing failure.
     *
     * <p>Declared by category rather than by listing MIME strings, so a type added to
     * {@code MediaTypes} in a category the endpoint already handles is picked up here instead
     * of silently staying unsupported.
     */
    @Override
    public Set<String> supportedMediaTypes() {
        return supportedMediaTypes;
    }

    @Override
    protected ChatResponse chat(ChatRequest request) {
        return chatModel.chat(request);
    }

    @Override
    protected void streamChat(ChatRequest request, StreamingChatResponseHandler handler) {
        getStreamingModel().chat(request, handler);
    }

    // Thread-safe lazy initialization using double-checked locking.
    private OpenAiStreamingChatModel getStreamingModel() {
        if (streamingModel == null) {
            synchronized (this) {
                if (streamingModel == null) {
                    OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder smBuilder =
                            OpenAiStreamingChatModel.builder()
                                    .apiKey(apiKey)
                                    .baseUrl(baseUrl)
                                    .modelName(modelName)
                                    .temperature(defaultTemperature)
                                    .topP(defaultTopP)
                                    .maxTokens(defaultMaxTokens)
                                    .returnThinking(true)
                                    .strictJsonSchema(strictJsonSchema)
                                    .timeout(timeout)
                                    .customHeaders(customHeaders)
                                    .logRequests(logRequests)
                                    .logResponses(logResponses);

                    if (needsCustomHttpClient()) {
                        // Same transport settings as the blocking model, via jdkClientBuilder():
                        // HTTP/1.1 to prevent gateway-side HTTP/2 SSE buffering and to drop the
                        // h2c upgrade a uvicorn/httptools vLLM rejects, plus the connect timeout
                        // that lets a failover pool give up on an unreachable endpoint fast.
                        smBuilder.httpClientBuilder(jdkClientBuilder());
                    }

                    streamingModel = smBuilder.build();
                }
            }
        }
        return streamingModel;
    }

    @Override
    protected LlmException mapException(Throwable ex) {
        String msg = errorMessage(ex);
        if (msg.contains("401") || msg.contains("invalid_api_key")) {
            return LlmException.authenticationError(PROVIDER, msg);
        }
        if (msg.contains("429") || msg.contains("rate_limit_exceeded")) {
            return LlmException.rateLimit(PROVIDER, msg);
        }
        if (msg.contains("context_length_exceeded")) {
            return LlmException.contextLengthExceeded(PROVIDER, modelName, msg);
        }

        return fallbackClassify(PROVIDER, msg, ex, timeout);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Creates a new builder for {@link OpenAiLlmClient}.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link OpenAiLlmClient}.
     *
     * <p>Shared configuration (API key, base URL, model, temperature, max tokens, timeout,
     * logging) lives in {@link AbstractLlmClientBuilder}; this class adds the knobs that are
     * OpenAI-specific. The only required field is {@link #apiKey(String)}.
     */
    public static final class Builder extends AbstractLlmClientBuilder<Builder> {
        private Double  topP;
        /** Nullable on purpose: null means "derive from baseUrl" — see supportedMediaTypes(). */
        private Boolean documentSupport;
        /** Nullable on purpose: null means "derive from baseUrl" — see supportsNativeStructuredOutput(). */
        private Boolean structuredOutputSupport;
        /** Nullable on purpose: null means langchain4j's default, i.e. {@code strict: false}. */
        private Boolean strictJsonSchema;
        private boolean forceHttp1 = false;
        private Duration connectTimeout;
        private Map<String, String> customHeaders = Map.of();

        /** Sets the model from the {@link Models} catalogue (preferred for hosted OpenAI). */
        public Builder model(Models model)        { this.modelName = model.id; return this; }

        /**
         * Nucleus sampling threshold. Unset by default (OpenAI applies its own default,
         * {@code 1.0}). OpenAI recommends altering either {@code temperature} or
         * {@code topP}, not both.
         */
        public Builder topP(double topP)          { this.topP = topP; return this; }

        /**
         * Extra HTTP headers applied to every request (chat and streaming) sent to the
         * OpenAI-compatible endpoint.
         *
         * <p>Useful for endpoints behind a gateway that keys quota or routing off a
         * header — e.g. opencode's {@code X-Session-ID}. Values should stay stable for
         * the duration of the conversation the client serves.
         */
        public Builder customHeaders(Map<String, String> headers) {
            this.customHeaders = headers != null ? headers : Map.of();
            return this;
        }

        /**
         * Declares whether this endpoint accepts PDFs as {@code file} content parts.
         *
         * <p>Leave it unset unless you have to: the default is hosted OpenAI ⇒ yes, custom
         * {@link #baseUrl(String)} ⇒ no, which is right for almost every deployment. Set it to
         * {@code true} for a proxy or gateway you know forwards {@code file} parts (Azure
         * OpenAI, say), and to {@code false} to refuse documents even on hosted OpenAI.
         *
         * <p>Getting it wrong in the generous direction is what this flag exists to prevent:
         * an endpoint that rejects {@code file} parts answers with an opaque
         * {@code "Unknown part type: file"} 400 instead of ARA naming the unsupported type
         * before the call. Wrong in the strict direction merely refuses a PDF that would have
         * worked, saying so clearly.
         */
        public Builder documentSupport(boolean v) { this.documentSupport = v; return this; }

        /**
         * Declares whether this endpoint accepts a native {@code response_format: json_schema}
         * (OpenAI structured output).
         *
         * <p>Leave it unset unless you have to: the default is hosted OpenAI ⇒ yes, custom
         * {@link #baseUrl(String)} ⇒ no, which is right for almost every deployment. Set it to
         * {@code true} for a gateway you know forwards {@code response_format} (Azure OpenAI,
         * say) so an agent with {@code nativeJsonSchema(true)} can use it, and to {@code false}
         * to force the prompt-based schema path even on hosted OpenAI.
         *
         * <p>Getting it wrong in the generous direction is what this flag prevents: an endpoint
         * that rejects {@code response_format} would otherwise answer with an opaque 400 (or
         * silently ignore the field) instead of ARA naming the unsupported capability before the
         * call. Wrong in the strict direction merely falls back to the schema-in-prompt path,
         * which works everywhere.
         */
        public Builder structuredOutputSupport(boolean v) { this.structuredOutputSupport = v; return this; }

        /**
         * Sends a native output schema with OpenAI's {@code strict: true} — the mode that
         * actually <em>guarantees</em> the answer matches the schema, by constraining decoding
         * rather than only instructing the model.
         *
         * <p>Off by default, and deliberately not derived from the endpoint: strict mode narrows
         * what a schema may contain (every property must appear in {@code required},
         * {@code additionalProperties} must be {@code false}, and a number of JSON Schema
         * keywords — {@code pattern}, {@code minimum}, {@code format}, … — are not supported).
         * A legal draft-07 schema that uses one of those is answered with a 400, so turning this
         * on is a statement about the schemas an agent declares, not about the provider.
         *
         * <p>Leave it off to keep the schema as strong guidance and let the contract's own
         * {@code JsonSchemaValidator} (plus {@code outputRepairAttempts}) catch the rare
         * violation; turn it on when the schemas are strict-compliant and the guarantee is worth
         * more than the flexibility. Applies to both the blocking and the streaming model.
         */
        public Builder strictJsonSchema(boolean v) { this.strictJsonSchema = v; return this; }

        /**
         * Forces HTTP/1.1 on both the blocking and the streaming model, preventing HTTP/2
         * SSE buffering (streaming) and the h2c upgrade handshake a uvicorn/{@code httptools}
         * vLLM rejects on every request (blocking included). Set this to {@code true} when the
         * endpoint is behind a proxy or gateway that buffers SSE frames, or a vLLM that logs
         * {@code "Unsupported upgrade request."} and returns an empty/failed completion. See the
         * {@code forceHttp1} field javadoc for the two failures in detail.
         */
        public Builder forceHttp1(boolean v) {
            this.forceHttp1 = v; return this;
        }

        /**
         * Bounds the TCP/TLS handshake separately from {@link #timeout(Duration)}, which
         * langchain4j applies to the whole call.
         *
         * <p>Leave it unset for the previous behaviour. Set it — a few seconds is usually right —
         * when this client is one candidate of a {@code FAILOVER} pool: without it an endpoint
         * that is simply unreachable is only abandoned after the full request timeout, so every
         * request pays that wait before the pool can try the next provider. With it, an
         * unreachable host is recognised in seconds, while a provider that is merely slow still
         * gets the full {@link #timeout(Duration)} to answer.
         *
         * <p>Reaches both the blocking and the streaming model, and composes with
         * {@link #forceHttp1(boolean)} on a single JDK HTTP client.
         *
         * @param v the connect timeout; must be positive
         */
        public Builder connectTimeout(Duration v) {
            if (v != null && (v.isZero() || v.isNegative()))
                throw new IllegalArgumentException("connectTimeout must be positive when set");
            this.connectTimeout = v; return this;
        }

        /**
         * Builds the {@link OpenAiLlmClient}.
         *
         * @throws IllegalStateException if {@code apiKey} is null
         */
        public OpenAiLlmClient build() {
            if (apiKey == null) throw new IllegalStateException("OpenAI API key is required");
            return new OpenAiLlmClient(this);
        }
    }
}