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
 *     .build();
 *
 * runtime.scheduler().register(schedule);
 * }</pre>
 */
public record AgentSchedule(
        String   scheduleId,
        AgentId  agentId,
        Trigger  trigger,
        String   inputTemplate,
        boolean  active
) {

    public AgentSchedule {
        Objects.requireNonNull(scheduleId,    "scheduleId must not be null");
        Objects.requireNonNull(agentId,       "agentId must not be null");
        Objects.requireNonNull(trigger,       "trigger must not be null");
        Objects.requireNonNull(inputTemplate, "inputTemplate must not be null");
        if (scheduleId.isBlank()) throw new IllegalArgumentException("scheduleId must not be blank");
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
         * fully parsed when the schedule is registered.
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

        public Builder active(boolean active) {
            this.active = active;
            return this;
        }

        public AgentSchedule build() {
            Objects.requireNonNull(scheduleId, "scheduleId is required");
            Objects.requireNonNull(agentId,    "agentId is required");
            Objects.requireNonNull(trigger,    "trigger is required — call every() or cron()");
            return new AgentSchedule(scheduleId, agentId, trigger, inputTemplate, active);
        }
    }
}
