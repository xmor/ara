package io.ara.core.agent;

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
 * <p>Immutable.
 *
 * @param toolId    the id of the tool that was called; never blank
 * @param arguments the JSON arguments the model sent; never {@code null}, {@code "{}"} when there were none
 * @param result    what the tool answered, an error text included; never {@code null}, empty when the tool chose not to
 *                  leave its result in the history
 */
public record ToolExchange(String toolId, String arguments, String result) {

    public ToolExchange {
        Objects.requireNonNull(toolId, "toolId must not be null");
        if (toolId.isBlank()) {
            throw new IllegalArgumentException("toolId must not be blank");
        }
        arguments = arguments == null || arguments.isBlank() ? "{}" : arguments;
        result = Objects.requireNonNullElse(result, "");
    }
}
