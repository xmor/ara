package io.ara.adapters.llm;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Flow;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.ara.core.llm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(AbstractLangChain4jLlmClient.class);

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
        // Hand the terminal ChatResponse to a caller-provided sink when the context asks
        // for one (see LlmCallContext.withCompletionSink): a streaming response that is
        // all tool calls carries no text tokens, so the sink is the only way the caller
        // can see what the stream actually produced without re-issuing the call in
        // blocking mode. Adapters not backed by this base class ignore the sink.
        return TokenStreamPublisher.of(
                handler -> streamChat(newChatRequest(messages, context).build(), handler),
                this::mapException,
                context != null && context.hasCompletionSink()
                        ? response -> context.completionSink().accept(toLlmCompletion(response))
                        : null);
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
        applyResponseFormat(reqBuilder, context);
        applyTools(reqBuilder, context);
        return context != null && context.hasReasoningOptions()
                ? withReasoningParameters(reqBuilder, context)
                : reqBuilder;
    }

    /**
     * Puts the agent's output schema on the request as a native {@code response_format:
     * json_schema}, when the agent asked for the native path and declared a schema.
     *
     * <p>Reached only when {@link LlmCallContext#nativeJsonSchema()} is {@code true} <em>and</em>
     * a schema is present: with the default {@code nativeJsonSchema(false)} the schema travels
     * in the system prompt instead (ARA's {@code OutputFormatEnforcer}), and this leaves the
     * request untouched — byte-for-byte what it was before the native path existed.
     *
     * <p>When the agent does ask for the native path but this client cannot honour it
     * ({@link LlmClient#supportsNativeStructuredOutput()} is {@code false} — e.g. a provider
     * with no structured-output API, or an OpenAI-compatible endpoint not known to accept
     * {@code response_format}), the call is rejected with a non-retryable {@link LlmException}
     * naming the provider, rather than sending a request that silently ignores the schema and
     * answers in prose. This mirrors the hard-failure contract of {@link
     * LlmClient#supportedMediaTypes()}: a capability the client lacks is an error before the
     * request goes out, never a quiet downgrade.
     */
    private void applyResponseFormat(ChatRequest.Builder reqBuilder, LlmCallContext context) {
        if (context == null || !context.nativeJsonSchema() || !context.hasOutputSchema()) {
            return;
        }
        if (!supportsNativeStructuredOutput()) {
            throw LlmException.invalidRequest(providerId(),
                    "Agent requested native structured output (nativeJsonSchema=true with an output "
                            + "schema) but '" + providerId() + "' does not send a provider-native "
                            + "response_format for this endpoint. Set nativeJsonSchema(false) to have the "
                            + "schema appended to the system prompt, or point the client at an endpoint that "
                            + "accepts response_format and declare it (e.g. OpenAiLlmClient."
                            + "structuredOutputSupport(true)).");
        }
        ResponseFormat responseFormat = buildResponseFormat(context);
        if (responseFormat != null) {
            reqBuilder.responseFormat(responseFormat);
        }
    }

    /**
     * The provider-native {@code response_format} carrying {@code context}'s output schema, or
     * {@code null} to send none. Called only when the agent asked for the native path, a schema
     * is present and {@link #supportsNativeStructuredOutput()} returned {@code true}, so an
     * implementation may assume {@link LlmCallContext#outputJsonSchema()} is non-null.
     *
     * <p>The default returns {@code null}: a client that declares the capability overrides this
     * to build the format from the raw schema (see {@code OpenAiLlmClient}). Kept separate from
     * {@link #supportsNativeStructuredOutput()} so the capability can be reported without this
     * class forcing a particular langchain4j construction on every adapter.
     */
    protected ResponseFormat buildResponseFormat(LlmCallContext context) {
        return null;
    }

    /**
     * Adds the provider-specific parameters that carry the agent's reasoning options.
     *
     * <p>They travel with the call because a client is shared by every agent that uses a transport
     * while the options belong to one agent. The provider's own parameter type goes <em>under</em> the
     * generic request (temperature, tools, stop sequences), so everything {@link #newChatRequest}
     * already set survives. Not reached at all when the agent set no option, which is what keeps a
     * request byte-for-byte what it was before these options existed.
     */
    private ChatRequest.Builder withReasoningParameters(ChatRequest.Builder generic, LlmCallContext context) {
        ChatRequestParameters providerParameters = reasoningParameters(context);
        ChatRequest built = generic.build();
        return ChatRequest.builder()
                .messages(built.messages())
                .parameters(providerParameters.overrideWith(built.parameters()));
    }

    /**
     * The provider's own request parameters for the reasoning options in {@code context}, or an
     * {@link LlmException#invalidRequest invalid-request} error naming the option this provider cannot
     * apply. Called only when at least one option is set. The default applies none: a provider that
     * has a reasoning feature overrides it.
     *
     * <p>An option the provider cannot honour is rejected rather than ignored. An ignored option gives
     * an agent that runs with a different behaviour from the one it declared and no error to say so;
     * and options do not translate between providers (there is no "high = N tokens"), so the nearest
     * thing would be a guess.
     */
    protected ChatRequestParameters reasoningParameters(LlmCallContext context) {
        throw unsupportedReasoningOption(context.reasoningEffort() != null ? "reasoningEffort"
                : context.thinkingBudgetTokens() != null ? "thinkingBudgetTokens" : "returnReasoning",
                "this provider has no reasoning feature");
    }

    /** An invalid-request error for a reasoning option this provider cannot apply. Non-retryable. */
    protected final LlmException unsupportedReasoningOption(String option, String reason) {
        return LlmException.invalidRequest(providerId(),
                "Reasoning option '" + option + "' cannot be applied by " + providerId() + ": " + reason);
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

        if (text.isBlank() && toolCalls.isEmpty()) {
            // No transport/HTTP error, no tool call, no text — the provider answered with
            // nothing. Log the raw response now: this is the one place that still has it,
            // and a warn-only line (as opposed to throwing silently) is what lets a future
            // incident be diagnosed from what the provider actually sent instead of guessed
            // at from a downstream timeout or an empty chat bubble.
            log.warn("Provider '{}' returned an empty completion "
                    + "(finishReason={}, tokens in={}/out={}): {}",
                    providerId(), finishReason, inputTokens, outputTokens, response);
            throw LlmException.emptyResponse(providerId(),
                    "Empty completion from '" + providerId() + "' (finishReason=" + finishReason + ")"
                            + emptyCompletionCause(outputTokens));
        }

        // The reasoning the provider returned in its own field, when it was asked to and did. Read
        // whenever it is present: whether a run records it is the agent's decision, not the client's.
        String reasoning = ai != null ? ai.thinking() : null;
        return new LlmCompletion(text, inputTokens, outputTokens, finishReason,
                toolCallJson, toolCallId, toolCalls, false, reasoning);
    }

    /**
     * What an empty completion most likely means, told from the one number that separates the two cases.
     *
     * <p>A provider that generated <em>no</em> tokens simply answered with nothing. One that generated
     * tokens and still returned no text and no tool call <em>had something to say and lost it</em>: a
     * model that writes a tool call in a format its server cannot parse (LM Studio logs
     * {@code Failed to generate a tool call ... this tool call will be omitted}) produces exactly this
     * — a few tokens, {@code finishReason=stop}, nothing in the response. Saying so tells the operator it
     * is the model/server pair mangling a tool call, not an empty prompt or a broken connection, and that
     * the place to look is the provider's own log.
     */
    private static String emptyCompletionCause(int outputTokens) {
        return outputTokens > 0
                ? " — the model generated " + outputTokens + " output token(s) but the server returned no text and"
                        + " no tool call: a tool call in a format the server could not parse was most likely dropped"
                        + " (check the provider's own log for 'Failed to generate a tool call')"
                : " — the model generated no output tokens";
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
     * then reports a non-retryable connection error for {@link IOException}s, and a
     * retryable network error otherwise.
     *
     * <p>Why a shared tail: langchain4j already maps HTTP failures to its retriable /
     * non-retriable hierarchy (verified against 1.17+), and reading that is both more
     * accurate than substring match and immune to a provider rewording its error bodies.
     * Without it a malformed request (400) was reported as a network error — retryable —
     * so the strategy retried it and every fallback in a failover pool was tried in turn,
     * for a request that could not succeed on any of them.
     *
     * <p>{@link IOException}s are connection failures (DNS, refused, reset, broken pipe) and
     * are non-retryable: the endpoint is unreachable and retrying would hit the same failure.
     */
    protected final LlmException fallbackClassify(
            String provider, String msg, Throwable ex) {
        return fallbackClassify(provider, msg, ex, null);
    }

    /**
     * Same as {@link #fallbackClassify(String, String, Throwable)}, with the client's
     * configured request timeout so a {@link dev.langchain4j.exception.TimeoutException} is
     * reported with the value that was actually in force — see
     * {@link ProviderErrorMapper#fromTypedException(String, Throwable, java.time.Duration)}.
     */
    protected final LlmException fallbackClassify(
            String provider, String msg, Throwable ex, java.time.Duration timeout) {
        LlmException typed = ProviderErrorMapper.fromTypedException(provider, ex, timeout);
        if (typed != null) return typed;
        if (ex instanceof IOException || isCausedByIOException(ex)) {
            return LlmException.connectionError(provider, msg, ex);
        }
        return LlmException.networkError(provider, msg, ex);
    }

    /**
     * Checks whether the cause chain contains an {@link IOException}.
     */
    private static boolean isCausedByIOException(Throwable ex) {
        Throwable c = ex.getCause();
        for (int depth = 0; c != null && depth < 8; c = c.getCause(), depth++) {
            if (c instanceof IOException) return true;
        }
        return false;
    }
}