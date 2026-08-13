package io.ara.core.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void of_buildsValidAmount() {
        Money money = Money.of("10.50", "EUR");
        assertEquals(0, money.compareTo(Money.of(new BigDecimal("10.50"), "EUR")));
        assertEquals("EUR", money.currency());
    }

    @Test
    void negativeAmount_throws() {
        assertThrows(IllegalArgumentException.class, () -> Money.of("-1", "EUR"));
    }

    @Test
    void blankCurrency_throws() {
        assertThrows(IllegalArgumentException.class, () -> Money.of("1", " "));
    }

    @Test
    void nullAmount_throws() {
        assertThrows(NullPointerException.class, () -> Money.of((BigDecimal) null, "EUR"));
    }

    @Test
    void plus_sameCurrency_sumsAmounts() {
        Money sum = Money.of("1.00", "EUR").plus(Money.of("2.00", "EUR"));
        assertEquals(0, sum.compareTo(Money.of("3.00", "EUR")));
    }

    @Test
    void plus_differentCurrency_throws() {
        Money eur = Money.of("1.00", "EUR");
        Money usd = Money.of("1.00", "USD");
        assertThrows(IllegalArgumentException.class, () -> eur.plus(usd));
    }

    @Test
    void multiply_fractionalFactor_roundsToScale() {
        Money result = Money.of("0.50", "EUR").multiply(new BigDecimal("1.5"));
        assertEquals(0, result.compareTo(Money.of("0.75", "EUR")));
    }

    @Test
    void multiply_negativeFactor_throws() {
        Money money = Money.of("1.00", "EUR");
        assertThrows(IllegalArgumentException.class, () -> money.multiply(new BigDecimal("-1")));
    }

    @Test
    void compareTo_sameCurrency_ordersByAmount() {
        assertTrue(Money.of("1", "EUR").compareTo(Money.of("2", "EUR")) < 0);
        assertTrue(Money.of("2", "EUR").compareTo(Money.of("1", "EUR")) > 0);
        assertEquals(0, Money.of("1", "EUR").compareTo(Money.of("1", "EUR")));
    }

    @Test
    void compareTo_differentCurrency_throws() {
        Money eur = Money.of("1", "EUR");
        Money usd = Money.of("1", "USD");
        assertThrows(IllegalArgumentException.class, () -> eur.compareTo(usd));
    }

    @Test
    void zero_hasZeroAmountInGivenCurrency() {
        Money zero = Money.zero("USD");
        assertEquals(0, zero.compareTo(Money.of("0", "USD")));
        assertEquals("USD", zero.currency());
    }

    @Test
    void zeroEur_isZeroEur() {
        assertEquals(0, Money.ZERO_EUR.compareTo(Money.zero("EUR")));
    }
}
