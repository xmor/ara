package io.ara.core.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BudgetTest {

    @Test
    void unlimited_permitsAnyAmount() {
        assertTrue(Budget.unlimited().permits(Money.of("999999", "EUR")));
    }

    @Test
    void limited_permitsAtOrBelowCap() {
        Budget budget = Budget.limited(Money.of("2", "EUR"));
        assertTrue(budget.permits(Money.of("1", "EUR")));
        assertTrue(budget.permits(Money.of("2", "EUR")));
    }

    @Test
    void limited_rejectsAboveCap() {
        Budget budget = Budget.limited(Money.of("2", "EUR"));
        assertFalse(budget.permits(Money.of("3", "EUR")));
    }

    @Test
    void limited_currencyMismatch_throws() {
        Budget budget = Budget.limited(Money.of("2", "EUR"));
        Money usd = Money.of("1", "USD");
        assertThrows(IllegalArgumentException.class, () -> budget.permits(usd));
    }
}
