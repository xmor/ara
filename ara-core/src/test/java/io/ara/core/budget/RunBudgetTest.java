package io.ara.core.budget;

import io.ara.core.agent.RunContext;
import io.ara.core.common.Budget;
import io.ara.core.common.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-054 D6 — one governor per run, on the journal: three axes checked against a running
 * tally, {@link RunBudget#charge} recording first and reporting the breach after, and
 * (ADR-0069 D2) spend reported up to a {@link HierarchicalBudget} parent.
 */
class RunBudgetTest {

    private static Spend eur(String amount, long tokens, long calls) {
        return Spend.of(Money.of(amount, "EUR"), tokens, calls);
    }

    @Test
    void unlimited_neverExceeds() {
        RunBudget b = RunBudget.unlimited("EUR");
        for (int i = 0; i < 1_000; i++) {
            assertTrue(b.charge(eur("1.00", 10_000, 5)).ok());
        }
        assertEquals(1_000, b.activations());
    }

    @Test
    void maxActivations_isTheGlobalSumAcrossNodes_notPerNode() {
        RunBudget b = RunBudget.of().maxActivations(3).build();

        assertTrue(b.charge(Spend.zero("EUR")).ok());
        assertTrue(b.charge(Spend.zero("EUR")).ok());
        assertTrue(b.charge(Spend.zero("EUR")).ok());

        RunBudget.Charge fourth = b.charge(Spend.zero("EUR"));
        assertFalse(fourth.ok());
        assertEquals(RunBudget.Charge.Axis.ACTIVATIONS, ((RunBudget.Charge.Exceeded) fourth).axis());
        assertEquals(4, fourth.activationsAfter());
    }

    @Test
    void maxTokens_breachIsReportedAfterTheEntryIsRecorded() {
        RunBudget b = RunBudget.of().maxTokens(200_000).build();

        assertTrue(b.charge(eur("0.00", 150_000, 0)).ok());
        RunBudget.Charge over = b.charge(eur("0.00", 60_000, 0));

        assertEquals(RunBudget.Charge.Axis.TOKENS, ((RunBudget.Charge.Exceeded) over).axis());
        assertEquals(210_000, over.spentAfter().tokens(), "the overspending entry is still recorded");
    }

    @Test
    void maxCost_capIsInTheBuilderCurrency() {
        RunBudget b = RunBudget.of().maxCost(2.00).build();

        assertTrue(b.charge(eur("1.50", 0, 0)).ok());
        RunBudget.Charge over = b.charge(eur("0.75", 0, 0));

        assertEquals(RunBudget.Charge.Axis.COST, ((RunBudget.Charge.Exceeded) over).axis());
        assertEquals(Money.of("2.25", "EUR"), over.spentAfter().money());
    }

    @Test
    void chargeRejectsSpendInAnotherCurrency() {
        RunBudget b = RunBudget.of().currency("EUR").maxTokens(10).build();
        assertThrows(IllegalArgumentException.class,
                () -> b.charge(Spend.of(Money.of("1.00", "USD"), 1, 0)));
    }

    @Test
    void reportingTo_recordsSpendOnTheParentTree() {
        HierarchicalBudget cycle = HierarchicalBudget.root(
                Budget.limited(Money.of("100.00", "EUR")), 1_000_000, null, null);
        RunBudget run = RunBudget.of().maxCost(50.00).reportingTo(cycle).build();

        run.charge(eur("3.00", 1000, 2));
        run.charge(eur("4.00", 500, 1));

        assertEquals(Money.of("7.00", "EUR"), cycle.spent().money());
        assertEquals(1500, cycle.spent().tokens());
    }

    @Test
    void reportingTo_breachAtAnAncestorStopsTheRunEvenWhenLocalAxesFit() {
        HierarchicalBudget cycle = HierarchicalBudget.root(
                Budget.limited(Money.of("5.00", "EUR")), 1_000_000, null, null);
        RunBudget run = RunBudget.of().maxCost(1000.00).reportingTo(cycle).build();  // local cap is generous

        assertTrue(run.charge(eur("4.00", 0, 0)).ok());
        RunBudget.Charge over = run.charge(eur("2.00", 0, 0));   // tree now at 6.00 > 5.00

        assertFalse(over.ok());
        assertEquals(RunBudget.Charge.Axis.HIERARCHY, ((RunBudget.Charge.Exceeded) over).axis());
    }

    @Test
    void build_rejectsAParentInAnotherCurrency() {
        HierarchicalBudget usd = HierarchicalBudget.root(
                Budget.limited(Money.of("100.00", "USD")), null, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> RunBudget.of().currency("EUR").reportingTo(usd).build());
    }

    @Test
    void attachTo_thenFrom_roundTripsTheSameInstance() {
        RunBudget b = RunBudget.of().maxActivations(10).build();
        RunContext ctx = b.attachTo(RunContext.empty());

        assertSame(b, RunBudget.from(ctx).orElseThrow());
        assertTrue(RunBudget.from(RunContext.empty()).isEmpty());
        assertTrue(RunBudget.from(null).isEmpty());
    }
}
