package io.ara.core.budget;

import io.ara.core.common.Money;

import java.util.Objects;

/**
 * The three decrementing budget axes aggregated into one immutable value: money, prompt
 * tokens, and LLM call count (ADR-0069 D1). Not a new domain concept — just the sum of
 * what {@code RunBudget} (ADR-054 D6) is meant to track per axis, in a shape that
 * {@link HierarchicalBudget} can add up the delegation tree.
 *
 * <p>Duration is deliberately <em>not</em> here: it is checked against a start timestamp,
 * not decremented (ADR-0069 D1) — see {@link HierarchicalBudget#permitsElapsed}.
 *
 * @param money  cost so far / projected; carries its own currency
 * @param tokens prompt+completion tokens; {@code >= 0}
 * @param calls  number of LLM calls; {@code >= 0}
 */
public record Spend(Money money, long tokens, long calls) {

    public Spend {
        Objects.requireNonNull(money, "money must not be null");
        if (tokens < 0) throw new IllegalArgumentException("tokens must be >= 0, got: " + tokens);
        if (calls < 0)  throw new IllegalArgumentException("calls must be >= 0, got: " + calls);
    }

    /** Nothing spent yet, in {@code currency}. */
    public static Spend zero(String currency) {
        return new Spend(Money.zero(currency), 0, 0);
    }

    public static Spend of(Money money, long tokens, long calls) {
        return new Spend(money, tokens, calls);
    }

    /**
     * This spend plus {@code other}, axis by axis. Both must be in the same currency —
     * {@link Money#plus} surfaces a mismatch as an exception rather than silently mixing.
     */
    public Spend plus(Spend other) {
        return new Spend(money.plus(other.money), tokens + other.tokens, calls + other.calls);
    }
}
