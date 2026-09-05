package io.ara.runtime.trace;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.common.Money;
import io.ara.core.eval.EvalCase;
import io.ara.core.trace.BlobStore;
import io.ara.core.trace.SpanStatus;
import io.ara.core.trace.TraceSpan;
import io.ara.core.trace.TraceStore;
import io.ara.runtime.eval.RegressionCaseBuilder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0068 D1 (deferred emission point): a single-agent execution is projected into a
 * run-id-queryable trace — a root span with the token/cost totals and the outcome, a
 * child span per {@link ExecutionStep} — with the root's {@code failureKind} classified on
 * a failure (ADR-0074 D6). Emission never breaks execution.
 */
class TraceEmittingAgentTest {

    private final TraceStore traces = TraceStore.inMemory();
    private final BlobStore blobs = BlobStore.inMemory();
    private final AgentId agentId = AgentId.of("reviewer-1");

    private AraAgent agent(Function<AgentTask, AgentResponse> behaviour) {
        return new AraAgent() {
            @Override public AgentId agentId() { return agentId; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) { return behaviour.apply(task); }
            @Override public void terminate() {}
        };
    }

    private AgentResponse success(AgentTask task, String content, List<ExecutionStep> steps) {
        return new AgentResponse(task.taskId(), agentId, content, AgentState.DONE, steps.size(),
                120, 45, Money.of("0.0031", "EUR"), Duration.ofMillis(1500), null,
                Instant.parse("2026-09-04T12:00:00Z"), steps, "gpt-x");
    }

    private AgentResponse failure(AgentTask task, String reason) {
        return new AgentResponse(task.taskId(), agentId, "", AgentState.FAILED, 1,
                10, 0, Money.ZERO_EUR, Duration.ofMillis(200), reason,
                Instant.parse("2026-09-04T12:00:00Z"), List.of(), "gpt-x");
    }

    private String blobText(String ref) {
        return new String(blobs.get(ref).orElseThrow(), StandardCharsets.UTF_8);
    }

    @Test
    void successfulExecutionProducesARootSpanPlusOneChildPerStep() {
        AgentTask task = AgentTask.of("review this diff", Map.of(), "run-42", "tester");
        List<ExecutionStep> steps = List.of(
                ExecutionStep.thought("the diff changes the parser", 1),
                ExecutionStep.toolCall("read_file", "{\"path\":\"P.java\"}", 1),
                ExecutionStep.observation("file contents...", 1),
                ExecutionStep.finalAnswer("LGTM with one nit", 2));

        AraAgent wrapped = new TraceEmittingAgent(
                agent(t -> success(t, "LGTM with one nit", steps)), traces, blobs);
        AgentResponse response = wrapped.execute(task);

        assertEquals("LGTM with one nit", response.content(), "the response is passed through untouched");

        List<TraceSpan> spans = traces.findByRunId("run-42");
        assertEquals(1 + 4, spans.size());

        TraceSpan root = spans.get(0);
        assertEquals("reviewer-1#run", root.spanId());
        assertNull(root.parentSpanId());
        assertInstanceOf(SpanStatus.Completed.class, root.status());
        assertEquals(120, root.tokensIn());
        assertEquals(45, root.tokensOut());
        assertEquals(Money.of("0.0031", "EUR"), root.cost());
        assertEquals("review this diff", blobText(root.promptRef()));
        assertEquals("LGTM with one nit", blobText(root.outputRef()));
        assertNull(root.failureKind());

        assertTrue(spans.stream().skip(1).allMatch(s -> "reviewer-1#run".equals(s.parentSpanId())));
        TraceSpan toolStep = spans.get(2);
        assertTrue(blobText(toolStep.promptRef()).startsWith("read_file "));
    }

    @Test
    void aFailedResponseGivesAFailedRootWithAClassifiedFailureKind() {
        AgentTask task = AgentTask.of("do the thing", Map.of(), "run-err", "tester");
        new TraceEmittingAgent(agent(t -> failure(t, "Cost budget exceeded: 1.00 EUR")), traces, blobs)
                .execute(task);

        TraceSpan root = traces.findByRunId("run-err").get(0);
        SpanStatus.Failed failed = assertInstanceOf(SpanStatus.Failed.class, root.status());
        assertTrue(failed.reason().contains("Cost budget exceeded"));
        assertEquals("BUDGET_EXCEEDED", root.failureKind());
        assertNull(root.outputRef(), "a failed run has no output blob");
    }

    @Test
    void aThrownExecutionStillRecordsAFailedTraceAndRethrows() {
        AgentTask task = AgentTask.of("boom", Map.of(), "run-throw", "tester");
        AraAgent wrapped = new TraceEmittingAgent(
                agent(t -> { throw new IllegalStateException("kaboom"); }), traces, blobs);

        assertThrows(IllegalStateException.class, () -> wrapped.execute(task));

        TraceSpan root = traces.findByRunId("run-throw").get(0);
        assertInstanceOf(SpanStatus.Failed.class, root.status());
        assertEquals("UNEXPECTED_ERROR", root.failureKind());
    }

    @Test
    void runIdFallsBackFromCorrelationIdToSessionIdToTaskId() {
        AgentTask withCorrelation = AgentTask.of("x", Map.of(), "corr-1", "t");
        AgentTask withSession = AgentTask.of("x").withSessionId(SessionId.of("sess-1"));
        AgentTask bare = AgentTask.of("x");

        assertEquals("corr-1", TraceProjection.runIdOf(withCorrelation));
        assertEquals("sess-1", TraceProjection.runIdOf(withSession));
        assertEquals(bare.taskId(), TraceProjection.runIdOf(bare));
    }

    @Test
    void emissionFailureNeverBreaksExecution() {
        BlobStore brokenBlobs = new BlobStore() {
            @Override public String put(byte[] content) { throw new RuntimeException("disk full"); }
            @Override public java.util.Optional<byte[]> get(String ref) { return java.util.Optional.empty(); }
        };
        AgentTask task = AgentTask.of("x", Map.of(), "run-1", "t");

        AgentResponse response = new TraceEmittingAgent(
                agent(t -> success(t, "done", List.of())), traces, brokenBlobs).execute(task);

        assertEquals("done", response.content(), "execution result is returned despite the emission failure");
        assertTrue(traces.findByRunId("run-1").isEmpty());
    }

    @Test
    void staticEmitHelperWorksWithoutWrappingTheAgent() {
        AgentTask task = AgentTask.of("q", Map.of(), "run-static", "t");
        AgentResponse response = success(task, "a", List.of(ExecutionStep.thought("t", 1)));

        TraceEmittingAgent.emit(task, response, traces, blobs);

        assertEquals(2, traces.findByRunId("run-static").size());
    }

    @Test
    void anEmittedTraceCanSeedARegressionCase() {
        AgentTask task = AgentTask.of("parse this input", Map.of(), "run-bug", "t");
        new TraceEmittingAgent(
                agent(t -> failure(t, "schema validation failed: missing required field 'id'")), traces, blobs)
                .execute(task);

        EvalCase seeded = RegressionCaseBuilder.from("run-bug", traces, blobs, "regression-suite");

        assertEquals("parse this input", seeded.input());
        assertEquals("production_failure", seeded.origin());
        assertEquals(EvalCase.Status.READY, seeded.status(), "a structural failure derives a real verifier");
    }
}
