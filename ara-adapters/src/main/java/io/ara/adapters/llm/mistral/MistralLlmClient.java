package io.ara.adapters.llm.mistral;

import java.time.Duration;
import java.util.Set;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.mistralai.MistralAiChatModel;
import dev.langchain4j.model.mistralai.MistralAiStreamingChatModel;
import io.ara.adapters.llm.AbstractLangChain4jLlmClient;
import io.ara.adapters.llm.AbstractLlmClientBuilder;
import io.ara.adapters.llm.AbstractLlmClientBuilder.LlmSettings;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmException;
import io.ara.core.media.MediaTypes;
import io.ara.core.media.MediaTypes.MediaKind;

/**
 * {@link LlmClient} adapter for the <a href="https://docs.mistral.ai/">Mistral AI</a> API,
 * backed by <a href="https://github.com/langchain4j/langchain4j">LangChain4j</a>.
 *
 * <p>Added for its native document handling: Mistral takes a PDF as a document — layout,
 * tables, stamps, scanned pages and all — rather than requiring the text to be extracted
 * upstream first. That is the case ADR-047 exists for, and it is why this adapter is the one
 * the multimodal work brought along.
 *
 * <pre>{@code
 * LlmClient mistral = MistralLlmClient.builder()
 *     .apiKey(System.getenv("MISTRAL_API_KEY"))
 *     .model(MistralLlmClient.Models.MISTRAL_MEDIUM_LATEST)
 *     .build();
 *
 * AraRuntime runtime = AraRuntime.builder()
 *     .llmClient("mistral", mistral)
 *     .mediaStore(MediaStore.inMemory())
 *     .build();
 * }</pre>
 *
 * <h2>Nothing provider-specific about the media path</h2>
 * <p>Messages go through the same {@code ToolConversionUtils} conversion as every other
 * adapter, which produces langchain4j {@code ImageContent}/{@code PdfFileContent} parts;
 * langchain4j's Mistral mapper turns those into Mistral's own document and image content on
 * the wire. So no code above the adapter — and nothing in this class — differs between
 * sending a PDF to Mistral and sending one to OpenAI.
 *
 * @see LlmClient
 */
public class MistralLlmClient extends AbstractLangChain4jLlmClient {

    private static final String PROVIDER = "mistral";

    private final MistralAiChatModel chatModel;
    // Lazily initialised, like the other adapters: an agent that never streams should not pay
    // for a second HTTP client and its connection pool.
    private volatile MistralAiStreamingChatModel streamingModel;
    private final String   modelName;
    private final String   apiKey;
    private final String   baseUrl;
    private final Double   defaultTemperature;
    private final Double   defaultTopP;
    private final Integer  defaultMaxTokens;
    private final Duration timeout;
    private final boolean  logRequests;
    private final boolean  logResponses;
    /**
     * Precomputed once: a pure function of the adapter's capabilities, which never change
     * after construction — rebuilding the set on every request only allocates needlessly.
     */
    private final Set<String> supportedMediaTypes;

    // ── Model catalogue ───────────────────────────────────────────────────────

    /**
     * Mistral models this adapter has been used against.
     *
     * <p>{@code documentCapable} records whether the model accepts a PDF as a document, which
     * is not a property of the API but of the model behind it: sending one to a text-only
     * model is a request the API accepts and the model cannot honour. It is informational —
     * {@link #supportedMediaTypes()} reports the adapter's capability, and the model is chosen
     * by whoever builds the client.
     *
     * <p>Model ids and context windows are from the
     * <a href="https://docs.mistral.ai/getting-started/models/models_overview/">Mistral model
     * overview</a> (last verified: 2026-08).
     */
    public enum Models {
        MISTRAL_LARGE_LATEST   ("mistral-large-latest",   128_000, true),
        MISTRAL_MEDIUM_LATEST  ("mistral-medium-latest",  128_000, true),
        MISTRAL_SMALL_LATEST   ("mistral-small-latest",   128_000, true),
        OPEN_MISTRAL_NEMO      ("open-mistral-nemo",      128_000, false),
        CODESTRAL_LATEST       ("codestral-latest",       256_000, false);

        /** Mistral model identifier string. */
        public final String id;
        /** Maximum context window in tokens. */
        public final int contextWindow;
        /** Whether this model can read a PDF as a native document. */
        public final boolean documentCapable;

        Models(String id, int contextWindow, boolean documentCapable) {
            this.id              = id;
            this.contextWindow   = contextWindow;
            this.documentCapable = documentCapable;
        }
    }

