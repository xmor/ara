package io.ara.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Encapsulates the runtime lifecycle state machine and the shared virtual-thread
 * executor (ADR-0069). Separates lifecycle management from the agent-creation
 * and scheduling concerns in {@link AraRuntime}.
 *
 * <p>Phases: {@code NEW → STARTED ⇄ STOPPED}. Explicit restart via
 * {@link #start()} is supported (a fresh executor is provisioned); only the
 * implicit auto-start performed by {@link AraRuntime#createAgent} /
 * {@link AraRuntime#submit} is limited to the {@code NEW} phase — see
 * {@link #autoStart()}.
 */
final class RuntimeLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RuntimeLifecycle.class);

    enum Phase { NEW, STARTED, STOPPED }

    private final Object lock = new Object();
    private volatile Phase phase = Phase.NEW;
    private volatile Executor agentExecutor;
    private final QuiescenceTracker quiescenceTracker;
    private final String name;
    private final int shutdownTimeoutSec;

    RuntimeLifecycle(String name, int shutdownTimeoutSec) {
        this.name = name;
        this.shutdownTimeoutSec = shutdownTimeoutSec;
        this.quiescenceTracker = new QuiescenceTracker();
    }

    /**
     * Transitions the phase from {@code NEW} or {@code STOPPED} to {@code STARTED},
     * provisioning a fresh virtual-thread executor. No-op if already {@code STARTED}.
     */
    void start() {
        synchronized (lock) {
            if (phase == Phase.STARTED) return;
            agentExecutor = Executors.newVirtualThreadPerTaskExecutor();
            phase = Phase.STARTED;
        }
    }

    /**
     * Transitions the phase to {@code STOPPED} and shuts down the executor
     * gracefully. No-op if already {@code STOPPED}.
     */
    void stop() {
        synchronized (lock) {
            if (phase != Phase.STARTED) return;
            shutdownExecutor();
            phase = Phase.STOPPED;
        }
    }

    /**
     * Prepares the runtime for agent creation or task submission. No-op if
     * already {@code STARTED}; throws {@link IllegalStateException} if
     * {@code STOPPED} — a stopped runtime cannot be implicitly resurrected.
     */
    void autoStart() {
        if (phase == Phase.STOPPED) {
            throw new IllegalStateException("AraRuntime [" + name + "] has been stopped — call start()"
                    + " to restart it explicitly before creating agents or submitting tasks");
        }
        start(); // no-op when already STARTED
    }

    /** Returns the shared virtual-thread executor, or {@code null} before {@link #start()}. */
    Executor getAgentExecutor() { return agentExecutor; }

    /**
     * Returns the executor, auto-starting the runtime first if it was never
     * started ({@code NEW}) and throwing {@link IllegalStateException} if it was
     * stopped — the atomic slow path behind {@link AraRuntime#submit}.
     */
    Executor getAgentExecutorOrAutoStart() {
        synchronized (lock) {
            autoStart(); // throws when stopped; no-op when STARTED
            return agentExecutor;
        }
    }

    /** {@code true} between a {@link #start()} and the next {@link #stop()}. */
    boolean isStarted() { return phase == Phase.STARTED; }

    /** {@code true} after a {@link #stop()}. */
    boolean isStopped() { return phase == Phase.STOPPED; }

    /** The lock that serializes lifecycle transitions and agent operations. */
    Object getLock() { return lock; }

    /** Current phase, for diagnostics and health-check surfaces. */
    Phase phase() { return phase; }

    // ── quiescence tracking — in-flight task bookkeeping ────────────────────

    void taskStarted() { quiescenceTracker.taskStarted(); }

    void taskFinished() { quiescenceTracker.taskFinished(); }

    boolean awaitQuiescence(long timeout, TimeUnit unit) throws InterruptedException {
        return quiescenceTracker.awaitQuiescence(timeout, unit);
    }

    int inFlightCount() { return quiescenceTracker.inFlightCount(); }

    /**
     * Shuts the shared executor down gracefully, waiting up to
     * {@code shutdownTimeoutSec} seconds for in-flight tasks to finish
     * before forcing {@code shutdownNow()}.
     */
    private void shutdownExecutor() {
        if (!(agentExecutor instanceof ExecutorService es)) return;
        es.shutdown();
        try {
            if (!es.awaitTermination(shutdownTimeoutSec, TimeUnit.SECONDS)) {
                log.warn("AraRuntime [{}] executor did not drain within {}s — forcing shutdownNow",
                        name, shutdownTimeoutSec);
                es.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            es.shutdownNow();
        }
    }
}
