package io.ara.runtime.scheduler;

import io.ara.core.agent.AgentSchedule;
import io.ara.core.agent.AgentSchedule.Trigger;
import io.ara.core.common.AgentId;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AgentScheduleTest {

    private final AgentId agentId = AgentId.of("test-agent");

    // ── Builder ───────────────────────────────────────────────────────────────

    @Test
    void build_with_interval_trigger() {
        AgentSchedule s = AgentSchedule.builder()
                .scheduleId("s1")
                .agentId(agentId)
                .every(Duration.ofMinutes(5))
                .withInput("run")
                .build();

        assertEquals("s1", s.scheduleId());
        assertEquals(agentId, s.agentId());
        assertEquals("run", s.inputTemplate());
        assertTrue(s.active());
        assertInstanceOf(Trigger.Interval.class, s.trigger());
        assertEquals(Duration.ofMinutes(5), ((Trigger.Interval) s.trigger()).every());
    }

    @Test
    void build_with_cron_trigger() {
        AgentSchedule s = AgentSchedule.builder()
                .scheduleId("s2")
                .agentId(agentId)
                .cron("0 9 * * MON-FRI")
                .withInput("morning-run")
                .build();

        assertInstanceOf(Trigger.Cron.class, s.trigger());
        assertEquals("0 9 * * MON-FRI", ((Trigger.Cron) s.trigger()).expression());
    }

    @Test
    void inactive_schedule_is_preserved() {
        AgentSchedule s = AgentSchedule.builder()
                .scheduleId("s3")
                .agentId(agentId)
                .every(Duration.ofHours(1))
                .withInput("input")
                .active(false)
                .build();

        assertFalse(s.active());
    }

    @Test
    void empty_input_template_is_allowed() {
        AgentSchedule s = AgentSchedule.builder()
                .scheduleId("s4")
                .agentId(agentId)
                .every(Duration.ofSeconds(30))
                .build();

        assertEquals("", s.inputTemplate());
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    void missing_scheduleId_throws() {
        assertThrows(NullPointerException.class, () ->
                AgentSchedule.builder()
                        .agentId(agentId)
                        .every(Duration.ofMinutes(1))
                        .build());
    }

    @Test
    void blank_scheduleId_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                AgentSchedule.builder()
                        .scheduleId("  ")
                        .agentId(agentId)
                        .every(Duration.ofMinutes(1))
                        .build());
    }

    @Test
    void missing_agentId_throws() {
        assertThrows(NullPointerException.class, () ->
                AgentSchedule.builder()
                        .scheduleId("s5")
                        .every(Duration.ofMinutes(1))
                        .build());
    }

    @Test
    void missing_trigger_throws() {
        assertThrows(NullPointerException.class, () ->
                AgentSchedule.builder()
                        .scheduleId("s6")
                        .agentId(agentId)
                        .build());
    }

    @Test
    void zero_interval_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new Trigger.Interval(Duration.ZERO));
    }

    @Test
    void negative_interval_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new Trigger.Interval(Duration.ofSeconds(-1)));
    }

    @Test
    void blank_cron_expression_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                new Trigger.Cron("   "));
    }

    // ── CronEvaluator ─────────────────────────────────────────────────────────

    @Test
    void cron_wildcard_returns_positive_delay() {
        long delay = LocalAgentScheduler.CronEvaluator.secondsUntilNext("* * * * *");
        assertTrue(delay >= 1, "delay should be at least 1 second, was: " + delay);
        assertTrue(delay <= 60, "wildcard cron should fire within 60 seconds");
    }

    @Test
    void cron_wrong_field_count_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 9 *"));
    }

    @Test
    void cron_out_of_range_minute_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                LocalAgentScheduler.CronEvaluator.secondsUntilNext("99 * * * *"));
    }

    @Test
    void cron_dow_range_never_lands_on_weekend() {
        // Regression test: MON-FRI was previously parsed to a sentinel that the
        // matcher treated as wildcard ("any day"), so weekend runs slipped through.
        long delaySeconds = LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 9 * * MON-FRI");
        java.time.DayOfWeek day = java.time.LocalDateTime.now()
                .plusSeconds(delaySeconds)
                .getDayOfWeek();
        assertTrue(day != java.time.DayOfWeek.SATURDAY && day != java.time.DayOfWeek.SUNDAY,
                "MON-FRI cron must never resolve to a weekend day, got: " + day);
    }

    @Test
    void cron_single_dow_matches_only_that_day() {
        long delaySeconds = LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 0 * * WED");
        // secondsUntilNext truncates to whole seconds (ChronoUnit.SECONDS.between), so when the
        // target is an exact minute boundary (00:00) the result can under-shoot by up to ~1s —
        // negligible for scheduling, but enough to land just before midnight on the day before.
        // +1s re-crosses that boundary without risking crossing into the following day.
        java.time.DayOfWeek day = java.time.LocalDateTime.now()
                .plusSeconds(delaySeconds + 1)
                .getDayOfWeek();
        assertEquals(java.time.DayOfWeek.WEDNESDAY, day);
    }

    @Test
    void cron_day_of_month_lands_only_on_that_day() {
        // Regression: dom was previously ignored, so "0 0 15 * *" fired every day at midnight
        // instead of only on the 15th.
        long delaySeconds = LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 0 15 * *");
        int dayOfMonth = java.time.LocalDateTime.now()
                .plusSeconds(delaySeconds + 1)
                .getDayOfMonth();
        assertEquals(15, dayOfMonth, "a day-of-month cron must resolve to that day");
    }

    @Test
    void cron_month_lands_only_in_that_month() {
        // Regression: month was previously ignored. "0 0 1 1 *" is 1st of January.
        long delaySeconds = LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 0 1 1 *");
        java.time.LocalDateTime fire = java.time.LocalDateTime.now().plusSeconds(delaySeconds + 1);
        assertEquals(java.time.Month.JANUARY, fire.getMonth());
        assertEquals(1, fire.getDayOfMonth());
    }

    @Test
    void cron_leap_day_resolves_within_the_scan_window() {
        // "0 0 29 2 *" (Feb 29) only exists on leap years — the evaluator must scan far
        // enough ahead to find it rather than exhaust the loop and throw.
        long delaySeconds = LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 0 29 2 *");
        java.time.LocalDateTime fire = java.time.LocalDateTime.now().plusSeconds(delaySeconds + 1);
        assertEquals(java.time.Month.FEBRUARY, fire.getMonth());
        assertEquals(29, fire.getDayOfMonth());
    }

    @Test
    void cron_dom_and_dow_both_restricted_use_or_semantics() {
        // Standard cron: when both dom and dow are set, a day matches if EITHER holds.
        // "0 0 13 * FRI" fires on the 13th of any month OR on any Friday.
        long delaySeconds = LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 0 13 * FRI");
        java.time.LocalDateTime fire = java.time.LocalDateTime.now().plusSeconds(delaySeconds + 1);
        boolean matches = fire.getDayOfMonth() == 13
                || fire.getDayOfWeek() == java.time.DayOfWeek.FRIDAY;
        assertTrue(matches, "expected the 13th or a Friday, got: " + fire);
    }

    @Test
    void cron_out_of_range_day_of_month_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 0 32 * *"));
    }

    @Test
    void cron_out_of_range_month_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 0 * 13 *"));
    }

    // ── Lists, ranges and steps ─────────────────────────────────────────────────

    @Test
    void cron_minute_list_fires_on_the_nearest_listed_minute() {
        // "0,15,30,45 * * * *" — the next fire must land on one of the listed minutes.
        long delaySeconds = LocalAgentScheduler.CronEvaluator.secondsUntilNext("0,15,30,45 * * * *");
        int minute = java.time.LocalDateTime.now().plusSeconds(delaySeconds + 1).getMinute();
        assertTrue(java.util.Set.of(0, 15, 30, 45).contains(minute),
                "expected a quarter-hour minute, got: " + minute);
    }

    @Test
    void cron_step_over_wildcard_fires_on_a_multiple_of_the_step() {
        // "*/10 * * * *" — every 10 minutes, so the target minute is a multiple of 10.
        long delaySeconds = LocalAgentScheduler.CronEvaluator.secondsUntilNext("*/10 * * * *");
        int minute = java.time.LocalDateTime.now().plusSeconds(delaySeconds + 1).getMinute();
        assertEquals(0, minute % 10, "expected a minute divisible by 10, got: " + minute);
    }

    @Test
    void cron_step_over_a_range_stays_within_the_range() {
        // "0-30/10 * * * *" yields minutes {0,10,20,30}.
        long delaySeconds = LocalAgentScheduler.CronEvaluator.secondsUntilNext("0-30/10 * * * *");
        int minute = java.time.LocalDateTime.now().plusSeconds(delaySeconds + 1).getMinute();
        assertTrue(java.util.Set.of(0, 10, 20, 30).contains(minute),
                "expected a minute in {0,10,20,30}, got: " + minute);
    }

    @Test
    void cron_hour_range_lands_within_business_hours() {
        // "0 9-17 * * *" — on the hour, only between 09:00 and 17:00 inclusive.
        long delaySeconds = LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 9-17 * * *");
        int hour = java.time.LocalDateTime.now().plusSeconds(delaySeconds + 1).getHour();
        assertTrue(hour >= 9 && hour <= 17, "expected an hour in [9,17], got: " + hour);
    }

    @Test
    void cron_day_of_month_list_lands_on_a_listed_day() {
        long delaySeconds = LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 0 1,15 * *");
        int dom = java.time.LocalDateTime.now().plusSeconds(delaySeconds + 1).getDayOfMonth();
        assertTrue(dom == 1 || dom == 15, "expected the 1st or the 15th, got: " + dom);
    }

    @Test
    void cron_symbolic_dow_list_lands_on_a_listed_day() {
        long delaySeconds = LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 0 * * MON,WED,FRI");
        java.time.DayOfWeek day = java.time.LocalDateTime.now()
                .plusSeconds(delaySeconds + 1).getDayOfWeek();
        assertTrue(java.util.Set.of(java.time.DayOfWeek.MONDAY,
                        java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.FRIDAY).contains(day),
                "expected Mon/Wed/Fri, got: " + day);
    }

    @Test
    void cron_dow_seven_is_treated_as_sunday() {
        long delaySeconds = LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 0 * * 7");
        java.time.DayOfWeek day = java.time.LocalDateTime.now()
                .plusSeconds(delaySeconds + 1).getDayOfWeek();
        assertEquals(java.time.DayOfWeek.SUNDAY, day);
    }

    @Test
    void cron_wrapping_dow_range_covers_the_weekend_edges() {
        // "FRI-MON" wraps the week end: Fri, Sat, Sun, Mon.
        long delaySeconds = LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 0 * * FRI-MON");
        java.time.DayOfWeek day = java.time.LocalDateTime.now()
                .plusSeconds(delaySeconds + 1).getDayOfWeek();
        assertTrue(java.util.Set.of(java.time.DayOfWeek.FRIDAY, java.time.DayOfWeek.SATURDAY,
                        java.time.DayOfWeek.SUNDAY, java.time.DayOfWeek.MONDAY).contains(day),
                "expected Fri/Sat/Sun/Mon, got: " + day);
    }

    @Test
    void cron_zero_step_is_rejected() {
        assertThrows(IllegalArgumentException.class, () ->
                LocalAgentScheduler.CronEvaluator.secondsUntilNext("*/0 * * * *"));
    }

    @Test
    void cron_out_of_range_value_inside_a_list_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                LocalAgentScheduler.CronEvaluator.secondsUntilNext("0,99 * * * *"));
    }

    @Test
    void cron_inverted_numeric_range_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 17-9 * * *"));
    }

    @Test
    void cron_invalid_symbolic_dow_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                LocalAgentScheduler.CronEvaluator.secondsUntilNext("0 0 * * FUNDAY"));
    }
}
