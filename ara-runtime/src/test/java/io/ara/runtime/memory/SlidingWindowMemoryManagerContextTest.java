package io.ara.runtime.memory;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.memory.EmbeddingClient;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.SemanticStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0078 D2–D4: real {@code SUMMARIZE} eviction with an honest fallback, episodic offload
 * before discard, and selective recall — all inert when their dependencies are absent.
 */
class SlidingWindowMemoryManagerContextTest {

    /** Fills the window past a small token budget with distinct user turns. */
    private static void fill(SlidingWindowMemoryManager m, int n) {
        for (int i = 0; i < n; i++) {
            m.appendToWorkingMemory("user", "message number " + i + " with some padding text to burn tokens");
        }
    }

    private static AraAgent summarizer(String output, boolean succeed, boolean throwing) {
        AgentId id = AgentId.generate();
        return new AraAgent() {
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                if (throwing) throw new IllegalStateException("summariser boom");
                return succeed
                        ? AgentResponse.success(task.taskId(), id, output, 1, 0, 0, Duration.ofMillis(1), List.of())
                        : AgentResponse.failure(task.taskId(), id, "no", Duration.ofMillis(1));
            }
            @Override public void terminate() {}
        };
    }

    static final class RecordingStore implements SemanticStore {
        final List<String> upserted = new ArrayList<>();
        List<MemoryEntry> nextSearchResult = List.of();

        @Override public void upsert(String agentId, String role, String type, String content, List<Float> vector) {
            upserted.add(content);
        }
        @Override public List<MemoryEntry> search(String agentId, List<Float> queryVector, int limit) {
            return nextSearchResult;
        }
    }

    private static final EmbeddingClient EMBED = new EmbeddingClient() {
        @Override public List<Float> embed(String text) { return List.of(0.1f, 0.2f, 0.3f); }
        @Override public int dimensions() { return 3; }
    };

    @Test
    void summarizeWithoutAnAgentStillDegradesToDropMiddle() {
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(60, EvictionPolicy.SUMMARIZE);
        fill(m, 12);

        assertTrue(m.workingMemory().size() < 12, "eviction happened");
        assertFalse(m.workingMemory().stream().anyMatch(e -> hasLabel(e, "context_summary")),
                "no summary entry without a summariser");
    }

    @Test
    void summarizeWithAnAgentReplacesTheMiddleRangeWithASummaryEntry() {
        // budget generous enough that one summarisation of the middle brings the window
        // back under it — so no follow-up eviction pass removes the fresh summary entry.
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                140, EvictionPolicy.SUMMARIZE, summarizer("SUMMARY OF EARLIER CONTEXT", true, false));
        fill(m, 12);

        List<MemoryEntry> w = m.workingMemory();
        assertTrue(w.size() < 12, "the middle was collapsed");
        assertTrue(w.stream().anyMatch(e -> hasLabel(e, "context_summary")
                        && "SUMMARY OF EARLIER CONTEXT".equals(e.content())),
                "the evicted middle was replaced by the agent's summary");
    }

    @Test
    void summarizeFallsBackWhenTheAgentFailsOrThrows() {
        SlidingWindowMemoryManager fails = new SlidingWindowMemoryManager(
                60, EvictionPolicy.SUMMARIZE, summarizer("x", false, false));
        SlidingWindowMemoryManager boom = new SlidingWindowMemoryManager(
                60, EvictionPolicy.SUMMARIZE, summarizer("x", false, true));
        fill(fails, 12);
        fill(boom, 12);

        assertFalse(fails.workingMemory().stream().anyMatch(e -> hasLabel(e, "context_summary")));
        assertFalse(boom.workingMemory().stream().anyMatch(e -> hasLabel(e, "context_summary")));
        assertTrue(boom.workingMemory().size() < 12, "still evicted despite the exception");
    }

    @Test
    void offloadUpsertsEvictedEntriesBeforeDiscardingThem() {
        RecordingStore store = new RecordingStore();
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                60, EvictionPolicy.DROP_OLDEST, null, store, EMBED, "agent-7");
        fill(m, 12);

        assertFalse(store.upserted.isEmpty(), "evicted entries were offloaded");
        assertTrue(store.upserted.stream().anyMatch(c -> c.contains("message number 0")),
                "the oldest evicted message reached the episodic store");
    }

    @Test
    void offloadIsInertWithoutAStore() {
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(60, EvictionPolicy.DROP_OLDEST);
        fill(m, 12);   // must simply not throw
        assertTrue(m.workingMemory().size() < 12);
    }

    @Test
    void recallRelevantPullsMatchesToTheHeadTaggedRecalled() {
        RecordingStore store = new RecordingStore();
        store.nextSearchResult = List.of(
                MemoryEntry.of("user", "an earlier relevant fact"),
                MemoryEntry.of("assistant", "an earlier relevant answer"));
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                0, EvictionPolicy.DROP_MIDDLE, null, store, EMBED, "agent-7");
        m.appendToWorkingMemory("user", "current question");

        m.recallRelevant("something relevant", 5);

        List<MemoryEntry> w = m.workingMemory();
        assertEquals("an earlier relevant fact", w.get(0).content());
        assertTrue(hasLabel(w.get(0), "recalled"));
        assertEquals("current question", w.get(2).content(), "recalled entries sit ahead of the live window");
    }

    @Test
    void recallRelevantIsANoOpForBlankQueryNonPositiveLimitOrNoStore() {
        RecordingStore store = new RecordingStore();
        store.nextSearchResult = List.of(MemoryEntry.of("user", "should not appear"));
        SlidingWindowMemoryManager withStore = new SlidingWindowMemoryManager(
                0, EvictionPolicy.DROP_MIDDLE, null, store, EMBED, "agent-7");
        withStore.appendToWorkingMemory("user", "q");

        withStore.recallRelevant("   ", 5);
        withStore.recallRelevant("real query", 0);
        assertEquals(1, withStore.workingMemory().size());

        SlidingWindowMemoryManager noStore = new SlidingWindowMemoryManager(0, EvictionPolicy.DROP_MIDDLE);
        noStore.appendToWorkingMemory("user", "q");
        noStore.recallRelevant("real query", 5);
        assertEquals(1, noStore.workingMemory().size());
    }

    private static boolean hasLabel(MemoryEntry e, String label) {
        return e.metadata() instanceof io.ara.core.memory.EpisodeLabel el && label.equals(el.value());
    }
}
