package io.ara.runtime.workflow.patterns;

import java.util.Objects;

/**
 * The outcome of a policy check (ADR-054 D2): let the candidate through, or discard it
 * with a stated reason.
 *
 * <p>Distinct from {@link Verdict} on purpose, even though both are two-armed. A rejected
 * {@link Verdict} goes <em>back</em> to the worker to be retried; a rejected {@link Gate}
 * goes <em>forward</em> to a declared discard terminal and is never retried. Collapsing
 * them into one type would hide that difference behind an arm name, and the two patterns
 * they serve — Adversarial Verification and Generate-and-Filter — differ in exactly that.
 *
 * @see Filter
 */
public sealed interface Gate permits Gate.Pass, Gate.Reject {

    /** The candidate is allowed through. {@code output} flows on to the pass target. */
    record Pass(String output) implements Gate {
        public Pass {
            Objects.requireNonNull(output, "output must not be null");
        }
    }

    /** The candidate is discarded. {@code reason} flows to the declared reject target. */
    record Reject(String reason) implements Gate {
        public Reject {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /** The text this gate carries — {@code output} when passing, {@code reason} when rejecting. */
    default String content() {
        return switch (this) {
            case Pass p -> p.output();
            case Reject r -> r.reason();
        };
    }
}
