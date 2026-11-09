package io.ara.core.agent;

import io.ara.core.media.MediaRef;

import java.util.List;
import java.util.Objects;

/**
 * One tool call of a past turn, as the session history keeps it: which tool, with which arguments, and what it
 * answered. Stored in {@link ConversationTurn#toolCalls()} and replayed into working memory on the turns that follow,
 * when the agent's memory configuration asks for it.
 *
 * <p>It exists because a history of questions and answers alone teaches a model the wrong lesson: a past "turn the
 * light on" answered by "the light is on" reads as a turn in which the model acted by saying so, and the next request
 * gets the same treatment, with no call at all. Keeping the call in the history keeps what was done next to what was
 * said. The result can be shortened or replaced by a placeholder when the turn is replayed; the call itself is the part
 * that matters.
 *
 * <h2>Media are recorded, not replayed</h2>
 * <p>When the tool returned media, {@link #media()} keeps the references. They are not sent to the model
 * again on the turns that follow — a replayed turn is text, as for the attachments of a turn — and the text
 * of {@link #result()} already names each one. What the references are for is that the session can say what
 * it has seen: a tool may read only the media its session has already had in front of it, and an image a
 * tool produced on an earlier turn is part of that. They also tell a store which media a session still cites.
 *
 * <p>The media follow the result: a tool whose history policy leaves its result out leaves its media out
 * with it, and one whose policy leaves the call out leaves out the whole exchange.
 *
 * <p>Immutable.
 *
 * @param toolId    the id of the tool that was called; never blank
 * @param arguments the JSON arguments the model sent; never {@code null}, {@code "{}"} when there were none
 * @param result    what the tool answered, an error text included; never {@code null}, empty when the tool chose not to
 *                  leave its result in the history
 * @param media     the media the tool returned, in order; never {@code null}, empty for almost every call
 */
public record ToolExchange(String toolId, String arguments, String result, List<MediaRef> media) {

    public ToolExchange {
        Objects.requireNonNull(toolId, "toolId must not be null");
        if (toolId.isBlank()) {
            throw new IllegalArgumentException("toolId must not be blank");
        }
        arguments = arguments == null || arguments.isBlank() ? "{}" : arguments;
        result = Objects.requireNonNullElse(result, "");
        media = media != null ? List.copyOf(media) : List.of();
    }

    /**
     * The 3-component shape from before tool results could carry media, kept so a call site and a stored
     * session written then read back unchanged, with no media.
     */
    public ToolExchange(String toolId, String arguments, String result) {
        this(toolId, arguments, result, List.of());
    }
}
