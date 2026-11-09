package io.ara.core.tool;

import io.ara.core.media.MediaRef;

import java.util.List;
import java.util.Objects;

/**
 * The result of a single {@link AraTool} execution.
 *
 * <p>All factory methods enforce the invariant that a successful result always has
 * non-null output and a failed result always has a non-null error message.
 *
 * <h2>Media travel as references, beside the text</h2>
 * <p>A tool that produces an image (a crop, a rendered page, a chart) returns it in {@link
 * #media()}, as {@link MediaRef}s whose bytes it has stored in a {@code MediaStore}. The reasons
 * are those of {@code LlmMessage}: bytes inside the text would be counted as characters by the
 * working-memory estimate, printed into the logs and persisted with the session. {@link #output()}
 * stays the tool's own text and should still say what was produced ("crop saved, 320x410 px"):
 * {@link #content()} adds the media lines, so a model that is not shown the image — it was
 * pushed out of the window, the provider cannot take it, the turn is a later one — still knows it
 * exists and how it is named.
 *
 * <p>A failed result carries no media. An image next to an error would invite the model to reason
 * about output the tool itself declares invalid.
 *
 * <p>Immutable, and so safe to share between the virtual threads of a parallel tool dispatch.
 *
 * @param toolId  the identifier of the tool that produced this result
 * @param output  the string output of the tool (may be JSON or plain text); never {@code null}
 * @param success {@code true} if the tool completed without error
 * @param error   human-readable error message; {@code null} on success
 * @param media   images and documents the tool produced, in presentation order; never {@code null},
 *                empty for almost every result and always empty on a failure
 */
public record ToolResult(String toolId, String output, boolean success, String error, List<MediaRef> media) {

    /** Starts the line {@link #content()} adds for each media, which the model reads as part of the result. */
    public static final String ATTACHED_PREFIX = "Attached: ";

    public ToolResult {
        Objects.requireNonNull(toolId, "toolId must not be null");
        if (toolId.isBlank()) throw new IllegalArgumentException("toolId must not be blank");
        Objects.requireNonNull(output, "output must not be null");
        if (!success && (error == null || error.isBlank()))
            throw new IllegalArgumentException("error must be provided when success is false");
        if (success && error != null)
            throw new IllegalArgumentException("error must be null when success is true");
        media = media != null ? List.copyOf(media) : List.of();
        if (!success && !media.isEmpty())
            throw new IllegalArgumentException("a failed result must not carry media");
    }

    /**
     * The 4-component shape from before {@code media} existed, kept so every construction already
     * in the wild compiles and behaves identically: no media.
     */
    public ToolResult(String toolId, String output, boolean success, String error) {
        this(toolId, output, success, error, List.of());
    }

    /** Returns {@code true} if the tool completed successfully. */
    public boolean isSuccess() {
        return success;
    }

    /** Returns {@code true} if the tool execution failed. */
    public boolean isFailed() {
        return !success;
    }

    /**
     * Returns the relevant content for this result: on success the {@code output} followed by one
     * {@code Attached: } line per media, on failure the {@code error}. Convenience method for
     * consumers that display a single string regardless of outcome — and the text an execution
     * step and the next turn's history are built from, which is why the media lines are here and
     * not only where the model's message is assembled: a turn that no longer has the image still
     * has the line that names it.
     *
     * <p>Without media this is {@code output} exactly, so nothing that reads it changes.
     */
    public String content() {
        if (!success) return error;
        if (media.isEmpty()) return output;
        StringBuilder text = new StringBuilder(output);
        for (MediaRef reference : media) {
            if (!text.isEmpty()) text.append('\n');
            text.append(ATTACHED_PREFIX).append(reference.describe());
        }
        return text.toString();
    }

    /** Factory method for a successful tool result. */
    public static ToolResult success(String toolId, String output) {
        return new ToolResult(toolId, output, true, null);
    }

    /**
     * Factory method for a successful tool result that also returns media. {@code output} should
     * say what was produced; the media lines are added by {@link #content()}.
     */
    public static ToolResult success(String toolId, String output, List<MediaRef> media) {
        return new ToolResult(toolId, output, true, null, media);
    }

    /** Factory method for a failed tool result. */
    public static ToolResult failure(String toolId, String error) {
        return new ToolResult(toolId, "", false,
                Objects.requireNonNull(error, "error must not be null"));
    }
}