package io.ara.runtime.scheduler;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentSchedule;
import io.ara.core.agent.AgentTask;
import io.ara.core.common.AgentId;
import io.ara.runtime.agent.AgentRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 P1/P2, §4 U7/U8 — regression tests for
 * {@link LocalAgentScheduler}.
 */
class LocalAgentSchedulerHardeningTest {

    private AgentRegistry       registry;
    private LocalAgentScheduler scheduler;

    @BeforeEach
    void setUp() {
        registry  = new AgentRegistry();
        scheduler = new LocalAgentScheduler(registry);
        scheduler.start();
    }

    @AfterEach
    void tearDown() {
        scheduler.stop();
    }

    // ── U7: a throwing trigger must not silently kill the schedule ─────────────

    /**
     * {@code fire()} throws synchronously when {@code inputTemplate} is blank — {@code
     * AgentTask.of("")} rejects blank input with no media — a real, easily reached case (a
     * schedule registered without ever calling {@code withInput(...)}, which defaults to
     * {@code ""}), not a contrived one. Before U7 this exception, thrown from inside a
     * {@code scheduleAtFixedRate} task, would silently cancel every future execution of that
     * periodic task (a documented {@code ScheduledThreadPoolExecutor} pitfall) — calling
     * {@link LocalAgentScheduler#safeFire} directly, repeatedly, is the only way to assert
     * "never propagates" deterministically; proving the same through the real {@code
     * scheduleAtFixedRate} machinery would only be provable indirectly and non-deterministically.
     */
    /**
     * A schedule never overlaps with itself: a tick arriving while the previous run of the same
     * schedule is still executing is skipped, and the next tick after that run completes is
     * eligible again.
     *
     * <p>Driven through {@link LocalAgentScheduler#safeFire} rather than a real interval so the
     * overlap window is opened by a latch instead of by {@code Thread.sleep}, which is what makes
     * this a regression test rather than a timing race: the same test against the pre-guard code
     * deterministically reports one start per {@code safeFire} call instead of one.
     */
    @Test
    void fixedInterval_skipsWhilePreviousInFlight() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch runReported = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();

        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("slow-agent")) {
            @Override public AgentResponse execute(AgentTask task) {
                int n = starts.incrementAndGet();
                started.countDown();
                if (n == 1) {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                return success(task);
            }
        });

        LocalAgentScheduler listening = new LocalAgentScheduler(registry, new ScheduleExecutionListener() {
            @Override public void onFire(String scheduleId, AgentId agentId) {}
            @Override public void onComplete(String scheduleId, AgentResponse outcome) {
                if (outcome.failureReason() != null
                        && outcome.failureReason().contains("skipped: previous run of this schedule is still in flight")) {
                    skipped.incrementAndGet();
                } else {
                    completed.incrementAndGet();
                    runReported.countDown();
                }
            }
        });
        listening.start();

        AgentSchedule s = AgentSchedule.builder()
                .scheduleId("overlap")
                .agentId(AgentId.of("slow-agent"))
                .every(Duration.ofHours(1))   // never fires on its own — safeFire drives it
                .withInput("go")
                .build();
        listening.register(s);

        listening.safeFire(s);
        assertTrue(started.await(5, TimeUnit.SECONDS), "first run should have started");

        // Three further ticks while run #1 is held at the gate
        listening.safeFire(s);
        listening.safeFire(s);
        listening.safeFire(s);

        assertEquals(1, starts.get(), "no second run may start while the first is in flight");
        assertEquals(3, skipped.get(), "every overlapping tick must be reported as skipped");

        gate.countDown();
        // onComplete is invoked after the overlap flag is released, so this latch is what makes
        // the next tick's eligibility deterministic — the agent's own return is not.
        assertTrue(runReported.await(5, TimeUnit.SECONDS), "first run should have reported a completion");

        // The flag must have been released by the completion, so the schedule runs again
        listening.safeFire(s);
        waitFor(() -> starts.get() == 2);
        assertEquals(2, starts.get(), "a tick after completion must be eligible to run again");
        assertEquals(3, skipped.get(), "the post-completion tick must not be reported as a skip");
        assertEquals(2, completed.get(), "both real runs report a completion; skips report their own");
    }

    /**
     * A manual {@code triggerNow} is an explicit one-off request, not a tick, so it must dispatch
     * even while the schedule's previous run is in flight. The caller is holding the returned
     * {@code AgentFuture}; silently dropping their request would strand it.
     */
    @Test
    void triggerNow_dispatches_evenWhileThePreviousRunIsInFlight() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();

        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("manual-agent")) {
            @Override public AgentResponse execute(AgentTask task) {
                starts.incrementAndGet();
                started.countDown();
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return success(task);
            }
        });

        AgentSchedule s = AgentSchedule.builder()
                .scheduleId("manual")
                .agentId(AgentId.of("manual-agent"))
                .every(Duration.ofHours(1))
                .withInput("go")
                .build();
        scheduler.register(s);

        scheduler.safeFire(s);
        assertTrue(started.await(5, TimeUnit.SECONDS) || starts.get() == 1, "first run started");

        // A tick would be skipped here; a manual trigger must not be
        scheduler.triggerNow("manual");

        assertTrue(started.await(5, TimeUnit.SECONDS), "triggerNow must dispatch a second run");
        assertEquals(2, starts.get());
        gate.countDown();
    }

    /**
     * The overlap flag is per schedule id: a long-running schedule must not stop an unrelated one
     * from firing. Guards against a flag accidentally shared across entries.
     */
    @Test
    void differentSchedules_areNotBlockedByEachOther() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();

        for (String id : new String[]{"a", "b"}) {
            registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("agent-" + id)) {
                @Override public AgentResponse execute(AgentTask task) {
                    starts.incrementAndGet();
                    bothStarted.countDown();
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return success(task);
                }
            });
            scheduler.register(AgentSchedule.builder()
                    .scheduleId(id)
                    .agentId(AgentId.of("agent-" + id))
                    .every(Duration.ofHours(1))
                    .withInput("go")
                    .build());
        }

        scheduler.safeFire(scheduleOf("a"));
        scheduler.safeFire(scheduleOf("b"));

        assertTrue(bothStarted.await(5, TimeUnit.SECONDS),
                "a second schedule must not be blocked by the first one's in-flight run");
        assertEquals(2, starts.get());
        gate.countDown();
    }

    private AgentSchedule scheduleOf(String id) {
        return AgentSchedule.builder()
                .scheduleId(id)
                .agentId(AgentId.of("agent-" + id))
                .every(Duration.ofHours(1))
                .withInput("go")
                .build();
    }

    /**
     * The flag must survive pause/resume, which swap the schedule's {@code ScheduledFuture}.
     * A fresh flag there would let a run resume alongside a run that was already in flight —
     * exactly the unbounded overlap the flag exists to prevent.
     */
    @Test
    void overlapFlag_survivesPauseAndResume() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();

        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("pause-agent")) {
            @Override public AgentResponse execute(AgentTask task) {
                starts.incrementAndGet();
                started.countDown();
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return success(task);
            }
        });

        AgentSchedule s = AgentSchedule.builder()
                .scheduleId("pausable")
                .agentId(AgentId.of("pause-agent"))
                .every(Duration.ofHours(1))
                .withInput("go")
                .build();
        scheduler.register(s);

        scheduler.safeFire(s);
        assertTrue(started.await(5, TimeUnit.SECONDS), "first run started");

        scheduler.pause("pausable");
        scheduler.resume("pausable");

        scheduler.safeFire(s);
        assertEquals(1, starts.get(),
                "a tick after pause/resume must still see the in-flight run and be skipped");

        gate.countDown();
    }

    /**
     * A dispatch that fails synchronously (blank {@code inputTemplate} makes
     * {@code AgentTask.of} throw, before any completion callback is registered) must still
     * release the flag. Without it the schedule would skip every tick forever after a single
     * failed dispatch — a dead schedule with no error.
     */
    @Test
    void overlapFlag_isReleased_whenDispatchFailsSynchronously() {
        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("blank-agent")));

        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger dispatchFailures = new AtomicInteger();
        LocalAgentScheduler listening = new LocalAgentScheduler(registry, new ScheduleExecutionListener() {
            @Override public void onFire(String scheduleId, AgentId agentId) {}
            @Override public void onComplete(String scheduleId, AgentResponse outcome) {
                if (outcome.failureReason() != null
                        && outcome.failureReason().contains("skipped: previous run of this schedule is still in flight")) {
                    skipped.incrementAndGet();
                } else if (outcome.failureReason() != null
                        && outcome.failureReason().contains("failed to dispatch")) {
                    dispatchFailures.incrementAndGet();
                }
            }
        });
        listening.start();

        AgentSchedule blank = AgentSchedule.builder()
                .scheduleId("blank-dispatch")
                .agentId(AgentId.of("blank-agent"))
                .every(Duration.ofHours(1))
                // no withInput(...) — inputTemplate is "", AgentTask.of("") throws
                .build();
        listening.register(blank);

        for (int i = 0; i < 3; i++) {
            listening.safeFire(blank);
        }

        // A leaked flag would report ticks 2 and 3 as skips instead of as dispatch failures.
        assertEquals(3, dispatchFailures.get(), "every tick must reach dispatch, not be skipped");
        assertEquals(0, skipped.get(), "a failed dispatch must not leave the flag set");
    }

    /** Polls {@code condition} for up to ~2s — a real run finishes on another thread. */
    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    /**
     * Integration-level companion: registering (and leaving active) a schedule whose every
     * trigger throws must not itself throw, and the schedule must remain listed — the
     * scheduler as a whole must keep running rather than being poisoned by one bad schedule.
     */
    @Test
    void repeatingScheduleWithABlankInput_staysRegistered_acrossSeveralTicks() throws InterruptedException {
        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("blank-input-agent-2")));

        assertDoesNotThrow(() -> scheduler.register(AgentSchedule.builder()
                .scheduleId("blank2")
                .agentId(AgentId.of("blank-input-agent-2"))
                .every(Duration.ofMillis(50))
                .build()));

        Thread.sleep(400);   // several ticks' worth of time — each one throws internally

        assertTrue(scheduler.list().stream().anyMatch(s -> "blank2".equals(s.scheduleId())),
                "a schedule whose every trigger throws must remain registered, not be silently dropped");
    }

    // ── U8: agent execution must not starve scheduling ticks ───────────────────

    /**
     * Before U8, {@code tickExecutor} and {@code agentExecutor} were the same fixed-size pool
     * (sized {@code max(2, availableProcessors())}). Saturating it with more concurrent
     * long-running agent executions than its size left nothing to fire the next tick on time.
     */
    @Test
    void aBurstOfLongRunningAgentExecutions_doesNotDelayAnotherSchedulesTick() throws InterruptedException {
        int cpus = Runtime.getRuntime().availableProcessors();
        int hogCount = cpus + 2;   // enough to have saturated the old shared fixed-size pool

        CountDownLatch hogsStarted = new CountDownLatch(hogCount);
        CountDownLatch releaseHogs = new CountDownLatch(1);
        for (int i = 0; i < hogCount; i++) {
            AgentId id = AgentId.of("hog-" + i);
            registry.register(new LocalAgentSchedulerTest.StubAgent(id) {
                @Override public AgentResponse execute(AgentTask task) {
                    hogsStarted.countDown();
                    try {
                        releaseHogs.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return success(task);
                }
            });
            scheduler.register(AgentSchedule.builder()
                    .scheduleId("hog-schedule-" + i)
                    .agentId(id)
                    .every(Duration.ofMillis(50))
                    .withInput("go")
                    .build());
        }

        try {
            assertTrue(hogsStarted.await(5, TimeUnit.SECONDS),
                    "hogs never started — cannot exercise the starvation scenario");

            // Registered only after the hogs have already saturated whatever pool backs
            // agent execution — before U8, sharing the tick pool meant this could never fire
            // until a hog released its worker.
            CountDownLatch fastFired = new CountDownLatch(1);
            registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("fast")) {
                @Override public AgentResponse execute(AgentTask task) {
                    fastFired.countDown();
                    return success(task);
                }
            });
            scheduler.register(AgentSchedule.builder()
                    .scheduleId("fast-schedule")
                    .agentId(AgentId.of("fast"))
                    .every(Duration.ofMillis(50))
                    .withInput("go")
                    .build());

            assertTrue(fastFired.await(3, TimeUnit.SECONDS),
                    "a schedule's tick must not be starved by long-running agent executions "
                            + "occupying the pool (U8: ticks and agent execution use separate executors)");
        } finally {
            releaseHogs.countDown();
        }
    }

    // ── P2 (unassigned, fixed alongside U8): register() race no longer orphans a job ──

    /**
     * {@code register}/{@code pause}/{@code resume} used to read {@code entries.get(id)} and
     * separately {@code entries.put(id, ...)} — two non-atomic map operations. Two concurrent
     * {@code register} calls for the same id could both observe the same (or no) starting
     * entry, each start their own {@link java.util.concurrent.ScheduledFuture}, and then
     * overwrite each other's map entry — whichever {@code put} landed last would win, silently
     * orphaning the other call's job, which would keep firing forever, unreachable from {@code
     * list}/{@code pause}/{@code cancel}. Fixed via {@code entries.compute}, which {@link
     * java.util.concurrent.ConcurrentHashMap} serializes per id.
     */
    @Test
    void concurrentRegisterCallsForTheSameId_neverLeaveAnOrphanedDuplicateJob() throws InterruptedException {
        AtomicInteger fireCount = new AtomicInteger();
        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("racer")) {
            @Override public AgentResponse execute(AgentTask task) {
                fireCount.incrementAndGet();
                return success(task);
            }
        });

        int racers = 8;
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                scheduler.register(AgentSchedule.builder()
                        .scheduleId("race")
                        .agentId(AgentId.of("racer"))
                        .every(Duration.ofMillis(50))
                        .withInput("go")
                        .build());
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        for (Thread t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(5));
        }

        Thread.sleep(300);   // let whichever job(s) exist fire a few times
        assertEquals(1, scheduler.list().stream().filter(s -> "race".equals(s.scheduleId())).count(),
                "exactly one schedule must remain registered under the id, however many callers raced");

        scheduler.cancel("race");
        int countAtCancel = fireCount.get();
        Thread.sleep(400);   // several more intervals' worth of time

        assertEquals(countAtCancel, fireCount.get(),
                "no firing after cancel() — an orphaned duplicate job from the race would still be firing here");
    }

    // ── P1: a failed re-registration must not kill the previous job ────────────

    /**
     * {@code register} used to cancel the existing entry's {@link java.util.concurrent.ScheduledFuture}
     * <em>before</em> calling {@code scheduleJob} — the point where a cron expression is actually
     * parsed ({@link io.ara.core.agent.AgentSchedule.Trigger.Cron} itself only checks
     * non-blankness). A re-registration with an invalid expression therefore threw out of the
     * {@code entries.compute} with the old job already cancelled: the caller saw the exception,
     * but the schedule it meant to keep never fired again — {@code list()} kept reporting it
     * active (its future was non-null, merely cancelled) and {@code resume()} could not re-arm it
     * (it only treats a null future as paused). Regression test drives the real path with a short
     * interval so "the old job keeps firing" is a counted, deterministic assertion rather than a
     * timing hope.
     */
    @Test
    void failedReRegistration_leavesThePreviousJobRunning() throws Exception {
        AtomicInteger fireCount = new AtomicInteger();
        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("zombie-agent")) {
            @Override public AgentResponse execute(AgentTask task) {
                fireCount.incrementAndGet();
                return success(task);
            }
        });

        scheduler.register(AgentSchedule.builder()
                .scheduleId("zombie")
                .agentId(AgentId.of("zombie-agent"))
                .every(Duration.ofMillis(50))
                .withInput("go")
                .build());
        waitFor(() -> fireCount.get() >= 2);   // old job demonstrably alive

        int firesAtFailure = fireCount.get();
        assertThrows(IllegalArgumentException.class, () ->
                scheduler.register(AgentSchedule.builder()
                        .scheduleId("zombie")
                        .agentId(AgentId.of("zombie-agent"))
                        .cron("99 * * * *")   // minute 99 is out of range — CronEvaluator throws
                        .withInput("go")
                        .build()));

        waitFor(() -> fireCount.get() > firesAtFailure);
        assertTrue(fireCount.get() > firesAtFailure,
                "the previous job must keep firing after a failed re-registration");
        assertTrue(listedAsActive("zombie"),
                "a failed re-registration must leave the old definition intact, still active");

        scheduler.cancel("zombie");
        int firesAtCancel = fireCount.get();
        Thread.sleep(300);
        assertEquals(firesAtCancel, fireCount.get(),
                "the surviving job must still be the reachable one — cancel() stops it");
    }

    // ── P2 (cron self-reschedule): pause() racing scheduleCron's own reschedule step ──

    /**
     * {@link LocalAgentScheduler#rescheduleCronAfterFire} used to read {@code entries.get(id)}
     * and separately {@code entries.put(id, ...)} — the identical get-then-put race already
     * fixed for {@code register}/{@code pause}/{@code resume} above, but reachable here too: a
     * {@code pause()} landing between the read and the write could be clobbered by the
     * reschedule's {@code put()}, resurrecting a schedule that had just been turned off. Fixed
     * via {@code entries.compute}. A real cron's reschedule step only runs after a delay of up
     * to a minute ({@link LocalAgentScheduler.CronEvaluator}), so this races the extracted,
     * package-private {@code rescheduleCronAfterFire} directly instead of waiting on real cron
     * timing — deterministic and fast, same testability reasoning as {@code safeFire} above.
     *
     * <p>With the fix, {@code entries.compute} serializes {@code pause} and {@code
     * rescheduleCronAfterFire} for the same id: whichever runs first is always fully observed
     * by the other, so no matter which wins the race, a {@code pause()} that ran at all must
     * leave the schedule paused once both have completed — {@code rescheduleCronAfterFire}
     * either sees the pause and declines to resurrect it, or runs first and installs a future
     * that the subsequent {@code pause()} then correctly cancels.
     */
    @Test
    void cronReschedule_racingPause_neverResurrectsAPausedSchedule() throws InterruptedException {
        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("cron-racer")));
        // "every minute" so CronEvaluator's scan is O(1) (200 rounds re-run it) — the real
        // ~1-60s one-shot future it schedules never actually fires within this test's lifetime.
        String cronExpression = "* * * * *";
        AgentSchedule schedule = AgentSchedule.builder()
                .scheduleId("cron-race")
                .agentId(AgentId.of("cron-racer"))
                .cron(cronExpression)
                .withInput("go")
                .build();
        scheduler.register(schedule);

        for (int round = 0; round < 200; round++) {
            CountDownLatch go = new CountDownLatch(1);
            Thread pauser = Thread.ofVirtual().start(() -> {
                await(go);
                scheduler.pause("cron-race");
            });
            Thread rescheduler = Thread.ofVirtual().start(() -> {
                await(go);
                scheduler.rescheduleCronAfterFire(schedule, cronExpression);
            });
            go.countDown();
            pauser.join(TimeUnit.SECONDS.toMillis(5));
            rescheduler.join(TimeUnit.SECONDS.toMillis(5));

            assertTrue(scheduler.isPaused("cron-race"),
                    "round " + round + ": pause() ran, so the schedule must end up paused "
                            + "regardless of interleaving with the concurrent reschedule");

            scheduler.resume("cron-race"); // reset for the next round
        }
    }

    // ── list() must report the live paused/active state, not the registered definition ──

    private boolean listedAsActive(String scheduleId) {
        return scheduler.list().stream()
                .filter(s -> scheduleId.equals(s.scheduleId()))
                .findFirst().orElseThrow()
                .active();
    }

    /**
     * {@code pause()} cancels the job but used to leave the stored {@link AgentSchedule}
     * untouched, so {@code list()} kept reporting {@code active=true} for a schedule that would
     * never fire — a caller (e.g. an agent tool) that paused a schedule and then listed it saw
     * it as still active.
     */
    @Test
    void list_reflectsPauseAndResume() {
        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("pausable")));
        scheduler.register(AgentSchedule.builder()
                .scheduleId("pausable-schedule")
                .agentId(AgentId.of("pausable"))
                .every(Duration.ofHours(1))
                .withInput("go")
                .build());
        assertTrue(listedAsActive("pausable-schedule"), "freshly registered schedule is active");

        scheduler.pause("pausable-schedule");
        assertFalse(listedAsActive("pausable-schedule"), "a paused schedule must be listed as inactive");

        scheduler.resume("pausable-schedule");
        assertTrue(listedAsActive("pausable-schedule"), "a resumed schedule must be listed as active again");
    }

    @Test
    void list_reportsARegisteredInactiveScheduleAsInactive_andResumeActivatesIt() {
        registry.register(new LocalAgentSchedulerTest.StubAgent(AgentId.of("dormant")));
        scheduler.register(AgentSchedule.builder()
                .scheduleId("dormant-schedule")
                .agentId(AgentId.of("dormant"))
                .every(Duration.ofHours(1))
                .withInput("go")
                .active(false)
                .build());
        assertFalse(listedAsActive("dormant-schedule"));

        scheduler.resume("dormant-schedule");
        assertTrue(listedAsActive("dormant-schedule"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
