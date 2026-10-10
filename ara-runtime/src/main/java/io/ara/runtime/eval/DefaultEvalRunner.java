package io.ara.runtime.eval;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.budget.RunBudget;
import io.ara.core.budget.Spend;
import io.ara.core.eval.CaseStats;
import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvalResult;
import io.ara.core.eval.EvaluationResult;
import io.ara.core.eval.EvaluationStrategy;
import io.ara.core.eval.StrategyRegistry;
import io.ara.core.eval.Verdict;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The concrete {@link EvalRunner} (ADR-0070 D3/D4): runs a suite's cases against an agent
 * {@code N ≥ 3} times each, scores every run through the case's {@link EvaluationStrategy},
 * aggregates into {@link CaseStats}, and computes a {@link Verdict} with the ADR-0059
 * cascade.
 *
 * <h2>Wiring</h2>
 * <ul>
 *   <li>{@code agentForSpecHash} resolves the {@code specHash} to a built {@link AraAgent}
 *       — the "out of band" resolution ADR-0070's port comment calls for (the runner
 *       stays free of {@code AgentSpec}, which is in another module);</li>
 *   <li>{@code baselineForSuite} returns the current {@code Default}'s last {@link EvalResult}
 *       for a suite, or empty — the ADR-0059 D3 "improvement over baseline" needs it.
 *       Empty ⇒ the variance step is skipped (a first-ever eval has nothing to beat).</li>
 * </ul>
 *
 * <h2>Cost</h2>
 * Each run is measured through a per-run {@link RunBudget} (ADR-054 D6) and the resulting
 * {@link Spend}s land on {@link EvalResult#runCosts()} — ADR-0085 D1 medians them to size
 * a topology's relative cost. That measuring budget is always uncapped ({@link #costOf}).
 * A second, optional {@link RunBudget} — {@link #evalBudgetCap}, one fresh instance per
 * {@link #run}/{@link #runHoldoutOnly} call — can additionally <em>enforce</em> a cap
 * across the whole call: every case starts only if the cap is not yet breached, so a
 * breach never cuts a case's {@code n} repetitions short (a {@link CaseStats} always
 * reflects a complete measurement, never a partial one) — the run instead stops before
 * the next case, mirroring {@link RunBudget#charge}'s own rule of stopping on the next
 * activation, not mid-activation. When it stops early, the verdict is forced to {@link
 * Verdict.NeedsReview}: an incomplete suite is not evidence either the cascade in {@link
 * #verdict} or a hold-out gate can safely act on. The default constructors leave this cap
 * unset ({@code () -> null}), matching the previous measurement-only behaviour.
 *
 * <h2>What is not here yet</h2>
 * Environment-provisioned evaluators (executable tests, browser/SQL) and a real LLM judge
 * are deferred (ADR-0070) — {@link StrategyRegistry#defaults()} ships deterministic
 * built-ins and an advisory judge placeholder.
 */
public final class DefaultEvalRunner implements EvalRunner {

    /** Mean score at or above which a case is considered "passing" (declared default, ADR-0059). */
    public static final double CASE_PASS_THRESHOLD = 0.5;

    /** A judge (advisory) case below this mean, with no blocking failure, forces {@code NeedsReview} (ADR-0059 D2). */
    public static final double JUDGE_ADVISORY_THRESHOLD = 0.6;

    private final EvalRepository repo;
    private final StrategyRegistry strategies;
    private final Function<String, AraAgent> agentForSpecHash;
    private final Function<String, Optional<EvalResult>> baselineForSuite;
    private final Supplier<RunBudget> evalBudgetCap;
    private final EvalSampleListener samples;

    public DefaultEvalRunner(EvalRepository repo, StrategyRegistry strategies,
                             Function<String, AraAgent> agentForSpecHash) {
        this(repo, strategies, agentForSpecHash, suiteId -> Optional.empty());
    }

    public DefaultEvalRunner(EvalRepository repo, StrategyRegistry strategies,
                             Function<String, AraAgent> agentForSpecHash,
                             Function<String, Optional<EvalResult>> baselineForSuite) {
        this(repo, strategies, agentForSpecHash, baselineForSuite, () -> null);
    }

    /**
     * @param evalBudgetCap supplies a fresh {@link RunBudget} at the start of every {@link
     *                      #run}/{@link #runHoldoutOnly} call to enforce a real cap on that
     *                      call's total spend (see the class-level "Cost" section) —
     *                      {@code () -> null} leaves the eval uncapped, the behaviour of the
     *                      other constructors.
     */
    public DefaultEvalRunner(EvalRepository repo, StrategyRegistry strategies,
                             Function<String, AraAgent> agentForSpecHash,
                             Function<String, Optional<EvalResult>> baselineForSuite,
                             Supplier<RunBudget> evalBudgetCap) {
        this(repo, strategies, agentForSpecHash, baselineForSuite, evalBudgetCap, EvalSampleListener.NONE);
    }

    /**
     * @param samples told about every single execution — the answer and the score's rationale,
     *                which {@link EvalResult} does not keep (see {@link EvalSample})
     */
    public DefaultEvalRunner(EvalRepository repo, StrategyRegistry strategies,
                             Function<String, AraAgent> agentForSpecHash,
                             Function<String, Optional<EvalResult>> baselineForSuite,
                             Supplier<RunBudget> evalBudgetCap,
                             EvalSampleListener samples) {
        this.samples          = Objects.requireNonNull(samples, "samples must not be null");
        this.repo             = Objects.requireNonNull(repo, "repo must not be null");
        this.strategies       = Objects.requireNonNull(strategies, "strategies must not be null");
        this.agentForSpecHash = Objects.requireNonNull(agentForSpecHash, "agentForSpecHash must not be null");
        this.baselineForSuite = Objects.requireNonNull(baselineForSuite, "baselineForSuite must not be null");
        this.evalBudgetCap    = Objects.requireNonNull(evalBudgetCap, "evalBudgetCap must not be null");
    }

    @Override
    public EvalResult run(String specHash, String suiteId, int nRunsPerCase) {
        return evaluate(specHash, suiteId, nRunsPerCase, false);
    }

    @Override
    public EvalResult runHoldoutOnly(String specHash, String suiteId, int nRunsPerCase) {
        return evaluate(specHash, suiteId, nRunsPerCase, true);
    }

    private EvalResult evaluate(String specHash, String suiteId, int n, boolean holdout) {
        Objects.requireNonNull(specHash, "specHash must not be null");
        Objects.requireNonNull(suiteId, "suiteId must not be null");
        if (n < CaseStats.MIN_RUNS) {
            throw new IllegalArgumentException("nRunsPerCase must be >= " + CaseStats.MIN_RUNS + ", got: " + n);
        }
        AraAgent agent = Objects.requireNonNull(agentForSpecHash.apply(specHash),
                "no agent could be resolved for spec " + specHash);
        // Fixed before the first run rather than at the end: the samples reported while the eval
        // runs must carry the id the result will have, or nothing could join them to it.
        String evalId = UUID.randomUUID().toString();

        List<EvalCase> cases = repo.findCases(suiteId, holdout).stream()
                .filter(EvalCase::countsTowardVerdict)   // DRAFT cases stay in the corpus, out of the verdict (ADR-0071 D4)
                .toList();

        List<CaseRun> runs = new ArrayList<>();
        RunBudget cap = evalBudgetCap.get();   // nullable — no cap, measurement only
        String capBreachDetail = null;

        for (EvalCase c : cases) {
            if (capBreachDetail != null) {
                break;   // stop before the next case, never mid-case (see class-level "Cost")
            }
            CaseRun run = measure(c, agent, evalId, n);
            runs.add(run);
            // the cap is charged per case rather than per run: a breach is only ever acted upon
            // between cases, so the sequence of charges it sees is the same either way
            capBreachDetail = chargeCap(cap, run.costs());
        }

        Optional<EvalResult> baseline = baselineForSuite.apply(suiteId);
        Map<String, Double> perTag = perTag(runs);
        List<String> regressions = regressions(runs, baseline);
        boolean truncated = capBreachDetail != null && runs.size() < cases.size();
        Verdict verdict = truncated
                ? new Verdict.NeedsReview(capBreachDetail + " — "
                        + (cases.size() - runs.size()) + " case(s) not run before the cap stopped the eval")
                : verdict(runs, baseline, holdout);

        EvalResult result = new EvalResult(evalId, specHash, suiteId, n,
                perCase(runs), perTag, regressions, verdict, runCosts(runs));
        repo.saveResult(result);
        return result;
    }

    /**
     * One case, run {@code n} times and measured. The budget cap is deliberately not charged
     * here — a cap breach only ever stops the eval <em>between</em> cases, which is
     * {@link #evaluate}'s business (see the class-level "Cost" section).
     */
    private CaseRun measure(EvalCase c, AraAgent agent, String evalId, int n) {
        EvaluationStrategy strategy = strategies.resolve(c.evaluationStrategy())
                .orElseThrow(() -> new IllegalStateException(
                        "no EvaluationStrategy registered for '" + c.evaluationStrategy()
                                + "' (case " + c.caseId() + ") — register one or leave the case DRAFT"));
        double[] scores = new double[n];
        List<Spend> costs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            // The correlation id becomes the runId of the agent's trace spans. Before, a run
            // was keyed by its random task id, which named nothing; this one names the eval,
            // the case and the execution, so a span is traceable to the sample it produced.
            String correlationId = evalId + "/" + c.caseId() + "/" + i;
            AgentResponse response = agent.execute(
                    AgentTask.of(c.input(), c.context(), correlationId, "system"));
            EvaluationResult scored = scoreOf(response, c, strategy);
            scores[i] = scored.score();
            samples.onSample(new EvalSample(evalId, c.caseId(), c.holdout(), i, correlationId,
                    response.isSuccess(), response.failureReasonOpt().orElse(null),
                    response.isSuccess() ? response.content() : "",
                    scored.score(), scored.rationale(), scored.metadata(), response.elapsedTime()));
            costs.add(costOf(response));
        }
        double worstRun = java.util.Arrays.stream(scores).min().orElse(0.0);
        return new CaseRun(c, CaseStats.of(c.caseId(), c.holdout(), scores), worstRun, isBlocking(c), List.copyOf(costs));
    }

    /**
     * One measured case: what it scored, whether it blocks, and what its runs cost. Replaces the
     * three collections {@link #evaluate} used to fill in lockstep — {@code perCase},
     * {@code blocking} and {@code runCosts} — which nothing but a shared caseId kept in step: a
     * case that was measured but never classified was a state the code allowed and no test could
     * see. The {@link EvalCase} travels with the numbers, so a case's tags and hold-out flag
     * cannot end up beside another case's stats. {@code worstRun} is the lowest of the case's
     * run scores: the mean hides a run that failed, and a veto has to see it.
     */
    private record CaseRun(EvalCase evalCase, CaseStats stats, double worstRun, boolean blocking,
                           List<Spend> costs) {
        String caseId() {
            return evalCase.caseId();
        }
    }

    /**
     * Charges {@code cap} (nullable) with one case's spends; the detail of the last breach, or
     * {@code null} when the cap held. Charging the case's runs as a batch rather than one by one
     * is not a change of accounting: nothing charges the cap in between, so the breach it reports
     * is the same one the per-run loop reported.
     */
    private static String chargeCap(RunBudget cap, List<Spend> costs) {
        if (cap == null) {
            return null;
        }
        String breach = null;
        for (Spend spend : costs) {
            if (cap.charge(spend) instanceof RunBudget.Charge.Exceeded ex) {
                breach = "eval run budget exceeded on " + ex.axis() + ": " + ex.detail();
            }
        }
        return breach;
    }

    /**
     * The score for one execution. A successful response is scored by the case's own strategy and
     * its number is taken as-is: an {@link EvaluationResult} cannot hold a value outside
     * {@code [0, 1]} — its compact constructor rejects one — so a clamp here would never see a
     * different number than the one the strategy already decided on.
     *
     * <p>A verifier that computes a score outside the range is therefore a bug in that verifier,
     * and it fails <em>there</em>, loudly, aborting the eval. That is the honest place for it: a
     * clamp here would quietly fold a broken verifier into the mean and the stdev, so the
     * resulting verdict would be evidence about a measurement nobody made.
     */
    private static EvaluationResult scoreOf(AgentResponse response, EvalCase evalCase, EvaluationStrategy strategy) {
        if (!response.isSuccess()) {
            // an agent that could not complete the task scores zero, whatever the verifier says
            return EvaluationResult.fail(0.0, "no answer: "
                    + response.failureReasonOpt().orElse("the agent did not complete"));
        }
        return strategy.evaluate(response, evalCase);
    }

    /**
     * The cost of one run, measured through a per-run {@link RunBudget} (ADR-054 D6) —
     * ADR-0085 D1 medians these to size a topology's relative cost. The budget is
     * uncapped: this is measurement, not enforcement (a capped eval run is a later
     * increment). The {@code calls} axis uses {@link AgentResponse#iterationsUsed()} as
     * the LLM-call proxy; {@code 0} for a deterministic agent.
     */
    private static Spend costOf(AgentResponse response) {
        RunBudget budget = RunBudget.of().currency(response.estimatedCost().currency()).build();
        budget.charge(Spend.of(response.estimatedCost(), response.totalTokens(), Math.max(0, response.iterationsUsed())));
        return budget.spent();
    }

    /**
     * A case vetoes on failure (ADR-0059 D1) unless it is a judge case — programmatic
     * verifiers default blocking (ADR-0075 D2), a judge is advisory (ADR-0059 D2). An
     * explicit {@code evaluationConfig["blocking"]} wins either way (ADR-0075 D6).
     */
    private static boolean isBlocking(EvalCase c) {
        String explicit = c.evaluationConfig().get("blocking");
        if (explicit != null) {
            return "true".equalsIgnoreCase(explicit);
        }
        return !"judge".equals(c.evaluationStrategy());
    }

    /** The measured stats by case id, in the order the cases were run. */
    private static Map<String, CaseStats> perCase(List<CaseRun> runs) {
        Map<String, CaseStats> out = new LinkedHashMap<>();
        runs.forEach(run -> out.put(run.caseId(), run.stats()));
        return out;
    }

    /** Every run's cost, in the order the runs happened — {@link EvalResult#runCosts()}. */
    private static List<Spend> runCosts(List<CaseRun> runs) {
        return runs.stream().flatMap(run -> run.costs().stream()).toList();
    }

    private static Map<String, Double> perTag(List<CaseRun> runs) {
        Map<String, List<Double>> byTag = new LinkedHashMap<>();
        for (CaseRun run : runs) {
            for (String tag : run.evalCase().tags()) {
                byTag.computeIfAbsent(tag, k -> new ArrayList<>()).add(run.stats().meanScore());
            }
        }
        Map<String, Double> out = new LinkedHashMap<>();
        byTag.forEach((tag, scores) ->
                out.put(tag, scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0)));
        return out;
    }

    private List<String> regressions(List<CaseRun> runs, Optional<EvalResult> baseline) {
        if (baseline.isEmpty()) {
            return List.of();
        }
        Map<String, CaseStats> base = baseline.get().perCase();
        List<String> out = new ArrayList<>();
        for (CaseRun run : runs) {
            CaseStats before = base.get(run.caseId());
            if (before != null
                    && before.meanScore() >= CASE_PASS_THRESHOLD
                    && run.stats().meanScore() < CASE_PASS_THRESHOLD) {
                out.add(run.caseId());
            }
        }
        return List.copyOf(out);
    }

    private Verdict verdict(List<CaseRun> runs, Optional<EvalResult> baseline, boolean holdout) {
        if (runs.isEmpty()) {
            return new Verdict.NeedsReview("the suite has no evaluable (READY) cases");
        }

        // D1 — blocking veto: a blocking verifier failing rejects outright (ADR-0059 D1). The test
        // is the worst run, not the mean: a verifier that failed on one run of three has failed, and
        // a mean of 2/3 would let it through while the case is still a flaky one. The mean stays in
        // the message so the reader can tell a case that never passed from one that passed mostly.
        for (CaseRun run : runs) {
            if (run.blocking() && run.worstRun() < CASE_PASS_THRESHOLD) {
                return new Verdict.Reject("blocking verifier failed on case " + run.caseId()
                        + " (worst run " + fmt(run.worstRun()) + ", mean score "
                        + fmt(run.stats().meanScore()) + ")");
            }
        }

        // D2 — advisory judge below threshold, no blocking failure → a human decides.
        for (CaseRun run : runs) {
            if (!run.blocking() && run.stats().meanScore() < JUDGE_ADVISORY_THRESHOLD) {
                return new Verdict.NeedsReview("advisory judge score " + fmt(run.stats().meanScore())
                        + " below " + JUDGE_ADVISORY_THRESHOLD + " on case " + run.caseId());
            }
        }

        // D3 — the mean gain over the baseline must exceed the observed variance (ADR-0059 D3 / D2 DR-4).
        if (baseline.isPresent()) {
            List<CaseStats> stats = runs.stream().map(CaseRun::stats).toList();
            double now = overallMean(stats);
            double before = overallMean(baseline.get().perCase().values());
            double variance = pooledStdev(stats);
            double gain = now - before;
            if (gain <= variance) {
                String why = "mean gain " + fmt(gain) + " does not exceed the observed variance "
                        + fmt(variance) + " (ADR-0059 D3)";
                return holdout ? new Verdict.RejectOverfit() : new Verdict.Reject(why);
            }
        }

        // Passed the cascade: a candidate for canary (confirmed by the hold-out partition when holdout==true).
        return new Verdict.PromoteToCanary();
    }

    private static double overallMean(Collection<CaseStats> stats) {
        return stats.stream().mapToDouble(CaseStats::meanScore).average().orElse(0.0);
    }

    /** Root-mean-square of the per-case stdevs — the spread the gain has to clear. */
    private static double pooledStdev(Collection<CaseStats> stats) {
        double sumSq = stats.stream().mapToDouble(s -> s.stdev() * s.stdev()).sum();
        int n = stats.size();
        return n == 0 ? 0.0 : Math.sqrt(sumSq / n);
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }
}
