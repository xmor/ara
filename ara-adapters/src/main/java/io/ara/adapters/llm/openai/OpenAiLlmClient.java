package io.ara.adapters.llm.openai;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Flow;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.ara.adapters.llm.CallParameterUtils;
import io.ara.adapters.llm.ProviderErrorMapper;
import io.ara.adapters.llm.TokenStreamPublisher;
import io.ara.adapters.llm.ToolConversionUtils;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.ToolCallEntry;
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
public class OpenAiLlmClient implements LlmClient {

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
    private final boolean documentSupport;
    /**
     * Forces HTTP/1.1 on the streaming model's underlying JDK {@code HttpClient}.
     *
     * <p>Some OpenAI-compatible gateways
     * use HTTP/2 multiplexing that buffers SSE data-frames server-side and delivers them all
     * at once at the end of the response instead of flushing each token as it arrives. The
     * fix is the same one LangChain4j already documents for vLLM:
     * {@code HttpClient.Version.HTTP_1_1} disables multiplexing so each {@code data:} frame
     * is flushed immediately — making streaming actually visible to the user. See
     * https://github.com/langchain4j/langchain4j/issues/3682.
     */
    private final boolean forceHttp1;

    private OpenAiLlmClient(Builder builder) {
        this.modelName = builder.modelName;
        this.apiKey = builder.apiKey;
        this.baseUrl = builder.baseUrl;
        this.defaultTemperature = builder.temperature;
        this.defaultTopP = builder.topP;
        this.defaultMaxTokens = builder.maxTokens;
        this.timeout = builder.timeout;
        this.logRequests = builder.logRequests;
        this.logResponses = builder.logResponses;
        // Unset by the caller ⇒ derive it: hosted OpenAI (no custom base URL) accepts `file`
        // parts, an arbitrary OpenAI-compatible endpoint usually does not. See
        // supportedMediaTypes().
        this.documentSupport = builder.documentSupport != null
                ? builder.documentSupport
                : (builder.baseUrl == null || builder.baseUrl.isBlank());

        this.forceHttp1 = builder.forceHttp1;

        this.chatModel = OpenAiChatModel.builder()
                .apiKey(builder.apiKey)
                .baseUrl(builder.baseUrl)
                .modelName(builder.modelName)
                .temperature(builder.temperature)
                .topP(builder.topP)
                .maxTokens(builder.maxTokens)
                .timeout(builder.timeout)
                .logRequests(builder.logRequests)
                .logResponses(builder.logResponses)
                .build();
    }

    @Override
    public String providerId() {
        return PROVIDER + "-" + modelName;
    }

    /** OpenAI's function-calling is sent natively — see {@link #complete} and {@link #toLlmCompletion}. */
    @Override
    public boolean supportsNativeTools() {
        return true;
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
        return documentSupport
                ? MediaTypes.ofKinds(MediaKind.IMAGE, MediaKind.DOCUMENT, MediaKind.TEXT)
                : MediaTypes.ofKinds(MediaKind.IMAGE, MediaKind.TEXT);
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        try {
            ChatRequest.Builder reqBuilder = ChatRequest.builder()
                    .messages(toLC4jMessages(messages, context));
            CallParameterUtils.applyTo(reqBuilder, context);

            if (context != null && context.hasResolvedTools()) {
                reqBuilder.toolSpecifications(ToolConversionUtils.toolSpecificationsFor(context));
            }

            ChatResponse response = chatModel.chat(reqBuilder.build());
            return toLlmCompletion(response);

        } catch (LlmException ex) {
            throw ex;
        } catch (Exception ex) {
            throw mapException(ex);
        }
    }

    @Override
    public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
        return TokenStreamPublisher.of(
                handler -> {
                    ChatRequest.Builder reqBuilder = ChatRequest.builder()
                            .messages(toLC4jMessages(messages, context));
                    CallParameterUtils.applyTo(reqBuilder, context);

                    if (context != null && context.hasResolvedTools()) {
                        reqBuilder.toolSpecifications(ToolConversionUtils.toolSpecificationsFor(context));
                    }

                    getStreamingModel().chat(reqBuilder.build(), handler);
                },
                this::mapException);
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
                                    .timeout(timeout)
                                    .logRequests(logRequests)
                                    .logResponses(logResponses);

                    if (forceHttp1) {
                        // Force HTTP/1.1 to prevent gateway-side HTTP/2 buffering from batching
                        // SSE frames — see the forceHttp1 field javadoc.
                        java.net.http.HttpClient.Builder jdkBuilder =
                                java.net.http.HttpClient.newBuilder()
                                        .version(java.net.http.HttpClient.Version.HTTP_1_1);
                        JdkHttpClientBuilder jdkHttpClientBuilder =
                                JdkHttpClient.builder().httpClientBuilder(jdkBuilder);
                        smBuilder.httpClientBuilder(jdkHttpClientBuilder);
                    }

