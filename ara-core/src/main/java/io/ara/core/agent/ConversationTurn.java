package io.ara.core.agent;

import io.ara.core.media.MediaRef;

import java.util.List;

/**
 * Immutable snapshot of a single conversational exchange — one user input and
 * the corresponding assistant output produced by the agent.
 *
 * <p>Stored in {@code ConversationHistory} (ara-runtime) and replayed into working
 * memory at the start of each subsequent task so the LLM can maintain continuity
 * across calls within the same session. Lives in {@code ara-core} (not alongside
 * {@code ConversationHistory}) so {@link SessionStore} can reference it without a
 * module-boundary violation.
 *
 * <p>{@link #media} records what the user attached on that turn, as references. Persisting
 * them keeps the history honest — a turn that was about a document does not read as a turn
 * about nothing — and keeps the document retrievable later. They are <em>not</em> re-attached
 * when the turn is replayed into working memory: an attachment is paid for on the turn that
 * introduced it, not again on every turn after it. That is an opinionated default, and the
 * opposite of re-sending the media each turn; a session that needs the model to look at the
 * document again should attach it again.
 *
 * <p>{@link #toolCalls} records the tool calls the agent made on that turn, when its memory configuration keeps
 * them ({@code MemoryConfig.replayToolCalls}). Without them a replayed history shows actions only as sentences ("the
 * light is on"), and a model rereading it learns to answer the next request with a sentence instead of a call. A turn
 * stored before the field existed, or by an agent that does not keep calls, has none: {@code null} reads as empty, so
 * an old persisted turn still loads.
 *
 * @param userInput       what the user said; may be blank only when {@code media} is non-empty
 * @param assistantOutput what the agent answered; {@code null} is normalised to empty
 * @param media           references to what the user attached on this turn, in order;
 *                        never {@code null}
 * @param toolCalls       the tool calls of this turn, in the order they were made; never {@code null}
 */
public record ConversationTurn(String userInput, String assistantOutput, List<MediaRef> media,
                               List<ToolExchange> toolCalls) {

    public ConversationTurn {
        media = media != null ? List.copyOf(media) : List.of();
        toolCalls = toolCalls != null ? List.copyOf(toolCalls) : List.of();
        // Blank input is legal for the same reason it is on AgentTask: a turn can consist of
        // a document and no words. A turn with neither records nothing and stays rejected.
        if (userInput == null || (userInput.isBlank() && media.isEmpty())) {
            throw new IllegalArgumentException("userInput must not be blank unless media is present");
        }
        if (assistantOutput == null) {
            assistantOutput = "";
        }
    }

    /** A turn without tool calls — the shape every caller used before tool calls were kept. */
    public ConversationTurn(String userInput, String assistantOutput, List<MediaRef> media) {
        this(userInput, assistantOutput, media, List.of());
    }

    /** A text-only turn — the shape every caller used before media existed. */
    public ConversationTurn(String userInput, String assistantOutput) {
        this(userInput, assistantOutput, List.of(), List.of());
    }
}
