package io.ara.adapters.llm.anthropic;

import io.ara.adapters.llm.AbstractLangChain4jLlmClient;
import io.ara.adapters.llm.AbstractLlmClientBuilder;
import io.ara.adapters.llm.AbstractLlmClientBuilder.LlmSettings;
import io.ara.core.llm.*;
import io.ara.core.media.MediaTypes;
import io.ara.core.media.MediaTypes.MediaKind;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.Set;

/**
 * {@link LlmClient} adapter for the <a href="https://www.anthropic.com/">Anthropic</a> API,
 * backed by <a href="https://github.com/langchain4j/langchain4j">LangChain4j</a>.
 *
 * <p>Supports all current Claude 3 and Claude 4 model families. Create an instance via the
 * static {@link #builder()} and wire it into an {@link io.ara.core.agent.AgentConfig}
 * or {@code io.ara.runtime.AraRuntime}:
 *
 * <pre>{@code
 * LlmClient claude = AnthropicLlmClient.builder()
 *     .apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *     .model(AnthropicLlmClient.Models.CLAUDE_SONNET_4_6)
 *     .build();
 *
 * AraRuntime runtime = AraRuntime.builder()
 *     .llmClient("claude", claude)
 *     .build();
 * }</pre>
 *
 * <h2>Function calling</h2>
 * <p>When {@link LlmCallContext} carries resolved tools, they are converted to
 * LangChain4j {@link ToolSpecification} objects and forwarded to the model's native
 * function-calling API.
 *
 * <h2>Streaming</h2>
 * <p>Tokens are emitted via the shared pipeline's {@code stream} method over a server-sent
 * events connection to the Anthropic streaming endpoint.
 *
 * @see LlmClient
 * @see AnthropicLlmClient.Models
 */
public class AnthropicLlmClient extends AbstractLangChain4jLlmClient {

    private static final String PROVIDER = "Anthropic";

    private final AnthropicChatModel          chatModel;
    private final AnthropicStreamingChatModel streamingModel;
    private final String                      modelName;
    /**
     * Precomputed once: a pure function of the adapter's capabilities, which never change
     * after construction — rebuilding the set on every request only allocates needlessly.
     */
    private final Set<String> supportedMediaTypes;

    // ── Model catalogue ───────────────────────────────────────────────────────

    /**
     * Enumeration of Anthropic Claude models supported by this adapter.
     *
     * <p>Model IDs and context windows are sourced from the
     * <a href="https://platform.claude.com/docs/en/about-claude/models/overview">Anthropic model overview</a>
     * (last verified: 2026-04).
     */
    public enum Models {
        // Claude 4.x family
        CLAUDE_OPUS_4_8      ("claude-opus-4-8",            200_000),
        CLAUDE_OPUS_4_6      ("claude-opus-4-6",            200_000),
        CLAUDE_SONNET_4_6    ("claude-sonnet-4-6",          200_000),
        CLAUDE_HAIKU_4_5     ("claude-haiku-4-5-20251001",  200_000),
        CLAUDE_OPUS_4        ("claude-opus-4-20250514",     200_000),
        CLAUDE_SONNET_4      ("claude-sonnet-4-20250514",   200_000),
        // Claude 3.x family
        CLAUDE_3_7_SONNET    ("claude-3-7-sonnet-20250219", 200_000),
        CLAUDE_3_5_SONNET    ("claude-3-5-sonnet-20241022", 200_000),
        CLAUDE_3_5_HAIKU     ("claude-3-5-haiku-20241022",  200_000),
        CLAUDE_3_OPUS        ("claude-3-opus-20240229",     200_000),
        CLAUDE_3_HAIKU       ("claude-3-haiku-20240307",    200_000);

        /** Anthropic model identifier string. */
        public final String id;
        /** Maximum context window in tokens. */
        public final int contextWindow;

        Models(String id, int contextWindow) {
            this.id            = id;
            this.contextWindow = contextWindow;
        }
    }

    // ── Construction ──────────────────────────────────────────────────────────

