package io.ara.examples.scheduler;

import io.ara.core.agent.AgentSchedule;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AraAgents;
import io.ara.core.common.AgentId;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.scheduler.AgentScheduler;
import io.ara.runtime.stubs.ScriptedLlmClient;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fully offline demonstration of {@link AgentScheduler} (ADR-032) — no LLM, no API key.
 *
 * <p>The scheduler runs a registered {@link AraAgent} on a recurring basis. Two kinds
 * of trigger exist:
 * <ul>
 *   <li>a fixed {@code every(Duration)} interval, and</li>
 *   <li>a {@code cron(expression)} 5-field cron expression
 *       ({@code minute hour day-of-month month day-of-week}).</li>
 * </ul>
 *
 * <p>To keep the demo watchable in a few seconds it registers a <em>fixed-interval</em>
 * schedule that fires every second, shows the management API around it
 * ({@link AgentScheduler#list() list}, {@link AgentScheduler#triggerNow(String) triggerNow},
 * {@link AgentScheduler#pause(String) pause}/{@link AgentScheduler#resume(String) resume},
 * {@link AgentScheduler#cancel(String) cancel}), and prints — but does not wait for — a
 * realistic cron schedule ("every weekday at 09:00"), since waiting until 9am would make
 * a poor demo.
 *
 * <p>The scheduled agent is a {@link AraAgents#deterministic deterministic agent}: it
 * has no {@code LlmClient} at all, so the whole example runs without a network.
 */
public final class AgentSchedulerExample {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) throws InterruptedException {

        System.out.println("=== ARA Agent Scheduler — Offline Demo ===\n");

        // ── 1. A deterministic agent — no LLM, counts how often it is fired ──────
        AtomicInteger fireCount = new AtomicInteger(0);
        AgentId heartbeatId = AgentId.of("heartbeat-agent");
        AraAgent heartbeat = AraAgents.deterministic(heartbeatId, task -> {
            int n = fireCount.incrementAndGet();
            String line = "[%s] heartbeat #%d — input=\"%s\""
                    .formatted(LocalTime.now().format(CLOCK), n, task.input());
            System.out.println("    " + line);
            return line;
        });

        // ── 2. Build the runtime and register the agent so the scheduler can find it ─
        // The runtime requires at least one LlmClient even when nothing uses it: the
        // scheduled agent is deterministic, so this scripted stub is never actually called.
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("unused", ScriptedLlmClient.script().build())
                .build();
        runtime.start();
        runtime.registry().register(heartbeat);

        AgentScheduler scheduler = runtime.scheduler();

        // ── 3. A fixed-interval schedule — fires once per second ─────────────────
        scheduler.register(AgentSchedule.builder()
                .scheduleId("heartbeat-1s")
                .agentId(heartbeatId)
                .every(Duration.ofSeconds(1))
                .withInput("tick")
                .build());
        System.out.println("Registered 'heartbeat-1s' (every 1s). Letting it fire ~3 times:\n");
        Thread.sleep(3_200);

        // ── 4. Inspect what is registered ────────────────────────────────────────
        System.out.println("\nRegistered schedules:");
        scheduler.list().forEach(s ->
                System.out.printf("  - %s → agent %s%n", s.scheduleId(), s.agentId().value()));

        // ── 5. Fire once, immediately, regardless of the next scheduled instant ──
        System.out.println("\ntriggerNow('heartbeat-1s') — one immediate off-cycle run:");
        scheduler.triggerNow("heartbeat-1s").get();   // block until this one-shot completes

        // ── 6. Pause, prove it stops firing, then resume ─────────────────────────
        System.out.println("\nPausing 'heartbeat-1s' for ~2s (no heartbeats expected):");
        scheduler.pause("heartbeat-1s");
        int before = fireCount.get();
        Thread.sleep(2_000);
        System.out.printf("  fires during pause: %d (expected 0)%n", fireCount.get() - before);

        System.out.println("Resuming 'heartbeat-1s' for ~2s:\n");
        scheduler.resume("heartbeat-1s");
        Thread.sleep(2_200);

        // ── 7. Cancel it — it is gone from the registry ──────────────────────────
        scheduler.cancel("heartbeat-1s");
        System.out.printf("%nCancelled 'heartbeat-1s'. Schedules now registered: %d%n",
                scheduler.list().size());

        // ── 8. A cron schedule — registered but not awaited (fires weekdays 09:00) ─
        scheduler.register(AgentSchedule.builder()
                .scheduleId("morning-report")
                .agentId(heartbeatId)
                .cron("0 9 * * MON-FRI")   // minute hour day-of-month month day-of-week
                .withInput("Generate the daily report")
                .build());
        System.out.println("""

                Registered 'morning-report' with cron "0 9 * * MON-FRI"
                (every weekday at 09:00 — not awaited here). Cron fields support
                lists, ranges and steps too, e.g.:
                  "*/15 * * * *"      every 15 minutes
                  "0 9,13,17 * * *"   at 09:00, 13:00 and 17:00
                  "0 0 1,15 * *"      at midnight on the 1st and the 15th""");

        // ── 9. Cleanup — stop() cancels every schedule and shuts the runtime down ─
        System.out.printf("%nTotal heartbeats fired: %d%n", fireCount.get());
        runtime.stop();
        System.out.println("Runtime stopped — all schedules cancelled.");
    }
}