                    streamingModel = smBuilder.build();
                }
            }
        }
        return streamingModel;
    }

    private List<ChatMessage> toLC4jMessages(List<LlmMessage> messages, LlmCallContext context) {
        // Delegates to ToolConversionUtils so "assistant_tool_call"/"assistant_tool_calls"/"tool"
        // roles are reconstructed as native AiMessage(toolExecutionRequests)/
        // ToolExecutionResultMessage instead of collapsing into a generic UserMessage — see its
        // javadoc for the previous bug this replaced — and so media is checked against this
        // client's declared types and flattened in one shared place.
        return ToolConversionUtils.toNativeAwareChatMessages(messages, context, this);
    }

    private LlmCompletion toLlmCompletion(ChatResponse response) {
        var ai = response.aiMessage();
        String text = (ai != null && ai.text() != null) ? ai.text() : "";

        String finishReason = response.finishReason() != null
                ? response.finishReason().toString().toLowerCase() : "stop";

        int inputTokens = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : 0;
        int outputTokens = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : 0;

        String toolCallJson = null;
        String toolCallId = null;
        List<ToolCallEntry> toolCalls = List.of();

        if (ai != null && ai.hasToolExecutionRequests()) {
            // Map ALL requests: OpenAI parallel function-calling is on by default when
            // tools are present, so multi-call completions are the norm, not the edge case.
            var requests = ai.toolExecutionRequests();
            toolCalls    = ToolConversionUtils.toToolCallEntries(requests);
            toolCallJson = ToolConversionUtils.toLegacyToolCallJson(requests.get(0));
            toolCallId   = requests.get(0).id();
            finishReason = "tool_calls";
        }

        return new LlmCompletion(text, inputTokens, outputTokens, finishReason,
                toolCallJson, toolCallId, toolCalls);
    }

    private LlmException mapException(Throwable ex) {
        // Escape JSON special characters without allocating a JSON serializer.
        String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        if (msg.contains("401") || msg.contains("invalid_api_key")) {
            return LlmException.authenticationError(PROVIDER, msg);
        }
        if (msg.contains("429") || msg.contains("rate_limit_exceeded")) {
            return LlmException.rateLimit(PROVIDER, msg);
        }
        if (msg.contains("context_length_exceeded")) {
            return LlmException.contextLengthExceeded(PROVIDER, modelName, 0, 0);
        }

        // Before falling through to a retryable network error: langchain4j classifies HTTP
        // failures onto its own retriable/non-retriable hierarchy, and reading that is both
        // more accurate than the substring checks above and immune to a provider rewording
        // its error bodies. Without it a malformed request (400) was reported as a network
        // error — retryable — so the strategy retried it and every fallback in a failover
        // pool was tried in turn, for a request that could not succeed on any of them.
        LlmException typed = ProviderErrorMapper.fromTypedException(PROVIDER, ex);
        if (typed != null) return typed;
        return LlmException.networkError(PROVIDER, msg, ex);
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
     * <p>The only required field is {@link #apiKey(String)}.  All other fields have sensible
     * defaults (temperature 0.7, 2000 max tokens).
     */
    public static final class Builder {
        private String   apiKey;
        private String   baseUrl;
        private String   modelName;
        private Double   temperature  = 0.7;
        private Double   topP;
        private Integer  maxTokens    = 2000;
        private Duration timeout      = Duration.ofSeconds(60);
        private boolean  logRequests  = false;
        private boolean  logResponses = false;
        /** Nullable on purpose: null means "derive from baseUrl" — see supportedMediaTypes(). */
        private Boolean  documentSupport;
        private boolean  forceHttp1   = false;

        /** Sets the OpenAI API key (required). */
        public Builder apiKey(String apiKey)       { this.apiKey = apiKey; return this; }

        /** Overrides the default API base URL (useful for Azure OpenAI, proxies, local LM Studio). */
        public Builder baseUrl(String baseUrl)     { this.baseUrl = baseUrl; return this; }

        /** Sets the model by string ID (use for non-catalogued or preview models). */
        public Builder modelName(String modelName) { this.modelName = modelName; return this; }

        /** Sampling temperature. Defaults to {@code 0.7}. */
        public Builder temperature(double t)       { this.temperature = t; return this; }

        /**
         * Nucleus sampling threshold. Unset by default (OpenAI applies its own default,
         * {@code 1.0}). OpenAI recommends altering either {@code temperature} or
         * {@code topP}, not both.
         */
        public Builder topP(double topP)           { this.topP = topP; return this; }

        /** Maximum output tokens. Defaults to {@code 2000}. */
        public Builder maxTokens(int maxTokens)    { this.maxTokens = maxTokens; return this; }

        /** HTTP request timeout. Defaults to {@code 60s}. */
        public Builder timeout(Duration timeout)   { this.timeout = timeout; return this; }

        /** Enables LangChain4j request logging to SLF4J. */
        public Builder logRequests(boolean v)      { this.logRequests = v; return this; }

        /** Enables LangChain4j response logging to SLF4J. */
        public Builder logResponses(boolean v)     { this.logResponses = v; return this; }

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
        public Builder documentSupport(boolean v)  { this.documentSupport = v; return this; }

        /**
         * Forces HTTP/1.1 on the streaming model to prevent gateway buffering of SSE frames.
         * Set this to {@code true} when the endpoint is behind a proxy or gateway that uses
         * HTTP/2 multiplexing.
         * Has no effect on the blocking {@link #complete} path, which uses the default {@link
         * OpenAiChatModel} (not affected by HTTP/2 buffering).
         */
        public Builder forceHttp1(boolean v) {
            this.forceHttp1 = v; return this;
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