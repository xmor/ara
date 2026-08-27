package io.ara.adapters.llm.chatjimmy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.ToolCallEntry;
import io.ara.core.tool.AraTool;

/**
 * {@link LlmClient} adapter for <a href="https://chatjimmy.ai/">chatjimmy.ai</a>'s public
 * {@code /api/chat} endpoint.
 *
 * <p>Unlike the other adapters in this module, chatjimmy.ai speaks neither the OpenAI nor the
 * Anthropic wire format, so this client talks to it directly with the JDK's own
 * {@link HttpClient} instead of going through LangChain4j: request messages are flattened to
 * chatjimmy's {@code {messages, chatOptions, attachment}} shape, and the plain-text response
 * stream (interleaved with {@code <|think|>} reasoning spans and a trailing
 * {@code <|stats|>} usage block) is parsed back into {@link LlmCompletion}. Modelled on the
 * translation logic of the reference implementation,
 * <a href="https://github.com/tanu360/chatjimmy-reverse-api">chatjimmy-reverse-api</a>.
 *
 * <h2>Authentication</h2>
 * <p>chatjimmy.ai's backend takes no credential at all — no API key, no cookie, no session
 * token — so {@link Builder} requires none either.
 *
 * <h2>Deliberately not ported</h2>
 * <p>The reference project also rotates the {@code X-Forwarded-For}/{@code X-Real-IP}/
 * {@code True-Client-IP}/{@code X-Client-IP}/{@code Forwarded} headers through fake residential
 * IP ranges to mask the caller's origin from chatjimmy.ai's own rate-limiting. That is a
 * detection-evasion mechanism against a third party's abuse controls, not a wire-format
 * translation, and it is not implemented here: this client sends only the headers chatjimmy.ai
 * actually needs to accept a request ({@code Content-Type}, {@code Accept}, {@code Origin},
 * {@code Referer}, an honest {@code User-Agent} identifying this client).
 *
 * <h2>Tool calling</h2>
 * <p>chatjimmy.ai has no structured function-calling channel — tools are described in the
 * system prompt and the model is asked to answer with a {@code <tool_calls>[{"name":...,
 * "arguments":{...}}]</tool_calls>} marker, which this adapter parses back into
 * {@link ToolCallEntry} before the completion is returned. Because the adapter — not the
 * strategy — builds the tool catalogue and parses the result, {@link #supportsNativeTools()}
 * returns {@code true}: the same convention {@code OpenAiLlmClient}/{@code AnthropicLlmClient}
 * use to tell {@code ReactStrategy} not to also inject its own text-based tool scaffolding.
 *
 * <pre>{@code
 * LlmClient jimmy = ChatJimmyLlmClient.builder()
 *     .modelName("llama3.1-8B")
 *     .build();
 *
 * AraRuntime runtime = AraRuntime.builder()
 *     .llmClient("jimmy", jimmy)
 *     .build();
 * }</pre>
 *
 * @see LlmClient
 */
public class ChatJimmyLlmClient implements LlmClient {

    private static final String PROVIDER = "chatjimmy";
    private static final String DEFAULT_BASE_URL = "https://chatjimmy.ai";
    private static final String CHAT_PATH = "/api/chat";
    private static final String MODELS_PATH = "/api/models";
    private static final String DEFAULT_MODEL = "llama3.1-8B";
    private static final int DEFAULT_TOP_K = 8;
    private static final String USER_AGENT = "ara-adapters-chatjimmy/1.0 (+https://github.com/xmor/ara)";

    // ── Upstream text-stream markers ─────────────────────────────────────────

    private static final String THINK_START = "<|think|>";
    private static final String THINK_END = "<|/think|>";
    private static final Pattern THINK_RE =
            Pattern.compile(Pattern.quote(THINK_START) + "[\\s\\S]*?" + Pattern.quote(THINK_END));

