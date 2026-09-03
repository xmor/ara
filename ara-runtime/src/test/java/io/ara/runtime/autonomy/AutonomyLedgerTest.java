package io.ara.runtime.autonomy;

import io.ara.core.autonomy.AutonomyLevel;
import io.ara.core.tool.Reversibility;
import io.ara.core.tool.SideEffects;
import io.ara.core.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0073 D4 (cold start at A0), D5 ({@code AutonomyLedger} computes promotion / demotion,
 * never assigned by hand; N≥3 + variance discipline; automatic demotion; immediate
 * demotion on an unnecessary irreversible action), and the composition of
 * {@link TrackRecordAutonomyPolicy} over the ledger.
 */
class AutonomyLedgerTest {

    private static final String TASK = "summarise_ticket";

    private static ToolSpec tool(Reversibility r) {
        return ToolSpec.builtin("t", SideEffects.LOCAL_WRITE, r);
    }

    private static void feed(AutonomyLedger ledger, String taskClass, int successes, int failures) {
        AutonomyLevel at = ledger.currentLevel(taskClass);
        for (int i = 0; i < successes; i++) ledger.record(taskClass, at, true);
        for (int i = 0; i < failures; i++) ledger.record(taskClass, at, false);
    }

    // ── D4 ─────────────────────────────────────────────────────────────────

    @Test
    void unseenTaskClassStartsAtA0() {
        assertSame(AutonomyLevel.A0, new InMemoryAutonomyLedger().currentLevel("brand-new"));
    }

    // ── D5 promotion ───────────────────────────────────────────────────────

    @Test
    void cleanWindowOfThreePromotesOneLevel() {
        InMemoryAutonomyLedger ledger = new InMemoryAutonomyLedger();
        feed(ledger, TASK, 3, 0);

        List<AutonomyLedger.Transition> transitions = ledger.evaluate();

        assertEquals(1, transitions.size());
        assertEquals(AutonomyLevel.A0, transitions.get(0).from());
        assertEquals(AutonomyLevel.A1, transitions.get(0).to());
        assertSame(AutonomyLevel.A1, ledger.currentLevel(TASK));
    }

    @Test
    void promotionNeedsAtLeastThreeObservations() {
        InMemoryAutonomyLedger ledger = new InMemoryAutonomyLedger();
        feed(ledger, TASK, 2, 0);

        assertTrue(ledger.evaluate().isEmpty());
        assertSame(AutonomyLevel.A0, ledger.currentLevel(TASK));
    }

    @Test
    void aFailureInTheWindowBlocksPromotion() {
        InMemoryAutonomyLedger ledger = new InMemoryAutonomyLedger();
        feed(ledger, TASK, 5, 1);   // 0.833 success — margin over 0.95 does not exceed stdev

        assertTrue(ledger.evaluate().isEmpty());
        assertSame(AutonomyLevel.A0, ledger.currentLevel(TASK));
    }

    @Test
    void promotionResetsTheWindowSoItClimbsOneLevelPerEvaluation() {
        InMemoryAutonomyLedger ledger = new InMemoryAutonomyLedger();

        feed(ledger, TASK, 3, 0);
        ledger.evaluate();
        assertSame(AutonomyLevel.A1, ledger.currentLevel(TASK));

        // stale observations recorded against A0 are dropped; fresh ones at A1 promote again
        feed(ledger, TASK, 3, 0);
        ledger.evaluate();
        assertSame(AutonomyLevel.A2, ledger.currentLevel(TASK));
    }

    // ── D5 demotion ────────────────────────────────────────────────────────

    @Test
    void aWindowOfFailuresDemotesAutomaticallyWithoutDeploy() {
        InMemoryAutonomyLedger ledger = new InMemoryAutonomyLedger();
        feed(ledger, TASK, 3, 0);
        ledger.evaluate();
        assertSame(AutonomyLevel.A1, ledger.currentLevel(TASK));

        feed(ledger, TASK, 0, 3);
        List<AutonomyLedger.Transition> transitions = ledger.evaluate();

        assertEquals(1, transitions.size());
        assertEquals(AutonomyLevel.A1, transitions.get(0).from());
        assertEquals(AutonomyLevel.A0, transitions.get(0).to());
    }

    @Test
    void aSingleIsolatedFailureDoesNotDemote() {
        InMemoryAutonomyLedger ledger = new InMemoryAutonomyLedger();
        feed(ledger, TASK, 3, 0);
        ledger.evaluate();                       // now A1

        feed(ledger, TASK, 4, 1);               // one bad run among five
        assertTrue(ledger.evaluate().isEmpty());
        assertSame(AutonomyLevel.A1, ledger.currentLevel(TASK));
    }

    @Test
    void demotionNeverGoesBelowA0() {
        InMemoryAutonomyLedger ledger = new InMemoryAutonomyLedger();
        feed(ledger, TASK, 0, 4);
        ledger.evaluate();
        assertSame(AutonomyLevel.A0, ledger.currentLevel(TASK));
    }

    // ── D5 immediate demotion ──────────────────────────────────────────────

    @Test
    void unnecessaryIrreversibleActionDemotesImmediatelyRegardlessOfN() {
        InMemoryAutonomyLedger ledger = new InMemoryAutonomyLedger();
        feed(ledger, TASK, 3, 0);
        ledger.evaluate();
        feed(ledger, TASK, 3, 0);
        ledger.evaluate();
        assertSame(AutonomyLevel.A2, ledger.currentLevel(TASK));

        ledger.recordUnnecessaryIrreversibleAction(TASK);
        assertSame(AutonomyLevel.A1, ledger.currentLevel(TASK));
    }

    @Test
    void immediateDemotionAtA0IsANoOp() {
        InMemoryAutonomyLedger ledger = new InMemoryAutonomyLedger();
        ledger.recordUnnecessaryIrreversibleAction(TASK);
        assertSame(AutonomyLevel.A0, ledger.currentLevel(TASK));
    }

    // ── composition: TrackRecordAutonomyPolicy over the ledger ──────────────

    @Test
    void policyFollowsTheLedgerLevelAsItChanges() {
        InMemoryAutonomyLedger ledger = new InMemoryAutonomyLedger();
        TrackRecordAutonomyPolicy policy = new TrackRecordAutonomyPolicy(ledger);

        // A0: a plain reversible action still escalates (nothing is autonomous)
        assertTrue(policy.escalate(TASK, tool(new Reversibility.Reversible()), 1.0));

        feed(ledger, TASK, 3, 0);
        ledger.evaluate();   // -> A1, floor = Reversible, threshold 0.90

        assertFalse(policy.escalate(TASK, tool(new Reversibility.Reversible()), 0.95));
        assertTrue(policy.escalate(TASK, tool(new Reversibility.Reversible()), 0.80));       // condition 3
        assertTrue(policy.escalate(TASK, tool(new Reversibility.CostlyButReversible()), 1.0)); // condition 2
    }

    @Test
    void policyNeverBypassesTheAbsoluteFloor() {
        InMemoryAutonomyLedger ledger = new InMemoryAutonomyLedger();
        TrackRecordAutonomyPolicy policy = new TrackRecordAutonomyPolicy(ledger);
        // drive it as high as it goes
        for (int i = 0; i < 6; i++) { feed(ledger, TASK, 3, 0); ledger.evaluate(); }
        assertSame(AutonomyLevel.A4, ledger.currentLevel(TASK));

        assertTrue(policy.escalate(TASK, tool(new Reversibility.IrreversibleHighImpact()), 1.0));
    }
}
