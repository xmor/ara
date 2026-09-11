package io.ara.runtime.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.llm.LlmCompletion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utility for extracting tool-call requests from LLM completions.
 *
 * <p>Centralises the parsing logic that was previously duplicated across
 * {@link ReactStrategy} and {@link PlanExecuteStrategy}.
 *
 * <p>Extraction priority inside {@link #extract}:
 * <ol>
 *   <li>Native tool call reported by the LLM adapter ({@link LlmCompletion#toolCallJson()})</li>
 *   <li>ARA inline JSON {@code {"tool_id":"...","arguments":{...}}} anywhere in the text</li>
 *   <li>Channel format {@code <|channel|>...to=TOOL...<|message|>{ARGS}} used by some local models</li>
 * </ol>
 */
public final class ToolCallParser {

    private static final Logger log = LoggerFactory.getLogger(ToolCallParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Matches the LM-Studio / channel-format tool call emitted by some local models.
     * Example: {@code <|channel|>commentary to=search_documents <|constrain|>json<|message|> {...}}
     */
    private static final Pattern CHANNEL_TOOL_PATTERN = Pattern.compile(
            "<\\|channel\\|>.*?to=(\\S+).*?<\\|message\\|>\\s*(\\{.+)",
            Pattern.DOTALL);

    private ToolCallParser() {}

    /**
     * Normalised representation of a tool invocation request extracted from an LLM completion.
     *
     * @param toolId       the identifier of the tool to invoke
     * @param argumentJson the JSON-serialised arguments for the tool
     * @param toolCallId   the provider-specific call id (e.g. OpenAI {@code tool_call_id}); may be null
     */
    public record ToolCallRequest(String toolId, String argumentJson, String toolCallId) {
        /** Backward-compatible 2-arg constructor — {@code toolCallId} defaults to {@code null}. */
        public ToolCallRequest(String toolId, String argumentJson) {
            this(toolId, argumentJson, null);
        }
    }

    /**
     * Attempts to extract a tool call from {@code completion}, trying native tool call
     * first and falling back to inline text parsing.
     */
    public static Optional<ToolCallRequest> extract(LlmCompletion completion) {
        if (completion.hasToolCall()) {
            return Optional.of(parseNative(completion.toolCallJson(), completion.toolCallId()));
        }
        return extractInline(completion.text());
    }

    /**
     * Extracts all tool calls from {@code completion}.
     *
     * <p>For completions that carry a {@link LlmCompletion#toolCalls()} list the full
     * list is returned, preserving per-call {@code toolCallId}s for native
     * function-calling providers (OpenAI, Anthropic).  Falls back to the single-call
     * path for legacy completions that only populate {@link LlmCompletion#toolCallJson()}.
     */
    public static List<ToolCallRequest> extractAll(LlmCompletion completion) {
        var entries = completion.toolCalls();
        if (!entries.isEmpty()) {
            return entries.stream()
                    .map(e -> new ToolCallRequest(e.toolId(), e.argumentJson(), e.toolCallId()))
                    .toList();
        }
        // Legacy single-call path
        return extract(completion).map(List::of).orElse(List.of());
    }

    /**
     * Extracts a tool call from raw text only (ARA inline JSON and channel format).
     * Used during synthesis mode where native tool calls are suppressed.
     */
    public static Optional<ToolCallRequest> extractInline(String output) {
        if (output == null || output.isBlank()) {
            return Optional.empty();
        }

        // 1 — ARA JSON / OpenAI JSON format anywhere in the text.
        // Extract every balanced JSON object in a single left-to-right pass — scanning
        // the text once per prefix was doing the same linear-time extraction twice.
        List<String> candidates = new ArrayList<>();
        int idx = output.indexOf("{");
        while (idx >= 0 && idx < output.length()) {
            String json = extractJson(output, idx);
            if (json != null) candidates.add(json);
            idx = output.indexOf("{", idx + 1);
        }
        // Prefer a candidate carrying "tool_id" over one that only carries "name",
        // preserving the original priority order across the same candidate list.
        // No try/catch here: parseToolCallJson never throws — it catches internally
        // and reports failure as the "unknown" sentinel this loop already skips.
        for (String key : new String[]{"\"tool_id\"", "\"name\""}) {
            for (String json : candidates) {
                if (json.contains(key)) {
                    ToolCallRequest tcr = parseToolCallJson(json);
                    if (!"unknown".equals(tcr.toolId())) return Optional.of(tcr);
                }
            }
        }

        // 2 — <|channel|>commentary to=TOOL ...<|message|>{ARGS}
        Matcher m = CHANNEL_TOOL_PATTERN.matcher(output);
        if (m.find()) {
            String toolName = m.group(1).strip();
            String argsJson = extractJson(m.group(2).strip(), 0);
            if (argsJson != null) {
                return Optional.of(new ToolCallRequest(toolName, argsJson));
            }
        }

        return Optional.empty();
    }

    /**
     * Extracts {@code tool_id}/{@code name} from ARA tool-call JSON.
     * Returns empty string on failure.
     */
    public static String extractToolName(String toolCallJson) {
        if (toolCallJson == null) return "";
        try {
            JsonNode n = MAPPER.readTree(toolCallJson);
            String id = n.has("tool_id") ? n.get("tool_id").asText() : n.path("name").asText("");
            int dot = id.lastIndexOf('.');
            return dot >= 0 ? id.substring(dot + 1) : id;
        } catch (Exception e) { return ""; }
    }

    /**
     * Extracts the {@code arguments} object from ARA tool-call JSON as a string.
     * Returns {@code "{}"} on failure.
     */
    public static String extractToolArgs(String toolCallJson) {
        if (toolCallJson == null) return "{}";
        try {
            JsonNode args = MAPPER.readTree(toolCallJson).path("arguments");
            return args.isMissingNode() ? "{}" : MAPPER.writeValueAsString(args);
        } catch (Exception e) { return "{}"; }
    }

    /**
     * Extracts the tool name and arguments from ARA tool-call JSON in a single parse.
     *
     * <p>Behaviour-identical merge of {@link #extractToolName} and {@link #extractToolArgs}
     * into one Jackson {@code readTree} — both fields are needed together by the hot path
     * (recordAssistantOutput), which would otherwise parse the same JSON twice.
     */
    static ToolCallRequest extractNameAndArgs(String toolCallJson) {
        if (toolCallJson == null) {
            return new ToolCallRequest("", "{}");
        }
        try {
            JsonNode node = MAPPER.readTree(toolCallJson);
            String id = node.has("tool_id") ? node.get("tool_id").asText() : node.path("name").asText("");
            int dot = id.lastIndexOf('.');
            if (dot >= 0) id = id.substring(dot + 1);
            JsonNode args = node.path("arguments");
            String argsJson = args.isMissingNode() ? "{}" : MAPPER.writeValueAsString(args);
            return new ToolCallRequest(id, argsJson);
        } catch (Exception e) {
            return new ToolCallRequest("", "{}");
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private static ToolCallRequest parseNative(String json, String toolCallId) {
        ToolCallRequest base = parseToolCallJson(json);
        return toolCallId != null ? new ToolCallRequest(base.toolId(), base.argumentJson(), toolCallId) : base;
    }

    private static ToolCallRequest parseToolCallJson(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            String toolId = node.has("tool_id")
                    ? node.get("tool_id").asText()
                    : node.path("name").asText("unknown");
            // Strip namespace prefixes emitted by some models (e.g. "functions.get_current_time")
            int dot = toolId.lastIndexOf('.');
            if (dot >= 0) toolId = toolId.substring(dot + 1);
            JsonNode argsNode = node.path("arguments");
            // Unwrap double-wrapped arguments: {"arguments":{...}} → {...}
            if (!argsNode.isMissingNode() && argsNode.has("arguments") && argsNode.size() == 1) {
                argsNode = argsNode.get("arguments");
            }
            // If the model passed the tool schema instead of actual arguments, normalise to {}
            boolean isSchema = !argsNode.isMissingNode()
                    && argsNode.has("type") && argsNode.has("properties");
            String argumentJson = (argsNode.isMissingNode() || isSchema)
                    ? "{}" : MAPPER.writeValueAsString(argsNode);
            return new ToolCallRequest(toolId, argumentJson);
        } catch (Exception e) {
            log.warn("Failed to parse tool-call JSON — falling back to no-op: {}", json, e);
            return new ToolCallRequest("unknown", "{}");
        }
    }

    /**
     * Extracts a balanced JSON object (curly-brace delimited) from {@code text}
     * starting at position {@code start}.
     *
     * @return the JSON substring, or {@code null} if no complete object is found
     */
    static String extractJson(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escape   = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape)                { escape = false; continue; }
            if (c == '\\' && inString) { escape = true;  continue; }
            if (c == '"')              { inString = !inString; continue; }
            if (inString)              { continue; }
            if (c == '{')              { depth++; }
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return null;
    }
}
