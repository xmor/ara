package io.ara.runtime.autonomy;

import io.ara.core.autonomy.AutonomyLevel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A process-local {@link AutonomyLedger} — not durable across a JVM restart (the exact
 * persistence of the ledger is left open by ADR-0073, "Non affrontato"). Enough to run
 * the promotion / demotion rule of ADR-0073 D5 and to be the level source for
 * {@link TrackRecordAutonomyPolicy}.
 *
 * <h2>The rule (ADR-0073 D5)</h2>
 * <ul>
 *   <li><b>Promotion</b> {@code A_k → A_{k+1}} ({@code k < 4}): at least
 *       {@value #MIN_OBSERVATIONS} observations at the current level (the {@code CaseStats}
 *       minimum of ADR-0070) with a success rate whose margin over
 *       {@value #PROMOTE_SUCCESS_THRESHOLD} exceeds the observed standard deviation — the
 *       same "the improvement must exceed the stdev" criterion ADR-0070 applies to a
 *       variant, reused here for a trust level.</li>
 *   <li><b>Demotion</b> {@code A_k → A_{k-1}} ({@code k > 0}, never below A0): the mirror
 *       — at least {@value #MIN_OBSERVATIONS} observations whose success rate falls short
 *       of {@value #DEMOTE_SUCCESS_THRESHOLD} by more than the observed stdev. Automatic,
 *       no deploy (the roadmap's explicit acceptance criterion), reusing ADR-0059 D3 (the
 *       observed signal prevails retroactively over a prior favourable assessment). A
 *       single isolated failure does not demote on its own; the gap between the two
 *       thresholds is hysteresis against flapping.</li>
 *   <li><b>Immediate demotion</b> — {@link #recordUnnecessaryIrreversibleAction} drops one
 *       level regardless of {@code N}.</li>
 * </ul>
 *
 * <p>For a 0/1 outcome series the population standard deviation is
 * {@code sqrt(p·(1−p))} with {@code p} the success rate, so in practice promotion needs a
 * near-clean window and demotion a near-total-failure one — deliberately conservative, and
 * the numeric thresholds are declared starting points, not calibrated values
 * (ADR-0073, "Non affrontato").
 */
public final class InMemoryAutonomyLedger implements AutonomyLedger {

    /** {@link io.ara.core.eval.CaseStats#MIN_RUNS} — a level change needs at least this many observations. */
    public static final int    MIN_OBSERVATIONS         = 3;

    /** Success rate a promotion must clear (beyond the observed stdev). ADR-0073 D5 — starting value. */
    public static final double PROMOTE_SUCCESS_THRESHOLD = 0.95;

    /** Success rate a demotion triggers below (beyond the observed stdev). ADR-0073 D5 — starting value. */
    public static final double DEMOTE_SUCCESS_THRESHOLD  = 0.80;

    /** Upper bound on a per-{@code task_class} window, oldest dropped first. */
    private static final int MAX_WINDOW = 200;

    private final Map<String, AutonomyLevel>   levels             = new HashMap<>();
    private final Map<String, Deque<Boolean>>  windows            = new HashMap<>();
    private final Set<String>                  pendingEvaluation  = new LinkedHashSet<>();

    @Override
    public synchronized AutonomyLevel currentLevel(String taskClass) {
        Objects.requireNonNull(taskClass, "taskClass must not be null");
        return levels.getOrDefault(taskClass, AutonomyLevel.INITIAL);
    }

    @Override
    public synchronized void record(String taskClass, AutonomyLevel levelAtExecution, boolean succeeded) {
        Objects.requireNonNull(taskClass, "taskClass must not be null");
        Objects.requireNonNull(levelAtExecution, "levelAtExecution must not be null");
        if (levelAtExecution != currentLevel(taskClass)) {
            return;   // stale observation from before a transition — the window is per level
        }
        Deque<Boolean> window = windows.computeIfAbsent(taskClass, k -> new ArrayDeque<>());
        window.addLast(succeeded);
        while (window.size() > MAX_WINDOW) {
            window.removeFirst();
        }
        pendingEvaluation.add(taskClass);
    }

    @Override
    public synchronized void recordUnnecessaryIrreversibleAction(String taskClass) {
        Objects.requireNonNull(taskClass, "taskClass must not be null");
        AutonomyLevel from = currentLevel(taskClass);
        AutonomyLevel to   = from.demoted();
        if (to != from) {
            applyTransition(taskClass, to);
        }
    }

    @Override
    public synchronized List<Transition> evaluate() {
        List<Transition> transitions = new ArrayList<>();
        for (String taskClass : new ArrayList<>(pendingEvaluation)) {
            Deque<Boolean> window = windows.getOrDefault(taskClass, new ArrayDeque<>());
            int n = window.size();
            if (n < MIN_OBSERVATIONS) {
                continue;
            }
            AutonomyLevel from = currentLevel(taskClass);
            double p     = successRate(window);
            double stdev = Math.sqrt(p * (1.0 - p));

            if (from != AutonomyLevel.A4 && (p - PROMOTE_SUCCESS_THRESHOLD) >= stdev) {
                AutonomyLevel to = from.promoted();
                applyTransition(taskClass, to);
                transitions.add(new Transition(taskClass, from, to,
                        "success rate %.3f (stdev %.3f) over %d observations clears promotion threshold %.2f"
                                .formatted(p, stdev, n, PROMOTE_SUCCESS_THRESHOLD)));
            } else if (from != AutonomyLevel.A0 && (DEMOTE_SUCCESS_THRESHOLD - p) > stdev) {
                AutonomyLevel to = from.demoted();
                applyTransition(taskClass, to);
                transitions.add(new Transition(taskClass, from, to,
                        "success rate %.3f (stdev %.3f) over %d observations falls below demotion threshold %.2f"
                                .formatted(p, stdev, n, DEMOTE_SUCCESS_THRESHOLD)));
            }
        }
        pendingEvaluation.clear();
        return List.copyOf(transitions);
    }

    private void applyTransition(String taskClass, AutonomyLevel to) {
        levels.put(taskClass, to);
        windows.remove(taskClass);        // fresh window at the new level (ADR-0073 D5)
        pendingEvaluation.remove(taskClass);
    }

    private static double successRate(Deque<Boolean> window) {
        long ok = window.stream().filter(Boolean::booleanValue).count();
        return (double) ok / window.size();
    }
}
