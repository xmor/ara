package io.ara.adapters.llm;

import java.util.List;
import java.util.concurrent.Flow;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.ara.core.llm.*;

/**
 * Shared request → response pipeline for the four LangChain4j-backed LLM adapters.
 *
 * <p>{@code OpenAiLlmClient}, {@code AnthropicLlmClient}, {@code OllamaLlmClient} and
 * {@code MistralLlmClient} all implement {@link LlmClient} by building a LangChain4j
 * {@code ChatRequest}, calling a provider model, mapping the response to
 * {@link LlmCompletion}, streaming tokens via {@link TokenStreamPublisher} and classifying
 * failures with {@link ProviderErrorMapper}. The shape of that pipeline is
 * byte-for-byte identical across the four adapters in the blocks that matter:
 * {@code complete()}, {@code stream()}, {@code toLlmCompletion()},
 * {@code toLC4jMessages()}, tool application and the {@code try/catch} tail of
 * {@code mapException}.
 *
 * <p>Two properties that look provider-specific are handled uniformly here: tools are
 * forwarded only when the client declares {@link LlmClient#supportsNativeTools()}, and
 * token usage / finish reason are read from the direct accessor with a fallback to
 * {@code response.metadata()} — Ollama's LC4j mapper populates the metadata rather than the
 * direct accessor. What varies is narrow and honest:
 * <ul>
 *   <li>which LC4j model instance handles the call (a different class per provider),
 *       so the model fields and construction logic live in the concrete adapter</li>
 *   <li>how each provider classifies its own failures — the substring checks in
 *       {@code mapException} are genuinely provider-specific</li>
 * </ul>
 *
 * <h2>What this class owns, and what stays in subclasses</h2>
 * <p><b>Inherited (nothing provider-specific about them — do not override):</b>
 * {@code complete()}, {@code stream()}, {@code toLlmCompletion()},
 * {@code toLC4jMessages()}, {@code newChatRequest()}, tool application, token usage and
 * finish-reason extraction, {@code errorMessage()}, {@code fallbackClassify()}.
 *
 * <p><b>Abstract (each adapter implements):</b>
 * {@link #chat(ChatRequest)}, {@link #streamChat(ChatRequest, StreamingChatResponseHandler)},
 * {@link #mapException(Throwable)} — plus the interface methods
 * {@code providerId()}, {@code supportsNativeTools()}, {@code supportedMediaTypes()}.
 *
 * <h2>Discarded alternatives</h2>
 * <p><b>Composition via static helpers.</b> One option was to leave the adapters intact and
 * extract a {@code ChatRequest} builder helper plus a {@code toLlmCompletion} static
 * method, passing the tool-policy decision as a boolean flag. That pushes what are really
 * two pipeline facts — "tools are forwarded only when the client declares support" and
 * "token usage may live on the metadata" — into parameters at every call site. Both belong
 * inside the pipeline instead: the first reads the client's own {@code supportsNativeTools()},
 * the second is a fallback that reads whatever the response actually carries. A boolean
 * flag per call site would also have had to serve both the blocking and the streaming
 * path, doubling the places that could disagree.
 *
 * <p><b>Full base class with model construction.</b> Putting the LC4j model builders
 * inside this base would require generic type parameters or a factory callback, because
 * {@code OpenAiChatModel.builder()}, {@code AnthropicStreamingChatModel.builder()} etc.
 * are distinct types. That indirection hides provider-specific configuration (the
 * HTTP/1.1 workaround in {@code OpenAiLlmClient}, lazy streaming init, topP per-provider
 * defaults) in a place where the reader has to jump to understand it, and the current
 * shape — concrete fields and constructors living in the adapter they describe — is both
 * simpler and honest about the fact that construction is genuinely different per provider.
 *
 * <p><b>Non-abstract {@code mapException} with a default.</b> That would mean a provider
 * that forgets to implement it silently returns a retryable network error for
 * everything, including malformed requests. An abstract {@code mapException} compiles to
 * a clear message: every provider must classify its own failures.
 *
 * @see LlmClient
 * @see AbstractLlmClientBuilder
 */
