package io.ara.runtime.bus;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
import io.ara.core.bus.AgentMessage;
import io.ara.core.common.AgentId;
import io.ara.runtime.agent.AgentRegistry;
import io.ara.runtime.agent.SessionScoped;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test: {@code LocalMessageBus.request()} used to call only
 * {@code future.cancel(true)} on timeout — a no-op for a {@code CompletableFuture}
 * obtained via {@code supplyAsync}, since {@code cancel()}'s {@code mayInterruptIfRunning}
 * has no effect on it (documented JDK behavior). The recipient agent kept running to
 * completion after the caller had already given up. {@code request()} now also calls
 * {@code recipient.terminate(sessionId)} so the recipient's in-flight execution is
 * actually cancelled.
 */
class LocalMessageBusCancellationTest {

    /**
     * Blocks {@code execute()} until interrupted (simulating a slow delegate) and
     * records every {@code terminate(SessionId)} call it receives.
     */
    private static final class HangingAgent implements AraAgent, SessionScoped {
        private final AgentId id;
        final List<SessionId> terminateCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
        final CountDownLatch executing = new CountDownLatch(1);

        HangingAgent(String id) {
            this.id = AgentId.of(id);
        }

        @Override public AgentId agentId() { return id; }
        @Override public AgentConfig config() { return AgentConfig.defaults().agentId(id).agentType("t").build(); }
        @Override public AgentState currentState() { return AgentState.IDLE; }

        @Override
        public AgentResponse execute(AgentTask task) {
            executing.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentResponse.success(task.taskId(), id, "too late", 1, 0, 0.0, Duration.ZERO, List.of());
        }

        @Override public void terminate() { }

        @Override
        public void terminate(SessionId sessionId) {
            terminateCalls.add(sessionId);
        }

        @Override public void invalidateSession(SessionId sessionId) { }
        @Override public int activeSessionCount() { return terminateCalls.isEmpty() ? 1 : 0; }
        @Override public void cancelAllSessions() { }
    }

    @Test
    void request_onTimeout_terminatesTheRecipientsSession() throws InterruptedException {
        AgentRegistry registry = new AgentRegistry();
        HangingAgent worker = new HangingAgent("worker");
        registry.register(worker);
        LocalMessageBus bus = new LocalMessageBus(registry);

        AgentMessage request = AgentMessage.of("caller", "worker", "do it");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> bus.request(request, Duration.ofMillis(200)));
        assertTrue(failure.getMessage().contains("timed out"));

        assertTrue(worker.executing.await(2, TimeUnit.SECONDS), "recipient never started executing");
        assertEquals(1, worker.terminateCalls.size(),
                "request() must terminate the recipient's session on timeout, not just abandon the future");
    }

    @Test
    void request_onCallerInterruption_terminatesTheRecipientsSession() throws InterruptedException {
        AgentRegistry registry = new AgentRegistry();
        HangingAgent worker = new HangingAgent("worker");
        registry.register(worker);
        LocalMessageBus bus = new LocalMessageBus(registry);

        AgentMessage request = AgentMessage.of("caller", "worker", "do it");

        AtomicReference<Throwable> caught = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                bus.request(request, Duration.ofSeconds(30));
            } catch (Throwable t) {
                caught.set(t);
            }
        });
        caller.start();

        assertTrue(worker.executing.await(2, TimeUnit.SECONDS), "recipient never started executing");
        caller.interrupt();
        caller.join(2000);

        assertFalse(caller.isAlive(), "caller thread should have unblocked after interruption");
        assertEquals(1, worker.terminateCalls.size(),
                "an interrupted request() must terminate the recipient's session too");
    }
}
