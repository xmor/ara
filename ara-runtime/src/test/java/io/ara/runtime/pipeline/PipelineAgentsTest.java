package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionBusyPolicy;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.ara.runtime.pipeline.PipelineTestAgents.CapturingAgent;
import static io.ara.runtime.pipeline.PipelineTestAgents.echoAgent;
import static io.ara.runtime.pipeline.PipelineTestAgents.failingAgent;
import static io.ara.runtime.pipeline.PipelineTestAgents.tokenAgent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link PipelineAgents} as a real {@code AgentInstance}-hosted agent
 * (via {@link PipelineStrategy}), specifically the two properties a hand-rolled
 * {@code AraAgent} implementation didn't have: per-session concurrency (ADR-016)
 * and full {@link AgentTask} propagation into every pipeline step.
 */
class PipelineAgentsTest {

    @Test
    void of_pipeline_executesSuccessfully_andReturnsLastStepResponse() {
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("first",  echoAgent("first",  "after-first"))
                .step("second", echoAgent("second", "after-second"))
                .build();

        AraAgent agent = PipelineAgents.of(pipeline);
        AgentTask task = AgentTask.of("go");
        AgentResponse response = agent.execute(task);

        assertTrue(response.isSuccess());
        assertEquals("after-second", response.content());
        assertEquals(task.taskId(), response.taskId(),
                "the outer task's taskId must be preserved end-to-end");
    }

    @Test
    void tokensAggregateAcrossSteps_onTheOuterAgentResponse_notJustTheLastStep() {
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("first",  tokenAgent("first",  "A", 100, 50, 0.02))
                .step("second", tokenAgent("second", "B", 200, 75, 0.03))
                .build();

        AraAgent agent = PipelineAgents.of(pipeline);
        AgentResponse response = agent.execute(AgentTask.of("go"));

        assertTrue(response.isSuccess());
        assertEquals(300, response.inputTokens(),
                "the outer pipeline agent's own response must sum every step's tokens");
        assertEquals(125, response.outputTokens());
    }

    @Test
    void failure_path_producesFailureResponse() {
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("a", echoAgent("a", "ok"))
                .step("b", failingAgent("b", "something went wrong"))
                .build();

        AraAgent agent = PipelineAgents.of(pipeline);
        AgentResponse response = agent.execute(AgentTask.of("go"));

        assertFalse(response.isSuccess());
        assertTrue(response.failureReason().contains("something went wrong"));
    }

    @Test
    void maxStepsFailure_stillCarriesLastStepsRealOutput() {
        // A looping step that never routes to null hits AgentPipeline's own maxSteps
        // cap — a genuine failure, but one where the last step DID run successfully
        // and produced real content. That content must reach the caller's
        // AgentResponse instead of being silently discarded as "".
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("loop", echoAgent("loop", "best answer so far"))
                .route("loop", Set.of("loop"), execution -> "loop")
                .maxSteps(3)
                .build();

        AraAgent agent = PipelineAgents.of(pipeline);
        AgentResponse response = agent.execute(AgentTask.of("go"));

        assertFalse(response.isSuccess());
        assertTrue(response.failureReason().contains("maximum step count"));
        assertEquals("best answer so far", response.content(),
                "the last successfully-executed step's output must survive onto a failed AgentResponse");
    }

    // ── Compromise #1: task context propagation ─────────────────────────────────

