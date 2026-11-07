package io.ara.runtime.scheduler;

import io.ara.core.agent.AgentFuture;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentSchedule;
import io.ara.core.agent.AgentSchedule.Trigger;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AraAgents;
import io.ara.core.common.AgentId;
import io.ara.runtime.agent.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-JVM implementation of {@link AgentScheduler} (ADR-032).
 *
 * <p>Uses a {@link ScheduledExecutorService} backed by virtual threads.
 * Schedules are held in-memory — they do not survive process restart.
 * For persistence and distributed coordination see {@code DistributedAgentScheduler}
 * in {@code ara-cluster} (ADR-031).
 *
 * <p>Execution is fire-and-forget: each trigger calls
 * {@link AraAgents#executeAsync(AraAgent, AgentTask, java.util.concurrent.Executor)} and
 * logs the outcome. If the agent is not found in the registry the trigger is
 * skipped with a warning.
 *
 * <p><b>P2, 2026-09-22 — two executors, not one.</b> {@link #tickExecutor} is a small,
 * fixed-size pool whose only job is deciding <em>when</em> to fire (the {@code
 * scheduleAtFixedRate}/{@code schedule} calls in {@link #scheduleJob}/{@link
 * #scheduleCron}); {@link #agentExecutor} is a separate, unbounded virtual-thread-per-task
 * executor that actually runs the agent ({@link #fire}'s {@code AraAgents.executeAsync}
 * call). Before this they were the same fixed-size pool (sized {@code max(2,
 * availableProcessors())}): enough concurrent long-running agent executions filled every
 * worker, leaving nothing to fire the next tick on time — a scheduler starving its own
 * ticks on the very agents it started. Splitting them means a slow agent can never delay
 * another schedule's trigger.
 *
 * <p><b>P2, 2026-09-22 — {@code entries} mutations are atomic per id.</b> {@link #register},
 * {@link #pause}, {@link #resume} and {@link #rescheduleCronAfterFire} (the cron self-reschedule step) each
 * used to read {@code entries.get(id)} and then separately {@code entries.put(id, ...)} — two
 * non-atomic map operations. Two concurrent calls for the <em>same</em> id (e.g. two {@code
 * register} calls racing on startup, a {@code resume} racing a concurrent {@code register}, or
 * a {@code pause}/{@code cancel} racing a cron schedule's own reschedule right after it fires)
 * could both observe the same starting entry, both start their own new {@link ScheduledFuture}
 * (via {@link #scheduleJob}/{@link #scheduleCron}), and then overwrite each other's map entry —
 * whichever {@code put} lands last wins in {@code entries}, silently orphaning the other call's
 * job: it keeps firing forever, no longer reachable from {@link #list}/{@link #pause}/{@link
 * #cancel} since the map only ever tracks one {@code Entry} per id — or, in the cron case,
 * resurrecting a schedule a concurrent {@code pause}/{@code cancel} had just turned off. Every
 * mutating method below (and the cron reschedule step) now goes through a single {@code
 * entries.compute}/{@code remove} call per id instead, which {@link ConcurrentHashMap}
 * serializes per-bin — the racing calls above are now fully
 * ordered, and the second one always observes (and correctly cancels, or declines to resurrect)
 * the first one's job before installing its own. Safe to do work inside the remapping function
 * here specifically because {@link #scheduleJob}/{@link #scheduleCron} never block (they only
 * register a task with {@link #tickExecutor} and return) — the same "short, no I/O" exception
 * N1's own findings call out, not the general "don't do slow work inside a map's per-bin lock"
 * pinning risk those findings are otherwise about.
 *
 * <p><b>P1, 2026-11-06 — {@code register} builds the new job before cancelling the old.</b>
 * The remapping function used to cancel the existing entry's {@link ScheduledFuture} and only
 * then call {@link #scheduleJob}, which is where a cron expression is actually parsed
 * ({@link AgentSchedule.Trigger.Cron} only validates non-blankness). An invalid expression —
 * malformed, or well-formed with no next occurrence like {@code "0 0 30 2 *"} — threw out of
 * {@code compute} after the cancellation had already landed: the caller saw the exception, but
 * the surviving entry held a cancelled future that {@link #list} kept reporting as active and
 * that {@link #resume} could never re-arm (it only treats a {@code null} future as paused), so
 * the schedule never fired again. Building first inverts the failure order — a throw now leaves
 * the old job untouched — and is safe inside the remapping function for the same reason the P2
 * note gives: {@link #scheduleJob} never blocks.
 *
 * <p><b>2026-09-30 — a schedule never overlaps with itself.</b> A tick whose previous run is
 * still executing is skipped. {@link #agentExecutor} is a virtual-thread-per-task executor with
 * no ceiling, so before this an agent that outlasted its own interval was launched again on every
 * tick: a 1s agent on a 50ms interval was measured starting 22 concurrent executions in 1.1s,
 * each one a full agent run with its own LLM calls, sessions and tool dispatches, all of them
 * overlapping and none able to influence the others. For a recurring job that is never what was
 * wanted — a "daily report" that takes 40 minutes should produce one report, not 48 concurrent
 * half-written ones — and it is the per-item-spawn-without-a-ceiling shape this project's
 * concurrency rules call out as a resource-exhaustion vector.
 *
 * <p>Deliberately <em>not</em> a configurable policy, even though the obvious next step looks
 * like one. A "run again once the previous one finishes" variant needs the completion hook to
 * re-dispatch, which drags a second piece of state (a pending run) in beside the in-flight one,
 * moves {@link ScheduleExecutionListener#onFire} off the tick thread onto whichever thread
 * finished the previous run, and makes the release path re-entrant — all of it to serve a second
 * consumer nobody has asked for yet. And an unbounded "let me overlap freely" option would be the
 * wrong thing to offer anyway: a caller who genuinely wants N runs in flight registers N
 * schedules, and a caller who wants a <em>bound</em> of N is asking for a ceiling feature, which
 * is a different thing from "no ceiling". So one honest rule is applied unconditionally, and
 * whoever needs coalescing adds it with a real second caller in hand.
 *
 * <p><b>Checklist.</b> {@link Entry#inFlight()} must travel with the schedule id through every
 * mutation — that is what {@link Entry#withFuture} is for, and every site that swaps a {@link
 * ScheduledFuture} goes through it. A fresh {@link AtomicBoolean} at any such site would leave
 * the running execution's release pointed at an orphaned flag while the schedule's new flag reads
 * idle, silently restoring the unbounded overlap this exists to prevent.
 */
public final class LocalAgentScheduler implements AgentScheduler {

    private static final Logger log = LoggerFactory.getLogger(LocalAgentScheduler.class);

    private final AgentRegistry            registry;
    private final ScheduledExecutorService tickExecutor;
    private final ExecutorService          agentExecutor;
    private final ScheduleExecutionListener listener;

    /**
     * The AgentSchedule definition, its active {@link ScheduledFuture} ({@code null} when paused)
     * and the overlap flag guarding this id.
     *
     * <p><b>Thread-safe.</b> The two reference components never change after construction; only
     * the {@link AtomicBoolean} inside {@link #inFlight()} is mutable, and it is mutated solely
     * through its own CAS / {@code set}.
     */
    private record Entry(AgentSchedule schedule, ScheduledFuture<?> future, AtomicBoolean inFlight) {

        /**
         * The same schedule with a different job state, keeping the overlap flag. Every site that
         * installs, cancels or replaces a {@link ScheduledFuture} must build its new Entry here
         * rather than calling the canonical constructor — see the class javadoc's checklist.
         */
        Entry withFuture(ScheduledFuture<?> replacement) {
            return new Entry(schedule, replacement, inFlight);
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public LocalAgentScheduler(AgentRegistry registry) {
        this(registry, null);
    }

    /**
     * Creates a scheduler with an optional {@link ScheduleExecutionListener} observing
     * fires and outcomes (see the listener's contract: callbacks are best-effort and
     * exceptions from them never propagate).
     *
     * @param listener the listener to notify, or {@code null} for no notifications
     */
    public LocalAgentScheduler(AgentRegistry registry, ScheduleExecutionListener listener) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.listener = listener;
        this.tickExecutor = Executors.newScheduledThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                Thread.ofVirtual().name("ara-scheduler-tick-", 0).factory());
        this.agentExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    // ── AgentScheduler ────────────────────────────────────────────────────────

    @Override
    public void register(AgentSchedule schedule) {
        Objects.requireNonNull(schedule, "schedule must not be null");

        entries.compute(schedule.scheduleId(), (id, existing) -> {
            // The new job is built *before* the old one is cancelled. If the new definition is
            // invalid, scheduleJob throws from inside CronEvaluator (a malformed expression, or
            // a well-formed one with no next occurrence like "0 0 30 2 *" — Feb 30 never
            // exists) and the exception aborts this compute with the mapping left untouched,
            // old still-live job included. Cancelling first, as this used to, killed the old
            // job before the new one existed: the caller saw the exception while the surviving
            // entry held an already-cancelled future that list() kept reporting as active and
            // that resume() could never re-arm (it only treats a null future as paused) — a
            // zombie schedule, recoverable only by registering it again.
            ScheduledFuture<?> future = schedule.active() ? scheduleJob(schedule) : null;
            if (existing != null && existing.future() != null) {
                existing.future().cancel(false);
            }
            // A re-registration replaces the definition but must not orphan a run of the previous
            // one: that run still holds the old flag and will release it on completion, so the
            // new definition keeps waiting on that same flag rather than starting a second,
            // unblocked execution alongside it.
            AtomicBoolean inFlight = existing != null
                    ? existing.inFlight()
                    : new AtomicBoolean();
            return new Entry(schedule, future, inFlight);
        });
        log.info("[Scheduler] registered '{}' trigger={} active={}",
                schedule.scheduleId(), describe(schedule.trigger()), schedule.active());
    }

    @Override
    public void pause(String scheduleId) {
        boolean[] alreadyPaused = {false};
        entries.compute(scheduleId, (id, existing) -> {
            if (existing == null) {
                throw new NoSuchElementException("No schedule registered with id: " + scheduleId);
            }
            if (existing.future() == null) {
                alreadyPaused[0] = true;
                return existing;
            }
            existing.future().cancel(false);
            return existing.withFuture(null);
        });
        if (!alreadyPaused[0]) {
            log.info("[Scheduler] paused '{}'", scheduleId);
        }
    }

    @Override
    public void resume(String scheduleId) {
        boolean[] alreadyActive = {false};
        entries.compute(scheduleId, (id, existing) -> {
            if (existing == null) {
                throw new NoSuchElementException("No schedule registered with id: " + scheduleId);
            }
            if (existing.future() != null) {
                alreadyActive[0] = true;
                return existing;
            }
            return existing.withFuture(scheduleJob(existing.schedule()));
        });
        if (!alreadyActive[0]) {
            log.info("[Scheduler] resumed '{}'", scheduleId);
        }
    }

    @Override
    public void cancel(String scheduleId) {
        Entry removed = entries.remove(scheduleId);
        if (removed == null) {
            throw new NoSuchElementException("No schedule registered with id: " + scheduleId);
        }
        if (removed.future() != null) {
            removed.future().cancel(false);
        }
        log.info("[Scheduler] cancelled '{}'", scheduleId);
    }

    @Override
    public AgentFuture triggerNow(String scheduleId) {
        AgentSchedule schedule = require(scheduleId).schedule();
        log.info("[Scheduler] triggerNow '{}'", scheduleId);
        // A manual trigger is an explicit one-off request, not a tick: it dispatches even if the
        // schedule's previous run is still in flight, because the caller is holding the returned
        // AgentFuture and silently dropping their request would strand it. The overlap guard
        // below belongs to recurring ticks only.
        return dispatch(schedule, null);
    }

    @Override
    public List<AgentSchedule> list() {
        return entries.values().stream()
                .map(LocalAgentScheduler::withLiveActiveFlag)
                .toList();
    }

    /**
     * The stored {@link AgentSchedule} is the definition as registered; whether it is running is
     * decided by {@code pause}/{@code resume}, which only add/remove the job. Reporting the
     * definition's own {@code active} flag made a paused schedule keep listing as active.
     */
    private static AgentSchedule withLiveActiveFlag(Entry entry) {
        AgentSchedule s = entry.schedule();
        boolean running = entry.future() != null;
        return s.active() == running
                ? s
                : new AgentSchedule(s.scheduleId(), s.agentId(), s.trigger(), s.inputTemplate(), running);
    }

    @Override
    public void start() {
        log.info("[Scheduler] started ({} schedule(s) registered)", entries.size());
    }

    @Override
    public void stop() {
        entries.values().forEach(e -> {
            if (e.future() != null) e.future().cancel(false);
        });
        tickExecutor.shutdownNow();
        agentExecutor.shutdownNow();
        log.info("[Scheduler] stopped");
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private ScheduledFuture<?> scheduleJob(AgentSchedule schedule) {
        return switch (schedule.trigger()) {
            case Trigger.Interval i -> tickExecutor.scheduleAtFixedRate(
                    () -> safeFire(schedule),
                    i.every().toMillis(),
                    i.every().toMillis(),
                    TimeUnit.MILLISECONDS);

            case Trigger.Cron c -> scheduleCron(schedule, c.expression());
        };
    }

    /**
     * Minimal cron scheduler: computes delay to next fire time, then reschedules
     * itself after each execution.
     */
    private ScheduledFuture<?> scheduleCron(AgentSchedule schedule, String expression) {
        long delaySeconds = CronEvaluator.secondsUntilNext(expression);
        return tickExecutor.schedule(() -> {
            safeFire(schedule);
            // Re-schedule for the next occurrence — attempted regardless of whether safeFire
            // above swallowed a failure or not (P1): one bad trigger must not also prevent
            // every future one from ever being scheduled.
            try {
                rescheduleCronAfterFire(schedule, expression);
            } catch (Throwable t) {
                log.error("[Scheduler] '{}' failed to reschedule after its cron trigger — "
                        + "this schedule will not fire again until re-registered", schedule.scheduleId(), t);
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * Re-arms a cron schedule's next occurrence right after it fires, unless it was
     * concurrently paused or cancelled.
     *
     * <p>P2, 2026-09-22: used to read {@code entries.get(id)} and then separately {@code
     * entries.put(id, ...)} — two non-atomic map operations. A {@link #pause}/{@link #cancel}
     * landing between the two could be clobbered by this step's {@code put()} re-inserting a
     * freshly-scheduled future right after, resurrecting a schedule that had just been turned
     * off. Now goes through a single {@code entries.compute} call, same fix as {@link
     * #register}/{@link #pause}/{@link #resume} (see the class javadoc's P2 note).
     *
     * <p>Package-private (not {@code private}) so {@code LocalAgentSchedulerHardeningTest} can
     * race it against {@link #pause}/{@link #cancel} directly and deterministically — the real
     * path only reaches here from inside {@link #scheduleCron}'s one-shot {@link
     * java.util.concurrent.ScheduledFuture}, whose delay (from {@link CronEvaluator}) can be up
     * to a minute, making the race untestable through real cron timing.
     */
    void rescheduleCronAfterFire(AgentSchedule schedule, String expression) {
        entries.compute(schedule.scheduleId(), (id, current) -> {
            if (current == null || current.future() == null) {
                // cancelled or paused concurrently — do not resurrect it.
                return current;
            }
            return current.withFuture(scheduleCron(schedule, expression));
        });
    }

    /**
     * Package-private test hook: {@code true} if {@code scheduleId} is registered but
     * currently paused (no active {@link ScheduledFuture}).
     */
    boolean isPaused(String scheduleId) {
        Entry e = entries.get(scheduleId);
        return e != null && e.future() == null;
    }

    /**
     * Runs {@link #fire}, catching and logging any {@link Throwable} instead of letting it
     * propagate.
     *
     * <p>P1, 2026-09-22: an uncaught exception thrown from a {@code scheduleAtFixedRate} task
     * silently cancels every future execution of that periodic task — a documented {@link
     * java.util.concurrent.ScheduledThreadPoolExecutor} pitfall, not a hypothetical one, since
     * {@link #fire} can throw synchronously (a malformed {@code inputTemplate}, the executor
     * rejecting a submission during shutdown) even though the agent's own execution is
     * fire-and-forget. An uncaught exception from the cron path's one-shot {@code schedule()}
     * call similarly vanishes with nothing ever observing it, which stops the reschedule step
     * below from running at all. Either way a schedule "dies" with no trace in the logs of
     * why — mirrors {@code SessionManager.sweep()}'s own catch-all for the identical
     * scheduleAtFixedRate pitfall.
     *
     * <p>Package-private (not {@code private}) so {@code LocalAgentSchedulerTest} can call it
     * directly with a schedule constructed to make {@link #fire} throw deterministically —
     * asserting the surrounding {@code scheduleAtFixedRate}/cron machinery never sees an
     * exception is otherwise only provable indirectly and non-deterministically (timing-
     * dependent), the same testability reasoning behind {@code ReactExecutionSupport}'s own
     * package-private methods.
     */
    void safeFire(AgentSchedule schedule) {
        try {
            fire(schedule);
        } catch (Throwable t) {
            log.error("[Scheduler] '{}' trigger threw unexpectedly — the schedule will keep "
                    + "firing on its own interval/cron", schedule.scheduleId(), t);
        }
    }

    /**
     * A recurring tick: dispatches, unless this schedule's previous run is still executing, in
     * which case the tick is dropped.
     */
    private AgentFuture fire(AgentSchedule schedule) {
        // Acquired first, and only when the entry is still present: a tick for a schedule that was
        // cancelled between the job firing and here must not be reported as overlapping anything.
        // Reading the Entry and then CASing its own AtomicBoolean is safe without holding the
        // map's per-bin lock — the worst case is acting on an Entry that a concurrent cancel just
        // detached, which is exactly the "nothing to overlap" outcome anyway. Acquiring *outside*
        // the bin lock is deliberate: a dispatch must never serialize unrelated schedule ids.
        Entry entry = entries.get(schedule.scheduleId());
        if (entry != null && !entry.inFlight().compareAndSet(false, true)) {
            return skipped(schedule);
        }
        return dispatch(schedule, entry != null ? entry.inFlight() : null);
    }

    /**
     * Announces and hands {@code schedule} to its agent. {@code inFlight} is the overlap flag
     * this dispatch acquired, or {@code null} for a dispatch that did not (a manual trigger, or a
     * tick whose schedule was cancelled mid-fire) and therefore must not release anything.
     *
     * <p>{@link #notifyFire} lives here rather than in {@link #fire} so that a manual
     * {@link #triggerNow} keeps announcing itself exactly as it did when it shared the tick path,
     * and so a skip can announce before it explains.
     *
     * <p><b>Checklist.</b> Every exit that returns <em>without</em> attaching a completion
     * callback must release {@code inFlight}; the three that do are the synchronous-dispatch
     * catch, the agent-not-found branch, and {@link #onFireOutcome} for the async path. Missing
     * one leaves the flag set forever and silently stops the schedule from ever firing again.
     */
    private AgentFuture dispatch(AgentSchedule schedule, AtomicBoolean inFlight) {
        notifyFire(schedule.scheduleId(), schedule.agentId());
        log.debug("[Scheduler] firing '{}' agent={}", schedule.scheduleId(), schedule.agentId().value());
        return registry.findById(schedule.agentId())
                .map(agent -> {
                    AgentFuture future;
                    try {
                        // The template renders here, at fire time: a date written when the schedule
                        // was registered would be off by however long it has been ticking since.
                        AgentTask task = AgentTask.of(InputTemplates.render(schedule.inputTemplate()));
                        future = AraAgents.executeAsync(agent, task, agentExecutor);
                        CompletableFuture<AgentResponse> outcome = future.async();
                        if (schedule.maxDuration() != null) {
                            // The timeout completes this very future (orTimeout mutates it), so it
                            // must be attached before the completion callback: when the oven bell
                            // rings, the callback sees the TimeoutException and releases the
                            // overlap flag like any other terminal outcome. The underlying run is
                            // not interrupted — it keeps going underneath, and a still-hung run
                            // holds its session busy (SessionBusyPolicy may refuse the next
                            // ticks), but the schedule itself is not wedged for ever.
                            outcome.orTimeout(schedule.maxDuration().toMillis(), TimeUnit.MILLISECONDS);
                        }
                        outcome.whenComplete((r, ex) -> onFireOutcome(schedule, task, inFlight, r, ex));
                    } catch (Throwable t) {
                        // Task construction / dispatch failed synchronously — before whenComplete
                        // was ever registered. Without this catch, onFire would have already fired
                        // with no matching onComplete ever following it: a schedule with a blank
                        // inputTemplate (AgentSchedule does not validate it) would violate the
                        // listener's "one onFire, one onComplete" contract on every single tick.
                        release(inFlight);
                        AgentResponse failure = AgentResponse.failure(
                                "scheduler-" + schedule.scheduleId(),
                                schedule.agentId(),
                                "scheduled execution failed to dispatch: " + t.getMessage(),
                                Duration.ZERO);
                        notifyComplete(schedule.scheduleId(), failure);
                        log.warn("[Scheduler] '{}' failed to dispatch: {}", schedule.scheduleId(), t.getMessage());
                        future = AgentFuture.completed(failure);
                    }
                    return future;
                })
                .orElseGet(() -> {
                    release(inFlight);
                    log.warn("[Scheduler] agent {} not found for schedule '{}' — skipping",
                            schedule.agentId().value(), schedule.scheduleId());
                    AgentResponse failure = AgentResponse.failure(
                            "scheduler-" + schedule.scheduleId(),
                            schedule.agentId(),
                            "agent not found",
                            Duration.ZERO);
                    notifyComplete(schedule.scheduleId(), failure);
                    return AgentFuture.completed(failure);
                });
    }

    /**
     * Reports a tick that was dropped because the previous run of the same schedule is still
     * executing.
     *
     * <p>The skip is announced with {@link #notifyFire} before {@link #notifyComplete}, so the
     * listener's one-fire/one-completion pairing holds on this path too — a dropped tick is
     * reported as a completion whose failure reason says why, never as a silent tick with no
     * outcome. This is the same shape as the agent-not-found path, which announces a fire and
     * then reports that nothing was dispatched.
     *
     * <p>Logged at debug rather than warn: a schedule whose agent is simply slower than its own
     * interval skips every single tick, and a warn line per tick would flood the log with a
     * condition that is working as intended. The listener is where an application that cares
     * opts in to visibility.
     */
    private AgentFuture skipped(AgentSchedule schedule) {
        log.debug("[Scheduler] '{}' skipped — previous run still in flight", schedule.scheduleId());
        notifyFire(schedule.scheduleId(), schedule.agentId());
        AgentResponse failure = AgentResponse.failure(
                "scheduler-" + schedule.scheduleId(),
                schedule.agentId(),
                "skipped: previous run of this schedule is still in flight",
                Duration.ZERO);
        notifyComplete(schedule.scheduleId(), failure);
        return AgentFuture.completed(failure);
    }

    /** Clears the overlap flag so the schedule's next tick is eligible to run again. */
    private static void release(AtomicBoolean inFlight) {
        if (inFlight != null) {
            inFlight.set(false);
        }
    }

    /** Reports the terminal outcome of a dispatched execution — the normal-path counterpart
     *  of the synchronous-dispatch-failure handling in {@link #dispatch}, and the release site
     *  for the happy path's overlap flag. */
    private void onFireOutcome(AgentSchedule schedule, AgentTask task, AtomicBoolean inFlight,
                               AgentResponse r, Throwable ex) {
        release(inFlight);
        AgentResponse outcome = r;
        if (outcome == null && ex instanceof TimeoutException timeout) {
            // The oven bell, not the run: the run itself has no say yet — it may still, in fact,
            // be going. The listener sees a failure with a reason that says so.
            outcome = AgentResponse.failure(
                    task.taskId(),
                    schedule.agentId(),
                    "timed out after " + schedule.maxDuration() + ": the run may still be executing underneath",
                    Duration.ZERO);
        } else if (outcome == null && ex != null) {
            outcome = AgentResponse.failure(
                    task.taskId(),
                    schedule.agentId(),
                    "scheduled execution failed unexpectedly: " + ex.getMessage(),
                    Duration.ZERO);
        }
        notifyComplete(schedule.scheduleId(), outcome);
        if (ex != null) {
            log.warn("[Scheduler] '{}' threw: {}", schedule.scheduleId(), ex.getMessage());
        } else if (r.isSuccess()) {
            log.debug("[Scheduler] '{}' completed successfully", schedule.scheduleId());
        } else {
            log.warn("[Scheduler] '{}' failed: {}", schedule.scheduleId(), r.failureReason());
        }
    }

    // ── listener ────────────────────────────────────────────────────────────────

    /**
     * Best-effort {@link ScheduleExecutionListener#onFire}: a faulty listener must never
     * break the fire it is observing nor stall the scheduling thread it runs on.
     */
    private void notifyFire(String scheduleId, AgentId agentId) {
        if (listener == null) return;
        try {
            listener.onFire(scheduleId, agentId);
        } catch (Throwable t) {
            log.error("[Scheduler] onFire listener for '{}' threw — ignored", scheduleId, t);
        }
    }

    /**
     * Best-effort {@link ScheduleExecutionListener#onComplete}: a faulty listener must
     * never affect the agent run it reports on.
     */
    private void notifyComplete(String scheduleId, AgentResponse response) {
        if (listener == null) return;
        try {
            listener.onComplete(scheduleId, response);
        } catch (Throwable t) {
            log.error("[Scheduler] onComplete listener for '{}' threw — ignored", scheduleId, t);
        }
    }

    private Entry require(String scheduleId) {
        Entry entry = entries.get(scheduleId);
        if (entry == null) throw new NoSuchElementException(
                "No schedule registered with id: " + scheduleId);
        return entry;
    }

    private static String describe(Trigger trigger) {
        return switch (trigger) {
            case Trigger.Interval i -> "every " + i.every();
            case Trigger.Cron c    -> "cron(" + c.expression() + ")";
        };
    }

    // ── CronEvaluator ─────────────────────────────────────────────────────────

    /**
     * Minimal 5-field cron evaluator (minute hour dom month dow).
     *
     * <p><b>Public API.</b> Applications may use this class to validate a cron expression
     * <em>before</em> registering a schedule and to compute how far in the future its next
     * fire lies — both useful for persistence-backed schedulers that want to fail fast on
     * bad expressions at input time rather than when the schedule is registered.
     *
     * <p>Every field accepts:
     * <ul>
     *   <li>{@code *} — any value</li>
     *   <li>a single value ({@code 5}, or {@code MON} for day-of-week)</li>
     *   <li>a range ({@code 1-5}, {@code MON-FRI}, wrapping for day-of-week)</li>
     *   <li>a step over the whole range ({@code *}&#47;{@code 15}) or over a range
     *       ({@code 0-30}&#47;{@code 10})</li>
     *   <li>a comma-separated list combining any of the above ({@code 1,15,30},
     *       {@code MON,WED,FRI})</li>
     * </ul>
     *
     * <p>Returns seconds until the next matching instant. Day-of-month and
     * day-of-week follow the standard cron OR semantics: when both are restricted
     * (neither is {@code *}) an instant matches if it satisfies <em>either</em>
     * field. When only one is restricted, only that one is applied.
     */
    public static final class CronEvaluator {

        // A leap-cycle-safe upper bound: scanning minute-by-minute over ~4 years and 1 day
        // guarantees we reach the next "Feb 29" for expressions like "0 0 29 2 *".
        private static final int MAX_SCAN_MINUTES = (366 * 4 + 1) * 24 * 60;

        private CronEvaluator() {}

        private record Parsed(java.util.Set<Integer> minutes, java.util.Set<Integer> hours,
                              java.util.Set<Integer> doms, java.util.Set<Integer> months,
                              java.util.Set<Integer> dows, boolean domRestricted, boolean dowRestricted) { }

        private static Parsed parse(String expression) {
            String[] fields = expression.trim().split("\\s+");
            if (fields.length != 5)
                throw new IllegalArgumentException(
                        "Cron expression must have 5 fields: " + expression);

            java.util.Set<Integer> minutes = parseField(fields[0], 0, 59, false);
            java.util.Set<Integer> hours   = parseField(fields[1], 0, 23, false);
            java.util.Set<Integer> doms     = parseField(fields[2], 1, 31, false);
            java.util.Set<Integer> months  = parseField(fields[3], 1, 12, false);
            java.util.Set<Integer> dows     = parseField(fields[4], 0,  6, true);

            return new Parsed(minutes, hours, doms, months, dows,
                    doms != null, dows != null);
        }

        /** The first minute strictly after {@code from}, with the seconds shaved the way cron matches them. */
        private static LocalDateTime minuteAfter(LocalDateTime from) {
            return from.plusMinutes(1).withSecond(0).withNano(0);
        }

        /**
         * Returns the number of seconds between now and the next fire of {@code expression}.
         * The expression is fully parsed first, so malformed input throws immediately.
         *
         * @param expression a 5-field cron expression
         * @throws IllegalArgumentException if the expression is syntactically invalid
         * @throws IllegalStateException    if no next occurrence can be found (should not
         *                                  happen for any well-formed expression)
         */
        public static long secondsUntilNext(String expression) {
            Parsed parsed = parse(expression);
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime next = nextOccurrence(minuteAfter(now), parsed, expression);
            long delay = java.time.temporal.ChronoUnit.SECONDS.between(now, next);
            return Math.max(1, delay);
        }

        /** One scan: the first instant from (and including) {@code start} whose fields all match. */
        private static LocalDateTime nextOccurrence(LocalDateTime start, Parsed parsed, String expression) {
            // A leap-cycle-safe upper bound: scanning minute-by-minute over ~4 years and 1 day
            // guarantees we reach the next "Feb 29" for expressions like "0 0 29 2 *".
            LocalDateTime next = start;
            for (int i = 0; i < MAX_SCAN_MINUTES; i++) {
                boolean domMatch = !parsed.domRestricted() || parsed.doms().contains(next.getDayOfMonth());
                boolean dowMatch = !parsed.dowRestricted()
                        || parsed.dows().contains(next.getDayOfWeek().getValue() % 7);
                // Standard cron day matching: OR when both restricted, AND (trivially) otherwise.
                boolean dayMatch = (parsed.domRestricted() && parsed.dowRestricted())
                        ? (domMatch || dowMatch)
                        : (domMatch && dowMatch);

                if ((parsed.minutes() == null || parsed.minutes().contains(next.getMinute()))
                        && (parsed.hours()  == null || parsed.hours().contains(next.getHour()))
                        && (parsed.months() == null || parsed.months().contains(next.getMonthValue()))
                        && dayMatch) {
                    return next;
                }
                next = next.plusMinutes(1);
            }
            throw new IllegalStateException("Could not find next occurrence for cron: " + expression);
        }

        /**
         * The forward chronology of {@code expression}: the next {@code max} fire times strictly
         * after {@code from}, in ascending order. This is what a composing UI shows ("would fire
         * at ..., ..., ...") without registering anything; for a well-formed expression it always
         * completes, so the bound is {@code max} and the per-scan ceiling as in {@link #secondsUntilNext}.
         *
         * @param from the instant the preview starts from (usually now, or a test's pinned time)
         * @throws IllegalArgumentException if the expression is syntactically invalid
         * @throws IllegalStateException    if a later occurrence cannot be found for some entry (should
         *                                  not happen for any well-formed expression)
         */
        public static java.util.List<LocalDateTime> nextFires(LocalDateTime from, String expression, int max) {
            if (max <= 0) throw new IllegalArgumentException("max must be positive, got " + max);
            Parsed parsed = parse(expression);
            java.util.List<LocalDateTime> out = new java.util.ArrayList<>(max);
            LocalDateTime cursor = minuteAfter(from);
            for (int i = 0; i < max; i++) {
                LocalDateTime fire = nextOccurrence(cursor, parsed, expression);
                out.add(fire);
                cursor = fire.plusMinutes(1);
            }
            return out;
        }

        /**
         * Validates {@code expression} without returning a value. Equivalent to
         * {@link #secondsUntilNext} discarding the result — a convenience for input
         * validation ("does this cron parse?") where the delay is not needed.
         *
         * @param expression a 5-field cron expression
         * @throws IllegalArgumentException if the expression is syntactically invalid
         * @throws IllegalStateException    if no next occurrence can be found
         */
        public static void validate(String expression) {
            secondsUntilNext(expression);
        }

        /**
         * Parses one cron field into the set of allowed values, or {@code null} for
         * the wildcard {@code *}. Supports comma-separated lists, ranges, and steps.
         *
         * @param dow when {@code true}, symbolic day names ({@code MON}) are accepted
         *            and {@code 7} is normalised to {@code 0} (Sunday)
         */
        private static java.util.Set<Integer> parseField(String field, int min, int max, boolean dow) {
            if ("*".equals(field)) return null;

            java.util.Set<Integer> values = new java.util.HashSet<>();
            for (String part : field.split(",")) {
                if (part.isBlank())
                    throw new IllegalArgumentException("Empty cron list element in: " + field);
                parsePart(part.trim(), min, max, dow, values);
            }
            return values;
        }

        /** Parses a single list element: a value, a range, or a step over either. */
        private static void parsePart(String part, int min, int max, boolean dow,
                                      java.util.Set<Integer> out) {
            int step = 1;
            String rangePart = part;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                rangePart = part.substring(0, slash);
                step = Integer.parseInt(part.substring(slash + 1));
                if (step < 1)
                    throw new IllegalArgumentException("Cron step must be >= 1: " + part);
            }

            int lo;
            int hi;
            if ("*".equals(rangePart)) {
                lo = min;
                hi = max;
            } else if (rangePart.contains("-")) {
                String[] bounds = rangePart.split("-", 2);
                lo = value(bounds[0], min, max, dow);
                hi = value(bounds[1], min, max, dow);
            } else {
                lo = value(rangePart, min, max, dow);
                hi = lo;
            }

            // day-of-week ranges may wrap around the week (e.g. FRI-MON)
            if (dow && lo > hi) {
                int span = max - min + 1;               // 7
                int length = (hi - lo + span) % span + 1; // inclusive count of days in the wrap
                for (int i = 0; i < length; i += step) {
                    out.add(min + (lo - min + i) % span);
                }
                return;
            }

            if (lo > hi)
                throw new IllegalArgumentException("Cron range start after end: " + part);
            for (int v = lo; v <= hi; v += step) out.add(v);
        }

        /** Parses a single numeric or symbolic value and range-checks it. */
        private static int value(String token, int min, int max, boolean dow) {
            int val = dow ? dowValue(token) : Integer.parseInt(token.trim());
            if (val < min || val > max)
                throw new IllegalArgumentException(
                        "Cron field out of range [" + min + "," + max + "]: " + token);
            return val;
        }

        /** Maps a day-of-week token (symbolic or numeric, {@code 7}=Sunday) to 0..6. */
        private static int dowValue(String field) {
            return switch (field.trim().toUpperCase()) {
                case "SUN", "0", "7" -> 0;
                case "MON", "1" -> 1;
                case "TUE", "2" -> 2;
                case "WED", "3" -> 3;
                case "THU", "4" -> 4;
                case "FRI", "5" -> 5;
                case "SAT", "6" -> 6;
                default -> throw new IllegalArgumentException("Invalid day-of-week: " + field);
            };
        }
    }
}
