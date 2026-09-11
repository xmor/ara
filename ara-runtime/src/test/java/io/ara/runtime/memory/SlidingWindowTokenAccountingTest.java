package io.ara.runtime.memory;

import io.ara.core.memory.EmbeddingClient;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.SemanticStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that {@link SlidingWindowMemoryManager}'s running token counter stays equal to a
 * fresh full-window recount after every mutation class the manager supports — append,
 * eviction (drop-middle and drop-oldest), clear, and recall. The counter is what makes
 * {@code append} {O(1)} instead of a quadratic-over-the-session full recount; an honest
 * equivalence check must therefore live in the tests rather than on the hot path it exists
 * to remove.
 */
class SlidingWindowTokenAccountingTest {

    /** Mirrored from the manager, which keeps {@code CHARS_PER_TOKEN} private. */
    private static final int CHARS_PER_TOKEN = 4;

    /** A fresh recording store driving the offload/recall dependencies. */
    private static final class PhoneStore implements SemanticStore {
        final List<MemoryEntry> hits = new ArrayList<>();

        @Override public void upsert(String agentId, String role, String type, String content, List<Float> vector) {}
        @Override public List<MemoryEntry> search(String agentId, List<Float> queryVector, int limit) { return hits; }
    }

    private static final EmbeddingClient EMBED = new EmbeddingClient() {
        @Override public List<Float> embed(String text) { return List.of(0.1f, 0.2f, 0.3f); }
        @Override public int dimensions() { return 3; }
    };

    private static int recount(SlidingWindowMemoryManager m) {
        int chars = m.workingMemory().stream()
                .mapToInt(e -> (e.role()    != null ? e.role().length()    : 0)
                             + (e.content() != null ? e.content().length() : 0))
                .sum();
        return chars / CHARS_PER_TOKEN;
    }

    private static void fill(SlidingWindowMemoryManager m, int n) {
        for (int i = 0; i < n; i++) {
            m.appendToWorkingMemory("user", "message number " + i + " with trailing padding text");
        }
    }

    @Test
    void growingWindowWithoutEviction_staysInSyncWithARecount() {
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(1_000_000, EvictionPolicy.DROP_MIDDLE);

        for (int i = 1; i <= 60; i++) {
            m.appendToWorkingMemory("user", "turn " + i + " padding characters");
            if (i % 10 == 0) {
                assertEquals(recount(m), m.estimatedTokens(),
                        "counter drifted from a recount after " + i + " appends");
            }
        }

        m.clearWorkingMemory();
        assertEquals(0, m.estimatedTokens(), "clear must reset the running counter");
    }

    @Test
    void windowAfterDropMiddleEviction_staysInSync() {
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(60, EvictionPolicy.DROP_MIDDLE);
        fill(m, 12);

        assertEquals(recount(m), m.estimatedTokens(), "counter drifted after eviction");
        fill(m, 5);
        assertEquals(recount(m), m.estimatedTokens(), "counter drifted after eviction then regrowth");
    }

    @Test
    void windowAfterDropOldest_staysInSyncAcrossToolCallGroups() {
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(70, EvictionPolicy.DROP_OLDEST);
        for (int round = 0; round < 6; round++) {
            m.appendToWorkingMemory("user", "turn " + round);
            m.appendToWorkingMemory("assistant_tool_call", "{\"tool_id\":\"noop\"}",
                    new io.ara.core.memory.ToolCallMetadata("call-" + round, "noop"));
            m.appendToWorkingMemory("tool", "result of " + round);
            assertEquals(recount(m), m.estimatedTokens(),
                    "counter drifted after a whole-group append round " + round);
        }
    }

    @Test
    void windowAfterRecall_staysInSync() {
        PhoneStore store = new PhoneStore();
        store.hits.add(MemoryEntry.of("user", "an earlier recalled fact"));
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                1_000_000, EvictionPolicy.DROP_MIDDLE, null, store, EMBED, "agent-7");
        m.appendToWorkingMemory("system", "the agent system prompt");
        m.workingMemory();   // touch the view: must not disturb the counter

        m.recallRelevant("relevant query", 5);

        assertEquals(recount(m), m.estimatedTokens(), "counter drifted after a recall prepend");
    }
}