    @Test
    void attachments_context_and_sessionId_propagateIntoEveryStep() {
        record SecurityContext(String clearance) {}
        SecurityContext scx = new SecurityContext("clearance-3");

        CapturingAgent first  = new CapturingAgent(AgentId.of("first"),  "first-output");
        CapturingAgent second = new CapturingAgent(AgentId.of("second"), "second-output");

        AgentPipeline pipeline = AgentPipeline.builder()
                .step("first",  first)
                .step("second", second)
                .build();

        AraAgent agent = PipelineAgents.of(pipeline);
        AgentTask task = AgentTask.of("go")
                .withSessionId(SessionId.of("session-42"))
                .withContextEntry("locale", "it-IT")
                .withAttachment("securityContext", scx);

        AgentResponse response = agent.execute(task);
        assertTrue(response.isSuccess());

        for (CapturingAgent step : List.of(first, second)) {
            assertNotNull(step.received, "step must have received a task");
            assertSame(scx, step.received.runContext().opaque("securityContext", SecurityContext.class),
                    "attachment must survive into step [" + step.agentId.value() + "]");
            assertEquals("it-IT", step.received.runContext().promptVar("locale"));
            // sessionId is not part of the outer AgentTask that reaches the step agent's own
            // execute() (each step is a full AraAgent with its own session handling), but the
            // pipeline-level task built via withInput(...) still carries it through unchanged.
            assertEquals(SessionId.of("session-42"), step.received.sessionId());
        }
        // Only input() varies between steps — first sees the pipeline's initial input,
        // second sees first's output.
        assertEquals("go",           first.received.input());
        assertEquals("first-output", second.received.input());
    }

    // ── Compromise #2: per-session concurrency ──────────────────────────────────

    @Test
    void differentSessions_runTrulyConcurrently_notSerialized() throws Exception {
        ConcurrencyProbeAgent probe = new ConcurrencyProbeAgent();
        AgentPipeline pipeline = AgentPipeline.builder().step("probe", probe).build();
        AraAgent agent = PipelineAgents.of(pipeline);

        CompletableFuture<AgentResponse> f1 = CompletableFuture.supplyAsync(() ->
                agent.execute(AgentTask.of("a").withSessionId(SessionId.of("s1"))));
        CompletableFuture<AgentResponse> f2 = CompletableFuture.supplyAsync(() ->
                agent.execute(AgentTask.of("b").withSessionId(SessionId.of("s2"))));

        // Both must be inside execute() at the same time — impossible with the old
        // single-shared-AgentState PipelineAgent implementation, which would have thrown
        // IllegalStateException("not IDLE") on the second concurrent call regardless
        // of session.
        assertTrue(probe.bothEnteredWithin(5, TimeUnit.SECONDS),
                "two different sessions must be able to execute concurrently");
        probe.release();

        assertTrue(f1.get(5, TimeUnit.SECONDS).isSuccess());
        assertTrue(f2.get(5, TimeUnit.SECONDS).isSuccess());
    }

    @Test
    void sameSession_concurrentCall_rejectedFastUnderRejectPolicy() throws Exception {
        BlockingAgent blocking = new BlockingAgent(AgentId.of("blocking"));
        AgentPipeline pipeline = AgentPipeline.builder().step("blocking", blocking).build();

        AgentConfig config = AgentConfig.defaults()
                .agentId(AgentId.of("rejecting-pipeline"))
                .agentType("pipeline")
                .sessionBusyPolicy(SessionBusyPolicy.REJECT)
                .build();
        AraAgent agent = PipelineAgents.of(AgentId.of("rejecting-pipeline"), config, pipeline);

        SessionId sameSession = SessionId.of("shared-session");
        CompletableFuture<AgentResponse> inFlight = CompletableFuture.supplyAsync(() ->
                agent.execute(AgentTask.of("first").withSessionId(sameSession)));
        assertTrue(blocking.awaitStarted(5, TimeUnit.SECONDS));

        // Second call on the SAME session must fail fast, not queue behind the first.
        AgentResponse rejected = agent.execute(AgentTask.of("second").withSessionId(sameSession));
        assertFalse(rejected.isSuccess());
        assertTrue(rejected.failureReason().toLowerCase().contains("busy"),
                "expected a session-busy rejection, got: " + rejected.failureReason());

        blocking.release();
        assertTrue(inFlight.get(5, TimeUnit.SECONDS).isSuccess());
    }

