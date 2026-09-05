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
import java.util.HashMap;
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

        List<EvalCase> cases = repo.findCases(suiteId, holdout).stream()
                .filter(EvalCase::countsTowardVerdict)   // DRAFT cases stay in the corpus, out of the verdict (ADR-0071 D4)
                .toList();

        Map<String, CaseStats> perCase = new LinkedHashMap<>();
        Map<String, Boolean> blocking = new HashMap<>();
        List<Spend> runCosts = new ArrayList<>();
        RunBudget cap = evalBudgetCap.get();   // nullable — no cap, measurement only
        boolean capExceeded = false;
        String capBreachDetail = null;

        for (EvalCase c : cases) {
            if (capExceeded) {
                break;   // stop before the next case, never mid-case (see class-level "Cost")
            }
            EvaluationStrategy strategy = strategies.resolve(c.evaluationStrategy())
                    .orElseThrow(() -> new IllegalStateException(
                            "no EvaluationStrategy registered for '" + c.evaluationStrategy()
                                    + "' (case " + c.caseId() + ") — register one or leave the case DRAFT"));
            double[] scores = new double[n];
            for (int i = 0; i < n; i++) {
                AgentResponse response = agent.execute(AgentTask.of(c.input(), c.context()));
                scores[i] = scoreOf(response, c, strategy);
                Spend spend = costOf(response);
                runCosts.add(spend);
                if (cap != null && cap.charge(spend) instanceof RunBudget.Charge.Exceeded ex) {
                    capExceeded = true;
                    capBreachDetail = "eval run budget exceeded on " + ex.axis() + ": " + ex.detail();
                }
            }
            perCase.put(c.caseId(), CaseStats.of(c.caseId(), c.holdout(), scores));
            blocking.put(c.caseId(), isBlocking(c));
        }

        Optional<EvalResult> baseline = baselineForSuite.apply(suiteId);
        Map<String, Double> perTag = perTag(cases, perCase);
        List<String> regressions = regressions(perCase, baseline);
        boolean truncated = capExceeded && perCase.size() < cases.size();
        Verdict verdict = truncated
                ? new Verdict.NeedsReview(capBreachDetail + " — "
                        + (cases.size() - perCase.size()) + " case(s) not run before the cap stopped the eval")
                : verdict(perCase, blocking, baseline, holdout);

        EvalResult result = new EvalResult(UUID.randomUUID().toString(), specHash, suiteId, n,
                perCase, perTag, regressions, verdict, List.copyOf(runCosts));
        repo.saveResult(result);
        return result;
    }

    private static double scoreOf(AgentResponse response, EvalCase c, EvaluationStrategy strategy) {
        if (!response.isSuccess()) {
            return 0.0;   // an agent that could not complete the task scores zero, whatever the verifier says
        }
        EvaluationResult r = strategy.evaluate(response, c);
        return Math.max(0.0, Math.min(1.0, r.score()));
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

    private static Map<String, Double> perTag(List<EvalCase> cases, Map<String, CaseStats> perCase) {
        Map<String, List<Double>> byTag = new LinkedHashMap<>();
        for (EvalCase c : cases) {
            CaseStats stats = perCase.get(c.caseId());
            if (stats == null) {
                continue;
            }
            for (String tag : c.tags()) {
                byTag.computeIfAbsent(tag, k -> new ArrayList<>()).add(stats.meanScore());
            }
        }
        Map<String, Double> out = new LinkedHashMap<>();
        byTag.forEach((tag, scores) ->
                out.put(tag, scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.0)));
        return out;
    }

    private List<String> regressions(Map<String, CaseStats> perCase, Optional<EvalResult> baseline) {
        if (baseline.isEmpty()) {
            return List.of();
        }
        Map<String, CaseStats> base = baseline.get().perCase();
        List<String> out = new ArrayList<>();
        perCase.forEach((caseId, now) -> {
            CaseStats before = base.get(caseId);
            if (before != null
                    && before.meanScore() >= CASE_PASS_THRESHOLD
                    && now.meanScore() < CASE_PASS_THRESHOLD) {
                out.add(caseId);
            }
        });
        return List.copyOf(out);
    }

    private Verdict verdict(Map<String, CaseStats> perCase, Map<String, Boolean> blocking,
                            Optional<EvalResult> baseline, boolean holdout) {
        if (perCase.isEmpty()) {
            return new Verdict.NeedsReview("the suite has no evaluable (READY) cases");
        }

        // D1 — blocking veto: a blocking verifier failing rejects outright (ADR-0059 D1).
        for (var e : perCase.entrySet()) {
            if (Boolean.TRUE.equals(blocking.get(e.getKey())) && e.getValue().meanScore() < CASE_PASS_THRESHOLD) {
                return new Verdict.Reject("blocking verifier failed on case " + e.getKey()
                        + " (mean score " + fmt(e.getValue().meanScore()) + ")");
            }
        }

        // D2 — advisory judge below threshold, no blocking failure → a human decides.
        for (var e : perCase.entrySet()) {
            if (!Boolean.TRUE.equals(blocking.get(e.getKey())) && e.getValue().meanScore() < JUDGE_ADVISORY_THRESHOLD) {
                return new Verdict.NeedsReview("advisory judge score " + fmt(e.getValue().meanScore())
                        + " below " + JUDGE_ADVISORY_THRESHOLD + " on case " + e.getKey());
            }
        }

        // D3 — the mean gain over the baseline must exceed the observed variance (ADR-0059 D3 / D2 DR-4).
        if (baseline.isPresent()) {
            double now = overallMean(perCase);
            double before = overallMean(baseline.get().perCase());
            double variance = pooledStdev(perCase);
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

    private static double overallMean(Map<String, CaseStats> perCase) {
        return perCase.values().stream().mapToDouble(CaseStats::meanScore).average().orElse(0.0);
    }

    /** Root-mean-square of the per-case stdevs — the spread the gain has to clear. */
    private static double pooledStdev(Map<String, CaseStats> perCase) {
        double sumSq = perCase.values().stream().mapToDouble(s -> s.stdev() * s.stdev()).sum();
        int n = perCase.size();
        return n == 0 ? 0.0 : Math.sqrt(sumSq / n);
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }
}
