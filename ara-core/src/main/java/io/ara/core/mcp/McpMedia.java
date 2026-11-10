package io.ara.core.mcp;

import java.util.Objects;

/**
 * A binary content an MCP server returned from a tool call: an image, an audio clip, or the blob
 * of an embedded resource, already decoded from the base64 of the wire format.
 *
 * <p>It carries bytes, not a {@link io.ara.core.media.MediaRef}, because the MCP client that
 * builds it has no {@link io.ara.core.media.MediaStore} and no session: storing the bytes, and
 * deciding whether they can be stored at all, is the job of whoever executes the tool with a
 * {@link io.ara.core.media.MediaAccess} in hand ({@code McpAraTool}).
 *
 * <p>{@code mimeType} is the one the server declared, not normalised and not checked against
 * {@link io.ara.core.media.MediaTypes}: a type ARA does not accept must still reach the tool so
 * that it can tell the model what was left out, instead of disappearing here.
 *
 * <p>{@code data} is not copied: the record is a carrier between the MCP client and the tool,
 * and nobody holds it after that. Its {@code equals} compares the array by identity.
 *
 * @param name     a file name for the content: the last segment of the resource URI when there is
 *                 one, otherwise a generated {@code mcp-<kind>-<n>.<ext>}
 * @param mimeType the media type the server declared
 * @param data     the decoded bytes
 */
public record McpMedia(String name, String mimeType, byte[] data) {

    public McpMedia {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(mimeType, "mimeType must not be null");
        Objects.requireNonNull(data, "data must not be null");
    }
}
