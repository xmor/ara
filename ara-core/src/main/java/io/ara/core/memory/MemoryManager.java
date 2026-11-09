package io.ara.core.memory;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;

import java.util.List;

/**
 * Manages the working-memory window for a single agent session.
 *
 * <p>Working memory is the conversation context sent to the LLM on each call.
 * Implementations may evict old entries when a token budget is exceeded
 * ({@code io.ara.runtime.memory.SlidingWindowMemoryManager}).
 *
 * <p>Semantic and episodic tiers are intentionally absent: they require a
 * vector store and a persistence backend. Introduce them as separate interfaces
 * when a real implementation exists.
 */
public interface MemoryManager {

    /**
     * Appends {@code entry} to the working memory window, whole: every field it carries
     * (role, content, metadata, media) must reach {@link #workingMemory()} unchanged.
     *
     * <p>This is the one operation an implementation has to provide, and the three
     * convenience overloads below only build an entry and call it. The shape is deliberate.
     * Each overload used to be its own abstract-or-default method, so a manager that
     * forwarded {@code (role, content, metadata)} to a delegate could not also forward
     * media: there was no overload carrying both, and every new field would have needed
     * another one, with every forwarding decorator updated by hand. A single entry-taking
     * method cannot lose a field it does not know about — a decorator that forwards the
     * entry forwards whatever the entry grows later.
     *
     * <p>Implementations that wrap another manager should forward {@code entry} itself, not
     * rebuild it from its parts. A manager that has no media-aware storage must say so by
     * refusing the entry or documenting that it drops media; silently storing a reference it
     * will never send is the failure this method exists to rule out.
     *
     * @param entry the entry to append; never {@code null}
     */
    void appendToWorkingMemory(MemoryEntry entry);

    /**
     * Appends a message to the working memory window.
     *
     * @param role    the speaker role ("system", "user", "assistant", "tool", "assistant_tool_call")
     * @param content the message content
     */
    default void appendToWorkingMemory(String role, String content) {
        appendToWorkingMemory(MemoryEntry.of(role, content));
    }

    /**
     * Appends a tool-call entry with its {@link ToolCallMetadata} (call id and tool name),
     * so adapters can reconstruct native tool-call messages on the next LLM call.
     */
    default void appendToWorkingMemory(String role, String content, ToolCallMetadata metadata) {
        appendToWorkingMemory(MemoryEntry.of(role, content, metadata));
    }

    /**
     * Appends an entry carrying media references — images or documents the model should look
     * at alongside {@code content}.
     */
    default void appendToWorkingMemory(String role, String content, java.util.List<io.ara.core.media.MediaRef> media) {
        appendToWorkingMemory(MemoryEntry.of(role, content, media));
    }

    /**
     * Returns the current working memory window, ordered oldest-first,
     * ready to be sent as conversation history to an LLM.
     */
    List<MemoryEntry> workingMemory();

    /**
     * Clears the working memory window, preparing the agent for a new task.
     */
    void clearWorkingMemory();

    /**
     * Pulls back into the working-memory window the entries most relevant to
     * {@code queryText} from whatever episodic/offload tier this manager has (ADR-0078 D4)
     * — the symmetric counterpart to token-budget eviction. Called at the start of a turn
     * with the incoming task text as the query.
     *
     * <p>The default is a no-op: a manager with no offload tier has nothing to recall, and
     * pretending otherwise would be worse than doing nothing. {@code
     * io.ara.runtime.memory.SlidingWindowMemoryManager} overrides it when configured with a
     * {@code SemanticStore}.
     *
     * @param queryText  the text to find relevant past context for; a blank query recalls nothing
     * @param maxResults upper bound on entries to pull back; {@code <= 0} recalls nothing
     */
    default void recallRelevant(String queryText, int maxResults) {}

    /**
     * Called once, right after a task completes successfully — the hook a memory
     * implementation that wants to react to a finished turn (e.g. extracting facts to
     * consolidate later) hangs off, without the runtime needing to know what "reacting"
     * means (ADR-0086). Called before {@link #clearWorkingMemory()}, so an implementation
     * that overrides this still sees the turn's full working-memory window if it needs it.
     *
     * <p>The default is a no-op: a manager with nothing to do at end-of-turn should not pay
     * for pretending otherwise.
     *
     * @param task     the task that was executed
     * @param response the resulting response
     */
    default void onTurnCompleted(AgentTask task, AgentResponse response) {}
}
