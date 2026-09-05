package io.ara.runtime.workflow;

import io.ara.core.agent.AgentChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-052 D4 — dynamic fan-out: a node's output determines, at runtime, how many
 * activations of a worker function run, each with its own input, individually
 * journaled — not the same task replicated ({@code ParallelAgent}'s limit).
 */
class WorkflowMapOverTest {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void onePerElement_eachWithItsOwnInput_fullCollection() {
        Workflow wf = Workflow.of()
                .node("plan", in -> "sub-a,sub-b,sub-c")
                .mapOver("plan", "worker",
                        planOutput -> List.of(planOutput.split(",")),
                        element -> element.toUpperCase(java.util.Locale.ROOT),
                        "findings", 10, AgentChain.FailurePolicy.FAIL_FAST)
                .build();

        WorkflowResult result = wf.run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        @SuppressWarnings("unchecked")
        List<Object> findings = (List<Object>) result.state().get("findings");
        assertEquals(3, findings.size());
        assertTrue(findings.containsAll(List.of("SUB-A", "SUB-B", "SUB-C")));
    }

    @Test
    void eachChild_isJournaledIndividually() {
        Workflow wf = Workflow.of()
                .node("plan", in -> "a,b")
                .mapOver("plan", "worker", planOutput -> List.of(planOutput.split(",")),
                        element -> element, 10, AgentChain.FailurePolicy.FAIL_FAST)
                .build();

        WorkflowResult result = wf.run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        long childEntries = result.journal().stream()
                .filter(JournalEntry.Finished.class::isInstance)
                .filter(e -> e.nodeId().startsWith("worker["))
                .count();
        assertEquals(2, childEntries, "one Finished journal entry per element: " + result.order());
    }

    @Test
    void exceedingMaxActivations_failsTheRun_namingTheConstruct() {
        Workflow wf = Workflow.of()
                .node("plan", in -> "a,b,c")
                .mapOver("plan", "worker", planOutput -> List.of(planOutput.split(",")),
                        element -> element, 2, AgentChain.FailurePolicy.FAIL_FAST)
                .build();

        WorkflowResult result = wf.run("go", pool);

        assertFalse(result.ok());
        assertTrue(result.failureReason().contains("maxActivations"), result.failureReason());
        assertTrue(result.failureReason().contains("plan"), result.failureReason());
    }

    @Test
    void failFast_oneChildFailing_failsTheWholeGroup() {
        Workflow wf = Workflow.of()
                .node("plan", in -> "ok,boom,ok")
                .mapOver("plan", "worker", planOutput -> List.of(planOutput.split(",")),
                        element -> {
                            if (element.equals("boom")) {
                                throw new RuntimeException("element failed");
                            }
                            return element;
                        },
                        "findings", 10, AgentChain.FailurePolicy.FAIL_FAST)
                .build();

        WorkflowResult result = wf.run("go", pool);

        assertFalse(result.ok());
        assertTrue(result.failureReason().contains("element failed"), result.failureReason());
    }

    @Test
    void partialOk_keepsSuccessfulChildren_ignoresTheFailedOne() {
        Workflow wf = Workflow.of()
                .node("plan", in -> "ok1,boom,ok2")
                .mapOver("plan", "worker", planOutput -> List.of(planOutput.split(",")),
                        element -> {
                            if (element.equals("boom")) {
                                throw new RuntimeException("element failed");
                            }
                            return element;
                        },
                        "findings", 10, AgentChain.FailurePolicy.PARTIAL_OK)
                .build();

        WorkflowResult result = wf.run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        @SuppressWarnings("unchecked")
        List<Object> findings = (List<Object>) result.state().get("findings");
        assertEquals(2, findings.size());
        assertTrue(findings.containsAll(List.of("ok1", "ok2")));
    }

    @Test
    void partialOk_everyChildFailing_stillFailsTheRun() {
        Workflow wf = Workflow.of()
                .node("plan", in -> "boom1,boom2")
                .mapOver("plan", "worker", planOutput -> List.of(planOutput.split(",")),
                        element -> { throw new RuntimeException(element); },
                        10, AgentChain.FailurePolicy.PARTIAL_OK)
                .build();

        WorkflowResult result = wf.run("go", pool);

        assertFalse(result.ok());
    }

    @Test
    void anEmptyElementList_isNotAFailure_justNoActivations() {
        Workflow wf = Workflow.of()
                .node("plan", in -> "")
                .mapOver("plan", "worker", planOutput -> List.of(),
                        element -> element, "findings", 10, AgentChain.FailurePolicy.FAIL_FAST)
                .build();

        WorkflowResult result = wf.run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        assertFalse(result.state().containsKey("findings"), "nothing was ever written");
    }

    @Test
    void withNoCollectInto_childrenRunAndAreJournaled_butWriteNothing() {
        Workflow wf = Workflow.of()
                .node("plan", in -> "a,b")
                .mapOver("plan", "worker", planOutput -> List.of(planOutput.split(",")),
                        element -> element, 10, AgentChain.FailurePolicy.FAIL_FAST)
                .build();

        WorkflowResult result = wf.run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        assertTrue(result.state().isEmpty());
    }

    @Test
    void theSourceNodesOwnOutputAndEdges_stillWorkNormallyAfterMapOver() {
        Workflow wf = Workflow.of()
                .node("plan", in -> "a,b")
                .node("after", in -> in + "-done")
                .mapOver("plan", "worker", planOutput -> List.of(planOutput.split(",")),
                        element -> element, 10, AgentChain.FailurePolicy.FAIL_FAST)
                .edge("plan", "after")
                .build();

        WorkflowResult result = wf.run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        assertEquals("a,b", result.firstOf("after").input(), "plan's own output still flows to its declared edge");
        NodeOutcome.Completed afterOutcome = (NodeOutcome.Completed) result.firstOf("after").outcome();
        assertEquals("a,b-done", afterOutcome.content());
    }
}
