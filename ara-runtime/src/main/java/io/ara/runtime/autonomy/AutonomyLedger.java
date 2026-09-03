package io.ara.runtime.autonomy;

import io.ara.core.autonomy.AutonomyLevel;

/**
 * The track record of autonomous execution per {@code task_class}, and the current
 * {@link AutonomyLevel} it justifies (ADR-0073 D5). The level is <em>calculated</em> from
 * observed outcomes, never assigned by hand.
 *
 * <p>{@link #record} is fed by the same {@code TraceSpan}s a {@code TraceStore} (ADR-0068)
 * already persists — {@code status == Completed} counts as a success, {@code Failed} as a
 * failure — filtered to the occurrences for which the escalation rule (ADR-0073 D2) did
 * <em>not</em> fire: only those measure how well autonomy is doing at a level. An
 * execution a human already approved is not evidence about the level's trust.
 *
 * <p>Promotion and demotion are applied by {@link #evaluate()}, meant to run from a
 * periodic job (reusing {@code AgentSchedule}/{@code AgentScheduler}, ADR-032 — the same
 * pattern ADR-0058/0061 reuse for their own jobs), not synchronously on every
 * {@link #record}.
 */
public interface AutonomyLedger {

    /** The level currently in force for {@code taskClass} — {@link AutonomyLevel#INITIAL} if never seen (ADR-0073 D4). */
    AutonomyLevel currentLevel(String taskClass);

    /**
     * Records the outcome of one autonomous execution (no escalation fired) that ran at
     * {@code levelAtExecution}. An observation whose level no longer matches the current
     * level for {@code taskClass} is dropped — the evaluation window is per level.
     */
    void record(String taskClass, AutonomyLevel levelAtExecution, boolean succeeded);

    /**
     * Immediately demotes {@code taskClass} by one level, regardless of how many
     * observations exist (ADR-0073 D5). Reserved for a failure whose attribution — once
     * ADR-0080's taxonomy exists — is "an irreversible action ran autonomously without
     * needing to". Same "one violation stops the subtree within a step" principle as
     * ADR-0069's kill switch, applied to a single level instead of the whole session.
     */
    void recordUnnecessaryIrreversibleAction(String taskClass);

    /**
     * Re-evaluates every {@code task_class} with at least one observation since the last
     * call, applying the promotion / demotion rule of ADR-0073 D5, and returns the
     * transitions that happened. A transition resets the observation window for that
     * {@code task_class}.
     */
    java.util.List<Transition> evaluate();

    /** One level change produced by {@link #evaluate()} or {@link #recordUnnecessaryIrreversibleAction}. */
    record Transition(String taskClass, AutonomyLevel from, AutonomyLevel to, String reason) {}
}
