package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentChain;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.RunState;
import io.ara.core.common.AgentId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static io.ara.runtime.pipeline.PipelineTestAgents.CapturingAgent;
import static io.ara.runtime.pipeline.PipelineTestAgents.echoAgent;
import static io.ara.runtime.pipeline.PipelineTestAgents.failingAgent;
import static io.ara.runtime.pipeline.PipelineTestAgents.tokenAgent;
import static org.junit.jupiter.api.Assertions.*;

class AgentPipelineTest {

    @Test
    void single_step_pipeline_succeeds() {
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("a", echoAgent("a-id", "result-A"))
                .build();
        PipelineResult r = pipeline.run("input");
        assertTrue(r.success());
        assertEquals("result-A", r.finalOutput());
        assertEquals(List.of("a"), r.stepsExecuted());
    }

    @Test
    void sequential_two_step_pipeline() {
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("first",  echoAgent("first",  "after-first"))
                .step("second", echoAgent("second", "after-second"))
                .build();
        PipelineResult r = pipeline.run("start");
        assertTrue(r.success());
        assertEquals("after-second", r.finalOutput());
        assertEquals(List.of("first", "second"), r.stepsExecuted());
    }

    @Test
    void tokensAndCost_areAggregatedAcrossAllSteps_notJustTheLast() {
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("first",  tokenAgent("first",  "A", 100, 50, 0.02))
                .step("second", tokenAgent("second", "B", 200, 75, 0.03))
                .build();

        PipelineResult r = pipeline.run("start");

        assertTrue(r.success());
        assertEquals(300, r.totalInputTokens());
        assertEquals(125, r.totalOutputTokens());
        assertEquals(425, r.totalTokens());
        assertEquals(0, r.totalCost().compareTo(io.ara.core.common.Money.of("0.05", "EUR")));
        // lastResponse() is unchanged: still only the last step's own response.
        assertEquals(200, r.lastResponse().inputTokens());
        assertEquals(75,  r.lastResponse().outputTokens());
    }

    @Test
    void output_of_step_becomes_input_of_next() {
        CapturingAgent second = new CapturingAgent(AgentId.of("second"), "done");
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("first",  echoAgent("first",  "first-output"))
                .step("second", second)
                .build();
        pipeline.run("initial");
        assertEquals("first-output", second.lastInput());
    }

    @Test
    void router_redirects_to_retry_step() {
        AtomicInteger calls = new AtomicInteger();
        AraAgent countingAgent = new AraAgent() {
            final AgentId id = AgentId.of("counter");
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                String out = "run-" + calls.incrementAndGet();
                return AgentResponse.success(task.taskId(), id, out, 1, 0, 0, Duration.ofMillis(1), List.of());
            }
            @Override public void terminate() {}
        };

