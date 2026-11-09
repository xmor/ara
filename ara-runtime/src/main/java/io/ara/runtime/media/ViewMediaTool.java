package io.ara.runtime.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.AgentTask;
import io.ara.core.media.MediaAccess;
import io.ara.core.media.MediaRef;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolResult;

import java.util.List;

/**
 * {@code view_media}: shows the model a media it can no longer see — an image that left the window
 * of {@code ToolMediaWindow}, a crop from an earlier turn, the document uploaded two turns ago.
 *
 * <p>It is the same mechanism as any tool that returns a media, not a channel of its own: the
 * result carries the {@link MediaRef}, the strategy records it, the adapter places it. What the
 * tool adds is the lookup, through the {@link MediaAccess} of the run, so an id the session never
 * saw is refused exactly as it is for every other tool.
 *
 * <p>Stateless; thread-safe.
 */
public final class ViewMediaTool implements AraTool {

    public static final String TOOL_ID = "view_media";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String toolId() {
        return TOOL_ID;
    }

    @Override
    public String description() {
        return "Shows you again an image or document that appeared earlier in this conversation, "
                + "given the id printed next to its name (the 12 hex characters after \"id\"). "
                + "Use it when you need to look at a media again; media you were shown earlier are "
                + "no longer attached to every message.";
    }

    @Override
    public String argumentSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "media_id": { "type": "string", "description": "The media id, as printed in \\"(id xxxxxxxxxxxx)\\"" }
                  },
                  "required": ["media_id"]
                }""";
    }

    @Override
    public ToolResult execute(String argumentJson) {
        return ToolResult.failure(TOOL_ID, "view_media needs the run it is called from");
    }

    @Override
    public ToolResult execute(String argumentJson, AgentTask task) {
        MediaAccess access = task.runContext().mediaAccess().orElse(null);
        if (access == null) {
            return ToolResult.failure(TOOL_ID, "This run has no media store, so there is nothing to show.");
        }
        JsonNode id;
        try {
            id = MAPPER.readTree(argumentJson).get("media_id");
        } catch (Exception e) {
            return ToolResult.failure(TOOL_ID, "Invalid arguments: " + e.getMessage());
        }
        if (id == null || !id.isTextual() || id.asText().isBlank()) {
            return ToolResult.failure(TOOL_ID, "Invalid arguments: missing required text field 'media_id'");
        }
        try {
            MediaRef ref = access.resolve(id.asText().trim()).orElse(null);
            if (ref == null) {
                return ToolResult.failure(TOOL_ID, "No media with id '" + id.asText().trim()
                        + "' in this conversation. Use an id printed next to a media name.");
            }
            return ToolResult.success(TOOL_ID, "Showing " + ref.name() + ".", List.of(ref));
        } catch (IllegalArgumentException ambiguous) {
            return ToolResult.failure(TOOL_ID, ambiguous.getMessage());
        }
    }
}
