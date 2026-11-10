package io.ara.adapters.mcp;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.RunContext;
import io.ara.core.mcp.McpClient;
import io.ara.core.mcp.McpMedia;
import io.ara.core.mcp.McpTool;
import io.ara.core.mcp.McpToolResult;
import io.ara.core.media.MediaAccess;
import io.ara.core.media.MediaRef;
import io.ara.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how {@link McpAraTool} turns the media of an MCP result into {@link ToolResult#media()}.
 *
 * <p>The rule under test: every media the server returned either reaches the session's store or
 * is named in the output with the reason it did not. None disappears without the model knowing.
 */
class McpAraToolMediaTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    /** Stores what it is given; refuses unsupported types the way the real view does, via MediaRef. */
    private static final class RecordingMediaAccess implements MediaAccess {
        final List<MediaRef> stored = new ArrayList<>();

        @Override public Optional<MediaRef> resolve(String idOrPrefix) { return Optional.empty(); }
        @Override public byte[] read(MediaRef reference) { return new byte[0]; }
        @Override public MediaRef put(String name, String mimeType, byte[] bytes) {
            MediaRef ref = new MediaRef("abcdef0123456789" + stored.size(), mimeType, name, bytes.length, null);
            stored.add(ref);
            return ref;
        }
    }

    private static McpAraTool toolReturning(McpToolResult result) {
        McpClient client = new McpClient() {
            @Override public CompletableFuture<List<McpTool>> listTools() {
                return CompletableFuture.completedFuture(List.of());
            }
            @Override public CompletableFuture<McpToolResult> callTool(String n, Map<String, Object> a) {
                return CompletableFuture.completedFuture(result);
            }
            @Override public void close() {}
        };
        return new McpAraTool(new McpTool("chart", "draws a chart", null), new McpToolRegistry(client));
    }

    private static AgentTask taskWith(MediaAccess access) {
        AgentTask task = AgentTask.of("draw");
        return task.withRunContext(task.runContext().withOpaque(RunContext.MEDIA_ACCESS_KEY, access));
    }

    @Test
    void storesTheMediaAndReturnsTheirReferences() {
        RecordingMediaAccess access = new RecordingMediaAccess();
        McpAraTool tool = toolReturning(McpToolResult.success("Here is the chart.",
                List.of(new McpMedia("chart.png", "image/png", PNG))));

        ToolResult result = tool.execute("{}", taskWith(access));

        assertTrue(result.isSuccess());
        assertEquals("Here is the chart.", result.output());
        assertEquals(1, result.media().size());
        assertEquals(access.stored, result.media());
        assertEquals("chart.png", result.media().get(0).name());
    }

    @Test
    void reportsAnUnsupportedTypeInsteadOfDroppingIt() {
        RecordingMediaAccess access = new RecordingMediaAccess();
        McpAraTool tool = toolReturning(McpToolResult.success("Two outputs.", List.of(
                new McpMedia("chart.png", "image/png", PNG),
                new McpMedia("voice.wav", "audio/wav", new byte[] {1, 2, 3}))));

        ToolResult result = tool.execute("{}", taskWith(access));

        assertTrue(result.isSuccess());
        assertEquals(1, result.media().size(), "the png is stored");
        assertTrue(result.output().startsWith("Two outputs.\nNot attached: 'voice.wav' (audio/wav, 3 bytes)"),
                result.output());
    }

    @Test
    void withoutAMediaStoreTheTextArrivesAndTheMediaAreNamed() {
        McpAraTool tool = toolReturning(McpToolResult.success("",
                List.of(new McpMedia("chart.png", "image/png", PNG))));

        ToolResult withoutTask = tool.execute("{}");
        ToolResult withoutAccess = tool.execute("{}", AgentTask.of("draw"));

        for (ToolResult result : List.of(withoutTask, withoutAccess)) {
            assertTrue(result.isSuccess());
            assertTrue(result.media().isEmpty());
            assertTrue(result.output().startsWith("Not attached: 'chart.png'"), result.output());
            assertTrue(result.output().contains("no media store"), result.output());
        }
    }

    @Test
    void anErrorResultCarriesNoMedia() {
        RecordingMediaAccess access = new RecordingMediaAccess();
        McpAraTool tool = toolReturning(new McpToolResult("boom", true,
                List.of(new McpMedia("chart.png", "image/png", PNG))));

        ToolResult result = tool.execute("{}", taskWith(access));

        assertTrue(result.isFailed());
        assertEquals("boom", result.error());
        assertTrue(access.stored.isEmpty());
    }
}
