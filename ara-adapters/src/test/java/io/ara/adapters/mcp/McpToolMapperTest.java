package io.ara.adapters.mcp;

import io.ara.core.mcp.McpMedia;
import io.ara.core.mcp.McpToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how the contents of an MCP tool result become an {@link McpToolResult}.
 *
 * <p>What this guards is that non-text contents are no longer dropped: before, only
 * {@code TextContent} was read, so a server answering with an image and no caption produced an
 * empty result indistinguishable from a tool that did nothing.
 */
class McpToolMapperTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    private static McpToolResult map(McpSchema.Content... contents) {
        return McpToolMapper.from(new McpSchema.CallToolResult(List.of(contents), false, null, null));
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void textOnlyResultIsUnchanged() {
        McpToolResult result = map(new McpSchema.TextContent("a"), new McpSchema.TextContent("b"));

        assertEquals("a\nb", result.content());
        assertTrue(result.media().isEmpty());
        assertFalse(result.isError());
    }

    @Test
    void imageContentBecomesDecodedMedia() {
        McpToolResult result = map(
                new McpSchema.TextContent("chart:"),
                new McpSchema.ImageContent(null, b64(PNG), "image/png"));

        assertEquals("chart:", result.content());
        assertEquals(1, result.media().size());
        McpMedia media = result.media().get(0);
        assertEquals("image/png", media.mimeType());
        assertEquals("mcp-image-1.png", media.name());
        assertArrayEquals(PNG, media.data());
    }

    @Test
    void audioContentBecomesMediaWithItsDeclaredType() {
        McpToolResult result = map(new McpSchema.AudioContent(null, b64(new byte[] {1, 2}), "audio/wav"));

        assertEquals(1, result.media().size());
        assertEquals("audio/wav", result.media().get(0).mimeType());
        assertEquals("mcp-audio-1.wav", result.media().get(0).name());
    }

    @Test
    void blobResourceIsNamedAfterItsUri() {
        McpToolResult result = map(new McpSchema.EmbeddedResource(null,
                new McpSchema.BlobResourceContents("file:///reports/q3.pdf?v=2", "application/pdf",
                        b64("%PDF".getBytes(StandardCharsets.US_ASCII)))));

        assertEquals(1, result.media().size());
        assertEquals("q3.pdf", result.media().get(0).name());
        assertEquals("application/pdf", result.media().get(0).mimeType());
    }

    @Test
    void textResourceContributesItsText() {
        McpToolResult result = map(
                new McpSchema.TextContent("summary"),
                new McpSchema.EmbeddedResource(null,
                        new McpSchema.TextResourceContents("file:///a.txt", "text/plain", "body")));

        assertEquals("summary\nbody", result.content());
        assertTrue(result.media().isEmpty());
    }

    @Test
    void resourceLinkIsReportedAsText() {
        McpToolResult result = map(McpSchema.ResourceLink.builder()
                .name("report").uri("https://example.org/r.pdf").mimeType("application/pdf").build());

        assertEquals("Resource link: report <https://example.org/r.pdf> (application/pdf)", result.content());
        assertTrue(result.media().isEmpty());
    }

    @Test
    void undecodableBase64IsReportedNotDropped() {
        McpToolResult result = map(new McpSchema.ImageContent(null, "not base64 !!", "image/png"));

        assertTrue(result.media().isEmpty());
        assertTrue(result.content().contains("could not be decoded"), result.content());
    }

    @Test
    void nameFromUriIgnoresUrisWithoutAUsefulLastSegment() {
        assertNull(McpToolMapper.nameFromUri(null));
        assertNull(McpToolMapper.nameFromUri("urn:x:y"));
        assertNull(McpToolMapper.nameFromUri("https://example.org/"));
        assertEquals("a.png", McpToolMapper.nameFromUri("https://example.org/img/a.png#frag"));
    }
}
