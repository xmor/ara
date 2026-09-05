package io.ara.runtime.workflow;

import io.ara.core.budget.RunBudget;
import io.ara.core.budget.Spend;
import io.ara.core.common.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-054 D7 — the {@link Workflow} builder facade: it assembles a {@link WorkflowGraph},
 * per-node cost, occurrence cap and {@link RunBudget} together, and runs them through a
 * fresh {@link DataflowScheduler} without the caller touching one directly.
 */
class WorkflowTest {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void buildsAndRunsALinearGraph() {
        WorkflowResult result = Workflow.of()
                .node("a", in -> "A")
                .node("b", in -> in + "B")
                .edge("a", "b")
                .build()
                .run("start", pool);

        assertTrue(result.ok(), result.failureReason());
        assertEquals("A", result.firstOf("b").input());
        assertEquals(2, result.journal().stream().filter(JournalEntry.Finished.class::isInstance).count());
    }

    @Test
    void budgetDeclaredOnTheBuilder_governsTheRun() {
        WorkflowResult result = Workflow.of()
                .node("a", in -> "A").node("b", in -> "B").node("c", in -> "C")
                .edge("a", "b").edge("b", "c")
                .budget(RunBudget.of().maxActivations(2).build())
                .build()
                .run("start", pool);

        assertFalse(result.ok());
        assertTrue(result.failureReason().contains("ACTIVATIONS"), result.failureReason());
        assertTrue(result.failureReason().contains("node c#0"), result.failureReason());
    }

    @Test
    void costFunctionAttachedViaTheBuilder_feedsTheBudget() {
        WorkflowResult result = Workflow.of()
                .node("a", in -> "A").cost("a", out -> Spend.of(Money.zero("EUR"), 80_000, 1))
                .node("b", in -> "B").cost("b", out -> Spend.of(Money.zero("EUR"), 80_000, 1))
                .edge("a", "b")
                .budget(RunBudget.of().maxTokens(100_000).build())
                .build()
                .run("start", pool);

        assertFalse(result.ok(), result.failureReason());
        assertTrue(result.failureReason().contains("TOKENS"), result.failureReason());
        assertTrue(result.failureReason().contains("node b#0"), result.failureReason());
    }

    @Test
    void duplicateNodeId_isRejected() {
        Workflow.Builder b = Workflow.of().node("a", in -> "A");
        assertThrows(IllegalArgumentException.class, () -> b.node("a", in -> "again"));
    }

    @Test
    void configuringAnUnknownNode_isRejected() {
        Workflow.Builder b = Workflow.of().node("a", in -> "A");
        assertThrows(IllegalArgumentException.class, () -> b.cost("ghost", out -> Spend.zero("EUR")));
        assertThrows(IllegalArgumentException.class, () -> b.onUncertainResume("ghost", UncertainResumePolicy.FAIL));
    }

    @Test
    void anEdgeToAnUnknownNode_isRejectedAtBuild() {
        Workflow.Builder b = Workflow.of().node("a", in -> "A").edge("a", "ghost");
        assertThrows(IllegalArgumentException.class, b::build);
    }

    @Test
    void anEmptyBuilder_isRejectedAtBuild() {
        assertThrows(IllegalStateException.class, () -> Workflow.of().build());
    }

    @Test
    void aWorkflowIsReusable_whenNoBudgetIsAttached() {
        Workflow wf = Workflow.of().node("a", in -> "A").node("b", in -> "B").edge("a", "b").build();

        assertTrue(wf.run("one", pool).ok());
        assertTrue(wf.run("two", pool).ok(), "a fresh scheduler per run");
    }

    @Test
    void aBudgetedWorkflowIsEffectivelySingleRun_theGovernorAccumulatesAcrossRuns() {
        RunBudget shared = RunBudget.of().maxActivations(3).build();
        Workflow wf = Workflow.of()
                .node("a", in -> "A").node("b", in -> "B").edge("a", "b")
                .budget(shared)
                .build();

        assertTrue(wf.run("one", pool).ok(), "first run: 2 activations, within cap");
        WorkflowResult second = wf.run("two", pool);
        assertFalse(second.ok(), "second run pushes the shared governor past 3 — rebuild for a fresh one");
        assertTrue(second.failureReason().contains("ACTIVATIONS"), second.failureReason());
    }
}
