package io.ara.runtime.workflow;

import io.ara.core.budget.HierarchicalBudget;
import io.ara.core.budget.RunBudget;
import io.ara.core.budget.Spend;
import io.ara.core.common.Budget;
import io.ara.core.common.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-054 D6 — the single run governor wired into {@link DataflowScheduler}: one charge
 * per journal entry, a breach stops the run naming the axis and the node that was firing,
 * and (ADR-0069 D2) a run reporting to a {@link HierarchicalBudget} parent stops when the
 * cycle/task-class/tenant tree is exhausted above it.
 */
class DataflowSchedulerBudgetTest {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static WorkflowNode step(String id) {
        return WorkflowNode.of(id, in -> id.toUpperCase());
    }

    private static WorkflowNode tokenStep(String id, long tokens) {
        return WorkflowNode.of(id, in -> id.toUpperCase())
                .withCost(out -> Spend.of(Money.zero("EUR"), tokens, 1));
    }

    private static WorkflowNode costStep(String id, String eur) {
        return WorkflowNode.of(id, in -> id.toUpperCase())
                .withCost(out -> Spend.of(Money.of(eur, "EUR"), 0, 1));
    }

    /** A linear {@code a -> b -> c -> ...} graph over the given nodes. */
    private static WorkflowGraph chain(WorkflowNode... nodes) {
        List<WorkflowEdge> edges = new ArrayList<>();
        for (int i = 1; i < nodes.length; i++) {
            edges.add(WorkflowEdge.of(nodes[i - 1].id(), nodes[i].id()));
        }
        return new WorkflowGraph(List.of(nodes), edges);
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    void noBudget_runIsUngoverned_onlyThePerNodeBackstopApplies() {
        WorkflowResult result = new DataflowScheduler(
                chain(step("a"), step("b"), step("c"), step("d"), step("e")), 10)
                .run("start", pool);

        assertTrue(result.ok(), result.failureReason());
        assertEquals(5, result.journal().stream().filter(JournalEntry.Finished.class::isInstance).count());
    }

    @Test
    void maxActivations_isAGlobalSumAcrossNodes_andNamesTheNodeThatTrippedIt() {
        RunBudget budget = RunBudget.of().maxActivations(3).build();

        WorkflowResult result = new DataflowScheduler(
                chain(step("a"), step("b"), step("c"), step("d"), step("e")), 10, budget)
                .run("start", pool);

        assertFalse(result.ok());
        assertTrue(result.failureReason().contains("ACTIVATIONS"), result.failureReason());
        assertTrue(result.failureReason().contains("node d#0"), result.failureReason());
        assertEquals(4, budget.activations(), "the tripping activation is still counted");
    }

    @Test
    void maxTokens_chargedFromTheNodeCostFn_stopsAfterTheOverspendingEntryIsJournalled() {
        RunBudget budget = RunBudget.of().maxTokens(100_000).build();

        WorkflowResult result = new DataflowScheduler(
                chain(tokenStep("a", 60_000), tokenStep("b", 60_000), tokenStep("c", 60_000)), 10, budget)
                .run("start", pool);

        assertFalse(result.ok());
        assertTrue(result.failureReason().contains("TOKENS"), result.failureReason());
        assertTrue(result.failureReason().contains("node b#0"), result.failureReason());
        assertTrue(result.order().contains("b#0"), "the overspending entry is recorded before the run stops");
        assertFalse(result.order().contains("c#0"), "the run does not proceed past the breach");
        assertEquals(120_000, budget.spent().tokens());
    }

    @Test
    void maxCost_namesTheConstructThatOverspent() {
        RunBudget budget = RunBudget.of().maxCost(2.00).build();

        WorkflowResult result = new DataflowScheduler(
                chain(costStep("a", "1.50"), costStep("b", "1.00")), 10, budget)
                .run("start", pool);

        assertFalse(result.ok());
        assertTrue(result.failureReason().contains("COST"), result.failureReason());
        assertTrue(result.failureReason().contains("node b#0"), result.failureReason());
    }

    @Test
    void aNodeWithoutACostFn_countsAsOneActivationButAddsNothingOnTheTokenAxis() {
        RunBudget budget = RunBudget.of().maxTokens(1).maxActivations(100).build();

        WorkflowResult result = new DataflowScheduler(
                chain(step("a"), step("b"), step("c")), 10, budget)
                .run("start", pool);

        assertTrue(result.ok(), result.failureReason());
        assertEquals(0, budget.spent().tokens());
        assertEquals(3, budget.activations());
    }

    @Test
    void resume_chargesTheReplayedEntries_soAResumedRunCarriesPriorSpend() {
        WorkflowGraph graph = chain(tokenStep("a", 40_000), tokenStep("b", 40_000), tokenStep("c", 40_000));

        WorkflowResult full = new DataflowScheduler(graph, 10, RunBudget.of().maxTokens(1_000_000).build())
                .run("start", pool);
        assertTrue(full.ok(), full.failureReason());

        // truncate to everything up to and including b#0's Finished entry
        List<JournalEntry> truncated = new ArrayList<>();
        for (JournalEntry e : full.journal()) {
            truncated.add(e);
            if (e instanceof JournalEntry.Finished f && f.nodeId().equals("b")) {
                break;
            }
        }

        WorkflowResult resumed = new DataflowScheduler(graph, 10, RunBudget.of().maxTokens(50_000).build())
                .run("start", pool, truncated);

        assertFalse(resumed.ok(), "prior spend replayed from the journal already exceeds the resumed cap");
        assertTrue(resumed.failureReason().contains("TOKENS"), resumed.failureReason());
    }

    @Test
    void reportingToAHierarchy_theWorkflowStopsWhenTheCycleBudgetAboveItIsExhausted() {
        HierarchicalBudget cycle = HierarchicalBudget.root(
                Budget.limited(Money.of("5.00", "EUR")), 1_000_000, null, null);
        RunBudget run = RunBudget.of().maxCost(1_000.00).reportingTo(cycle).build();  // generous local cap

        WorkflowResult result = new DataflowScheduler(
                chain(costStep("a", "3.00"), costStep("b", "3.00"), costStep("c", "3.00")), 10, run)
                .run("start", pool);

        assertFalse(result.ok());
        assertTrue(result.failureReason().contains("HIERARCHY"), result.failureReason());
        assertTrue(result.failureReason().contains("node b#0"), result.failureReason());
        assertEquals(Money.of("6.00", "EUR"), cycle.spent().money(), "spend propagated up to the cycle node");
    }
}
