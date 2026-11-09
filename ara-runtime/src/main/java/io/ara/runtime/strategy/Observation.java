package io.ara.runtime.strategy;

import io.ara.core.media.MediaRef;
import io.ara.core.tool.ToolResult;

import java.util.List;
import java.util.Objects;

/**
 * What one tool call becomes inside a run: the text the model reads and the media the tool
 * returned, ready to be written to working memory or to a step's local history.
 *
 * <h2>Why one type, used by every strategy</h2>
 * <p>Two places turned a {@link ToolResult} into an observation — {@code ReactExecutionSupport}
 * for the ReAct family and {@code PlanStepExecutor} for plan-execute — and each built the string
 * by hand, down to the wording of a failure. When a result gained media, that was two copies of
 * the same decision to keep in step, and a third strategy would have been a third. Here the
 * decision is made once: the text is {@link ToolResult#content()} (which already names each media),
 * or the failure sentence, and the media travel beside it.
 *
 * <h2>Where the media lines come from</h2>
 * <p>The {@code Attached: …} lines are added by {@link ToolResult#content()}, not here, because the
 * same text also feeds the execution step that the next turn's history is rebuilt from. Adding them
 * only in this class would leave a later turn without the id of an image it can no longer be shown.
 *
 * <p>Immutable, so safe to share between the virtual threads of a parallel tool dispatch.
 *
 * @param text  what the model reads as the result of the call; never {@code null}
 * @param media the media the tool returned, in order; never {@code null}, usually empty
 */
record Observation(String text, List<MediaRef> media) {

    Observation {
        Objects.requireNonNull(text, "text must not be null");
        media = media != null ? List.copyOf(media) : List.of();
    }

    /**
     * The observation of {@code result}, the answer to the call of {@code toolId}.
     *
     * <p>{@code toolId} is the id the model called, not {@code result.toolId()}: the failure sentence
     * has always named the call, and a registry that answers under another id must not change what
     * the model is told.
     */
    static Observation of(String toolId, ToolResult result) {
        String text = result.success()
                ? result.content()
                : "Tool [%s] failed — %s".formatted(toolId, result.error());
        return new Observation(text, result.media());
    }

    /** An observation that is only text, such as the answer to a {@code close_step} call. */
    static Observation ofText(String text) {
        return new Observation(text, List.of());
    }

    /**
     * The same observation as the turn the text protocol uses when a call has no id to pair a result
     * with: the model's own call went out as plain text, so the result comes back as a user turn.
     */
    String asTextProtocolTurn() {
        return "Observation: " + text;
    }
}
