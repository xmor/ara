package io.ara.runtime.workflow.patterns;

import java.util.Objects;

/**
 * The outcome of a review step (ADR-054 D2): approve what the worker produced, or reject
 * it with the feedback the next attempt should act on.
 *
 * <p>A sealed type rather than a string, for the reason ADR-054 gives: the node that
 * decides which edge is taken decides it on a value the compiler can check exhaustively —
 * the same treatment {@code io.ara.core.hitl.ApprovalDecision} already gets. A string
 * verdict is one typo away from silently taking the wrong branch, and nothing would catch
 * it at build time.
 *
 * @see Critic
 */
public sealed interface Verdict permits Verdict.Approved, Verdict.Rejected {

    /** The work passes. {@code output} is what flows on to the next node. */
    record Approved(String output) implements Verdict {
        public Approved {
            Objects.requireNonNull(output, "output must not be null");
        }
    }

    /**
     * The work does not pass. {@code feedback} is what flows <em>back</em> to the worker,
     * as its next input — so it should say what to change, not merely that something is
     * wrong.
     */
    record Rejected(String feedback) implements Verdict {
        public Rejected {
            Objects.requireNonNull(feedback, "feedback must not be null");
        }
    }

    /** The text this verdict carries — {@code output} when approved, {@code feedback} when rejected. */
    default String content() {
        return switch (this) {
            case Approved a -> a.output();
            case Rejected r -> r.feedback();
        };
    }
}