    private static final String STATS_START = "<|stats|>";
    private static final String STATS_END = "<|/stats|>";
    private static final Pattern LEGACY_STATS_RE = Pattern.compile("<stats>([\\s\\S]*?)</stats>");

    private static final String TOOL_CALLS_START = "<tool_calls>";
    private static final String TOOL_CALLS_END = "</tool_calls>";
    private static final Pattern TOOL_CALLS_RE = Pattern.compile(
            Pattern.quote(TOOL_CALLS_START) + "\\s*([\\s\\S]*?)\\s*" + Pattern.quote(TOOL_CALLS_END));
    private static final Pattern FENCED_TOOL_CALL_RE = Pattern.compile("```tool_call\\s*([\\s\\S]*?)```");

    /** How many trailing characters a streaming flush must withhold so a marker split across
     *  two network chunks (e.g. {@code "<|sta"} + {@code "ts|>"}) is never emitted as text. */
    private static final int MARKER_LOOKBEHIND =
            Math.max(THINK_START.length(), STATS_START.length()) - 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String modelName;
    private final Double defaultTemperature;
    private final Double defaultTopP;
    private final Integer defaultMaxTokens;
    private final int topK;
    private final Duration timeout;

    private ChatJimmyLlmClient(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.modelName = builder.modelName;
        this.defaultTemperature = builder.temperature;
        this.defaultTopP = builder.topP;
        this.defaultMaxTokens = builder.maxTokens;
        this.topK = builder.topK;
        this.timeout = builder.timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(builder.timeout)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String providerId() {
        return PROVIDER + "-" + modelName;
    }

    /**
     * {@code true}: this adapter builds the tool catalogue and parses
     * {@code <tool_calls>...</tool_calls>} back into {@link LlmCompletion#toolCalls()} itself,
     * so {@code ReactStrategy}'s own text-based tool scaffolding would only compete with it.
     */
    @Override
    public boolean supportsNativeTools() {
        return true;
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        ObjectNode body = buildRequestBody(messages, context);
        HttpResponse<String> response = send(body);
        ParsedResponse parsed = parseUpstreamText(response.body());
        return toLlmCompletion(parsed);
    }

    @Override
    public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
        ObjectNode body = buildRequestBody(messages, context);
        SubmissionPublisher<String> publisher = new SubmissionPublisher<>(Runnable::run, Flow.defaultBufferSize());
        Thread.ofVirtual().start(() -> runStream(body, publisher));
        return publisher;
    }

