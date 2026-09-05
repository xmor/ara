package io.ara.runtime.workflow;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.budget.RunBudget;
import io.ara.core.common.AgentId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-052 D7 — hosting a {@link Workflow} as a real {@code AgentInstance} via {@link
 * WorkflowAgents}, the same move {@code PipelineAgentsTest} verifies for {@code
 * AgentPipeline}.
 */
class WorkflowAgentsTest {

    @Test
    void of_workflow_executesSuccessfully_andReturnsTheLastFinishedNodesOutput() {
        Workflow workflow = Workflow.of()
                .node("a", in -> "A")
                .node("b", in -> in + "-B")
                .edge("a", "b")
                .build();

        AraAgent agent = WorkflowAgents.of(workflow);
        AgentTask task = AgentTask.of("go");
        AgentResponse response = agent.execute(task);

        assertTrue(response.isSuccess());
        assertEquals("A-B", response.content());
        assertEquals(task.taskId(), response.taskId(),
                "the outer task's taskId must be preserved end-to-end");
    }

    @Test
    void failure_path_producesFailureResponse() {
        Workflow workflow = Workflow.of()
                .node("a", in -> { throw new RuntimeException("boom"); })
                .build();

        AraAgent agent = WorkflowAgents.of(workflow);
        AgentResponse response = agent.execute(AgentTask.of("go"));

        assertFalse(response.isSuccess());
        assertTrue(response.failureReason().contains("boom"));
    }

    @Test
    void aBudgetBreach_surfacesAsAFailureResponse() {
        Workflow workflow = Workflow.of()
                .node("a", in -> "A").node("b", in -> "B").edge("a", "b")
                .budget(RunBudget.of().maxActivations(1).build())
                .build();

        AraAgent agent = WorkflowAgents.of(workflow);
        AgentResponse response = agent.execute(AgentTask.of("go"));

        assertFalse(response.isSuccess());
        assertTrue(response.failureReason().contains("ACTIVATIONS"), response.failureReason());
    }

    @Test
    void explicitConfig_withNonDefaultPlannerStrategyName_stillResolvesCorrectly() {
        Workflow workflow = Workflow.of().node("only", in -> "ok").build();

        AgentConfig config = AgentConfig.defaults()
                .agentId(AgentId.of("custom"))
                .agentType("workflow")
                .plannerStrategy("some-unrelated-name")   // deliberately not "workflow"
                .build();

        AraAgent agent = WorkflowAgents.of(AgentId.of("custom"), config, workflow);
        AgentResponse response = agent.execute(AgentTask.of("go"));

        assertTrue(response.isSuccess(), "must resolve regardless of the config's plannerStrategy value");
        assertEquals("ok", response.content());
    }
}
