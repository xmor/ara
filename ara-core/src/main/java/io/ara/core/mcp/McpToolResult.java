package io.ara.core.mcp;

import java.util.List;

/**
 * Immutable result of an MCP tool invocation.
 *
 * <p>An MCP tool result is a list of contents of different kinds. The text ones are joined into
 * {@code content}; the binary ones (images, audio, blob resources) are in {@code media}, decoded
 * but not stored — see {@link McpMedia} for why the client does not store them itself.
 *
 * @param content text content returned by the tool (empty string on error)
 * @param isError {@code true} if the server signaled an error condition
 * @param media   binary contents the server returned, in the order it returned them; never
 *                {@code null}, empty for a text-only result
 */
public record McpToolResult(String content, boolean isError, List<McpMedia> media) {

    public McpToolResult {
        if (content == null) content = "";
        media = media != null ? List.copyOf(media) : List.of();
    }

    /** The text-only shape from before {@code media} existed, kept so existing clients compile unchanged. */
    public McpToolResult(String content, boolean isError) {
        this(content, isError, List.of());
    }

    public static McpToolResult success(String content) {
        return new McpToolResult(content, false);
    }

    public static McpToolResult success(String content, List<McpMedia> media) {
        return new McpToolResult(content, false, media);
    }

    public static McpToolResult error(String message) {
        return new McpToolResult(message, true);
    }
}
