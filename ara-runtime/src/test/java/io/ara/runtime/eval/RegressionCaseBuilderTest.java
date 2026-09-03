package io.ara.runtime.eval;

import io.ara.core.eval.EvalCase;
import io.ara.core.trace.BlobStore;
import io.ara.core.trace.InMemoryBlobStore;
import io.ara.core.trace.InMemoryTraceStore;
import io.ara.core.trace.SpanStatus;
import io.ara.core.trace.TraceSpan;
import io.ara.core.trace.TraceStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0071 — {@link RegressionCaseBuilder}: the input is recovered byte-for-byte from the
 * content-addressed trace (ADR-0068), a verifier is auto-derived only for a structurally
 * recognisable failure, and everything else is born {@link EvalCase.Status#DRAFT}.
 */
class RegressionCaseBuilderTest {

    private static final Instant T0 = Instant.parse("2026-09-04T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-04T10:00:03Z");

    /** A one-span run: a root span with the given failure reason (or Completed if reason is null). */
    private static String seedRun(TraceStore traces, BlobStore blobs, String prompt, String failureReason) {
        String runId = "run-" + Integer.toHexString(prompt.hashCode());
        String promptRef = blobs.put(prompt.getBytes(StandardCharsets.UTF_8));
        SpanStatus status = failureReason == null ? new SpanStatus.Completed() : new SpanStatus.Failed(failureReason);
        traces.append(TraceSpan.builder(runId, "agent-1#0", "agent-1")
                .promptRef(promptRef).startedAt(T0).endedAt(T1).status(status).build());
        return runId;
    }

    @Test
    void structuralFailure_yieldsAReadyCaseWithAnAutoVerifier() {
        TraceStore traces = new InMemoryTraceStore();
        BlobStore blobs = new InMemoryBlobStore();
        String runId = seedRun(traces, blobs, "classify: my invoice is wrong",
                "output is not valid JSON: missing required field 'intent'");

        EvalCase c = RegressionCaseBuilder.from(runId, traces, blobs, "regression");

        assertEquals("production_failure", c.origin());
        assertEquals("classify: my invoice is wrong", c.input(), "input recovered byte-for-byte from BlobStore");
        assertEquals(EvalCase.Status.READY, c.status());
        assertEquals("assertion", c.evaluationStrategy());
        assertTrue(c.countsTowardVerdict());
        assertTrue(!c.holdout(), "a regression case is always seen");
        assertEquals(runId, c.context().get("run_id"));
    }

    @Test
    void semanticFailure_yieldsADraftCaseWithNoVerifier() {
        TraceStore traces = new InMemoryTraceStore();
        BlobStore blobs = new InMemoryBlobStore();
        String runId = seedRun(traces, blobs, "summarise this ticket",
                "the summary omitted the customer's actual problem");

        EvalCase c = RegressionCaseBuilder.from(runId, traces, blobs, "regression");

        assertEquals(EvalCase.Status.DRAFT, c.status());
        assertEquals(RegressionCaseBuilder.PENDING_VERIFIER, c.evaluationStrategy());
        assertTrue(!c.countsTowardVerdict(), "a DRAFT case is excluded from verdict");
    }

    @Test
    void noFailureSpan_yieldsADraftCase() {
        TraceStore traces = new InMemoryTraceStore();
        BlobStore blobs = new InMemoryBlobStore();
        String runId = seedRun(traces, blobs, "do something", null);   // Completed, not Failed

        assertEquals(EvalCase.Status.DRAFT,
                RegressionCaseBuilder.from(runId, traces, blobs, "regression").status());
    }

    @Test
    void everyCallProducesExactlyOneDistinctEntry() {
        TraceStore traces = new InMemoryTraceStore();
        BlobStore blobs = new InMemoryBlobStore();
        String runId = seedRun(traces, blobs, "p", "schema mismatch");

        EvalCase a = RegressionCaseBuilder.from(runId, traces, blobs, "regression");
        EvalCase b = RegressionCaseBuilder.from(runId, traces, blobs, "regression");
        assertTrue(!a.caseId().equals(b.caseId()), "each conversion is a discrete, countable entry (ADR-0071 D5)");
    }

    @Test
    void unbuildableRuns_failLoudly() {
        TraceStore traces = new InMemoryTraceStore();
        BlobStore blobs = new InMemoryBlobStore();

        // no trace at all
        assertThrows(IllegalStateException.class,
                () -> RegressionCaseBuilder.from("ghost", traces, blobs, "regression"));

        // a span whose promptRef points nowhere
        traces.append(TraceSpan.builder("run-x", "s#0", "a").startedAt(T0).endedAt(T1)
                .promptRef("deadbeef").build());
        assertThrows(IllegalStateException.class,
                () -> RegressionCaseBuilder.from("run-x", traces, blobs, "regression"));

        // a run with no root span (the only span has a parent)
        traces.append(TraceSpan.builder("run-y", "child#0", "a").parentSpanId("missing-parent")
                .startedAt(T0).endedAt(T1).promptRef(blobs.put("x".getBytes(StandardCharsets.UTF_8))).build());
        assertThrows(IllegalStateException.class,
                () -> RegressionCaseBuilder.from("run-y", traces, blobs, "regression"));
    }
}
