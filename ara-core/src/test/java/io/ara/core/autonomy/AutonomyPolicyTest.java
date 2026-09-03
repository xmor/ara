package io.ara.core.autonomy;

import io.ara.core.tool.Reversibility;
import io.ara.core.tool.SideEffects;
import io.ara.core.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0073 D1 (the five levels: risk floor + confidence threshold + audit rate), D2
 * (the unified escalation rule, conditions 1–3), D3 (A4 shares A3's floor, no level makes
 * {@code IrreversibleHighImpact} autonomous), D4 ({@link AutonomyLevel#INITIAL} is A0).
 */
class AutonomyPolicyTest {

    private static ToolSpec tool(Reversibility r) {
        return ToolSpec.builtin("t", SideEffects.LOCAL_WRITE, r);
    }

    // ── D1 / D4: the level table ─────────────────────────────────────────────

    @Test
    void initialLevelIsA0AndGatesEverything() {
        assertSame(AutonomyLevel.A0, AutonomyLevel.INITIAL);
        assertFalse(AutonomyLevel.A0.permits(new Reversibility.Reversible()));
        assertEquals(1.00, AutonomyLevel.A0.confidenceThreshold(), 1e-9);
        assertEquals(1.00, AutonomyLevel.A0.auditSamplingRate(), 1e-9);
    }

    @Test
    void riskFloorRisesWithLevelButStopsAtIrreversibleLowImpact() {
        assertTrue(AutonomyLevel.A1.permits(new Reversibility.Reversible()));
        assertFalse(AutonomyLevel.A1.permits(new Reversibility.CostlyButReversible()));

        assertTrue(AutonomyLevel.A2.permits(new Reversibility.CostlyButReversible()));
        assertFalse(AutonomyLevel.A2.permits(new Reversibility.IrreversibleLowImpact()));

        assertTrue(AutonomyLevel.A3.permits(new Reversibility.IrreversibleLowImpact()));
        assertTrue(AutonomyLevel.A4.permits(new Reversibility.IrreversibleLowImpact()));
    }

    // ── D3: IrreversibleHighImpact is never autonomous, A4 == A3 floor ───────

    @Test
    void noLevelMakesIrreversibleHighImpactAutonomous() {
        for (AutonomyLevel level : AutonomyLevel.values()) {
            assertFalse(level.permits(new Reversibility.IrreversibleHighImpact()),
                    level + " must never permit IrreversibleHighImpact");
        }
    }

    @Test
    void a4DiffersFromA3OnlyInThresholdAndAudit() {
        assertTrue(AutonomyLevel.A3.permits(new Reversibility.IrreversibleLowImpact())
                == AutonomyLevel.A4.permits(new Reversibility.IrreversibleLowImpact()));
        assertTrue(AutonomyLevel.A4.confidenceThreshold() < AutonomyLevel.A3.confidenceThreshold());
        assertTrue(AutonomyLevel.A4.auditSamplingRate() < AutonomyLevel.A3.auditSamplingRate());
    }

    // ── D5: promotion / demotion clamp at the ends ──────────────────────────

    @Test
    void promotedAndDemotedClampAtA4AndA0() {
        assertSame(AutonomyLevel.A1, AutonomyLevel.A0.promoted());
        assertSame(AutonomyLevel.A4, AutonomyLevel.A4.promoted());
        assertSame(AutonomyLevel.A0, AutonomyLevel.A0.demoted());
        assertSame(AutonomyLevel.A3, AutonomyLevel.A4.demoted());
    }

    // ── D2: the unified escalation rule via AutonomyPolicy.fixed(...) ────────

    @Test
    void condition1_approvalRequiredToolAlwaysEscalatesEvenAtA4() {
        AutonomyPolicy policy = AutonomyPolicy.fixed(AutonomyLevel.A4);
        assertTrue(policy.escalate("send_email", tool(new Reversibility.IrreversibleHighImpact()), 1.0));
    }

    @Test
    void condition2_actionAboveTheLevelFloorEscalates() {
        AutonomyPolicy a1 = AutonomyPolicy.fixed(AutonomyLevel.A1);
        assertTrue(a1.escalate("job", tool(new Reversibility.CostlyButReversible()), 1.0));
        assertFalse(a1.escalate("job", tool(new Reversibility.Reversible()), 1.0));
    }

    @Test
    void condition3_confidenceBelowTheLevelThresholdEscalates() {
        AutonomyPolicy a2 = AutonomyPolicy.fixed(AutonomyLevel.A2);   // threshold 0.80
        assertTrue(a2.escalate("job", tool(new Reversibility.Reversible()), 0.79));
        assertFalse(a2.escalate("job", tool(new Reversibility.Reversible()), 0.80));
    }

    @Test
    void fixedRejectsNullLevel() {
        assertThrows(NullPointerException.class, () -> AutonomyPolicy.fixed(null));
    }
}