        AgentPipeline pipeline = AgentPipeline.builder()
                .step("loop", countingAgent)
                .route("loop", execution -> calls.get() < 3 ? "loop" : null)
                .build();
        PipelineResult r = pipeline.run("go");
        assertTrue(r.success());
        assertEquals(3, calls.get());
        assertEquals("run-3", r.finalOutput());
    }

    @Test
    void attemptsOf_countsThatStepsPriorRuns_forABoundedRetryWithoutARunStateCounter() {
        AraAgent generate = echoAgent("generate", "INVALID");
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("generate", generate)
                .step("validate", echoAgent("validate", "still invalid"))
                .step("giveUp",   echoAgent("giveUp",   "gave up"))
                .route("validate", execution ->
                        execution.attemptsOf("generate") < 3 ? "generate" : "giveUp")
                .maxSteps(12)
                .build();

        PipelineResult r = pipeline.run("start");

        assertTrue(r.success(), "giveUp is a normal step, so the pipeline still succeeds");
        assertEquals("gave up", r.finalOutput());
        assertEquals(3, r.stepsExecuted().stream().filter("generate"::equals).count());
    }

    @Test
    void attemptsOf_isZero_forAStepThatHasNotRunYet() {
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("first", echoAgent("first", "out"))
                .route("first", execution -> {
                    assertEquals(0, execution.attemptsOf("never-declared"));
                    assertEquals(1, execution.attemptsOf("first"));
                    return null;
                })
                .build();

        assertTrue(pipeline.run("start").success());
    }

    @Test
    void pipeline_fails_when_step_fails() {
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("a", echoAgent("a", "ok"))
                .step("b", failingAgent("b", "something went wrong"))
                .step("c", echoAgent("c", "unreachable"))
                .build();
        PipelineResult r = pipeline.run("start");
        assertFalse(r.success());
        assertTrue(r.failureReason().contains("something went wrong"));
        assertFalse(r.stepsExecuted().contains("c"));
    }

    @Test
    void max_steps_guard() {
        AraAgent looping = echoAgent("loop", "out");
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("loop", looping)
                .route("loop", execution -> "loop")  // infinite loop
                .maxSteps(5)
                .build();
        PipelineResult r = pipeline.run("go");
        assertFalse(r.success());
        assertTrue(r.failureReason().contains("maximum step count"));
    }

    @Test
    void router_receives_full_execution_context() {
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("first",  echoAgent("first",  "first-output"))
                .step("second", echoAgent("second", "second-output"))
                .route("second", execution -> {
                    assertEquals("initial",      execution.initialInput());
                    assertEquals(2,              execution.stepCount());
                    assertEquals("second-output", execution.lastOutput());
                    assertTrue(execution.resultOf("first").isPresent());
                    assertEquals("first-output", execution.resultOf("first").get().output());
                    return null; // end pipeline
                })
                .build();
        PipelineResult r = pipeline.run("initial");
        assertTrue(r.success());
    }

    @Test
    void duplicate_step_name_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                AgentPipeline.builder()
                        .step("a", echoAgent("a", "out"))
                        .step("a", echoAgent("a2", "out"))
                        .build());
    }

    @Test
    void inputShaper_combinesTwoPriorNamedSteps() {
        CapturingAgent third = new CapturingAgent(AgentId.of("third"), "final");
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("first",  echoAgent("first",  "A"))
                .step("second", echoAgent("second", "B"))
                .step("third", third, execution -> execution.task().withInput(
                        execution.resultOf("first").get().output() + "+"
                        + execution.resultOf("second").get().output()))
                .build();
        PipelineResult r = pipeline.run("start");
        assertTrue(r.success());
        assertEquals("A+B", third.lastInput());
    }

    @Test
    void inputShaper_thatThrows_producesGracefulFailure_insteadOfCrashing() {
        // An input shaper referencing a step that was never declared (typo, or a step
        // that hasn't run yet) throws from .get() on an empty Optional. The pipeline must
        // report this as a normal failed PipelineResult, not let the exception escape run().
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("first", echoAgent("first", "A"))
                .step("second", echoAgent("second", "B"), execution ->
                        execution.task().withInput(execution.resultOf("nonexistent").get().output()))
                .build();

        PipelineResult r = pipeline.run("start");

        assertFalse(r.success());
        assertTrue(r.failureReason().contains("second"));
        assertTrue(r.failureReason().contains("input shaper threw"));
    }

    @Test
    void step_withoutInputShaper_keepsDefaultBehavior_previousOutputVerbatim() {
        // Regression guard: the 2-arg step(name, agent) overload must behave exactly
        // as before now that it delegates to the 3-arg one with input = null.
        CapturingAgent second = new CapturingAgent(AgentId.of("second"), "done");
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("first",  echoAgent("first", "first-output"))
                .step("second", second)
                .build();
        pipeline.run("initial");
        assertEquals("first-output", second.lastInput());
    }

    @Test
    void route_onUndeclaredStep_throws() {
        AgentPipeline.Builder builder = AgentPipeline.builder().step("a", echoAgent("a", "out"));
        assertThrows(IllegalArgumentException.class, () -> builder.route("nonexistent", execution -> null));
    }

    @Test
    void router_returningUndeclaredStepAtRuntime_failsGracefully() {
        // Unlike route(stepName, ...), which validates stepName at build time, a router's
        // *return value* is only known at runtime — a typo here must not crash run().
        AgentPipeline pipeline = AgentPipeline.builder()
                .step("a", echoAgent("a", "out-a"))
                .step("b", echoAgent("b", "out-b"))
                .route("a", execution -> "nonexistent")
                .build();

        PipelineResult r = pipeline.run("start");

        assertFalse(r.success());
        assertTrue(r.failureReason().contains("nonexistent"));
        assertTrue(r.failureReason().contains("declared steps"));
        assertTrue(r.failureReason().contains("a"));
        assertTrue(r.failureReason().contains("b"));
    }

    @Test
    void state_writtenByOneStep_isVisibleToALaterStepsInputShaper() {
        AraAgent writer = new AraAgent() {
            final AgentId id = AgentId.of("writer");
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                task.runContext().state().put("fromWriter", "hello-state");
                return AgentResponse.success(task.taskId(), id, "writer-output", 1, 0, 0, Duration.ofMillis(1), List.of());
            }
            @Override public void terminate() {}
        };
        CapturingAgent reader = new CapturingAgent(AgentId.of("reader"), "done");

        AgentPipeline pipeline = AgentPipeline.builder()
                .step("write", writer)
                .step("read", reader, execution -> execution.task().withInput(
                        execution.state().get("fromWriter", String.class).orElse("MISSING")))
                .build();

        AgentTask task = AgentTask.of("start")
                .withRunContext(RunContext.empty().withState(RunState.inMemory()));
        PipelineResult r = pipeline.run(task);

        assertTrue(r.success());
        assertEquals("hello-state", reader.lastInput(),
                "every step's task derives from the same original task, so state is shared by construction");
    }

    @Test
    void parallel_step_mergesMemberOutputs_andFeedsTheNextStep() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            CapturingAgent after = new CapturingAgent(AgentId.of("after"), "done");
            AgentPipeline pipeline = AgentPipeline.builder()
                    .parallel("fanout",
                            List.of(echoAgent("a", "A"), echoAgent("b", "B")),
                            executor, AgentChain.MergeStrategy.joining(","))
                    .step("after", after)
                    .build();

            PipelineResult r = pipeline.run("start");

            assertTrue(r.success());
            assertEquals("A,B", after.lastInput());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void parallel_step_failure_failsThePipeline() {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            AgentPipeline pipeline = AgentPipeline.builder()
                    .parallel("fanout",
                            List.of(echoAgent("ok", "fine"), failingAgent("bad", "boom")),
                            executor, AgentChain.MergeStrategy.joining(","))
                    .step("unreachable", echoAgent("unreachable", "never"))
                    .build();

            PipelineResult r = pipeline.run("start");

            assertFalse(r.success());
            assertTrue(r.failureReason().contains("boom"));
            assertFalse(r.stepsExecuted().contains("unreachable"));
        } finally {
            executor.shutdownNow();
        }
    }
}
