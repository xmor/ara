package io.ara.core.trace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-local {@link TraceStore}: one append-only {@link CopyOnWriteArrayList} per
 * {@code runId}. {@link #findByRunId} returns an immutable snapshot in append order.
 */
public final class InMemoryTraceStore implements TraceStore {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<TraceSpan>> byRun = new ConcurrentHashMap<>();

    @Override
    public void append(TraceSpan span) {
        Objects.requireNonNull(span, "span must not be null");
        byRun.computeIfAbsent(span.runId(), k -> new CopyOnWriteArrayList<>()).add(span);
    }

    @Override
    public List<TraceSpan> findByRunId(String runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        CopyOnWriteArrayList<TraceSpan> spans = byRun.get(runId);
        return spans == null ? List.of() : List.copyOf(spans);
    }

    @Override
    public List<TraceSpan> findSince(Instant since) {
        Objects.requireNonNull(since, "since must not be null");
        List<TraceSpan> out = new ArrayList<>();
        for (CopyOnWriteArrayList<TraceSpan> spans : byRun.values()) {
            for (TraceSpan span : spans) {
                if (!span.startedAt().isBefore(since)) {
                    out.add(span);
                }
            }
        }
        return List.copyOf(out);
    }
}
