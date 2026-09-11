package io.ara.adapters.llm.ollama;

import io.ara.adapters.llm.AbstractLangChain4jLlmClient;
import io.ara.core.llm.*;
import io.ara.core.media.MediaTypes;
import io.ara.core.media.MediaTypes.MediaKind;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;

import java.time.Duration;
import java.util.*;

/**
 * {@link LlmClient} adapter for <a href="https://ollama.com/">Ollama</a>,
 * backed by <a href="https://github.com/langchain4j/langchain4j">LangChain4j</a>.
 *
 * <p>Ollama runs open-weight models locally (or on a self-hosted server).
 * No API key is needed — only a running Ollama instance and the desired model pulled via
 * {@code ollama pull <model>}.
 *
 * <pre>{@code
 * LlmClient llama = OllamaLlmClient.builder()
 *     .baseUrl("http://localhost:11434")
 *     .model(OllamaLlmClient.Models.LLAMA_3_2)
 *     .build();
 *
 * AraRuntime runtime = AraRuntime.builder()
 *     .llmClient("llama3", llama)
 *     .build();
 * }</pre>
 *
 * <h2>Versioned model tags</h2>
 * <p>Use {@link Builder#modelName(String)} for versioned tags not in the {@link Models} enum:
 * <pre>{@code
 * .modelName("llama3.2:70b")
 * .modelName("qwen2.5-coder:32b")
 * }</pre>
 *
 * <h2>Function calling</h2>
 * <p>Off by default: tools in {@link LlmCallContext} are ignored and the strategy's
 * prompt-based routing applies. Whether a model can use tools natively depends on the model
 * rather than on Ollama — {@code llama3.1} and later, {@code qwen2.5} and {@code mistral-nemo}
 * can, {@code llama3}, {@code gemma} and {@code phi} cannot — and this client has no reliable
 * way to tell which one it is talking to, so enabling it is left to the caller:
 * <pre>{@code
 * LlmClient llama = OllamaLlmClient.builder()
 *     .model(OllamaLlmClient.Models.LLAMA_3_1)
 *     .nativeTools(true)
 *     .build();
 * }</pre>
 * See {@link Builder#nativeTools(boolean)} for what goes wrong if it is enabled for a model
 * that does not support tools.
 *
 * @see LlmClient
 * @see OllamaLlmClient.Models
 */
public class OllamaLlmClient extends AbstractLangChain4jLlmClient {

    private static final String PROVIDER = "Ollama";

    private final OllamaChatModel          chatModel;
    private final OllamaStreamingChatModel streamingModel;
    private final String                   modelName;
    private final boolean                  nativeTools;
    /**
     * Precomputed once: a pure function of the adapter's capabilities, which never change
     * after construction — rebuilding the set on every request only allocates needlessly.
     */
    private final Set<String> supportedMediaTypes;

    // ── Model catalogue ───────────────────────────────────────────────────────

    /**
     * Base model families available via Ollama.
     *
     * <p>These are the canonical base names used with {@code ollama pull}. For specific
     * size variants (e.g. {@code llama3.2:70b}) use {@link Builder#modelName(String)}.
     * Context windows are sourced from the
     * <a href="https://ollama.com/library">Ollama library</a> (last verified: 2026-04).
     */
    public enum Models {
        // Llama 3.x (Meta)
        LLAMA_3_2          ("llama3.2",      128_000),
        LLAMA_3_1          ("llama3.1",      128_000),
        LLAMA_3            ("llama3",          8_192),
        // Mistral / Mixtral
        MISTRAL            ("mistral",        32_768),
        MIXTRAL            ("mixtral",        32_768),
        MISTRAL_NEMO       ("mistral-nemo",  128_000),
        // Qwen 2.5 (Alibaba)
        QWEN_2_5           ("qwen2.5",       128_000),
        QWEN_2_5_CODER     ("qwen2.5-coder", 128_000),
        // Gemma (Google)
        GEMMA_3            ("gemma3",          8_192),
        GEMMA_2            ("gemma2",          8_192),
        // Phi (Microsoft)
        PHI_4              ("phi4",           16_384),
        PHI_3_5            ("phi3.5",          4_096),
        // DeepSeek
        DEEPSEEK_R1        ("deepseek-r1",   128_000),
        DEEPSEEK_CODER_V2  ("deepseek-coder-v2", 128_000),
        // Code-focused
        CODELLAMA          ("codellama",      16_384);

        /** Ollama model identifier (base name, without size tag). */
        public final String id;
        /** Maximum context window in tokens. */
        public final int contextWindow;

        Models(String id, int contextWindow) {
            this.id            = id;
            this.contextWindow = contextWindow;
        }
    }

    // ── Construction ──────────────────────────────────────────────────────────

    private OllamaLlmClient(Builder builder) {
        this.modelName     = builder.modelName;
        this.nativeTools   = builder.nativeTools;
        this.supportedMediaTypes = MediaTypes.ofKinds(MediaKind.IMAGE, MediaKind.TEXT);
        this.chatModel     = OllamaChatModel.builder()
                .baseUrl(builder.baseUrl)
                .modelName(builder.modelName)
                .temperature(builder.temperature)
                .timeout(builder.timeout)
                .logRequests(builder.logRequests)
                .logResponses(builder.logResponses)
                .build();
        this.streamingModel = OllamaStreamingChatModel.builder()
                .baseUrl(builder.baseUrl)
                .modelName(builder.modelName)
                .temperature(builder.temperature)
                .timeout(builder.timeout)
                .build();
    }

