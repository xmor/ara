package io.ara.core.common;

import java.util.Objects;

/**
 * A spending cap for an agent's LLM cost tracking.
 *
 * <p>Sealed so "no cap" is an explicit variant rather than a magic {@code null}
 * or negative sentinel value — {@link Unlimited} always permits, {@link Limited}
 * compares the projected spend against its {@link Limited#cap()}.
 */
public sealed interface Budget permits Budget.Unlimited, Budget.Limited {

    record Unlimited() implements Budget {}

    record Limited(Money cap) implements Budget {
        public Limited {
            Objects.requireNonNull(cap, "cap must not be null");
        }
    }

    Unlimited UNLIMITED = new Unlimited();

    static Budget unlimited() {
        return UNLIMITED;
    }

    static Budget limited(Money cap) {
        return new Limited(cap);
    }

    /**
     * Whether {@code projected} spend stays within this budget. Always {@code true}
     * for {@link Unlimited}; for {@link Limited}, requires {@code projected} to be
     * in the same currency as the cap (a currency mismatch signals misconfiguration
     * and is surfaced as an exception rather than silently permitted or denied).
     */
    default boolean permits(Money projected) {
        return switch (this) {
            case Unlimited ignored -> true;
            case Limited limited -> projected.compareTo(limited.cap()) <= 0;
        };
    }
}
