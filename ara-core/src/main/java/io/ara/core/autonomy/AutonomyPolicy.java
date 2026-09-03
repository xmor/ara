package io.ara.core.autonomy;

import io.ara.core.tool.ToolSpec;

import java.util.Objects;

/**
 * Decides whether one action must escalate to a human, given its {@code task_class}, the
 * risk classification of the tool it would call, and the classifier confidence behind it
 * (ADR-0073 D2).
 *
 * <p>This is the third disjunct of the unified escalation rule — additive to the two that
 * already exist, never a replacement:
 *
 * <ol>
 *   <li><b>Absolute floor</b> — {@link ToolSpec#approvalRequired()} (ADR-0067 D1/D2:
 *       {@code IrreversibleHighImpact}). Not negotiable by any autonomy level.</li>
 *   <li><b>Level floor</b> — the action needs a {@link io.ara.core.tool.Reversibility}
 *       above the current level's floor for this {@code task_class}
 *       ({@link AutonomyLevel#permits}).</li>
 *   <li><b>Confidence threshold</b> — the action's confidence is under the level's cutoff
 *       ({@link AutonomyLevel#confidenceThreshold()}).</li>
 * </ol>
 *
 * <p>An OR of the three, the same "veto, not weighted average" shape ADR-0059 D5 chose for
 * a structurally analogous problem. A high autonomy level can only <em>remove</em>
 * escalations 2 and 3 as a {@code task_class}'s track record earns it — it can never
 * weaken escalation 1 (ADR-0073 DR-2, the same principle as ADR-0067 D6: a mechanism that
 * "adds a gate" is safe by construction, one that can "take it away" is not).
 *
 * <p>Condition 4 of ADR-0073 D2 (proposals of the evolution cycle already classified by
 * ADR-0055) is deliberately out of this interface: it is the composition with ADR-0055,
 * evaluated where evolution-cycle proposals are handled, not on an ordinary tool call.
 */
@FunctionalInterface
public interface AutonomyPolicy {

    /** {@code true} if the action must escalate — conditions 1–3 of ADR-0073 D2, always evaluated. */
    boolean escalate(String taskClass, ToolSpec toolSpec, double confidence);

    /**
     * A policy that treats every {@code task_class} as fixed at {@code level}, ignoring any
     * track record — the conditions 1–3 of ADR-0073 D2 evaluated against a constant level.
     * Useful as a default before an {@code AutonomyLedger} is wired, and in tests.
     */
    static AutonomyPolicy fixed(AutonomyLevel level) {
        Objects.requireNonNull(level, "level must not be null");
        return (taskClass, toolSpec, confidence) -> {
            if (toolSpec.approvalRequired()) {
                return true;                                       // condition 1 — never bypassable
            }
            if (!level.permits(toolSpec.reversibility())) {
                return true;                                       // condition 2
            }
            return confidence < level.confidenceThreshold();       // condition 3
        };
    }
}
