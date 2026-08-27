package io.ara.adapters.llm;

import io.ara.adapters.llm.anthropic.AnthropicLlmClient;
import io.ara.adapters.llm.chatjimmy.ChatJimmyLlmClient;
import io.ara.adapters.llm.mistral.MistralLlmClient;
import io.ara.adapters.llm.ollama.OllamaLlmClient;
import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.llm.LlmClient;

/**
 * Factory that provides builder access for all ARA-supported LLM adapters.
 *
 * <p>Each method returns the adapter's fluent {@code LlmClient.Builder}
 * so that clients can be configured and built in a single expression and wired directly into
 * {@code io.ara.runtime.AraRuntime}:
 *
 * <pre>{@code
 * LlmClient gpt4o = AraLlmClientFactory.openAi()
 *     .apiKey(System.getenv("OPENAI_API_KEY"))
 *     .model(OpenAiLlmClient.Models.GPT_4O)
 *     .build();
 *
 * LlmClient claude = AraLlmClientFactory.anthropic()
 *     .apiKey(System.getenv("ANTHROPIC_API_KEY"))
 *     .model(AnthropicLlmClient.Models.CLAUDE_SONNET_4_6)
 *     .build();
 *
 * LlmClient llama = AraLlmClientFactory.ollama()
 *     .model(OllamaLlmClient.Models.LLAMA_3_2)
 *     .build();
 *
 * LlmClient mistral = AraLlmClientFactory.mistral()
 *     .apiKey(System.getenv("MISTRAL_API_KEY"))
 *     .model(MistralLlmClient.Models.MISTRAL_MEDIUM_LATEST)
 *     .build();
 *
 * LlmClient jimmy = AraLlmClientFactory.chatJimmy()
 *     .modelName("llama3.1-8B")
 *     .build();
 *
 * AraRuntime runtime = AraRuntime.builder()
 *     .llmClient("gpt-4o",  gpt4o)
 *     .llmClient("claude",  claude)
 *     .llmClient("local",   llama)
 *     .build();
 * }</pre>
 *
 * <p>For OpenAI-compatible endpoints (Azure OpenAI, Groq, LM Studio, Together AI, …)
 * use {@link #openAi()} and call {@code .baseUrl(String)} on the returned builder.
 *
 * @see OpenAiLlmClient
 * @see AnthropicLlmClient
 * @see OllamaLlmClient
 * @see MistralLlmClient
 * @see ChatJimmyLlmClient
 * @see LlmClient
 */
public final class AraLlmClientFactory {

    private AraLlmClientFactory() {}

    /**
     * Returns a builder for an OpenAI (or OpenAI-compatible) {@link LlmClient}.
     *
     * @return {@link OpenAiLlmClient.Builder}
     */
    public static OpenAiLlmClient.Builder openAi() {
        return OpenAiLlmClient.builder();
    }

    /**
     * Returns a builder for an Anthropic Claude {@link LlmClient}.
     *
     * @return {@link AnthropicLlmClient.Builder}
     * @see AnthropicLlmClient.Models
     */
    public static AnthropicLlmClient.Builder anthropic() {
        return AnthropicLlmClient.builder();
    }

    /**
     * Returns a builder for a local Ollama {@link LlmClient}.
     *
     * <p>Defaults to {@code http://localhost:11434} — override with
     * {@link OllamaLlmClient.Builder#baseUrl(String)} for remote instances.
     *
     * @return {@link OllamaLlmClient.Builder}
     * @see OllamaLlmClient.Models
     */
    public static OllamaLlmClient.Builder ollama() {
        return OllamaLlmClient.builder();
    }

    /**
     * Returns a builder for a Mistral AI {@link LlmClient} — the adapter to reach for when a
     * task attaches a PDF that must be read as a document rather than pre-extracted to text.
     *
     * @return {@link MistralLlmClient.Builder}
     * @see MistralLlmClient.Models
     */
    public static MistralLlmClient.Builder mistral() {
        return MistralLlmClient.builder();
    }

    /**
     * Returns a builder for a <a href="https://chatjimmy.ai/">chatjimmy.ai</a>
     * {@link LlmClient}. Unlike the other adapters this one is not LangChain4j-backed — see
     * {@link ChatJimmyLlmClient} for why and for what was deliberately left out.
     *
     * @return {@link ChatJimmyLlmClient.Builder}
     */
    public static ChatJimmyLlmClient.Builder chatJimmy() {
        return ChatJimmyLlmClient.builder();
    }
}
