package io.ara.core.budget;

import io.ara.core.common.Budget;
import io.ara.core.common.Money;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0069: {@link HierarchicalBudget} enforces "subtracted from the parent, never
 * independent" (D1) up the delegation tree, with a request-time stop rather than a
 * retroactive undo (D5). {@code RunBudget} (ADR-054) is meant to compose in as the leaf.
 */
class HierarchicalBudgetTest {

    private static Money eur(String amount) {
        return Money.of(amount, "EUR");
    }

    private static Spend spend(String money, long tokens, long calls) {
        return Spend.of(eur(money), tokens, calls);
    }

    // Spend — the three-axis aggregate
    @Test
    void spend_addsAxisByAxisAndRejectsCrossCurrency() {
        Spend a = spend("0.10", 100, 2);
        Spend b = spend("0.05", 50, 1);
        Spend sum = a.plus(b);

        assertEquals(eur("0.15"), sum.money());
        assertEquals(150, sum.tokens());
        assertEquals(3, sum.calls());
        assertThrows(IllegalArgumentException.class,
                () -> a.plus(Spend.of(Money.of("0.01", "USD"), 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> Spend.of(eur("0"), -1, 0));
    }

    // D1 local — a single node enforces each axis independently; an unset axis never denies
    @Test
    void rootNode_enforcesEachCapAndIgnoresUnsetAxes() {
        HierarchicalBudget b = HierarchicalBudget.root(
                Budget.limited(eur("1.00")), 1000, 10, Duration.ofMinutes(5));

        assertTrue(b.permits(spend("0.99", 999, 9)));
        assertFalse(b.permits(spend("1.01", 0, 0)), "over money cap");
        assertFalse(b.permits(spend("0.00", 1001, 0)), "over token cap");
        assertFalse(b.permits(spend("0.00", 0, 11)), "over call cap");

        HierarchicalBudget moneyOnly = HierarchicalBudget.root(Budget.limited(eur("1.00")), null, null, null);
        assertTrue(moneyOnly.permits(spend("0.50", 9_999_999, 9_999_999)), "unset token/call axes never deny");
    }

    // D1 recursive — permits is true only if this node AND every ancestor allow it
    @Test
    void permits_isFalseWhenAnyAncestorIsExhausted() {
        HierarchicalBudget tenant = HierarchicalBudget.root(Budget.limited(eur("1.00")), null, null, null);
        HierarchicalBudget run = tenant.child(Budget.limited(eur("10.00")), null, null, null);

        assertTrue(run.permits(spend("0.90", 0, 0)), "within both");
        run.record(spend("0.90", 0, 0));
        // run's own cap (10.00) still has room, but the tenant (1.00) is nearly full
        assertFalse(run.permits(spend("0.20", 0, 0)), "child within its own cap, parent over");
        assertTrue(run.permits(spend("0.05", 0, 0)));
    }

    // D1 — record propagates spend to every ancestor, so siblings see the shared drain
    @Test
    void record_propagatesUpAndSiblingsShareTheParentBudget() {
        HierarchicalBudget cycle = HierarchicalBudget.root(Budget.limited(eur("5.00")), 10_000, null, null);
        HierarchicalBudget runA = cycle.child(Budget.limited(eur("5.00")), null, null, null);
        HierarchicalBudget runB = cycle.child(Budget.limited(eur("5.00")), null, null, null);

        runA.record(spend("3.00", 6000, 0));

        assertEquals(eur("3.00"), cycle.spent().money());
        assertEquals(6000, cycle.spent().tokens());
        assertEquals(eur("3.00"), runA.spent().money());
        assertEquals(Money.zero("EUR"), runB.spent().money(), "sibling's own counter untouched");
        assertFalse(runB.permits(spend("2.50", 0, 0)), "but the sibling still sees the shared cycle drain");
        assertFalse(runB.permits(spend("0.00", 4001, 0)), "shared token cap too");
        assertTrue(runB.permits(spend("1.50", 4000, 0)));
    }

    // D5 — a denial records nothing; work already done is not undone
    @Test
    void permits_isPureAndRecordsNothing() {
        HierarchicalBudget b = HierarchicalBudget.root(Budget.limited(eur("1.00")), null, null, null);
        b.record(spend("0.40", 0, 0));

        assertFalse(b.permits(spend("0.80", 0, 0)));
        assertFalse(b.permits(spend("0.80", 0, 0)));
        assertEquals(eur("0.40"), b.spent().money(), "a rejected projection leaves spend unchanged");
    }

    // duration axis — checked against elapsed, not decremented, recursive over ancestors
    @Test
    void permitsElapsed_appliesNodeAndAncestorDurationCaps() {
        HierarchicalBudget outer = HierarchicalBudget.root(Budget.unlimited(), null, null, Duration.ofMinutes(10), "EUR");
        HierarchicalBudget inner = outer.child(Budget.unlimited(), null, null, Duration.ofMinutes(2));

        assertTrue(inner.permitsElapsed(Duration.ofSeconds(90)));
        assertFalse(inner.permitsElapsed(Duration.ofMinutes(3)), "over inner cap");
        assertTrue(outer.permitsElapsed(Duration.ofMinutes(3)), "outer cap alone allows it");
        assertFalse(outer.permitsElapsed(Duration.ofMinutes(11)), "over outer cap");
    }

    // currency consistency across the tree
    @Test
    void unlimitedRoot_needsExplicitCurrency_andChildCapMustMatch() {
        assertThrows(IllegalArgumentException.class,
                () -> HierarchicalBudget.root(Budget.unlimited(), null, null, null));

        HierarchicalBudget root = HierarchicalBudget.root(Budget.unlimited(), null, null, null, "EUR");
        assertEquals("EUR", root.currency());
        assertThrows(IllegalArgumentException.class,
                () -> root.child(Budget.limited(Money.of("1.00", "USD")), null, null, null));
    }

    @Test
    void limitedRoot_takesCurrencyFromItsCap() {
        HierarchicalBudget root = HierarchicalBudget.root(Budget.limited(Money.of("10", "GBP")), null, null, null);
        assertEquals("GBP", root.currency());
        assertEquals(Money.zero("GBP"), root.spent().money());
    }

    @Test
    void negativeCapsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> HierarchicalBudget.root(Budget.limited(eur("1")), -1, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> HierarchicalBudget.root(Budget.limited(eur("1")), null, null, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> HierarchicalBudget.root(Budget.limited(eur("1")), null, null, Duration.ZERO));
    }
}
