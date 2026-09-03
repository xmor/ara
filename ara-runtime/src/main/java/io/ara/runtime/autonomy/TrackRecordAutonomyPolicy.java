package io.ara.runtime.autonomy;

import io.ara.core.autonomy.AutonomyLevel;
import io.ara.core.autonomy.AutonomyPolicy;
import io.ara.core.tool.ToolSpec;

import java.util.Objects;

/**
 * An {@link AutonomyPolicy} whose level per {@code task_class} comes from an
 * {@link AutonomyLedger}'s track record (ADR-0073 D2/D5) instead of being fixed.
 *
 * <p>The three conditions of ADR-0073 D2, in order — the first is never bypassable by any
 * level (ADR-0067 D1), the other two are gated by the level the ledger currently reports:
 *
 * <ol>
 *   <li>{@link ToolSpec#approvalRequired()} — absolute floor.</li>
 *   <li>the action's {@link io.ara.core.tool.Reversibility} is above the level's floor
 *       ({@link AutonomyLevel#permits}).</li>
 *   <li>the action's confidence is below the level's threshold
 *       ({@link AutonomyLevel#confidenceThreshold()}).</li>
 * </ol>
 */
public final class TrackRecordAutonomyPolicy implements AutonomyPolicy {

    private final AutonomyLedger ledger;

    public TrackRecordAutonomyPolicy(AutonomyLedger ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger must not be null");
    }

    @Override
    public boolean escalate(String taskClass, ToolSpec toolSpec, double confidence) {
        Objects.requireNonNull(taskClass, "taskClass must not be null");
        Objects.requireNonNull(toolSpec, "toolSpec must not be null");
        if (toolSpec.approvalRequired()) {
            return true;                                           // condition 1 — never bypassable
        }
        AutonomyLevel level = ledger.currentLevel(taskClass);      // A0 by default (ADR-0073 D4)
        if (!level.permits(toolSpec.reversibility())) {
            return true;                                           // condition 2
        }
        return confidence < level.confidenceThreshold();           // condition 3
    }
}