    /** Fetches the model catalogue from {@code GET /api/models}, model ids only. */
    public List<String> listModels() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + MODELS_PATH))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw mapHttpError(response.statusCode(), response.body());
            }
            JsonNode data = MAPPER.readTree(response.body()).path("data");
            List<String> ids = new ArrayList<>();
            if (data.isArray()) {
                data.forEach(n -> {
                    String id = n.path("id").asText(null);
                    if (id != null) ids.add(id);
                });
            }
            if (!ids.contains(DEFAULT_MODEL)) ids.add(0, DEFAULT_MODEL);
            return ids;
        } catch (LlmException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw LlmException.networkError(PROVIDER, ex.getMessage(), ex);
        }
    }

    // ── Request building (ARA conversation → chatjimmy shape) ───────────────

    private ObjectNode buildRequestBody(List<LlmMessage> messages, LlmCallContext context) {
        checkNoMedia(messages);

        List<String> systemPrompts = new ArrayList<>();
        ArrayNode upstreamMessages = MAPPER.createArrayNode();

        for (LlmMessage m : messages) {
            switch (m.role()) {
                case "system" -> {
                    if (m.content() != null && !m.content().isBlank()) systemPrompts.add(m.content());
                }
                case "user" -> upstreamMessages.add(upstreamMessage("user", m.content()));
                case "assistant" -> upstreamMessages.add(upstreamMessage("assistant", m.content()));
                case "assistant_tool_call" -> upstreamMessages.add(upstreamMessage("assistant",
                        renderToolCallMarker(List.of(new ToolCallEntry(m.toolCallId(), m.toolName(), m.content())))));
                case "assistant_tool_calls" -> upstreamMessages.add(upstreamMessage("assistant",
                        renderToolCallMarker(parseParallelToolCallsJson(m.content()))));
                case "tool" -> upstreamMessages.add(upstreamMessage("user",
                        "Tool \"" + m.toolName() + "\" returned:\n" + nullToEmpty(m.content())));
                default -> upstreamMessages.add(upstreamMessage("user", "[" + m.role() + "] " + m.content()));
            }
        }

        if (context != null && context.hasResolvedTools() && !context.resolvedTools().isEmpty()) {
            systemPrompts.add(buildToolSystemPrompt(context.resolvedTools()));
        }

        ObjectNode chatOptions = MAPPER.createObjectNode();
        chatOptions.put("selectedModel", modelName != null ? modelName : DEFAULT_MODEL);
        chatOptions.put("systemPrompt", String.join("\n\n", systemPrompts));
        chatOptions.put("topK", topK);
        applyCallParameters(chatOptions, context);

        ObjectNode body = MAPPER.createObjectNode();
        body.set("messages", upstreamMessages);
        body.set("chatOptions", chatOptions);
        body.putNull("attachment");
        return body;
    }

    private void checkNoMedia(List<LlmMessage> messages) {
        for (LlmMessage m : messages) {
            for (var ref : m.media()) {
                throw LlmException.unsupportedMediaType(providerId(), ref.mimeType(), ref.name(), supportedMediaTypes());
            }
        }
    }

    /**
     * Applies sampling parameters the same way {@code CallParameterUtils} does for the
     * LangChain4j adapters: a per-call value from {@code context} overrides the client-level
     * default, and a value neither side ever set is simply omitted from the request rather than
     * substituted with some other default.
     */
    private void applyCallParameters(ObjectNode chatOptions, LlmCallContext context) {
        if (context != null) {
            Double temperature = context.temperature() != null ? context.temperature() : defaultTemperature;
            if (temperature != null) chatOptions.put("temperature", temperature);
            Double topP = context.topP() != null ? context.topP() : defaultTopP;
            if (topP != null) chatOptions.put("topP", topP);
            chatOptions.put("maxTokens", context.maxOutputTokens());
            if (context.hasStopSequences()) {
                ArrayNode stops = chatOptions.putArray("stopSequences");
                context.stopSequences().forEach(stops::add);
            }
        } else {
            if (defaultTemperature != null) chatOptions.put("temperature", defaultTemperature);
            if (defaultTopP != null) chatOptions.put("topP", defaultTopP);
            if (defaultMaxTokens != null) chatOptions.put("maxTokens", defaultMaxTokens);
        }
    }

    private ObjectNode upstreamMessage(String role, String content) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("role", role);
        n.put("content", nullToEmpty(content));
        return n;
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    /** Serialises tool calls back into the {@code <tool_calls>[...]</tool_calls>} marker the
     *  model was instructed to use, so a replayed conversation shows the model its own prior
     *  calls in the same syntax. */
    private String renderToolCallMarker(List<ToolCallEntry> calls) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (ToolCallEntry call : calls) {
            ObjectNode entry = MAPPER.createObjectNode();
            entry.put("name", call.toolId());
            entry.set("arguments", parseArgumentsOrEmpty(call.argumentJson()));
            arr.add(entry);
        }
        return TOOL_CALLS_START + arr.toString() + TOOL_CALLS_END;
    }

    private JsonNode parseArgumentsOrEmpty(String json) {
        try {
            return (json != null && !json.isBlank()) ? MAPPER.readTree(json) : MAPPER.createObjectNode();
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    /** Mirrors {@code ToolConversionUtils.parseParallelToolCallsJson} for the
     *  {@code "assistant_tool_calls"} role — the id/name/args of each call live inside this
     *  JSON, not on the {@link LlmMessage} itself. */
    private List<ToolCallEntry> parseParallelToolCallsJson(String json) {
        try {
            List<java.util.Map<String, String>> raw =
                    MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            return raw.stream()
                    .map(e -> new ToolCallEntry(e.get("id"), e.get("name"),
                            e.getOrDefault("args", "{}")))
                    .toList();
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed assistant_tool_calls JSON: " + json, e);
        }
    }

    private String buildToolSystemPrompt(List<AraTool> tools) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (AraTool tool : tools) {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("name", tool.toolId());
            n.put("description", tool.description());
            n.set("parameters", parseArgumentsOrEmpty(tool.argumentSchema()));
            arr.add(n);
        }
        return """
                # Tool Use

                The following tools are available to you. They are proxy-parsed text markers, \
                not native functions — do not use native tool-call syntax.

                ## Available Tools
                %s

                ## How to Call a Tool
                Whenever a task requires one of the tools above, output the call in this EXACT \
                format and nothing else:
                <tool_calls>
                [{"name":"tool_name","arguments":{...}}]
                </tool_calls>

                ## Rules
                1. Use ONLY the exact tool names listed above.
                2. "arguments" MUST be a valid JSON object matching the tool's parameter schema.
                3. Always include every required parameter.
                4. You may call multiple tools at once by placing multiple objects in the array.
                5. If no tool is needed, respond in plain text without a <tool_calls> block.
                6. NEVER wrap the <tool_calls> block inside markdown code fences.
                """.formatted(arr.toPrettyString());
    }

    // ── HTTP ──────────────────────────────────────────────────────────────────

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "*/*")
                .header("Origin", baseUrl)
                .header("Referer", baseUrl + "/")
                .header("User-Agent", USER_AGENT);
    }

    private HttpResponse<String> send(ObjectNode body) {
        HttpRequest request = baseRequest(CHAT_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw mapHttpError(response.statusCode(), response.body());
            }
            return response;
        } catch (LlmException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw LlmException.networkError(PROVIDER, ex.getMessage(), ex);
        }
    }

    private LlmException mapHttpError(int status, String body) {
        String msg = "HTTP " + status + ": " + body;
        return switch (status) {
            case 401, 403 -> LlmException.authenticationError(PROVIDER, msg);
            case 404 -> LlmException.modelNotFound(PROVIDER, modelName);
            case 429 -> LlmException.rateLimit(PROVIDER, msg);
            case 400, 413, 422 -> LlmException.invalidRequest(PROVIDER, msg);
            default -> status >= 500
                    ? LlmException.serverError(PROVIDER, msg, status)
                    : LlmException.networkError(PROVIDER, msg, null);
        };
    }

    // ── Streaming ─────────────────────────────────────────────────────────────

    private void runStream(ObjectNode body, SubmissionPublisher<String> publisher) {
        HttpRequest request = baseRequest(CHAT_PATH)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                publisher.closeExceptionally(mapHttpError(response.statusCode(), errorBody));
                return;
            }
            pumpStream(response.body(), publisher);
            publisher.close();
        } catch (LlmException ex) {
            publisher.closeExceptionally(ex);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            publisher.closeExceptionally(LlmException.networkError(PROVIDER, ex.getMessage(), ex));
        } catch (Exception ex) {
            publisher.closeExceptionally(LlmException.networkError(PROVIDER, ex.getMessage(), ex));
        }
    }

    /**
     * Re-implements the reference project's {@code pumpJimmyTextStream}: scans the buffer for
     * whichever of {@code <|think|>} / {@code <|stats|>} appears first, flushes any plain text
     * before it, then either drops the {@code think} span or stops (usage stats carry no channel
     * on {@link Flow.Publisher}&lt;String&gt; — see {@link #stream}). A trailing
     * {@link #MARKER_LOOKBEHIND} characters are withheld from every non-final flush so a marker
     * split across two network chunks is never emitted as text.
     */
    private void pumpStream(InputStream in, SubmissionPublisher<String> publisher) throws IOException {
        StringBuilder buffer = new StringBuilder();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buffer.append(new String(chunk, 0, n, StandardCharsets.UTF_8));
            drainBuffer(buffer, publisher, false);
        }
        drainBuffer(buffer, publisher, true);
    }

    private void drainBuffer(StringBuilder buffer, SubmissionPublisher<String> publisher, boolean finalFlush) {
        while (true) {
            int thinkIdx = buffer.indexOf(THINK_START);
            int statsIdx = buffer.indexOf(STATS_START);
            int earliest = earliestIndex(thinkIdx, statsIdx);

            if (earliest == -1) {
                int flushTo = finalFlush ? buffer.length() : Math.max(0, buffer.length() - MARKER_LOOKBEHIND);
                if (flushTo > 0) {
                    publisher.submit(buffer.substring(0, flushTo));
                    buffer.delete(0, flushTo);
                }
                return;
            }

            if (earliest > 0) {
                publisher.submit(buffer.substring(0, earliest));
                buffer.delete(0, earliest);
            }

            if (buffer.indexOf(THINK_START) == 0) {
                int end = buffer.indexOf(THINK_END);
                if (end == -1) return; // closing marker hasn't arrived yet — wait for more data
                buffer.delete(0, end + THINK_END.length());
                continue;
            }

            // STATS_START is at position 0
            int end = buffer.indexOf(STATS_END);
            if (end == -1) return; // closing marker hasn't arrived yet — wait for more data
            buffer.delete(0, end + STATS_END.length());
        }
    }

    private static int earliestIndex(int a, int b) {
        if (a == -1) return b;
        if (b == -1) return a;
        return Math.min(a, b);
    }

    // ── Response parsing (chatjimmy text stream → LlmCompletion) ───────────────

    private record ParsedResponse(String content, JsonNode stats) {}

    /** Mirrors the reference project's {@code parseJimmyResponse}: strips {@code <|think|>}
     *  spans, then extracts the trailing {@code <|stats|>} JSON block (or its legacy
     *  {@code <stats>} form). */
    private ParsedResponse parseUpstreamText(String raw) {
        String text = THINK_RE.matcher(raw != null ? raw : "").replaceAll("");

        int statsStart = text.lastIndexOf(STATS_START);
        int statsEnd = text.lastIndexOf(STATS_END);
        if (statsStart == -1 || statsEnd == -1 || statsEnd < statsStart) {
            Matcher legacy = LEGACY_STATS_RE.matcher(text);
            if (legacy.find()) {
                JsonNode stats = tryParseJson(legacy.group(1));
                String content = text.substring(0, legacy.start()) + text.substring(legacy.end());
                return new ParsedResponse(content, stats);
            }
            return new ParsedResponse(text, null);
        }

        JsonNode stats = tryParseJson(text.substring(statsStart + STATS_START.length(), statsEnd));
        String content = text.substring(0, statsStart) + text.substring(statsEnd + STATS_END.length());
        return new ParsedResponse(content, stats);
    }

    private JsonNode tryParseJson(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private LlmCompletion toLlmCompletion(ParsedResponse parsed) {
        List<ToolCallEntry> toolCalls = parseToolCalls(parsed.content());
        String text = stripToolCallsMarkers(parsed.content()).strip();

        int promptTokens = intField(parsed.stats(), "prefill_tokens");
        int outputTokens = intField(parsed.stats(), "decode_tokens");

        String toolCallJson = null;
        String toolCallId = null;
        String finishReason;
        if (!toolCalls.isEmpty()) {
            toolCallJson = buildLegacyToolCallJson(toolCalls.get(0));
            toolCallId = toolCalls.get(0).toolCallId();
            finishReason = "tool_calls";
        } else {
            finishReason = mapStopReason(parsed.stats());
        }

        return new LlmCompletion(text, promptTokens, outputTokens, finishReason,
                toolCallJson, toolCallId, toolCalls);
    }

    private List<ToolCallEntry> parseToolCalls(String content) {
        String block = extractToolCallsBlock(content);
        if (block == null) return List.of();
        try {
            JsonNode arr = MAPPER.readTree(block);
            if (!arr.isArray()) return List.of();
            List<ToolCallEntry> calls = new ArrayList<>();
            for (JsonNode entry : arr) {
                String name = entry.path("name").asText(null);
                if (name == null || name.isBlank()) continue;
                JsonNode args = entry.has("arguments") ? entry.get("arguments")
                        : entry.has("input") ? entry.get("input")
                        : entry.has("args") ? entry.get("args")
                        : MAPPER.createObjectNode();
                calls.add(new ToolCallEntry(UUID.randomUUID().toString(), name, args.toString()));
            }
            return calls;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String extractToolCallsBlock(String content) {
        Matcher m = TOOL_CALLS_RE.matcher(content);
        if (m.find()) return m.group(1).strip();
        Matcher fenced = FENCED_TOOL_CALL_RE.matcher(content);
        if (fenced.find()) return fenced.group(1).strip();
        return null;
    }

    private String stripToolCallsMarkers(String content) {
        String stripped = TOOL_CALLS_RE.matcher(content).replaceAll("");
        return FENCED_TOOL_CALL_RE.matcher(stripped).replaceAll("");
    }

    private String buildLegacyToolCallJson(ToolCallEntry entry) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("tool_id", entry.toolId());
        n.set("arguments", parseArgumentsOrEmpty(entry.argumentJson()));
        return n.toString();
    }

    private String mapStopReason(JsonNode stats) {
        String reason = stats != null
                ? (stats.has("done_reason") ? stats.get("done_reason").asText("")
                   : stats.has("reason") ? stats.get("reason").asText("") : "")
                : "";
        reason = reason.toLowerCase(Locale.ROOT);
        return (reason.contains("length") || reason.contains("max")) ? "length" : "stop";
    }

    private int intField(JsonNode stats, String field) {
        return stats != null && stats.has(field) ? stats.get(field).asInt(0) : 0;
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Builder for {@link ChatJimmyLlmClient}. No field is required — chatjimmy.ai's backend
     * takes no credential, and every other field has a working default.
     */
    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String modelName = DEFAULT_MODEL;
        private Double temperature;
        private Double topP;
        private Integer maxTokens = 1024;
        private int topK = DEFAULT_TOP_K;
        private Duration timeout = Duration.ofSeconds(30);

        /** Overrides the default {@code https://chatjimmy.ai} base URL (useful for testing). */
        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }

        /** Sets the {@code chatOptions.selectedModel} sent upstream. Defaults to {@code "llama3.1-8B"}. */
        public Builder modelName(String modelName) { this.modelName = modelName; return this; }

        /** Sampling temperature. Left unset (upstream default) unless set here or per-call. */
        public Builder temperature(double t) { this.temperature = t; return this; }

        /** Nucleus sampling threshold. Left unset (upstream default) unless set here or per-call. */
        public Builder topP(double topP) { this.topP = topP; return this; }

        /** Maximum output tokens. Defaults to {@code 1024}. */
        public Builder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }

        /** {@code chatOptions.topK}. Defaults to {@code 8}, chatjimmy.ai's own default. */
        public Builder topK(int topK) { this.topK = topK; return this; }

        /** HTTP request timeout. Defaults to {@code 30s}, matching the reference implementation. */
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }

        public ChatJimmyLlmClient build() {
            return new ChatJimmyLlmClient(this);
        }
    }
}
