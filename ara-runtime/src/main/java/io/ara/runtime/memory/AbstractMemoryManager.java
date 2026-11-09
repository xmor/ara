package io.ara.runtime.memory;

import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.MemoryManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class that provides the canonical in-process working-memory implementation.
 *
 * <p>Subclasses inherit {@link #appendToWorkingMemory}, {@link #workingMemory}, and
 * {@link #clearWorkingMemory} and only need to implement the semantic/episodic tiers.
 * {@link SlidingWindowMemoryManager} overrides {@code appendToWorkingMemory} to apply
 * token-budget eviction while still sharing the same {@code working} list.
 *
 * <p><b>Thread safety:</b> confined, not internally synchronised. One memory manager belongs to
 * one {@link io.ara.core.agent.SessionId}, and a session runs one task at a time, so {@code
 * working} is <em>mutated</em> only by the single thread driving that session's strategy. That is
 * the invariant every mutation here relies on; it is not enforced by the type.
 *
 * <p>The one place other threads touch {@code working} is {@link #workingMemory()}: during a
 * parallel tool dispatch ({@code ReactExecutionSupport.dispatchBounded}) the worker virtual threads
 * can call it — but only through {@code InterceptingToolRegistry}, and so only when an interceptor
 * is registered. Those are concurrent <em>reads</em>, and each returns a {@link List#copyOf}
 * snapshot rather than a live view, so they are safe against each other. They stay safe against the
 * driver thread because the driver writes {@code working} only after {@code dispatchBounded}'s latch
 * has joined every worker — the read phase and the write phase do not overlap.
 *
 * <p><b>The invariant a tool or interceptor must not break:</b> do not write to working memory
 * (append/clear) from inside a tool's or interceptor's own execution during a parallel dispatch.
 * A write concurrent with another worker's {@link #workingMemory()} read would race on the plain
 * {@link ArrayList} — a {@link java.util.ConcurrentModificationException} or a torn read.
 * {@code CopyOnWriteArrayList} was rejected as the guard: working memory is rewritten every ReAct
 * iteration, so its O(n)-per-append copy would tax the hot path to defend a case the confinement
 * invariant already rules out (A1 — simplicity is not traded for a cost nobody measured as needed).
 */
public abstract class AbstractMemoryManager implements MemoryManager {

    /**
     * Working-memory entries. Mutated only by the session's single strategy-driver thread (see the
     * class-level thread-safety note); read concurrently only via {@link #workingMemory()}, which
     * snapshots rather than exposing this list.
     */
    protected final List<MemoryEntry> working = new ArrayList<>();

    @Override
    public void appendToWorkingMemory(MemoryEntry entry) {
        working.add(entry);
    }

    /**
     * P6/U19, 2026-09-23: a snapshot, not a live view over {@link #working}. The previous
     * {@code Collections.unmodifiableList(working)} was read-only to its caller but still
     * backed by the same mutable {@code ArrayList} this class keeps mutating — a caller
     * iterating it (building a prompt, an {@code AgentExecutionContext} an interceptor reads
     * later) while {@code appendToWorkingMemory}/{@code clearWorkingMemory}/eviction touched
     * {@code working} underneath it risked a {@link java.util.ConcurrentModificationException}
     * or, worse, a torn read that never throws. {@link List#copyOf} is the one-time cost this
     * trades for it — no longer O(1) identity-memoised, but every call site in the codebase
     * (verified: {@code ReactExecutionSupport}, {@code ReflexionStrategy}, {@code
     * PlanExecuteStrategy}, {@code AgentInstance.context}) only ever reads the result once,
     * immediately, never holds it across a later mutation — none relied on the old live view.
     */
    @Override
    public List<MemoryEntry> workingMemory() {
        return List.copyOf(working);
    }

    @Override
    public void clearWorkingMemory() {
        working.clear();
    }
}
