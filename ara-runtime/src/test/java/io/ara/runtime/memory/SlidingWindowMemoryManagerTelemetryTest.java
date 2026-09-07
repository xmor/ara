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
import io.ara.core.telemetry.SpanStatus;
import io.ara.runtime.telemetry.RecordingTelemetry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0078 D5: {@code memory.evict}/{@code memory.recall} — no preexisting span to extend
 * on either operation, both new (unlike most of this backlog's other observability
 * decisions).
 */
class SlidingWindowMemoryManagerTelemetryTest {

    private static void fill(SlidingWindowMemoryManager m, int n) {
        for (int i = 0; i < n; i++) {
            m.appendToWorkingMemory("user", "message number " + i + " with some padding text to burn tokens");
        }
    }

    private static AraAgent summarizer(String output, boolean succeed) {
        AgentId id = AgentId.generate();
        return new AraAgent() {
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
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
    void dropOldestEmitsOneEvictSpanPerPassWithNoOffloadNoSummary() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                60, EvictionPolicy.DROP_OLDEST, null, null, null, null, telemetry);

        fill(m, 12);

        List<RecordingTelemetry.RecordedSpan> spans = telemetry.spansNamed("memory.evict");
        assertTrue(spans.size() >= 1, "at least one eviction pass happened");
        for (RecordingTelemetry.RecordedSpan span : spans) {
            assertEquals(SpanStatus.OK, span.status());
            assertEquals("DROP_OLDEST", span.attributes().get("policy"));
            assertEquals(false, span.attributes().get("offloaded"));
            assertEquals(false, span.attributes().get("summarized"));
            assertTrue((long) span.attributes().get("entries_evicted") >= 1);
        }
    }

    @Test
    void offloadConfiguredMarksTheSpanOffloadedTrue() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        RecordingStore store = new RecordingStore();
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                60, EvictionPolicy.DROP_OLDEST, null, store, EMBED, "agent-7", telemetry);

        fill(m, 12);

        List<RecordingTelemetry.RecordedSpan> spans = telemetry.spansNamed("memory.evict");
        assertTrue(spans.stream().allMatch(s -> Boolean.TRUE.equals(s.attributes().get("offloaded"))));
    }

    @Test
    void successfulSummarizeMarksTheSpanSummarizedTrue() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                140, EvictionPolicy.SUMMARIZE, summarizer("SUMMARY", true), null, null, null, telemetry);

        fill(m, 12);

        List<RecordingTelemetry.RecordedSpan> spans = telemetry.spansNamed("memory.evict");
        assertTrue(spans.stream().anyMatch(s -> Boolean.TRUE.equals(s.attributes().get("summarized"))),
                "at least one pass replaced the range with a real summary");
    }

    @Test
    void aDegradedSummarizeMarksTheSpanSummarizedFalse() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        // No summarizer agent at all -> every pass degrades to DROP_MIDDLE.
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                60, EvictionPolicy.SUMMARIZE, null, null, null, null, telemetry);

        fill(m, 12);

        List<RecordingTelemetry.RecordedSpan> spans = telemetry.spansNamed("memory.evict");
        assertTrue(spans.size() >= 1);
        assertTrue(spans.stream().noneMatch(s -> Boolean.TRUE.equals(s.attributes().get("summarized"))));
    }

    @Test
    void recallWithHitsEmitsASpanWithTheRecalledCount() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        RecordingStore store = new RecordingStore();
        store.nextSearchResult = List.of(
                MemoryEntry.of("user", "an earlier relevant fact"),
                MemoryEntry.of("assistant", "an earlier relevant answer"));
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                0, EvictionPolicy.DROP_MIDDLE, null, store, EMBED, "agent-7", telemetry);
        m.appendToWorkingMemory("user", "current question");

        m.recallRelevant("something relevant", 5);

        List<RecordingTelemetry.RecordedSpan> spans = telemetry.spansNamed("memory.recall");
        assertEquals(1, spans.size());
        assertEquals(SpanStatus.OK, spans.get(0).status());
        assertEquals(2L, spans.get(0).attributes().get("recalled_count"));
    }

    @Test
    void recallWithNoHitsEmitsASpanWithZero() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        RecordingStore store = new RecordingStore();
        store.nextSearchResult = List.of();
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                0, EvictionPolicy.DROP_MIDDLE, null, store, EMBED, "agent-7", telemetry);

        m.recallRelevant("something relevant", 5);

        List<RecordingTelemetry.RecordedSpan> spans = telemetry.spansNamed("memory.recall");
        assertEquals(1, spans.size());
        assertEquals(0L, spans.get(0).attributes().get("recalled_count"));
    }

    @Test
    void recallDisabledWithoutOffloadEmitsNoSpanAtAll() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        SlidingWindowMemoryManager m = new SlidingWindowMemoryManager(
                0, EvictionPolicy.DROP_MIDDLE, null, null, null, null, telemetry);
        // no offload store wired — recall is inert, and not even attempted

        m.recallRelevant("something relevant", 5);

        assertTrue(telemetry.spansNamed("memory.recall").isEmpty());
    }
}