    // ── Construction ──────────────────────────────────────────────────────────

    private MistralLlmClient(Builder builder) {
        LlmSettings s = builder.settings();
        this.modelName          = s.modelName();
        this.apiKey             = s.apiKey();
        this.baseUrl            = s.baseUrl();
        this.defaultTemperature = s.temperature();
        this.defaultTopP        = builder.topP;
        this.defaultMaxTokens   = s.maxTokens();
        this.timeout            = s.timeout();
        this.logRequests        = s.logRequests();
        this.logResponses       = s.logResponses();
        this.supportedMediaTypes =
                MediaTypes.ofKinds(MediaKind.IMAGE, MediaKind.DOCUMENT, MediaKind.TEXT);

        this.chatModel = MistralAiChatModel.builder()
                .apiKey(s.apiKey())
                .baseUrl(s.baseUrl())
                .modelName(s.modelName())
                .temperature(s.temperature())
                .topP(builder.topP)
                .maxTokens(s.maxTokens())
                .timeout(s.timeout())
                .logRequests(s.logRequests())
                .logResponses(s.logResponses())
                .build();
    }

    @Override
    public String providerId() {
        return PROVIDER + "-" + modelName;
    }

    /** Mistral's function calling is sent natively — see the shared pipeline's response mapping in {@link AbstractLangChain4jLlmClient}. */
    @Override
    public boolean supportsNativeTools() {
        return true;
    }

    /**
     * Images, PDFs as native documents, and text files inlined as text — the whole accepted
     * vocabulary. Declared by category rather than as a list of MIME strings, so a type added
     * to {@code MediaTypes} in a category Mistral already handles is picked up here instead of
     * silently staying unsupported.
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

    // Thread-safe lazy initialisation using double-checked locking, same as the other adapters.
    private MistralAiStreamingChatModel getStreamingModel() {
        if (streamingModel == null) {
            synchronized (this) {
                if (streamingModel == null) {
                    streamingModel = MistralAiStreamingChatModel.builder()
                            .apiKey(apiKey)
                            .baseUrl(baseUrl)
                            .modelName(modelName)
                            .temperature(defaultTemperature)
                            .topP(defaultTopP)
                            .maxTokens(defaultMaxTokens)
                            .timeout(timeout)
                            .logRequests(logRequests)
                            .logResponses(logResponses)
                            .build();
                }
            }
        }
        return streamingModel;
    }

    @Override
    protected LlmException mapException(Throwable ex) {
        String msg = errorMessage(ex);
        if (msg.contains("401") || msg.contains("Unauthorized") || msg.contains("api_key")) {
            return LlmException.authenticationError(PROVIDER, msg);
        }
        if (msg.contains("429") || msg.contains("rate limit") || msg.contains("rate_limit")) {
            return LlmException.rateLimit(PROVIDER, msg);
        }
        if (msg.contains("500") || msg.contains("502") || msg.contains("503")) {
            return LlmException.serverError(PROVIDER, msg, 500);
        }
        if (msg.contains("too large") || msg.contains("context") && msg.contains("length")) {
            return LlmException.contextLengthExceeded(PROVIDER, modelName, 0, 0);
        }

        return fallbackClassify(PROVIDER, msg, ex);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Creates a new builder for {@link MistralLlmClient}.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link MistralLlmClient}.
     *
     * <p>Shared configuration (API key, base URL, model, temperature, max tokens, timeout,
     * logging) lives in {@link AbstractLlmClientBuilder}; this class adds the {@link Models}
     * catalogue and the {@link #topP(double)} knob, and defaults the model to
     * {@link Models#MISTRAL_MEDIUM_LATEST}. The only required field is {@code apiKey}.
     */
    public static final class Builder extends AbstractLlmClientBuilder<Builder> {
        private Double topP;

        public Builder() {
            modelName = Models.MISTRAL_MEDIUM_LATEST.id;
        }

        /** Sets the model from the {@link Models} catalogue (preferred). */
        public Builder model(Models model)         { this.modelName = model.id; return this; }

        /**
         * Nucleus sampling threshold. Unset by default, so Mistral applies its own — altering
         * either {@code temperature} or {@code topP} is recommended, not both.
         */
        public Builder topP(double topP)           { this.topP = topP; return this; }

        /**
         * Builds the {@link MistralLlmClient}.
         *
         * @throws IllegalStateException if {@code apiKey} is null
         */
        public MistralLlmClient build() {
            if (apiKey == null) throw new IllegalStateException("Mistral API key is required");
            return new MistralLlmClient(this);
        }
    }
}
