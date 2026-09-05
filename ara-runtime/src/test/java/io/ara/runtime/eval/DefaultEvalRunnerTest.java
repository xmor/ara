package io.ara.runtime.eval;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.budget.RunBudget;
import io.ara.core.common.AgentId;
import io.ara.core.eval.CaseStats;
import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvalResult;
import io.ara.core.eval.EvalSuite;
import io.ara.core.eval.StrategyRegistry;
import io.ara.core.eval.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0070 D3/D4: the concrete runner — N≥3 runs per case, per-case {@link CaseStats},
 * per-tag aggregation, DRAFT exclusion, and the ADR-0059 cascade {@link Verdict}
 * (blocking veto → advisory judge → variance threshold → hold-out gate).
 */
class DefaultEvalRunnerTest {

    private static final String SUITE = "diff-review-suite";
    private final EvalRepository repo = EvalRepository.inMemory();

    @BeforeEach
    void seedSuite() {
        repo.saveSuite(new EvalSuite(SUITE, "Diff review", "structural + judge checks"));
    }

    /** An agent whose reply is a pure function of the task input. */
    private static AraAgent agent(Function<String, String> reply) {
        AgentId id = AgentId.generate();
        return new AraAgent() {
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.success(task.taskId(), id, reply.apply(task.input()),
                        1, 0, 0, Duration.ofMillis(1), List.of());
            }
            @Override public void terminate() {}
        };
    }

    private static AraAgent failingAgent() {
        AgentId id = AgentId.generate();
        return new AraAgent() {
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.failure(task.taskId(), id, "boom", Duration.ofMillis(1));
            }
            @Override public void terminate() {}
        };
    }

    private void addCase(String caseId, boolean holdout, EvalCase.Status status, List<String> tags,
                         String input, String strategyId, Map<String, String> config) {
        repo.saveCase(new EvalCase(caseId, SUITE, holdout, tags, "curated", input,
                Map.of(), strategyId, config, repo.findCases(SUITE).size(), status));
    }

    private DefaultEvalRunner runner(AraAgent agent) {
        return new DefaultEvalRunner(repo, StrategyRegistry.defaults(), specHash -> agent);
    }

    private DefaultEvalRunner runner(AraAgent agent, EvalResult baseline) {
        return new DefaultEvalRunner(repo, StrategyRegistry.defaults(), specHash -> agent,
                suiteId -> Optional.ofNullable(baseline));
    }

    private DefaultEvalRunner runner(AraAgent agent, java.util.function.Supplier<RunBudget> cap) {
        return new DefaultEvalRunner(repo, StrategyRegistry.defaults(), specHash -> agent,
                suiteId -> Optional.empty(), cap);
    }

    @Test
    void allCasesPassing_producesPromoteToCanaryWithPerCaseStats() {
        addCase("c1", false, EvalCase.Status.READY, List.of("shape"), "review this", "exact_match",
                Map.of("expected", "LGTM"));
        addCase("c2", false, EvalCase.Status.READY, List.of("shape"), "check that", "contains",
                Map.of("substring", "issue"));

        EvalResult r = runner(agent(in -> in.equals("review this") ? "LGTM" : "found an issue"))
                .run("spec-A", SUITE, 3);

        assertInstanceOf(Verdict.PromoteToCanary.class, r.verdict());
        assertEquals(2, r.perCase().size());
        assertEquals(3, r.perCase().get("c1").n());
        assertEquals(1.0, r.perCase().get("c1").meanScore(), 1e-9);
        assertEquals(1.0, r.perTag().get("shape"), 1e-9);
        assertTrue(repo.findResult(r.evalId()).isPresent(), "the result is persisted");
    }

    @Test
    void aFailingBlockingVerifierVetoes() {
        addCase("c1", false, EvalCase.Status.READY, List.of(), "x", "exact_match", Map.of("expected", "YES"));

        EvalResult r = runner(agent(in -> "NO")).run("spec-A", SUITE, 3);

        Verdict.Reject reject = assertInstanceOf(Verdict.Reject.class, r.verdict());
        assertTrue(reject.reason().contains("blocking verifier failed on case c1"));
    }

    @Test
    void aFailingAgentScoresZeroAndVetoesABlockingCase() {
        addCase("c1", false, EvalCase.Status.READY, List.of(), "x", "non_empty", Map.of());
        EvalResult r = runner(failingAgent()).run("spec-A", SUITE, 3);
        assertEquals(0.0, r.perCase().get("c1").meanScore(), 1e-9);
        assertInstanceOf(Verdict.Reject.class, r.verdict());
    }

    @Test
    void aLowJudgeScoreWithNoBlockingFailureNeedsReview() {
        addCase("c1", false, EvalCase.Status.READY, List.of(), "x", "exact_match", Map.of("expected", "ok"));
        addCase("j1", false, EvalCase.Status.READY, List.of(), "x", "judge", Map.of());   // placeholder → 0.5 < 0.6

        EvalResult r = runner(agent(in -> "ok")).run("spec-A", SUITE, 3);

        Verdict.NeedsReview nr = assertInstanceOf(Verdict.NeedsReview.class, r.verdict());
        assertTrue(nr.reason().contains("advisory judge"));
    }

    @Test
    void draftCasesAreExcludedFromTheVerdict() {
        addCase("c1", false, EvalCase.Status.READY, List.of(), "x", "exact_match", Map.of("expected", "ok"));
        addCase("d1", false, EvalCase.Status.DRAFT, List.of(), "x", "pending_human_verifier", Map.of());

        EvalResult r = runner(agent(in -> "ok")).run("spec-A", SUITE, 3);

        assertEquals(List.of("c1"), List.copyOf(r.perCase().keySet()), "the DRAFT case is not scored");
        assertInstanceOf(Verdict.PromoteToCanary.class, r.verdict());
    }

    @Test
    void runSkipsHoldoutCases_runHoldoutOnlyRunsOnlyThem() {
        addCase("c1", false, EvalCase.Status.READY, List.of(), "x", "exact_match", Map.of("expected", "ok"));
        addCase("h1", true, EvalCase.Status.READY, List.of(), "x", "exact_match", Map.of("expected", "ok"));

        AraAgent a = agent(in -> "ok");
        assertEquals(List.of("c1"), List.copyOf(runner(a).run("spec-A", SUITE, 3).perCase().keySet()));
        assertEquals(List.of("h1"), List.copyOf(runner(a).runHoldoutOnly("spec-A", SUITE, 3).perCase().keySet()));
    }

    @Test
    void withABaseline_noImprovementRejects_andRunHoldoutOnlyCallsItOverfit() {
        addCase("c1", false, EvalCase.Status.READY, List.of(), "x", "exact_match", Map.of("expected", "ok"));
        // baseline already scores this case a clean 1.0
        EvalResult baseline = new EvalResult("base", "spec-default", SUITE, 3,
                Map.of("c1", new CaseStats("c1", 1.0, 0.0, 3, false)), Map.of(), List.of(),
                new Verdict.PromoteToCanary());

        EvalResult onRun = runner(agent(in -> "ok"), baseline).run("spec-A", SUITE, 3);
        assertInstanceOf(Verdict.Reject.class, onRun.verdict());

        addCase("h1", true, EvalCase.Status.READY, List.of(), "x", "exact_match", Map.of("expected", "ok"));
        EvalResult baselineH = new EvalResult("baseH", "spec-default", SUITE, 3,
                Map.of("h1", new CaseStats("h1", 1.0, 0.0, 3, true)), Map.of(), List.of(),
                new Verdict.PromoteToCanary());
        EvalResult onHoldout = runner(agent(in -> "ok"), baselineH).runHoldoutOnly("spec-A", SUITE, 3);
        assertInstanceOf(Verdict.RejectOverfit.class, onHoldout.verdict());
    }

    @Test
    void withABaseline_aRealImprovementOverVariancePromotes() {
        addCase("c1", false, EvalCase.Status.READY, List.of(), "x", "exact_match", Map.of("expected", "ok"));
        EvalResult baseline = new EvalResult("base", "spec-default", SUITE, 3,
                Map.of("c1", new CaseStats("c1", 0.0, 0.0, 3, false)), Map.of(), List.of(),
                new Verdict.Reject("was bad"));

        EvalResult r = runner(agent(in -> "ok"), baseline).run("spec-A", SUITE, 3);

        assertInstanceOf(Verdict.PromoteToCanary.class, r.verdict());
        assertTrue(r.regressions().isEmpty(), "an improvement is not a regression");
    }

    @Test
    void regressions_areCasesThatPassedInTheBaselineAndFailNow() {
        addCase("c1", false, EvalCase.Status.READY, List.of(), "x", "exact_match", Map.of("expected", "ok"));
        EvalResult baseline = new EvalResult("base", "spec-default", SUITE, 3,
                Map.of("c1", new CaseStats("c1", 1.0, 0.0, 3, false)), Map.of(), List.of(),
                new Verdict.PromoteToCanary());

        EvalResult r = runner(agent(in -> "WRONG"), baseline).run("spec-A", SUITE, 3);

        assertEquals(List.of("c1"), r.regressions());
    }

    @Test
    void unknownStrategyIdFailsLoud_andTooFewRunsIsRejected() {
        addCase("c1", false, EvalCase.Status.READY, List.of(), "x", "no_such_strategy", Map.of());
        assertThrows(IllegalStateException.class, () -> runner(agent(in -> "x")).run("spec-A", SUITE, 3));
        assertThrows(IllegalArgumentException.class, () -> runner(agent(in -> "x")).run("spec-A", SUITE, 2));
    }

    @Test
    void variancePicksUpFlakyRuns() {
        addCase("c1", false, EvalCase.Status.READY, List.of(), "x", "exact_match", Map.of("expected", "ok"));
        AtomicInteger call = new AtomicInteger();
        // alternates ok / no across the 3 runs → mean 2/3, non-zero stdev
        EvalResult r = runner(agent(in -> call.getAndIncrement() % 2 == 0 ? "ok" : "no")).run("spec-A", SUITE, 3);
        CaseStats s = r.perCase().get("c1");
        assertEquals(2.0 / 3.0, s.meanScore(), 1e-9);
        assertTrue(s.stdev() > 0.0);
    }

    // ── ADR-054 D6 / ADR-0085 D1 — a RunBudget-measured cost per run ──────────

    /** An agent that reports a fixed cost / token draw per run. */
    private static AraAgent costedAgent(Function<String, String> reply, String eur, int tokens, int iterations) {
        AgentId id = AgentId.generate();
        return new AraAgent() {
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.success(task.taskId(), id, reply.apply(task.input()),
                        iterations, 0, tokens, io.ara.core.common.Money.of(eur, "EUR"),
                        Duration.ofMillis(1), List.of());
            }
            @Override public void terminate() {}
        };
    }

    @Test
    void aRealCapStopsBeforeTheNextCase_andForcesNeedsReview() {
        addCase("c1", false, EvalCase.Status.READY, List.of(), "x", "exact_match", Map.of("expected", "ok"));
        addCase("c2", false, EvalCase.Status.READY, List.of(), "y", "exact_match", Map.of("expected", "ok"));

        // 100 tokens/run × 3 runs = 300 for c1 alone, over a 250 cap — breached mid-c1,
        // reported only once c2 would otherwise start.
        EvalResult r = runner(costedAgent(in -> "ok", "0.00", 100, 1),
                () -> RunBudget.of().maxTokens(250).build()).run("spec-A", SUITE, 3);

        assertEquals(List.of("c1"), List.copyOf(r.perCase().keySet()), "c2 never starts once the cap is breached");
        assertEquals(3, r.runCosts().size(), "c1's own 3 runs are never cut short");
        Verdict.NeedsReview nr = assertInstanceOf(Verdict.NeedsReview.class, r.verdict());
        assertTrue(nr.reason().contains("run budget exceeded"), "reason: " + nr.reason());
        assertTrue(nr.reason().contains("TOKENS"), "reason: " + nr.reason());
        assertTrue(nr.reason().contains("1 case(s) not run"), "reason: " + nr.reason());
    }

    @Test
    void capBreachOnTheOnlyCase_doesNotForceNeedsReview_sinceNoCaseWasSkipped() {
        addCase("c1", false, EvalCase.Status.READY, List.of(), "x", "exact_match", Map.of("expected", "ok"));

        EvalResult r = runner(costedAgent(in -> "ok", "0.00", 100, 1),
                () -> RunBudget.of().maxTokens(250).build()).run("spec-A", SUITE, 3);

        assertInstanceOf(Verdict.PromoteToCanary.class, r.verdict(),
                "the cap was breached, but nothing was skipped, so the normal cascade still applies");
    }

    @Test
    void noCapSupplied_matchesUnlimitedMeasurementOnlyBehaviour() {
        addCase("c1", false, EvalCase.Status.READY, List.of(), "x", "exact_match", Map.of("expected", "ok"));

        EvalResult r = runner(costedAgent(in -> "ok", "0.00", 1_000_000, 1), () -> null).run("spec-A", SUITE, 3);

        assertInstanceOf(Verdict.PromoteToCanary.class, r.verdict());
    }

    @Test
    void recordsOnePerRunCostForEveryRunAcrossEveryCase() {
        addCase("c1", false, EvalCase.Status.READY, List.of("shape"), "x", "exact_match", Map.of("expected", "ok"));
        addCase("c2", false, EvalCase.Status.READY, List.of("shape"), "x", "exact_match", Map.of("expected", "ok"));

        EvalResult r = runner(costedAgent(in -> "ok", "0.02", 1_500, 2)).run("spec-A", SUITE, 3);

        assertEquals(6, r.runCosts().size(), "2 cases × 3 runs");
        assertTrue(r.runCosts().stream().allMatch(s ->
                s.money().equals(io.ara.core.common.Money.of("0.02", "EUR")) && s.tokens() == 1_500 && s.calls() == 2));
    }

    @Test
    void aDeterministicAgentWithNoReportedCost_yieldsZeroSpends() {
        addCase("c1", false, EvalCase.Status.READY, List.of(), "x", "exact_match", Map.of("expected", "ok"));

        EvalResult r = runner(agent(in -> "ok")).run("spec-A", SUITE, 3);

        assertEquals(3, r.runCosts().size());
        assertTrue(r.runCosts().stream().allMatch(s -> s.tokens() == 0 && s.money().amount().signum() == 0));
    }

    // measuredCosts_feedTopologyCostGate_asARatioOfMedians and
    // topologyEvalWithNoRecordedCosts_isNotComputable relocated to
    // io.ara.meta.evolution.TopologyCostGateMeasuredTest (ara-private/ara-meta) together
    // with PromotionPipeline.TopologyCostGate — this file keeps only the generic,
    // meta-agent-agnostic DefaultEvalRunner coverage.
}