    // ── LlmClient ─────────────────────────────────────────────────────────────

    @Override
    public String providerId() {
        return "ollama-" + modelName;
    }

    /** Reflects {@link Builder#nativeTools(boolean)} — see its javadoc for why this is opt-in. */
    @Override
    public boolean supportsNativeTools() {
        return nativeTools;
    }

    /**
     * Images and text files, but <strong>not</strong> PDFs: Ollama's chat API has no document
     * part, so there is nothing for a PDF to become. It is declared unsupported rather than
     * silently converted or dropped, which makes a PDF sent here a non-retryable failure
     * naming the provider — the alternative would be an answer about a document the model
     * never received.
     */
    @Override
    public Set<String> supportedMediaTypes() {
        return supportedMediaTypes;
    }

    @Override
    protected ChatResponse chat(ChatRequest request) {
        return chatModel.chat(request);
    }

    /**
     * Streaming call into the shared {@code stream()} pipeline. Each token is emitted
     * individually; the publisher completes when Ollama sends a {@code done} signal, or
     * exceptionally on connection errors.
     */
    @Override
    protected void streamChat(ChatRequest request, StreamingChatResponseHandler handler) {
        streamingModel.chat(request, handler);
    }

    @Override
    protected LlmException mapException(Throwable ex) {
        String msg = errorMessage(ex);
        if (msg.contains("Connection refused") || msg.contains("connect")) {
            return LlmException.networkError(PROVIDER,
                    "Cannot reach Ollama at the configured base URL. Is Ollama running?", ex);
        }
        if (msg.contains("404") || msg.contains("model not found") || msg.contains("pull model")) {
            return LlmException.modelNotFound(PROVIDER, modelName);
        }
        return fallbackClassify(PROVIDER, msg, ex);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Creates a new builder for {@link OllamaLlmClient}.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link OllamaLlmClient}.
     *
     * <p>Defaults: base URL {@code http://localhost:11434}, model {@link Models#LLAMA_3_2},
     * timeout 5 minutes (Ollama can be slow on first run).
     */
    public static final class Builder {
        private String   baseUrl      = "http://localhost:11434";
        private String   modelName    = Models.LLAMA_3_2.id;
        private Double   temperature;
        private Duration timeout      = Duration.ofMinutes(5);
        private boolean  logRequests  = false;
        private boolean  logResponses = false;
        private boolean  nativeTools  = false;

        /** Sets the Ollama base URL. Defaults to {@code http://localhost:11434}. */
        public Builder baseUrl(String baseUrl)     { this.baseUrl = baseUrl; return this; }

        /** Sets the model by string ID — use for versioned tags (e.g. {@code "llama3.2:70b"}). */
        public Builder modelName(String modelName) { this.modelName = modelName; return this; }

        /** Sets the model from the {@link Models} catalogue (preferred for base model names). */
        public Builder model(Models model)         { this.modelName = model.id; return this; }

        /** Sampling temperature. Defaults to the model's built-in value when not set. */
        public Builder temperature(double t)       { this.temperature = t; return this; }

        /** HTTP request timeout. Defaults to {@code 5 minutes}. */
        public Builder timeout(Duration timeout)   { this.timeout = timeout; return this; }

        /** Enables LangChain4j request logging to SLF4J. */
        public Builder logRequests(boolean v)      { this.logRequests = v; return this; }

        /** Enables LangChain4j response logging to SLF4J. */
        public Builder logResponses(boolean v)     { this.logResponses = v; return this; }

        /**
         * Sends tools to the model natively instead of leaving them to the strategy's
         * prompt-based routing. Off by default.
         *
         * <p>Opt-in rather than automatic because tool support is a property of the model,
         * not of Ollama, and this client cannot infer it: {@link #modelName(String)} accepts
         * any tag the server happens to host, so there is no name to match on reliably. Turn
         * it on for a model that does support tools — {@code llama3.1} and later,
         * {@code qwen2.5}, {@code mistral-nemo}, {@code mistral} — and leave it off for one
         * that does not, such as {@code llama3}, {@code gemma2}/{@code gemma3} or
         * {@code phi3.5}: enabling it there sends tool specifications the model ignores while
         * {@link LlmClient#supportsNativeTools()} tells the strategy to drop the text
         * scaffolding those models actually rely on, so the agent would stop calling tools
         * altogether.
         */
        public Builder nativeTools(boolean v)      { this.nativeTools = v; return this; }

        /**
         * Builds the {@link OllamaLlmClient}.
         *
         * @throws IllegalArgumentException if {@code baseUrl} or {@code modelName} is blank
         */
        public OllamaLlmClient build() {
            if (baseUrl == null || baseUrl.isBlank())
                throw new IllegalArgumentException("Ollama base URL is required");
            if (modelName == null || modelName.isBlank())
                throw new IllegalArgumentException("Model name is required");
            return new OllamaLlmClient(this);
        }
    }
}
