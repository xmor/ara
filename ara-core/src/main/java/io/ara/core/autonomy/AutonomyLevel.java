package io.ara.core.autonomy;

import io.ara.core.tool.Reversibility;
import io.ara.core.tool.ToolSpec;

/**
 * The five levels of operational autonomy A0–A4 for a {@code task_class} (ADR-0073 D1).
 * Each level is fully defined by two numbers plus an audit rate:
 *
 * <ul>
 *   <li>a <b>risk floor</b> — the most-irreversible {@link Reversibility} an action may
 *       carry and still run without a human gate at this level;</li>
 *   <li>a <b>confidence threshold</b> — the {@code escalateBelow}-style cutoff (ADR-050):
 *       an action whose classifier confidence is under it escalates;</li>
 *   <li>an <b>audit sampling rate</b> — the fraction of autonomous executions selected for
 *       after-the-fact human review, reusing the ADR-0061 sampling infrastructure. Not a
 *       gate: the action already ran; a review that marks it bad feeds the ledger as an
 *       observed failure (ADR-0073 D1).</li>
 * </ul>
 *
 * <p><b>No level makes {@link Reversibility.IrreversibleHighImpact} autonomous</b> — not
 * even A4. {@link #permits(Reversibility)} returns {@code false} for it at every level, by
 * construction, matching {@link ToolSpec#approvalRequired()} (ADR-0067 D1: "ALWAYS gated",
 * independent of the caller). A4 is not "A3 with the floor removed": it shares A3's floor
 * ({@link Reversibility.IrreversibleLowImpact}) and differs only in a more permissive
 * confidence threshold and a rarer audit (ADR-0073 D3).
 *
 * <p>{@link #INITIAL} is A0: every new {@code task_class} starts fully gated and earns its
 * way up through {@code AutonomyLedger} (ADR-0073 D4 — no unearned trust). The numeric
 * values here are declared starting points, not values calibrated on real data
 * (ADR-0073, "Non affrontato").
 */
public enum AutonomyLevel {

    /** Nothing runs autonomously — every action escalates. Equivalent to {@code escalateBelow(1.0)}. */
    A0(-1, 1.00, 1.00),

    /** {@link Reversibility.Reversible} only. Every autonomous execution is audited. */
    A1(0, 0.90, 1.00),

    /** Up to {@link Reversibility.CostlyButReversible}. Half of autonomous executions audited. */
    A2(1, 0.80, 0.50),

    /** Up to {@link Reversibility.IrreversibleLowImpact}. One in five audited. */
    A3(2, 0.70, 0.20),

    /** Same floor as {@link #A3}; lower confidence threshold, one in ten audited (ADR-0073 D3). */
    A4(2, 0.60, 0.10);

    /** The level every {@code task_class} starts at (ADR-0073 D4). */
    public static final AutonomyLevel INITIAL = A0;

    private final int    maxAutonomousRank;
    private final double confidenceThreshold;
    private final double auditSamplingRate;

    AutonomyLevel(int maxAutonomousRank, double confidenceThreshold, double auditSamplingRate) {
        this.maxAutonomousRank   = maxAutonomousRank;
        this.confidenceThreshold = confidenceThreshold;
        this.auditSamplingRate   = auditSamplingRate;
    }

    /** The {@code escalateBelow}-style cutoff for this level (ADR-0073 D1, column 3). */
    public double confidenceThreshold() {
        return confidenceThreshold;
    }

    /**
     * Fraction of autonomous executions at this level to sample for after-the-fact human
     * review (ADR-0073 D1, column 4 — reuses ADR-0061 sampling). Between {@code 0.0} and
     * {@code 1.0} inclusive.
     */
    public double auditSamplingRate() {
        return auditSamplingRate;
    }

    /**
     * Whether an action carrying {@code reversibility} may run without a human gate at
     * this level — the risk floor of ADR-0073 D2 condition 2.
     * {@link Reversibility.IrreversibleHighImpact} is never permitted, at any level
     * (ADR-0073 D3).
     */
    public boolean permits(Reversibility reversibility) {
        if (reversibility instanceof Reversibility.IrreversibleHighImpact) {
            return false;
        }
        return rankOf(reversibility) <= maxAutonomousRank;
    }

    /** The next level up, or {@code this} if already at {@link #A4} (ADR-0073 D5 — never above A4). */
    public AutonomyLevel promoted() {
        return this == A4 ? A4 : values()[ordinal() + 1];
    }

    /** The next level down, or {@code this} if already at {@link #A0} (ADR-0073 D5 — never below A0). */
    public AutonomyLevel demoted() {
        return this == A0 ? A0 : values()[ordinal() - 1];
    }

    private static int rankOf(Reversibility r) {
        return switch (r) {
            case Reversibility.Reversible ignored            -> 0;
            case Reversibility.CostlyButReversible ignored   -> 1;
            case Reversibility.IrreversibleLowImpact ignored -> 2;
            case Reversibility.IrreversibleHighImpact ignored -> 3;
        };
    }
}