public abstract class AbstractLangChain4jLlmClient implements LlmClient {

    // ── Pipeline template ──────────────────────────────────────────────────

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context)
            throws LlmException {
        try {
            return toLlmCompletion(chat(newChatRequest(messages, context).build()));
        } catch (LlmException ex) {
            throw ex;
        } catch (Exception ex) {
            throw mapException(ex);
        }
    }

    @Override
    public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
        return TokenStreamPublisher.of(
                handler -> streamChat(newChatRequest(messages, context).build(), handler),
                this::mapException);
    }

    /**
     * Builds the LC4j request: messages, call-time parameters (temperature, max tokens),
     * then the call's resolved tools via {@link #applyTools}.
     *
     * <p>Order matters: tools are applied last so that any call-parameter adjustments
     * made by {@link CallParameterUtils} land first.
     */
    protected final ChatRequest.Builder newChatRequest(
            List<LlmMessage> messages, LlmCallContext context) {
        ChatRequest.Builder reqBuilder = ChatRequest.builder()
                .messages(toLC4jMessages(messages, context));
        CallParameterUtils.applyTo(reqBuilder, context);
        applyTools(reqBuilder, context);
        return reqBuilder;
    }

    /**
     * Converts ARA messages to LC4j chat messages.
     *
     * <p>Delegates to {@link ToolConversionUtils} so native tool-call/tool-result turns are
     * reconstructed rather than collapsed into a generic {@code UserMessage} — a session
     * using ROUND_ROBIN/FAILOVER can hand this adapter a history whose earlier turns were
     * answered by a different provider, and collapsing would lose the structure. Media is
     * checked against this client's declared types and flattened in one shared place.
     */
    protected final List<ChatMessage> toLC4jMessages(
            List<LlmMessage> messages, LlmCallContext context) {
        return ToolConversionUtils.toNativeAwareChatMessages(messages, context, this);
    }

    /**
     * Resolves the call's tools on the request.
     *
     * <p>Tools are forwarded only when the client declares {@link LlmClient#supportsNativeTools()}
     * — for any adapter, sending a tool specification to a client that says it cannot use
     * tools natively is a bug. That gate is what makes Ollama work: tool support is a
     * property of the <em>model</em>, not of Ollama, so Ollama exposes it as an opt-in flag
     * that feeds {@code supportsNativeTools()}. Forwarding unconditionally would cause the
     * strategy to drop the text-based scaffolding those models rely on, so the agent would
     * stop calling tools altogether. See {@code OllamaLlmClient.Builder#nativeTools(boolean)}.
     * The gate is not redundant with {@code hasResolvedTools()}: {@code ReactStrategy}
     * attaches resolved tools to the context unconditionally, for every client, so keying
     * off the context alone would send tool specifications to a model that cannot use them
     * the moment any agent has tools registered.
     */
    private void applyTools(ChatRequest.Builder reqBuilder, LlmCallContext context) {
        if (context != null && context.hasResolvedTools() && supportsNativeTools()) {
            reqBuilder.toolSpecifications(
                    ToolConversionUtils.toolSpecificationsFor(context));
        }
    }

    /** The blocking model call (provider-specific ChatModel instance). */
    protected abstract ChatResponse chat(ChatRequest request);

    /** The streaming model call; reports tokens into {@code handler}. */
    protected abstract void streamChat(
            ChatRequest request, StreamingChatResponseHandler handler);

    // ── Response mapping ───────────────────────────────────────────────────

    /**
     * Maps an LC4j {@link ChatResponse} to a {@link LlmCompletion}.
     *
     * <p>Tool-call blocks are extracted universally: all four providers emit parallel
     * tool calls whenever tools are present, so every request in the block is mapped
     * (not just the first). A model that does not send a call id alongside its tool-name
     * identifier (Ollama) leaves {@link ToolCallEntry#toolCallId()} null — that field is
     * documented as nullable for that reason.
     */
    private LlmCompletion toLlmCompletion(ChatResponse response) {
        var ai = response.aiMessage();
        String text = (ai != null && ai.text() != null) ? ai.text() : "";
        FinishReason fr = finishReasonOf(response);
        String finishReason = fr != null ? fr.toString().toLowerCase() : "stop";
        TokenUsage tu = tokenUsageOf(response);
        int inputTokens = tu != null ? tu.inputTokenCount() : 0;
        int outputTokens = tu != null ? tu.outputTokenCount() : 0;

        String toolCallJson = null;
        String toolCallId = null;
        List<ToolCallEntry> toolCalls = List.of();

        if (ai != null && ai.hasToolExecutionRequests()) {
            var requests = ai.toolExecutionRequests();
            toolCalls    = ToolConversionUtils.toToolCallEntries(requests);
            toolCallJson = ToolConversionUtils.toLegacyToolCallJson(requests.get(0));
            toolCallId   = requests.get(0).id();
            finishReason = "tool_calls";
        }

        return new LlmCompletion(text, inputTokens, outputTokens, finishReason,
                toolCallJson, toolCallId, toolCalls);
    }

    /**
     * Extracts the finish reason from a chat response.
     *
     * <p>Reads the direct accessor, falling back to {@code response.metadata()} when it is
     * null — Ollama's LC4j mapper populates finish reason (and token usage) on the metadata
     * rather than the direct accessor. The fallback is null-guarded in both places and
     * behavior-neutral for the adapters whose direct accessor is populated: they never read
     * the metadata, whose presence is not guaranteed for every provider's response.
     */
    private FinishReason finishReasonOf(ChatResponse response) {
        FinishReason fr = response.finishReason();
        if (fr == null && response.metadata() != null) {
            fr = response.metadata().finishReason();
        }
        return fr;
    }

    /**
     * Extracts token usage from a chat response — same shape, and the same metadata
     * fallback, as {@link #finishReasonOf}.
     */
    private TokenUsage tokenUsageOf(ChatResponse response) {
        TokenUsage tu = response.tokenUsage();
        if (tu == null && response.metadata() != null) {
            tu = response.metadata().tokenUsage();
        }
        return tu;
    }

    // ── Error classification ───────────────────────────────────────────────

    /**
     * Reads the message from a provider failure — the shared head of every
     * {@link #mapException}: use the exception's own message, falling back to its simple
     * name when the message is null.
     *
     * <p>All four adapters start their classification with this exact expression, so the
     * head lives here once rather than in four copies. The fallback is not a formality:
     * some transport failures deliberately carry no message, and substring classification
     * against a {@code null} would crash instead of falling through to the correct error.
     */
    protected final String errorMessage(Throwable ex) {
        return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    }

    /**
     * Classifies a provider failure. Subclasses check provider-specific substrings
     * first, then fall through to {@link #fallbackClassify}.
     */
    protected abstract LlmException mapException(Throwable ex);

    /**
     * Final fallback classification: reads the LC4j typed exception hierarchy first,
     * and reports a retryable network error otherwise.
     *
     * <p>Why a shared tail: langchain4j already maps HTTP failures to its retriable /
     * non-retriable hierarchy (verified against 1.17+), and reading that is both more
     * accurate than substring match and immune to a provider rewording its error bodies.
     * Without it a malformed request (400) was reported as a network error — retryable —
     * so the strategy retried it and every fallback in a failover pool was tried in turn,
     * for a request that could not succeed on any of them.
     */
    protected final LlmException fallbackClassify(
            String provider, String msg, Throwable ex) {
        LlmException typed = ProviderErrorMapper.fromTypedException(provider, ex);
        if (typed != null) return typed;
        return LlmException.networkError(provider, msg, ex);
    }
}