package io.ara.runtime.observability;

import io.ara.core.autonomy.AutonomyLevel;
import io.ara.core.common.Money;
import io.ara.core.eval.CaseStats;
import io.ara.core.eval.EvalResult;
import io.ara.core.trace.SpanStatus;
import io.ara.core.trace.TraceSpan;
import io.ara.core.trace.TraceStore;
import io.ara.runtime.agent.FailureKind;
import io.ara.runtime.autonomy.AutonomyLedger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The ADR-0074 observability read model: the eight dashboard panels, aggregated on demand
 * from {@link TraceStore} (ADR-0068), the current {@link EvalResult}s (ADR-0070) and the
 * {@link AutonomyLedger} (ADR-0073), with a short in-process cache. No metrics pipeline,
 * no fourth datastore, no rollup table (D1/D4) — a fresh query behind a short TTL.
 *
 * <h2>What this layer is, and is not</h2>
 * The ADR's full scope also adds a {@code Surface.OBSERVABILITY} on {@code ara-gateway}
 * (ADR-045 opt-in pattern, authenticated like {@code RUNS}/{@code CONTROL}). That HTTP
 * surface lives in the private gateway module; this class is the aggregation it would
 * serve — usable directly, and unit-testable without a running gateway.
 *
 * <h2>{@code task_class} grouping (D2)</h2>
 * D2 groups every trace panel by {@code task_class} via a join on {@code routing.label}.
 * {@link TraceSpan} does not carry a routing label at this layer, so the join is a
 * caller-supplied {@link Function} ({@code taskClassOf}); the convenience constructor
 * defaults it to {@link TraceSpan#agentId()}. Swap in the real routing-label resolver once
 * the label is on the span.
 *
 * <h2>Cache (D4)</h2>
 * {@link #snapshot()} recomputes only when the last snapshot is older than the configured
 * TTL (default {@value #DEFAULT_CACHE_TTL_SECONDS}s — a starting point, not a final value).
 * {@link #invalidate()} drops it early.
 */
public final class DashboardQueryService {

    /** D4 — starting value for the snapshot cache TTL, same order of magnitude as ADR-032's min trigger cadence. */
    public static final long DEFAULT_CACHE_TTL_SECONDS = 60;

    /** D5 — the rolling horizon every trend panel reports alongside the cumulative one. */
    public static final Duration ROLLING_WINDOW = Duration.ofDays(30);

    private final TraceStore traceStore;
    private final Supplier<? extends Collection<EvalResult>> evalResults;
    private final AutonomyLedger ledger;
    private final Function<TraceSpan, String> taskClassOf;
    private final Duration cacheTtl;
    private final Clock clock;

    private DashboardSnapshot cached;

    /** Aggregation with default {@code task_class} = {@code agentId}, 60s cache, system UTC clock. */
    public DashboardQueryService(TraceStore traceStore,
                                 Supplier<? extends Collection<EvalResult>> evalResults,
                                 AutonomyLedger ledger) {
        this(traceStore, evalResults, ledger, TraceSpan::agentId,
                Duration.ofSeconds(DEFAULT_CACHE_TTL_SECONDS), Clock.systemUTC());
    }

    public DashboardQueryService(TraceStore traceStore,
                                 Supplier<? extends Collection<EvalResult>> evalResults,
                                 AutonomyLedger ledger,
                                 Function<TraceSpan, String> taskClassOf,
                                 Duration cacheTtl,
                                 Clock clock) {
        this.traceStore  = Objects.requireNonNull(traceStore, "traceStore must not be null");
        this.evalResults = Objects.requireNonNull(evalResults, "evalResults must not be null");
        this.ledger      = Objects.requireNonNull(ledger, "ledger must not be null");
        this.taskClassOf = Objects.requireNonNull(taskClassOf, "taskClassOf must not be null");
        this.cacheTtl    = Objects.requireNonNull(cacheTtl, "cacheTtl must not be null");
        this.clock       = Objects.requireNonNull(clock, "clock must not be null");
        if (cacheTtl.isNegative()) {
            throw new IllegalArgumentException("cacheTtl must not be negative, got: " + cacheTtl);
        }
    }

    /** The eight panels, recomputed only when the cached snapshot has aged past the TTL (D4). */
    public synchronized DashboardSnapshot snapshot() {
        Instant now = clock.instant();
        if (cached != null && Duration.between(cached.generatedAt(), now).compareTo(cacheTtl) < 0) {
            return cached;
        }
        cached = compute(now);
        return cached;
    }

    /** Drops the cached snapshot so the next {@link #snapshot()} recomputes. */
    public synchronized void invalidate() {
        cached = null;
    }

    private DashboardSnapshot compute(Instant now) {
        List<TraceSpan> cumulativeSpans = traceStore.findSince(Instant.EPOCH);
        Instant windowStart = now.minus(ROLLING_WINDOW);
        List<TraceSpan> rollingSpans = new ArrayList<>();
        for (TraceSpan span : cumulativeSpans) {
            if (!span.startedAt().isBefore(windowStart)) {
                rollingSpans.add(span);
            }
        }

        Map<String, List<TraceSpan>> rollingByClass    = groupByTaskClass(rollingSpans);
        Map<String, List<TraceSpan>> cumulativeByClass  = groupByTaskClass(cumulativeSpans);

        Set<String> taskClasses = new HashSet<>();
        taskClasses.addAll(rollingByClass.keySet());
        taskClasses.addAll(cumulativeByClass.keySet());

        Map<String, DashboardSnapshot.TaskClassMetrics> byTaskClass = new LinkedHashMap<>();
        Map<String, AutonomyLevel> autonomyLevel = new LinkedHashMap<>();
        for (String taskClass : new TreeSet<>(taskClasses)) {
            List<TraceSpan> rolling    = rollingByClass.getOrDefault(taskClass, List.of());
            List<TraceSpan> cumulative = cumulativeByClass.getOrDefault(taskClass, List.of());
            byTaskClass.put(taskClass, new DashboardSnapshot.TaskClassMetrics(
                    horizon(rolling),
                    horizon(cumulative),
                    failureBreakdown(cumulative)));
            autonomyLevel.put(taskClass, ledger.currentLevel(taskClass));
        }

        return new DashboardSnapshot(
                now,
                ROLLING_WINDOW,
                byTaskClass,
                autonomyLevel,
                suiteVsProduction(rollingSpans),
                DashboardSnapshot.ReuseRate.notWired());
    }

    private Map<String, List<TraceSpan>> groupByTaskClass(List<TraceSpan> spans) {
        Map<String, List<TraceSpan>> out = new HashMap<>();
        for (TraceSpan span : spans) {
            String taskClass = taskClassOf.apply(span);
            out.computeIfAbsent(taskClass == null ? "(unclassified)" : taskClass, k -> new ArrayList<>()).add(span);
        }
        return out;
    }

    private static DashboardSnapshot.Horizon horizon(List<TraceSpan> spans) {
        if (spans.isEmpty()) {
            return DashboardSnapshot.Horizon.empty();
        }
        long completed = 0;
        Money cost = Money.ZERO_EUR;
        List<Long> latenciesMillis = new ArrayList<>(spans.size());
        Set<String> runs = new HashSet<>();
        Set<String> runsWithSuspension = new HashSet<>();
        for (TraceSpan span : spans) {
            if (span.status() instanceof SpanStatus.Completed) {
                completed++;
            }
            cost = addCost(cost, span.cost());
            latenciesMillis.add(Duration.between(span.startedAt(), span.endedAt()).toMillis());
            runs.add(span.runId());
            if (span.status() instanceof SpanStatus.Suspended) {
                runsWithSuspension.add(span.runId());
            }
        }
        latenciesMillis.sort(Long::compareTo);
        double humanIntervention = runs.isEmpty() ? 0.0 : (double) runsWithSuspension.size() / runs.size();
        return new DashboardSnapshot.Horizon(
                spans.size(),
                runs.size(),
                (double) completed / spans.size(),
                cost,
                Duration.ofMillis(percentile(latenciesMillis, 50)),
                Duration.ofMillis(percentile(latenciesMillis, 95)),
                humanIntervention);
    }

    /** Sums costs, tolerating a currency mismatch by keeping the first-seen currency's running total. */
    private static Money addCost(Money running, Money next) {
        if (next.amount().signum() == 0) {
            return running;
        }
        if (running.amount().signum() == 0 && !running.currency().equals(next.currency())) {
            return next;
        }
        return running.currency().equals(next.currency()) ? running.plus(next) : running;
    }

    /** Nearest-rank percentile over a pre-sorted ascending list; {@code 0} for an empty list. */
    private static long percentile(List<Long> sortedAsc, int p) {
        if (sortedAsc.isEmpty()) {
            return 0L;
        }
        int rank = (int) Math.ceil(p / 100.0 * sortedAsc.size());
        int idx = Math.min(Math.max(rank, 1), sortedAsc.size()) - 1;
        return sortedAsc.get(idx);
    }

    private static DashboardSnapshot.FailureBreakdown failureBreakdown(List<TraceSpan> spans) {
        long totalFailed = 0;
        long unclassified = 0;
        Map<FailureKind, Long> byKind = new EnumMap<>(FailureKind.class);
        for (TraceSpan span : spans) {
            if (!(span.status() instanceof SpanStatus.Failed)) {
                continue;
            }
            totalFailed++;
            FailureKind kind = parseFailureKind(span.failureKind());
            if (kind == null) {
                unclassified++;
            } else {
                byKind.merge(kind, 1L, Long::sum);
            }
        }
        if (totalFailed == 0) {
            return DashboardSnapshot.FailureBreakdown.empty();
        }
        return new DashboardSnapshot.FailureBreakdown(totalFailed, byKind, unclassified);
    }

    private static FailureKind parseFailureKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return FailureKind.valueOf(raw);
        } catch (IllegalArgumentException unknownLabel) {
            return FailureKind.OTHER;
        }
    }

    /**
     * ADR-0059 D4 panel: for every {@code specHash} an {@link EvalResult} covers, the gap
     * between the suite's mean non-hold-out score and the production success rate of spans
     * carrying that hash over the rolling window. A hash with no production spans in the
     * window is skipped — there is nothing to compare it against yet.
     */
    private Map<String, DashboardSnapshot.SuiteProductionGap> suiteVsProduction(List<TraceSpan> rollingSpans) {
        Map<String, long[]> spanTally = new HashMap<>();          // specHash -> [completedSpans, totalSpans]
        Map<String, Set<String>> runsByHash = new HashMap<>();    // specHash -> distinct runIds
        for (TraceSpan span : rollingSpans) {
            String hash = span.specHash();
            if (hash == null || hash.isBlank()) {
                continue;
            }
            long[] tally = spanTally.computeIfAbsent(hash, k -> new long[2]);
            if (span.status() instanceof SpanStatus.Completed) {
                tally[0]++;
            }
            tally[1]++;
            runsByHash.computeIfAbsent(hash, k -> new HashSet<>()).add(span.runId());
        }

        Map<String, DashboardSnapshot.SuiteProductionGap> out = new TreeMap<>();
        for (EvalResult result : evalResults.get()) {
            String hash = result.specHash();
            long[] tally = spanTally.get(hash);
            if (tally == null || tally[1] == 0) {
                continue;
            }
            double suiteMean = result.perCase().values().stream()
                    .filter(s -> !s.holdout())
                    .mapToDouble(CaseStats::meanScore)
                    .average()
                    .orElse(0.0);
            double productionRate = (double) tally[0] / tally[1];
            long runs = runsByHash.getOrDefault(hash, Set.of()).size();
            out.put(hash, new DashboardSnapshot.SuiteProductionGap(hash, suiteMean, productionRate, runs));
        }
        return out;
    }
}
