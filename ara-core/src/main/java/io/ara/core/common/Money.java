package io.ara.core.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable monetary amount in a given ISO-4217-ish currency code.
 *
 * <p>Used throughout the cost/budget model — {@link io.ara.core.llm.LlmProfile}
 * tariffs and budget cap, {@link io.ara.core.agent.AgentResponse#estimatedCost()} —
 * so amounts always carry their currency and can't be silently mixed across
 * currencies at the arithmetic level.
 */
public record Money(BigDecimal amount, String currency) {

    private static final int SCALE = 6;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public static final Money ZERO_EUR = zero("EUR");

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be >= 0, got " + amount);
        }
        if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        amount = amount.setScale(SCALE, ROUNDING);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money multiply(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor must not be null");
        if (factor.signum() < 0) {
            throw new IllegalArgumentException("factor must be >= 0, got " + factor);
        }
        return new Money(amount.multiply(factor).setScale(SCALE, ROUNDING), currency);
    }

    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
