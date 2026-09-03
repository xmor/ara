package io.ara.core.eval;

import java.util.Objects;

/**
 * The decision an {@link EvalResult} carries, computed by applying the ADR-0059 cascade
 * to the aggregated result (ADR-0070 D4):
 *
 * <ol>
 *   <li><b>Veto</b> (ADR-0059 D1): any case with a failed blocking verifier → {@link Reject}.</li>
 *   <li><b>Advisory judge</b> (ADR-0059 D2): a low judge score with no blocking failure → {@link NeedsReview}.</li>
 *   <li><b>Variance threshold</b> (D2/DR-4): candidate only if the mean improvement over
 *       baseline exceeds the observed {@code stdev} on non-hold-out cases → else {@link Reject}.</li>
 *   <li><b>Hold-out gate</b> (only when {@code runHoldoutOnly(...)} was called by ADR-0083):
 *       hold-out confirms the non-hold-out gain → {@link PromoteToCanary}; it does not →
 *       {@link RejectOverfit}.</li>
 * </ol>
 */
public sealed interface Verdict
        permits Verdict.PromoteToCanary, Verdict.Reject, Verdict.RejectOverfit, Verdict.NeedsReview {

    /** Non-hold-out gain confirmed by the hold-out partition — advance to canary (ADR-0083). */
    record PromoteToCanary() implements Verdict {}

    /** A blocking verifier failed, or no significant improvement over baseline. */
    record Reject(String reason) implements Verdict {
        public Reject {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /** The hold-out partition did not confirm the improvement seen on non-hold-out cases. */
    record RejectOverfit() implements Verdict {}

    /** A judge score under threshold on a case with no blocking failure — a human decides. */
    record NeedsReview(String reason) implements Verdict {
        public NeedsReview {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
