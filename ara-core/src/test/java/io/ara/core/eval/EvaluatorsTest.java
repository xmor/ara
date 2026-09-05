package io.ara.core.eval;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.common.AgentId;
import io.ara.core.common.Money;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0070 / ADR-019: the deterministic built-in {@link EvaluationStrategy} implementations
 * and their registration in {@link StrategyRegistry#defaults()}.
 */
class EvaluatorsTest {

    private static AgentResponse output(String content) {
        return new AgentResponse("t", AgentId.of("a"), content, AgentState.DONE,
                1, 0, 0, Money.ZERO_EUR, Duration.ofMillis(1), null, Instant.EPOCH, List.of(), null);
    }

    private static EvalCase caseWith(String strategyId, Map<String, String> config) {
        return new EvalCase("c", "s", false, List.of(), "curated", "in", Map.of(), strategyId, config, 0);
    }

    @Test
    void exactMatch_respectsTrimAndIgnoreCase() {
        var s = new io.ara.core.eval.evaluator.ExactMatchEvaluator();
        assertTrue(s.evaluate(output("  LGTM \n"), caseWith("exact_match", Map.of("expected", "LGTM"))).passed());
        assertFalse(s.evaluate(output("lgtm"), caseWith("exact_match", Map.of("expected", "LGTM"))).passed());
        assertTrue(s.evaluate(output("lgtm"),
                caseWith("exact_match", Map.of("expected", "LGTM", "ignore_case", "true"))).passed());
        assertFalse(s.evaluate(output("x"), caseWith("exact_match", Map.of())).passed(), "missing config → error");
    }

    @Test
    void regex_findVsFullMatch() {
        var s = new io.ara.core.eval.evaluator.RegexEvaluator();
        assertTrue(s.evaluate(output("error code 42 here"), caseWith("regex", Map.of("pattern", "code \\d+"))).passed());
        assertFalse(s.evaluate(output("error code 42 here"),
                caseWith("regex", Map.of("pattern", "code \\d+", "full_match", "true"))).passed());
        assertFalse(s.evaluate(output("x"), caseWith("regex", Map.of("pattern", "["))).passed(), "bad regex → error");
    }

    @Test
    void contains_and_nonEmpty() {
        assertTrue(new io.ara.core.eval.evaluator.ContainsEvaluator()
                .evaluate(output("the diff has an issue"), caseWith("contains", Map.of("substring", "issue"))).passed());
        assertTrue(new io.ara.core.eval.evaluator.NonEmptyEvaluator()
                .evaluate(output("anything"), caseWith("non_empty", Map.of())).passed());
        assertFalse(new io.ara.core.eval.evaluator.NonEmptyEvaluator()
                .evaluate(output("   "), caseWith("non_empty", Map.of())).passed());
    }

    @Test
    void jsonWellFormed() {
        var s = new io.ara.core.eval.evaluator.JsonWellFormedEvaluator();
        assertTrue(s.evaluate(output("{\"ok\":true,\"n\":[1,2]}"), caseWith("json_well_formed", Map.of())).passed());
        assertFalse(s.evaluate(output("{not json"), caseWith("json_well_formed", Map.of())).passed());
    }

    @Test
    void assertion_dispatchesOnWhicheverKeyIsPresent_elseNonEmpty() {
        var s = new io.ara.core.eval.evaluator.AssertionEvaluator();
        assertTrue(s.evaluate(output("YES"), caseWith("assertion", Map.of("expected", "YES"))).passed());
        assertTrue(s.evaluate(output("v1.2.3"), caseWith("assertion", Map.of("pattern", "v\\d"))).passed());
        assertTrue(s.evaluate(output("has foo"), caseWith("assertion", Map.of("substring", "foo"))).passed());
        assertTrue(s.evaluate(output("anything"), caseWith("assertion", Map.of())).passed(), "no key → non_empty floor");
    }

    @Test
    void judgePlaceholder_isAdvisoryNeutral() {
        var r = new io.ara.core.eval.evaluator.PlaceholderJudgeEvaluator()
                .evaluate(output("whatever"), caseWith("judge", Map.of()));
        assertEquals(0.5, r.score(), 1e-9);
        assertTrue(r.passed());
    }

    @Test
    void defaultsRegistersEveryBuiltInId() {
        StrategyRegistry r = StrategyRegistry.defaults();
        for (String id : List.of("exact_match", "regex", "contains", "non_empty",
                "json_well_formed", "schema", "assertion", "judge")) {
            assertTrue(r.has(id), id);
        }
        assertFalse(r.has("nope"));
        assertEquals("exact_match", r.resolve("exact_match").orElseThrow().strategyId());
    }
}
