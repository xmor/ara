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
     * Appends a message to the working memory window.
     *
     * @param role    the speaker role ("system", "user", "assistant", "tool", "assistant_tool_call")
     * @param content the message content
     */
    void appendToWorkingMemory(String role, String content);

    /**
     * Appends a tool-call entry with its {@link ToolCallMetadata} (call id and tool name),
     * so adapters can reconstruct native tool-call messages on the next LLM call.
     * The default implementation ignores {@code metadata}.
     */
    default void appendToWorkingMemory(String role, String content, ToolCallMetadata metadata) {
        appendToWorkingMemory(role, content);
    }

    /**
     * Appends an entry carrying media references — images or documents the model should look
     * at alongside {@code content}.
     *
     * <p>The default drops the references, which is the right behaviour for a manager that
     * has no media-aware storage: the alternative would be to pretend the attachments are in
     * the window when nothing will ever send them. Managers that store {@link MemoryEntry}
     * (all of the built-in ones, via {@code AbstractMemoryManager}) override this.
     */
    default void appendToWorkingMemory(String role, String content, java.util.List<io.ara.core.media.MediaRef> media) {
        appendToWorkingMemory(role, content);
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
