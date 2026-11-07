package io.ara.core.agent;

import io.ara.core.common.AgentId;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable description of when and how to execute an {@link AraAgent} on a
 * recurring basis (ADR-032).
 *
 * <p>{@code AgentSchedule} is a pure domain record — it has no dependency on
 * {@code ara-runtime} and is designed to be persisted in {@code ARA_AGENT_SCHEDULE}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * AgentSchedule schedule = AgentSchedule.builder()
 *     .scheduleId("daily-report")
 *     .agentId(agent.id())
 *     .every(Duration.ofHours(24))
 *     .withInput("Genera il report giornaliero")
 *     .maxDuration(Duration.ofMinutes(30))   // optional: a run still going past the limit is timed out
 *     .build();
 *
 * runtime.scheduler().register(schedule);
 * }</pre>
 *
 * <p><b>Input templating.</b> {@code inputTemplate} is rendered at every fire, so the text the
 * agent receives is never a date that was right when the schedule was registered. The tokens are:
 * <ul>
 *   <li>{@code {today}} — now, ISO ({@code yyyy-MM-dd})</li>
 *   <li>{@code {today:FORMAT}} — now, in a {@link java.time.format.DateTimeFormatter} pattern</li>
 *   <li>{@code {date±N}} — N days from today, ISO (e.g. {@code {date-7}})</li>
 *   <li>{@code {date±N:FORMAT}} — N days from today, in a pattern</li>
 * </ul>
 * Unknown tokens are left as they are; a broken pattern fails the fire with a dispatch failure,
 * which the schedule history shows.
 */
public record AgentSchedule(
        String   scheduleId,
        AgentId  agentId,
        Trigger  trigger,
        String   inputTemplate,
        boolean  active,
        Duration maxDuration
) {

    /** The old arity, before {@code maxDuration}: a schedule without a run-timeout line. */
    public AgentSchedule(String scheduleId, AgentId agentId, Trigger trigger, String inputTemplate, boolean active) {
        this(scheduleId, agentId, trigger, inputTemplate, active, null);
    }

    public AgentSchedule {
        Objects.requireNonNull(scheduleId,    "scheduleId must not be null");
        Objects.requireNonNull(agentId,       "agentId must not be null");
        Objects.requireNonNull(trigger,       "trigger must not be null");
        Objects.requireNonNull(inputTemplate, "inputTemplate must not be null");
        if (scheduleId.isBlank()) throw new IllegalArgumentException("scheduleId must not be blank");
        if (maxDuration != null && (maxDuration.isNegative() || maxDuration.isZero())) {
            throw new IllegalArgumentException("maxDuration must be positive, or null for no limit");
        }
    }

    // ── Trigger ───────────────────────────────────────────────────────────────

    /**
     * Defines when a schedule fires. Two variants are supported:
     * <ul>
     *   <li>{@link Interval} — fires every fixed {@link Duration}</li>
     *   <li>{@link Cron}     — fires according to a 5-field cron expression</li>
     * </ul>
     */
    public sealed interface Trigger permits AgentSchedule.Trigger.Interval, AgentSchedule.Trigger.Cron {

        /** Fires every {@code every} duration after the previous execution. */
        record Interval(Duration every) implements Trigger {
            public Interval {
                Objects.requireNonNull(every, "every must not be null");
                if (every.isNegative() || every.isZero())
                    throw new IllegalArgumentException("Interval duration must be positive");
            }
        }

        /**
         * Fires according to a standard 5-field cron expression.
         * Format: {@code minute hour day-of-month month day-of-week}
         * Example: {@code "0 9 * * MON-FRI"} — every weekday at 09:00.
         *
         * <p>Every field accepts the standard cron syntax:
         * <ul>
         *   <li>{@code *} — any value</li>
         *   <li>a single value ({@code 5}; {@code MON} or {@code 7} for day-of-week,
         *       where {@code 0} and {@code 7} both mean Sunday)</li>
         *   <li>a range ({@code 1-5}, {@code MON-FRI}; day-of-week ranges may wrap,
         *       e.g. {@code FRI-MON})</li>
         *   <li>a step over the whole range ({@code *}&#47;{@code 15}) or over a
         *       range ({@code 0-30}&#47;{@code 10})</li>
         *   <li>a comma-separated list combining any of the above ({@code 1,15,30},
         *       {@code MON,WED,FRI})</li>
         * </ul>
         *
         * <p>When both day-of-month and day-of-week are restricted, standard cron OR
         * semantics apply: the schedule fires when <em>either</em> matches.
         *
         * <p>Only the single-JVM {@code LocalAgentScheduler} interprets this
         * expression; the string itself is validated for non-blankness here and
         * fully parsed when the schedule is registered. To validate an expression and
         * inspect its next fire time <em>ahead of</em> registration (e.g. before
         * persisting a schedule), use {@code LocalAgentScheduler.CronEvaluator}.
         */
        record Cron(String expression) implements Trigger {
            public Cron {
                Objects.requireNonNull(expression, "cron expression must not be null");
                if (expression.isBlank())
                    throw new IllegalArgumentException("cron expression must not be blank");
            }
        }
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {

        private String  scheduleId;
        private AgentId agentId;
        private Trigger trigger;
        private String  inputTemplate = "";
        private boolean active        = true;
        private Duration maxDuration  = null;

        public Builder scheduleId(String scheduleId) {
            this.scheduleId = scheduleId;
            return this;
        }

        public Builder agentId(AgentId agentId) {
            this.agentId = agentId;
            return this;
        }

        /** Sets a fixed-interval trigger. */
        public Builder every(Duration interval) {
            this.trigger = new Trigger.Interval(interval);
            return this;
        }

        /** Sets a cron-expression trigger. */
        public Builder cron(String expression) {
            this.trigger = new Trigger.Cron(expression);
            return this;
        }

        public Builder withInput(String inputTemplate) {
            this.inputTemplate = inputTemplate;
            return this;
        }

        /**
         * A hard ceiling on how long one scheduled run may take. {@code null} (the default) runs
         * without a limit. A run that is still going when the ceiling passes is reported to the
         * listener as a failure ("timed out ...") and the schedule is free to fire again — the
         * underlying execution is not interrupted: it keeps running to its end or its own stall.
         */
        public Builder maxDuration(Duration maxDuration) {
            this.maxDuration = maxDuration;
            return this;
        }

        /** Same as {@link #maxDuration(Duration)} in the units the schedule document speaks. */
        public Builder maxDurationMs(Long maxDurationMs) {
            this.maxDuration = maxDurationMs == null ? null : Duration.ofMillis(maxDurationMs);
            return this;
        }

        public Builder active(boolean active) {
            this.active = active;
            return this;
        }

        public AgentSchedule build() {
            Objects.requireNonNull(scheduleId, "scheduleId is required");
            Objects.requireNonNull(agentId,    "agentId is required");
            Objects.requireNonNull(trigger,    "trigger is required — call every() or cron()");
            return new AgentSchedule(scheduleId, agentId, trigger, inputTemplate, active, maxDuration);
        }
    }
}
