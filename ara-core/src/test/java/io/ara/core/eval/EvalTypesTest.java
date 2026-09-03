package io.ara.core.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0070: the relocated eval value types — {@link EvalCase} (+holdout/tags, D1),
 * {@link CaseStats} (mean/stdev over N≥3, D2), {@link Verdict} (the ADR-0059 cascade
 * outcomes, D4), {@link EvaluationResult} (verbatim from ADR-019).
 */
class EvalTypesTest {

    // D1 — EvalCase invariants + the holdout/tags additions
    @Test
    void evalCase_defaultsAndGuards() {
        EvalCase c = EvalCase.curated("c1", "s1", "do the thing", "exact_match", 0);
        assertFalse(c.holdout());
        assertTrue(c.tags().isEmpty());
        assertEquals("curated", c.origin());

        assertTrue(c.asHoldout().holdout());
        assertEquals("c1", c.asHoldout().caseId(), "asHoldout keeps identity");

        assertThrows(IllegalArgumentException.class,
                () -> EvalCase.curated(" ", "s1", "x", "exact_match", 0));
        assertThrows(NullPointerException.class,
                () -> EvalCase.curated("c1", "s1", null, "exact_match", 0));
    }

    @Test
    void evalCase_collectionsAreDefensivelyCopiedAndImmutable() {
        java.util.List<String> tags = new java.util.ArrayList<>(List.of("finance"));
        EvalCase c = new EvalCase("c1", "s1", false, tags, "curated", "in",
                Map.of(), "regex", Map.of(), 1);
        tags.add("mutated");
        assertEquals(List.of("finance"), c.tags());
        assertThrows(UnsupportedOperationException.class, () -> c.tags().add("x"));
    }

    // D2 — CaseStats: mean + population stdev, N≥3 enforced
    @Test
    void caseStats_aggregatesMeanAndPopulationStdev() {
        CaseStats s = CaseStats.of("c1", false, 1.0, 0.0, 0.5);
        assertEquals(0.5, s.meanScore(), 1e-9);
        // population stdev of {1, 0, 0.5}: mean 0.5, var = (0.25+0.25+0)/3 = 1/6
        assertEquals(Math.sqrt(1.0 / 6.0), s.stdev(), 1e-9);
        assertEquals(3, s.n());
    }

    @Test
    void caseStats_rejectsFewerThanThreeRuns() {
        assertThrows(IllegalArgumentException.class, () -> CaseStats.of("c1", false, 1.0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new CaseStats("c1", 0.5, 0.1, 2, false));
        assertEquals(3, CaseStats.MIN_RUNS);
    }

    // D4 — Verdict is a sealed set of exactly four outcomes; failure variants carry a reason
    @Test
    void verdict_isTheFourOutcomeCascadeSet() {
        for (Verdict v : List.of(new Verdict.PromoteToCanary(), new Verdict.Reject("no gain"),
                new Verdict.RejectOverfit(), new Verdict.NeedsReview("judge low"))) {
            String label = switch (v) {
                case Verdict.PromoteToCanary p -> "promote";
                case Verdict.Reject r          -> r.reason();
                case Verdict.RejectOverfit r   -> "overfit";
                case Verdict.NeedsReview n     -> n.reason();
            };
            assertFalse(label.isBlank());
        }
        assertThrows(NullPointerException.class, () -> new Verdict.Reject(null));
        assertThrows(NullPointerException.class, () -> new Verdict.NeedsReview(null));
    }

    // EvaluationResult — relocated verbatim from ADR-019
    @Test
    void evaluationResult_factoriesAndScoreRange() {
        assertTrue(EvaluationResult.pass(1.0, "exact match").passed());
        assertFalse(EvaluationResult.fail(0.2, "regex mismatch").passed());
        EvaluationResult err = EvaluationResult.error("npx not found");
        assertFalse(err.passed());
        assertEquals(0.0, err.score());
        assertTrue(err.rationale().contains("npx not found"));
        assertThrows(IllegalArgumentException.class, () -> EvaluationResult.pass(1.5, "x"));
    }

    // EvalResult — aggregate, nRunsPerCase guard, perTag/regressions
    @Test
    void evalResult_guardsRunsAndExposesAggregates() {
        CaseStats cs = CaseStats.of("c1", false, 0.9, 1.0, 0.8);
        EvalResult r = new EvalResult("e1", "a".repeat(64), "s1", 3,
                Map.of("c1", cs), Map.of("finance", 0.9), List.of("c-was-passing"),
                new Verdict.PromoteToCanary());

        assertInstanceOf(Verdict.PromoteToCanary.class, r.verdict());
        assertEquals(List.of("c-was-passing"), r.regressions());
        assertEquals(0.9, r.perTag().get("finance"));
        assertTrue(r.hasEnoughRuns());
        assertThrows(IllegalArgumentException.class, () -> new EvalResult(
                "e2", "h", "s1", 2, Map.of(), Map.of(), List.of(), new Verdict.RejectOverfit()));
    }
}
