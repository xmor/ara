package io.ara.adapters.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.mcp.McpMedia;
import io.ara.core.mcp.McpTool;
import io.ara.core.mcp.McpToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Stateless mapper between MCP SDK types and ARA core records. */
final class McpToolMapper {

    private static final Logger log = LoggerFactory.getLogger(McpToolMapper.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpToolMapper() {}

    static McpTool from(McpSchema.Tool sdkTool) {
        JsonNode schema = toJsonNode(sdkTool.inputSchema());
        return new McpTool(sdkTool.name(), sdkTool.description(), schema);
    }

    /**
     * Maps every kind of content the server returned, not only the text.
     *
     * <ul>
     *   <li>{@code TextContent}: its text.</li>
     *   <li>{@code ImageContent}, {@code AudioContent}: decoded into an {@link McpMedia}.</li>
     *   <li>{@code EmbeddedResource}: a text resource contributes its text, a blob resource
     *       becomes an {@link McpMedia} named after the resource URI.</li>
     *   <li>{@code ResourceLink}: a text line naming the link, since ARA does not fetch it.</li>
     * </ul>
     *
     * <p>Until this mapped only {@code TextContent}, and everything else was dropped without a
     * trace: a server that answered with a chart and no caption produced an empty result, and
     * the model concluded the tool had done nothing. Content whose base64 cannot be decoded is
     * reported as a text line rather than dropped, for the same reason.
     */
    static McpToolResult from(McpSchema.CallToolResult sdkResult) {
        boolean isError = Boolean.TRUE.equals(sdkResult.isError());
        List<String> text = new ArrayList<>();
        List<McpMedia> media = new ArrayList<>();
        List<McpSchema.Content> contents = sdkResult.content();
        if (contents != null) {
            for (McpSchema.Content content : contents) {
                map(content, text, media);
            }
        }
        return new McpToolResult(String.join("\n", text), isError, media);
    }

    private static void map(McpSchema.Content content, List<String> text, List<McpMedia> media) {
        switch (content) {
            case McpSchema.TextContent t -> text.add(t.text());
            case McpSchema.ImageContent i ->
                    decoded(i.data(), i.mimeType(), generatedName("image", i.mimeType(), media), text, media);
            case McpSchema.AudioContent a ->
                    decoded(a.data(), a.mimeType(), generatedName("audio", a.mimeType(), media), text, media);
            case McpSchema.EmbeddedResource e -> {
                McpSchema.ResourceContents resource = e.resource();
                if (resource instanceof McpSchema.TextResourceContents tr) {
                    if (tr.text() != null) text.add(tr.text());
                } else if (resource instanceof McpSchema.BlobResourceContents br) {
                    String name = nameFromUri(br.uri());
                    decoded(br.blob(), br.mimeType(),
                            name != null ? name : generatedName("resource", br.mimeType(), media), text, media);
                }
            }
            case McpSchema.ResourceLink l -> text.add("Resource link: "
                    + (l.name() != null ? l.name() + " " : "") + "<" + l.uri() + ">"
                    + (l.mimeType() != null ? " (" + l.mimeType() + ")" : ""));
            default -> log.debug("MCP content of type '{}' is not mapped", content.type());
        }
    }

    private static void decoded(String base64, String mimeType, String name,
                                List<String> text, List<McpMedia> media) {
        String type = mimeType != null ? mimeType : "application/octet-stream";
        if (base64 == null) {
            text.add("[The server returned " + type + " content '" + name + "' with no data.]");
            return;
        }
        try {
            media.add(new McpMedia(name, type, Base64.getMimeDecoder().decode(base64)));
        } catch (IllegalArgumentException e) {
            log.warn("MCP content '{}' ({}) is not valid base64 — reported as unreadable", name, type, e);
            text.add("[The server returned " + type + " content '" + name + "' that could not be decoded.]");
        }
    }

    private static String generatedName(String kind, String mimeType, List<McpMedia> media) {
        return "mcp-" + kind + "-" + (media.size() + 1) + "." + extension(mimeType);
    }

    /** The last path segment of {@code uri}, or {@code null} when it has none worth showing. */
    static String nameFromUri(String uri) {
        if (uri == null || uri.isBlank()) return null;
        String path;
        try {
            // The path only: neither the host of "https://example.org/" nor the scheme-specific
            // part of an opaque "urn:x:y" is a file name.
            path = java.net.URI.create(uri.strip()).getPath();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (path == null) return null;
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String last = path.substring(path.lastIndexOf('/') + 1);
        return last.isBlank() ? null : last;
    }

    private static String extension(String mimeType) {
        if (mimeType == null) return "bin";
        return switch (mimeType.trim().toLowerCase(Locale.ROOT)) {
            case "image/png"       -> "png";
            case "image/jpeg"      -> "jpg";
            case "image/webp"      -> "webp";
            case "image/gif"       -> "gif";
            case "application/pdf" -> "pdf";
            case "text/plain"      -> "txt";
            case "text/markdown"   -> "md";
            case "text/csv"        -> "csv";
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/mpeg"      -> "mp3";
            default                -> "bin";
        };
    }

    private static JsonNode toJsonNode(Object inputSchema) {
        if (inputSchema == null) return MAPPER.createObjectNode();
        try {
            if (inputSchema instanceof JsonNode node) return node;
            return MAPPER.valueToTree(inputSchema);
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }
}
