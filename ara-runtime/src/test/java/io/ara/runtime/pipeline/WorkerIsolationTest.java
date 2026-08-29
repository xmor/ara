package io.ara.runtime.pipeline;

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
import java.util.Map;

import static io.ara.runtime.pipeline.PipelineTestAgents.echoAgent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the isolation {@link AgentPipeline.Builder#worker(String, AraAgent)} gives its
 * step, added alongside the classify-and-act tiers: a worker sees the run's {@link
 * RunState} (so it can read what a classifier wrote) but its own writes are private to
 * that one call, never visible to the caller, a sibling worker, or a later run.
 *
 * <p>{@code step()} keeps the single shared {@code RunState} — see {@code
 * AgentPipelineTest.state_writtenByOneStep_isVisibleToALaterStepsInputShaper} — so these
 * tests exist specifically to pin the opposite, deliberately narrower contract that
 * {@code worker()} alone carries.
 */
class WorkerIsolationTest {

    /** Puts one entry under a fixed key, then returns whatever {@code output} was given. */
    private static AraAgent stateWriter(String id, String key, String value, String output) {
        AgentId agentId = AgentId.of(id);
        return new AraAgent() {
            @Override public AgentId agentId() { return agentId; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                task.runContext().state().put(key, value);
                return AgentResponse.success(task.taskId(), agentId, output, 1, 0, 0, Duration.ofMillis(1), List.of());
            }
            @Override public void terminate() {}
        };
    }

    private static AgentTask taskWithState() {
        return AgentTask.of("start").withRunContext(new RunContext(Map.of(), Map.of(), RunState.inMemory()));
    }

    @Test
    void a_worker_reads_state_a_classifier_wrote_before_it() {
        PipelineTestAgents.CapturingAgent worker = new PipelineTestAgents.CapturingAgent(AgentId.of("worker"), "done");

        AgentPipeline pipeline = AgentPipeline.builder()
                .step("classify", stateWriter("classify", "intent", "BILLING", "{\"intent\":\"BILLING\"}"))
                .route("classify", execution -> "billing")
                .worker("billing", new AraAgent() {
                    final AgentId id = AgentId.of("billing");
                    @Override public AgentId agentId() { return id; }
                    @Override public AgentConfig config() { return null; }
                    @Override public AgentState currentState() { return AgentState.IDLE; }
                    @Override public AgentResponse execute(AgentTask task) {
                        String seen = task.runContext().state().get("intent", String.class).orElse("MISSING");
                        return AgentResponse.success(task.taskId(), id, seen, 1, 0, 0, Duration.ofMillis(1), List.of());
                    }
                    @Override public void terminate() {}
                })
                .build();

        PipelineResult result = pipeline.run(taskWithState());

        assertEquals("BILLING", result.finalOutput(), "the worker must see what an earlier tier wrote to state");
    }

    @Test
    void a_workers_own_state_writes_are_not_visible_to_the_caller_after_the_run() {
        AgentTask task = taskWithState();

        AgentPipeline pipeline = AgentPipeline.builder()
                .worker("billing", stateWriter("billing", "sideEffect", "leaked", "billing handled"))
                .build();

        PipelineResult result = pipeline.run(task);

        assertTrue(result.success());
        assertEquals("billing handled", result.finalOutput());
        assertTrue(task.runContext().state().get("sideEffect", String.class).isEmpty(),
                "a worker's own writes must be contained to its own call, never visible on the outer task");
    }

    @Test
    void a_worker_cannot_overwrite_the_classification_bookkeeping_a_caller_still_relies_on() {
        AgentTask task = taskWithState();
        task.runContext().state().put("intent", "BILLING");

        AgentPipeline pipeline = AgentPipeline.builder()
                .worker("billing", stateWriter("billing", "intent", "CORRUPTED", "billing handled"))
                .build();

        pipeline.run(task);

        assertEquals("BILLING", task.runContext().state().get("intent", String.class).orElseThrow(),
                "the worker's overwrite must not escape into the caller's own RunState");
    }
}
