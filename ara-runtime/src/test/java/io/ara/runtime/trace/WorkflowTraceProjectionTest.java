package io.ara.runtime.trace;

import io.ara.core.trace.BlobStore;
import io.ara.core.trace.SpanStatus;
import io.ara.core.trace.TraceSpan;
import io.ara.core.trace.TraceStore;
import io.ara.runtime.workflow.JournalEntry;
import io.ara.runtime.workflow.NodeOutcome;
import io.ara.runtime.workflow.WorkflowResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0068 D1 (2nd emission point): a workflow journal projects to one {@link TraceSpan}
 * per node occurrence, keyed {@code nodeId#occurrence}, with status from the
 * {@link NodeOutcome} and {@code failureKind} classified on a failure (ADR-0074 D6).
 */
class WorkflowTraceProjectionTest {

    private final BlobStore blobs = BlobStore.inMemory();

    private static JournalEntry.Finished done(String node, int occ, String in, String out) {
        return new JournalEntry.Finished(node, occ, in, new NodeOutcome.Completed(out, List.of()));
    }

    private String blobText(String ref) {
        return new String(blobs.get(ref).orElseThrow(), StandardCharsets.UTF_8);
    }

    @Test
    void oneSpanPerOccurrence_withStatusAndBlobsFromTheJournal() {
        WorkflowResult result = new WorkflowResult(List.of(
                new JournalEntry.Started("classify", 0, "the request"),
                done("classify", 0, "the request", "category: bug"),
                new JournalEntry.Started("fix", 0, "category: bug"),
                new JournalEntry.Finished("fix", 0, "category: bug", new NodeOutcome.Failed("patch did not apply")),
                new JournalEntry.Started("fix", 1, "category: bug (retry)"),
                done("fix", 1, "category: bug (retry)", "patched")
        ), true, null);

        List<TraceSpan> spans = WorkflowTraceProjection.project("run-wf-1", result, blobs);

        assertEquals(List.of("classify#0", "fix#0", "fix#1"),
                spans.stream().map(TraceSpan::spanId).toList());
        assertTrue(spans.stream().allMatch(s -> s.runId().equals("run-wf-1") && s.parentSpanId() == null));

        TraceSpan classify = spans.get(0);
        assertInstanceOf(SpanStatus.Completed.class, classify.status());
        assertEquals("the request", blobText(classify.promptRef()));
        assertEquals("category: bug", blobText(classify.outputRef()));

        TraceSpan failed = spans.get(1);
        SpanStatus.Failed f = assertInstanceOf(SpanStatus.Failed.class, failed.status());
        assertEquals("patch did not apply", f.reason());
        assertEquals("OTHER", failed.failureKind());
        assertNull(failed.outputRef());

        assertInstanceOf(SpanStatus.Completed.class, spans.get(2).status());
    }

    @Test
    void aStartedEntryWithNoFinishedBecomesASuspendedSpan() {
        WorkflowResult result = new WorkflowResult(List.of(
                done("a", 0, "in", "out"),
                new JournalEntry.Started("b", 0, "handed to b")   // crashed in flight
        ), false, "process crashed");

        List<TraceSpan> spans = WorkflowTraceProjection.project("run-wf-2", result, blobs);

        assertInstanceOf(SpanStatus.Completed.class, spans.get(0).status());
        SpanStatus.Suspended s = assertInstanceOf(SpanStatus.Suspended.class, spans.get(1).status());
        assertTrue(s.reason().contains("no Finished entry"));
        assertEquals("handed to b", blobText(spans.get(1).promptRef()));
    }

    @Test
    void aSuspendedOutcomeIsCarriedThrough() {
        WorkflowResult result = new WorkflowResult(List.of(
                new JournalEntry.Started("approve", 0, "needs sign-off"),
                new JournalEntry.Finished("approve", 0, "needs sign-off",
                        new NodeOutcome.Suspended("awaiting approval decision"))
        ), false, "suspended");

        TraceSpan span = WorkflowTraceProjection.project("run-wf-3", result, blobs).get(0);
        SpanStatus.Suspended s = assertInstanceOf(SpanStatus.Suspended.class, span.status());
        assertEquals("awaiting approval decision", s.reason());
    }

    @Test
    void emitAppendsTheTraceQueryableByRunId() {
        TraceStore traces = TraceStore.inMemory();
        WorkflowResult result = new WorkflowResult(List.of(
                done("only", 0, "in", "out")), true, null);

        WorkflowTraceProjection.emit("run-wf-4", result, traces, blobs);

        assertEquals(1, traces.findByRunId("run-wf-4").size());
        assertEquals("only#0", traces.findByRunId("run-wf-4").get(0).spanId());
    }

    @Test
    void emitSwallowsAProjectionFailure() {
        TraceStore traces = TraceStore.inMemory();
        BlobStore broken = new BlobStore() {
            @Override public String put(byte[] content) { throw new RuntimeException("disk full"); }
            @Override public java.util.Optional<byte[]> get(String ref) { return java.util.Optional.empty(); }
        };
        WorkflowResult result = new WorkflowResult(List.of(done("n", 0, "in", "out")), true, null);

        WorkflowTraceProjection.emit("run-wf-5", result, traces, broken);   // must not throw

        assertTrue(traces.findByRunId("run-wf-5").isEmpty());
    }
}
