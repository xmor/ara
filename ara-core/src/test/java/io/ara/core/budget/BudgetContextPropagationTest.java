package io.ara.core.budget;

import io.ara.core.agent.RunContext;
import io.ara.core.agent.RunState;
import io.ara.core.common.Budget;
import io.ara.core.common.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0069 D3 — the budget node rides {@link RunContext}'s leak-safe {@code opaque}
 * channel, so it survives every {@code withX} copy the delegation tool performs and a
 * delegated worker gets the <em>same</em> node (spend propagates up).
 */
class BudgetContextPropagationTest {

    private static HierarchicalBudget budget() {
        return HierarchicalBudget.root(Budget.limited(Money.of("10.00", "EUR")), 1000, null, null);
    }

    @Test
    void attachThenFrom_roundTripsTheSameInstance() {
        HierarchicalBudget b = budget();
        RunContext ctx = b.attachTo(RunContext.empty());

        assertTrue(HierarchicalBudget.from(ctx).isPresent());
        assertSame(b, HierarchicalBudget.from(ctx).orElseThrow());
    }

    @Test
    void from_isEmptyWhenNoBudgetAttached() {
        assertTrue(HierarchicalBudget.from(RunContext.empty()).isEmpty());
        assertTrue(HierarchicalBudget.from(null).isEmpty());
    }

    @Test
    void budgetSurvivesEveryRunContextCopyTheDelegationToolPerforms() {
        HierarchicalBudget b = budget();
        RunContext ctx = b.attachTo(RunContext.empty())
                .withState(RunState.inMemory())          // OVERLAY / ISOLATED path
                .withPromptVar("lang", "it")
                .withUserMemory(RunState.inMemory());

        assertSame(b, HierarchicalBudget.from(ctx).orElseThrow());
    }

    @Test
    void aWorkerRecordingOnTheContextNodePropagatesToTheCallerTree() {
        HierarchicalBudget caller = budget();
        RunContext delegated = caller.attachTo(RunContext.empty());

        // the worker resolves the same node from its context and records its spend
        HierarchicalBudget seenByWorker = HierarchicalBudget.from(delegated).orElseThrow();
        seenByWorker.record(Spend.of(Money.of("2.50", "EUR"), 300, 3));

        assertEquals(Money.of("2.50", "EUR"), caller.spent().money(),
                "spend recorded by the delegate lands on the caller's node");
        assertEquals(300, caller.spent().tokens());
    }
}