    // ── Registering under an arbitrary caller-supplied plannerStrategy ──────────

    @Test
    void explicitConfig_withNonDefaultPlannerStrategyName_stillResolvesCorrectly() {
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("only", echoAgent("only", "ok"))
                .build();

        AgentConfig config = AgentConfig.defaults()
                .agentId(AgentId.of("custom"))
                .agentType("pipeline")
                .plannerStrategy("some-unrelated-name")   // deliberately not "pipeline"
                .build();

        AraAgent agent = PipelineAgents.of(AgentId.of("custom"), config, pipeline);
        AgentResponse response = agent.execute(AgentTask.of("go"));

        assertTrue(response.isSuccess(), "must resolve regardless of the config's plannerStrategy value");
        assertEquals("ok", response.content());
    }

    // ── Composability ────────────────────────────────────────────────────────

    @Test
    void pipelineInPipeline_innerPipelineAgentComposesAsAnOrdinaryStep() {
        AgentPipeline inner = AgentPipeline.builder()
                .step("innerFirst",  echoAgent("innerFirst",  "inner-A"))
                .step("innerSecond", echoAgent("innerSecond", "inner-B"))
                .build();
        AraAgent innerAgent = PipelineAgents.of(inner);

        AgentPipeline outer = AgentPipeline.builder()
                .step("enrich", echoAgent("enrich", "enriched"))
                .step("nested", innerAgent)
                .step("format", echoAgent("format", "formatted"))
                .build();

        PipelineResult r = outer.run("start");

        assertTrue(r.success());
        assertEquals("formatted", r.finalOutput());
        assertEquals(List.of("enrich", "nested", "format"), r.stepsExecuted());
    }

    // ── NoopLlmClient ────────────────────────────────────────────────────────

    @Test
    void noopLlmClient_complete_throwsIfEverCalled() {
        // Unreachable through the public pipeline API (PipelineStrategy never calls it) —
        // this exists purely so that guarantee stays true if something changes later.
        PipelineAgents.NoopLlmClient client = new PipelineAgents.NoopLlmClient();
        assertThrows(UnsupportedOperationException.class,
                () -> client.complete(List.of(), (LlmCallContext) null));
    }

    // ── Stubs ─────────────────────────────────────────────────────────────────

    /** Blocks inside execute() until explicitly released; signals when it has started. */
    private static final class BlockingAgent implements AraAgent {
        final AgentId agentId;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        BlockingAgent(AgentId id) { this.agentId = id; }

        @Override public AgentId agentId() { return agentId; }
        @Override public AgentConfig config() { return null; }
        @Override public AgentState currentState() { return AgentState.IDLE; }
        @Override public AgentResponse execute(AgentTask task) {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentResponse.success(task.taskId(), agentId, "done", 1, 0, 0, Duration.ofMillis(1), List.of());
        }
        @Override public void terminate() {}

        boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return started.await(timeout, unit);
        }
        void release() { release.countDown(); }
    }

    /** Tracks how many concurrent invocations are inside execute() at once, then blocks until released. */
    private static final class ConcurrencyProbeAgent implements AraAgent {
        private final AgentId agentId = AgentId.of("probe");
        private final AtomicInteger concurrent = new AtomicInteger();
        private final CountDownLatch bothEntered = new CountDownLatch(2);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override public AgentId agentId() { return agentId; }
        @Override public AgentConfig config() { return null; }
        @Override public AgentState currentState() { return AgentState.IDLE; }
        @Override public AgentResponse execute(AgentTask task) {
            concurrent.incrementAndGet();
            bothEntered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentResponse.success(task.taskId(), agentId, "done", 1, 0, 0, Duration.ofMillis(1), List.of());
        }
        @Override public void terminate() {}

        boolean bothEnteredWithin(long timeout, TimeUnit unit) throws InterruptedException {
            return bothEntered.await(timeout, unit);
        }
        void release() { release.countDown(); }
    }
}
