package io.ara.core.tool;

/**
 * How reversible a tool's effect is, on the fused four-level scale this backlog adopted
 * (ADR-0067 D1 / ADR-0055 D2 / ADR-0063 D3) — reversibility and impact merged into one
 * axis, not the two orthogonal axes of the source document §4.3.
 *
 * <p>Sealed records rather than an enum so a future policy datum can attach to a level
 * without touching every {@code switch} — the {@code ApprovalDecision} shape.
 */
public sealed interface Reversibility
        permits Reversibility.Reversible, Reversibility.CostlyButReversible,
                Reversibility.IrreversibleLowImpact, Reversibility.IrreversibleHighImpact {

    /** Read, compute, scratch space — fully autonomous. */
    record Reversible() implements Reversibility {}

    /** Long jobs, staging environments — autonomous within a budget. */
    record CostlyButReversible() implements Reversibility {}

    /** Internal send, commit to a branch — autonomous, but notify. */
    record IrreversibleLowImpact() implements Reversibility {}

    /** External communication, payments, deploys — ALWAYS gated ({@link ToolSpec#approvalRequired()}). */
    record IrreversibleHighImpact() implements Reversibility {}
}