    private AnthropicLlmClient(Builder builder) {
        LlmSettings s = builder.settings();
        this.modelName = s.modelName();
        this.supportedMediaTypes =
                MediaTypes.ofKinds(MediaKind.IMAGE, MediaKind.DOCUMENT, MediaKind.TEXT);
        this.chatModel = AnthropicChatModel.builder()
                .apiKey(s.apiKey())
                .baseUrl(s.baseUrl())
                .modelName(s.modelName())
                .temperature(s.temperature())
                .maxTokens(s.maxTokens())
                .timeout(s.timeout())
                .logRequests(s.logRequests())
                .logResponses(s.logResponses())
                .build();
        this.streamingModel = AnthropicStreamingChatModel.builder()
                .apiKey(s.apiKey())
                .baseUrl(s.baseUrl())
                .modelName(s.modelName())
                .temperature(s.temperature())
                .maxTokens(s.maxTokens())
                .timeout(s.timeout())
                .build();
    }

    // ── LlmClient ─────────────────────────────────────────────────────────────

    @Override
    public String providerId() {
        return "anthropic-" + modelName;
    }

    /** Anthropic's tool use is sent natively — see the shared pipeline's response mapping in {@link AbstractLangChain4jLlmClient}. */
    @Override
    public boolean supportsNativeTools() {
        return true;
    }

    /**
     * Images as image blocks, PDFs as document blocks, and text files inlined as text — the
     * whole accepted vocabulary. Declared by category rather than as a list of MIME strings,
     * so a type added to {@code MediaTypes} in a category Anthropic already handles is picked
     * up here instead of silently staying unsupported.
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
     * Streaming call into the shared {@code stream()} pipeline. Each token is emitted as an
     * individual {@code String} item; the publisher completes normally on {@code STOP} or
     * {@code END_TURN}, and exceptionally on any network or API error.
     */
    @Override
    protected void streamChat(ChatRequest request, StreamingChatResponseHandler handler) {
        streamingModel.chat(request, handler);
    }

    @Override
    protected LlmException mapException(Throwable ex) {
        String msg = errorMessage(ex);
        if (msg.contains("401") || msg.contains("authentication") || msg.contains("api_key")) {
            return LlmException.authenticationError(PROVIDER, msg);
        }
        if (msg.contains("429") || msg.contains("rate_limit") || msg.contains("rate limit")) {
            return LlmException.rateLimit(PROVIDER, msg);
        }
        if (msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("overloaded")) {
            return LlmException.serverError(PROVIDER, msg, 500);
        }
        if (msg.contains("context_length") || msg.contains("too long") || msg.contains("max_tokens")) {
            return LlmException.contextLengthExceeded(PROVIDER, modelName, 0, 0);
        }

        return fallbackClassify(PROVIDER, msg, ex);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Creates a new builder for {@link AnthropicLlmClient}.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link AnthropicLlmClient}.
     *
     * <p>Shared configuration (API key, base URL, model, temperature, max tokens, timeout,
     * logging) lives in {@link AbstractLlmClientBuilder}; this class adds the {@link Models}
     * catalogue and overrides the shared defaults max-tokens {@code 4096} (vs the base
     * {@code 2000}) and model {@link Models#CLAUDE_SONNET_4_6}. The only required field is
     * {@code apiKey}.
     */
    public static final class Builder extends AbstractLlmClientBuilder<Builder> {

        public Builder() {
            modelName = Models.CLAUDE_SONNET_4_6.id;
            maxTokens = 4096;
        }

        /** Sets the model from the {@link Models} catalogue (preferred). */
        public Builder model(Models model)         { this.modelName = model.id; return this; }

        /**
         * Builds the {@link AnthropicLlmClient}.
         *
         * @throws IllegalArgumentException if {@code apiKey} is null or blank
         */
        public AnthropicLlmClient build() {
            if (apiKey == null || apiKey.isBlank())
                throw new IllegalArgumentException("Anthropic API key is required");
            return new AnthropicLlmClient(this);
        }
    }
}
