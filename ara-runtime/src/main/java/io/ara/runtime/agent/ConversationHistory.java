package io.ara.runtime.agent;

import io.ara.core.agent.ConversationTurn;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;
import io.ara.core.agent.ToolExchange;
import io.ara.core.media.MediaRef;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Ordered log of {@link ConversationTurn}s for a single agent session.
 *
 * <p>Turns are stored oldest-first. {@link #recentTurns(int)} returns a
 * tail view bounded by {@code maxTurns} so callers can enforce a token budget
 * without knowing the full history length.
 *
 * <p>Seeded from {@code store.loadTurns(sessionId)} at construction, and every {@link
 * #addTurn} also writes through to {@code store} — with {@link SessionStore#noop()}
 * (the default), this degrades to exactly today's in-memory-only behavior at the cost
 * of one no-op call per turn.
 *
 * <p><strong>Thread-safe.</strong> Writes ({@link #addTurn}, {@link #clear}) happen on the
 * task thread, serialised in practice by the session's execution lock — {@link #writeLock}
 * exists to protect the rarer case that does not hold (e.g. an explicit {@code invalidate}
 * landing from another thread while a task is still in flight), not the common one. Reads
 * ({@link #recentTurns}, {@link #allTurns}, {@link #size}) never take {@link #writeLock} at
 * all: {@code turns} is a {@link CopyOnWriteArrayList}, whose snapshot iteration is always
 * safe against a concurrent {@link #addTurn}/{@link #clear} with no locking and no risk of
 * {@link java.util.ConcurrentModificationException}, unlike a plain {@code ArrayList}.
 *
 * <p><b>N1/U27, 2026-09-22:</b> this used to be one {@code synchronized(this)} shared by every
 * reader and writer alike. {@code AraRuntime.conversationHistory(...)}/{@code
 * SessionManager.listActive()} are read-only observability APIs callable from any thread while
 * a task is running, and on this project's JDK 21 target a reader merely <em>waiting to enter</em>
 * a monitor contended by a slow {@link SessionStore#appendTurn} call pins its own carrier exactly
 * as the writer holding it does — not just the writer's, every concurrent reader's too. Splitting
 * reads onto a lock-free path removes that multiplier entirely; {@link #writeLock} being a {@link
 * ReentrantLock} rather than a monitor means the writer itself no longer pins either — but neither
 * change makes a genuinely slow {@code store} implementation free of contention, only of pinning:
 * see {@link SessionStore}'s own javadoc for the non-blocking constraint a real implementation
 * must uphold.
 */
public final class ConversationHistory {

    private final List<ConversationTurn> turns;
    private final SessionStore store;
    private final SessionId sessionId;
    /** Serialises {@link #addTurn}/{@link #clear} against each other. Dedicated object so callers cannot lock on us. */
    private final ReentrantLock writeLock = new ReentrantLock();

    public ConversationHistory(SessionStore store, SessionId sessionId) {
        this.store     = Objects.requireNonNull(store, "store must not be null");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        this.turns     = new CopyOnWriteArrayList<>(store.loadTurns(sessionId));
    }

    /**
     * Appends a new turn at the end of the history and writes it through to the store.
     * The store write stays inside the lock so that the in-memory order and the persisted
     * order can never diverge.
     */
    public void addTurn(String userInput, String assistantOutput) {
        addTurn(userInput, assistantOutput, List.of());
    }

    /**
     * Appends a turn that carried attachments, recording their references. Same write-through
     * contract as {@link #addTurn(String, String)}; only references are stored, never bytes,
     * so a session's persisted history stays small however large the documents were.
     */
    public void addTurn(String userInput, String assistantOutput, List<MediaRef> media) {
        addTurn(userInput, assistantOutput, media, List.of());
    }

    /**
     * Appends a turn with the tool calls the agent made on it, when its memory configuration keeps them. Same
     * write-through contract as {@link #addTurn(String, String)}.
     */
    public void addTurn(String userInput, String assistantOutput, List<MediaRef> media, List<ToolExchange> toolCalls) {
        ConversationTurn turn = new ConversationTurn(userInput, assistantOutput, media, toolCalls);
        writeLock.lock();
        try {
            turns.add(turn);
            store.appendTurn(sessionId, turn);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Returns an immutable snapshot of the most recent {@code maxTurns} turns, ordered
     * oldest-first. If {@code maxTurns <= 0} or the history is empty, returns an empty list.
     */
    public List<ConversationTurn> recentTurns(int maxTurns) {
        List<ConversationTurn> snapshot = List.copyOf(turns);   // stable: a COW list never mutates under us
        if (maxTurns <= 0 || snapshot.isEmpty()) return List.of();
        int from = Math.max(0, snapshot.size() - maxTurns);
        return List.copyOf(snapshot.subList(from, snapshot.size()));
    }

    /** Returns an immutable snapshot of every recorded turn, oldest-first. */
    public List<ConversationTurn> allTurns() {
        return List.copyOf(turns);
    }

    /** Total number of turns recorded so far. */
    public int size() {
        return turns.size();
    }

    /** Resets the in-memory history (e.g. on explicit session invalidation). */
    public void clear() {
        writeLock.lock();
        try {
            turns.clear();
        } finally {
            writeLock.unlock();
        }
    }
}
