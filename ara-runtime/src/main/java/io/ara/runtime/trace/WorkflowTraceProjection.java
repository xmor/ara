package io.ara.runtime.trace;

import io.ara.core.common.Money;
import io.ara.core.trace.BlobStore;
import io.ara.core.trace.SpanStatus;
import io.ara.core.trace.TraceSpan;
import io.ara.core.trace.TraceStore;
import io.ara.runtime.agent.FailureKind;
import io.ara.runtime.workflow.JournalEntry;
import io.ara.runtime.workflow.NodeOutcome;
import io.ara.runtime.workflow.WorkflowResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Projects a completed {@link WorkflowResult}'s journal into a run trace — the second of
 * ADR-0068 D1's two emission points ("on each journal entry written, a {@code TraceSpan}
 * is derived"). Done post-hoc from the journal the scheduler already returns, so nothing
 * in {@code DataflowScheduler} changes.
 *
 * <p><b>Shape.</b> One {@link TraceSpan} per node occurrence ({@code spanId =
 * "<nodeId>#<occurrence>"}, {@code agentId = nodeId} — a {@code WorkflowNode} is a
 * function, it carries no agent identity), with:
 * <ul>
 *   <li>{@code promptRef} = the occurrence's {@code input} (from its {@code Started} or
 *       {@code Finished} entry), content-addressed;</li>
 *   <li>{@code status} / {@code outputRef} from the {@link NodeOutcome}:
 *       {@code Completed} → {@code Completed} + output blob; {@code Failed} →
 *       {@code Failed(reason)} + {@code failureKind} (ADR-0074 D6); {@code Suspended} →
 *       {@code Suspended(reason)};</li>
 *   <li>a {@code Started} with no matching {@code Finished} (a node in flight at a crash) →
 *       {@code Suspended} ("uncertain resume", ADR-052 D1).</li>
 * </ul>
 *
 * <p>The journal carries no tokens, cost or per-node timestamps, so those are zero and a
 * single projection-time {@link Instant} is used for {@code startedAt}/{@code endedAt}.
 * Spans are flat ({@code parentSpanId == null}) — the journal has no nesting.
 */
public final class WorkflowTraceProjection {

    private WorkflowTraceProjection() {}

    /** The spans for one workflow run, in journal order. {@code runId} is the run's correlation id. */
    public static List<TraceSpan> project(String runId, WorkflowResult result, BlobStore blobs) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(blobs, "blobs must not be null");

        Instant now = Instant.now();
        Map<String, Occurrence> byKey = new LinkedHashMap<>();   // insertion order == journal order
        for (JournalEntry entry : result.journal()) {
            Occurrence occ = byKey.computeIfAbsent(
                    entry.nodeId() + "#" + entry.occurrence(),
                    k -> new Occurrence(entry.nodeId(), entry.occurrence(), entry.input()));
            if (entry instanceof JournalEntry.Finished finished) {
                occ.outcome = finished.outcome();
            }
        }

        List<TraceSpan> spans = new ArrayList<>(byKey.size());
        for (Occurrence occ : byKey.values()) {
            TraceSpan.Builder span = TraceSpan.builder(runId, occ.nodeId + "#" + occ.occurrence, occ.nodeId)
                    .promptRef(ref(blobs, occ.input))
                    .cost(Money.ZERO_EUR)
                    .startedAt(now)
                    .endedAt(now);

            if (occ.outcome == null) {
                span.status(new SpanStatus.Suspended(
                        "in flight — no Finished entry in the journal (uncertain resume, ADR-052 D1)"));
            } else if (occ.outcome instanceof NodeOutcome.Completed completed) {
                span.status(new SpanStatus.Completed()).outputRef(ref(blobs, completed.content()));
            } else if (occ.outcome instanceof NodeOutcome.Failed failed) {
                span.status(new SpanStatus.Failed(failed.reason()))
                        .failureKind(FailureKind.classify(failed.reason()).name());   // ADR-0074 D6
            } else if (occ.outcome instanceof NodeOutcome.Suspended suspended) {
                span.status(new SpanStatus.Suspended(suspended.reason()));
            }
            spans.add(span.build());
        }
        return spans;
    }

    /** Projects and appends the trace for one workflow run — best-effort, never throws to the caller. */
    public static void emit(String runId, WorkflowResult result, TraceStore traces, BlobStore blobs) {
        Objects.requireNonNull(traces, "traces must not be null");
        try {
            project(runId, result, blobs).forEach(traces::append);
        } catch (RuntimeException ignored) {
            // trace emission must never break a workflow run
        }
    }

    private static String ref(BlobStore blobs, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        return blobs.put(content.getBytes(StandardCharsets.UTF_8));
    }

    private static final class Occurrence {
        final String nodeId;
        final int occurrence;
        final String input;
        NodeOutcome outcome;   // null until a Finished entry is seen

        Occurrence(String nodeId, int occurrence, String input) {
            this.nodeId = nodeId;
            this.occurrence = occurrence;
            this.input = input;
        }
    }
}
