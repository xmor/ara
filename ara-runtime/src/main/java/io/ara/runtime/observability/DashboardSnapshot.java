package io.ara.runtime.observability;

import io.ara.core.autonomy.AutonomyLevel;
import io.ara.core.common.Money;
import io.ara.runtime.agent.FailureKind;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One aggregated read of the ADR-0074 observability dashboard — the eight panels the ADR's
 * D2 table lists, all derived by aggregation from stores this backlog already decided
 * ({@code TraceStore} ADR-0068, {@code EvalResult} ADR-0070, {@code AutonomyLedger}
 * ADR-0073), never from a real-time metrics pipeline (D1). Produced by
 * {@link DashboardQueryService#snapshot()}; immutable.
 *
 * <p><b>Dual horizon (D5).</b> Every panel with a trend over time carries both a rolling
 * 30-day window and a cumulative figure since the first observed span. The only panel
 * without a horizon is the autonomy level, which is a current value, not an aggregate.
 *
 * @param generatedAt          when {@link DashboardQueryService} computed this snapshot
 * @param rollingWindow        the rolling-window length behind {@link TaskClassMetrics#rolling30d()} (D5, 30d)
 * @param byTaskClass          per-{@code task_class} success / cost / latency / human-intervention / failures
 * @param autonomyLevel        current {@link AutonomyLevel} per {@code task_class} — direct {@code AutonomyLedger} read (D2 panel 8)
 * @param suiteVsProductionGap per {@code specHash}: eval suite mean score vs observed production success rate (ADR-0059 D4)
 * @param reuseRate            ADR-0064's {@code reuse_rate} — explicitly partial / not yet wired at this layer (see {@link ReuseRate})
 */
public record DashboardSnapshot(
        Instant                       generatedAt,
        Duration                      rollingWindow,
        Map<String, TaskClassMetrics> byTaskClass,
        Map<String, AutonomyLevel>    autonomyLevel,
        Map<String, SuiteProductionGap> suiteVsProductionGap,
        ReuseRate                     reuseRate
) {

    public DashboardSnapshot {
        Objects.requireNonNull(generatedAt, "generatedAt must not be null");
        Objects.requireNonNull(rollingWindow, "rollingWindow must not be null");
        Objects.requireNonNull(reuseRate, "reuseRate must not be null");
        byTaskClass          = Map.copyOf(Objects.requireNonNullElse(byTaskClass, Map.of()));
        autonomyLevel        = Map.copyOf(Objects.requireNonNullElse(autonomyLevel, Map.of()));
        suiteVsProductionGap = Map.copyOf(Objects.requireNonNullElse(suiteVsProductionGap, Map.of()));
    }

    /** The two horizons plus the failure breakdown for one {@code task_class}. */
    public record TaskClassMetrics(Horizon rolling30d, Horizon cumulative, FailureBreakdown failures) {
        public TaskClassMetrics {
            Objects.requireNonNull(rolling30d, "rolling30d must not be null");
            Objects.requireNonNull(cumulative, "cumulative must not be null");
            Objects.requireNonNull(failures, "failures must not be null");
        }
    }

    /**
     * The aggregate figures over one horizon for one {@code task_class} (D2 rows 1–4).
     *
     * @param spans                 spans counted in this horizon
     * @param runs                  distinct {@code runId}s counted in this horizon
     * @param successRate           {@code count(Completed) / count(*)} over {@code spans} (D2 row 1); {@code 0.0} when {@code spans == 0}
     * @param cost                  sum of {@code TraceSpan.cost} over the horizon (D2 row 2)
     * @param latencyP50            median span wall-clock ({@code endedAt - startedAt}); {@link Duration#ZERO} when empty
     * @param latencyP95            95th-percentile span wall-clock (D2 row 3)
     * @param humanInterventionRate fraction of {@code runs} with at least one {@link io.ara.core.trace.SpanStatus.Suspended}
     *                              span — the L0 stand-in for D2 row 4's ledger-based {@code 1 - autonomous/total}
     *                              (the {@code AutonomyLedger} does not expose observation counts yet)
     */
    public record Horizon(
            long     spans,
            long     runs,
            double   successRate,
            Money    cost,
            Duration latencyP50,
            Duration latencyP95,
            double   humanInterventionRate
    ) {
        public Horizon {
            Objects.requireNonNull(cost, "cost must not be null");
            Objects.requireNonNull(latencyP50, "latencyP50 must not be null");
            Objects.requireNonNull(latencyP95, "latencyP95 must not be null");
        }

        static Horizon empty() {
            return new Horizon(0, 0, 0.0, Money.ZERO_EUR, Duration.ZERO, Duration.ZERO, 0.0);
        }
    }

    /**
     * "Top failure modes" (D2 row 5, D6). {@code byKind} counts {@code status = Failed}
     * spans by their {@link FailureKind}; {@code unclassified} counts the Failed spans that
     * carry no {@code failureKind} yet — until the emission points populate it (ADR-0068
     * follow-up) the panel degrades to {@code totalFailed} with everything unclassified,
     * never a silent placeholder that looks complete (D6).
     */
    public record FailureBreakdown(long totalFailed, Map<FailureKind, Long> byKind, long unclassified) {
        public FailureBreakdown {
            byKind = Map.copyOf(Objects.requireNonNullElse(byKind, Map.of()));
        }

        static FailureBreakdown empty() {
            return new FailureBreakdown(0, Map.of(), 0);
        }
    }

    /**
     * One {@code specHash}'s divergence between the eval suite and production (ADR-0059 D4:
     * "not just a number on the dashboard (ADR-0074)").
     *
     * @param specHash              the evaluated {@code AgentSpec} hash
     * @param suiteMeanScore        mean {@code CaseStats.meanScore} over the non-hold-out cases of the latest {@code EvalResult} for this hash
     * @param productionSuccessRate success rate of production spans carrying this {@code specHash}, over the rolling window
     * @param productionRuns        distinct production {@code runId}s carrying this {@code specHash} in the window
     */
    public record SuiteProductionGap(
            String specHash,
            double suiteMeanScore,
            double productionSuccessRate,
            long   productionRuns
    ) {
        public SuiteProductionGap {
            Objects.requireNonNull(specHash, "specHash must not be null");
        }

        /** Positive when the suite is more optimistic than production — the ADR-0059 D4 concern. */
        public double gap() {
            return suiteMeanScore - productionSuccessRate;
        }
    }

    /**
     * ADR-0064's {@code reuse_rate}, which ADR-0074 D2 promised as a primary panel "labelled
     * explicitly partial" (ADR-0064 Negative). It is fed by the fast-path recipe-cache
     * signal (ADR-0072's {@code routing.recipe_cache_hit}), which is a routing span
     * attribute not carried on {@link io.ara.core.trace.TraceSpan} — so at this layer the
     * panel is present in shape but not yet computable. {@link #available()} is {@code false}
     * and {@link #value()} is {@code null} until that wiring (or ADR-0075's factory) lands;
     * the field exists so a dashboard renders an honest "not yet measured", never omits it.
     */
    public record ReuseRate(boolean available, Double value, String note) {
        static ReuseRate notWired() {
            return new ReuseRate(false, null,
                    "partial — fast-path recipe-cache signal (ADR-0072 routing.recipe_cache_hit) "
                            + "is not on TraceSpan; wiring is an ADR-0074/ADR-0075 follow-up (ADR-0064 Negative)");
        }
    }
}
