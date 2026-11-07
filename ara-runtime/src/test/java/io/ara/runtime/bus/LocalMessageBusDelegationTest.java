package io.ara.runtime.bus;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
import io.ara.core.bus.AgentMessage;
import io.ara.core.bus.Delegation;
import io.ara.core.common.AgentId;
import io.ara.runtime.agent.AgentRegistry;
import io.ara.runtime.agent.SessionScoped;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LocalMessageBus#delegate}: start without waiting, hand back what a caller that does not wait needs. */
class LocalMessageBusDelegationTest {

    /** Runs until released; records the task it ran and every session it was asked to terminate. */
    private static final class GatedAgent implements AraAgent, SessionScoped {
        private final AgentId id = AgentId.of("worker");
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<AgentTask> ran = new AtomicReference<>();
        final List<SessionId> terminated = new CopyOnWriteArrayList<>();

        @Override public AgentId agentId() { return id; }
        @Override public AgentConfig config() { return AgentConfig.defaults().agentId(id).agentType("t").build(); }
        @Override public AgentState currentState() { return AgentState.IDLE; }

        @Override
        public AgentResponse execute(AgentTask task) {
            ran.set(task);
            running.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentResponse.success(task.taskId(), id, "finished", 1, 0, 0.0, Duration.ZERO, List.of());
        }

        @Override public void terminate() { }
        @Override public void terminate(SessionId sessionId) { terminated.add(sessionId); }
        @Override public void invalidateSession(SessionId sessionId) { }
        @Override public int activeSessionCount() { return 0; }

        @Override
        public List<Map<String, Object>> sessionsSnapshot() { return List.of(); }
        @Override public void cancelAllSessions() { }
    }

    private static LocalMessageBus busWith(GatedAgent worker) {
        AgentRegistry registry = new AgentRegistry();
        registry.register(worker);
        return new LocalMessageBus(registry);
    }

    @Test
    void delegate_returnsBeforeTheRecipientFinishes_andItsTaskIdIsTheOneTheRecipientRuns() throws Exception {
        GatedAgent worker = new GatedAgent();
        LocalMessageBus bus = busWith(worker);

        Delegation delegation = bus.delegate(AgentMessage.of("caller", "worker", "do it"));

        assertTrue(worker.running.await(5, TimeUnit.SECONDS));
        assertFalse(delegation.response().isDone(), "the bus must not wait for the recipient");
        assertEquals(worker.ran.get().taskId(), delegation.taskId());
        worker.release.countDown();
        assertEquals("finished", delegation.response().get(5, TimeUnit.SECONDS).content());
    }

    @Test
    void delegate_givesTheWholeResponse_soAFailureKeepsItsReason() throws Exception {
        AgentRegistry registry = new AgentRegistry();
        AgentId id = AgentId.of("worker");
        registry.register(new AraAgent() {
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return AgentConfig.defaults().agentId(id).agentType("t").build(); }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.failure(task.taskId(), id, "no capacity", Duration.ZERO);
            }
            @Override public void terminate() { }
        });

        AgentResponse response = new LocalMessageBus(registry)
                .delegate(AgentMessage.of("caller", "worker", "do it")).response().get(5, TimeUnit.SECONDS);

        assertFalse(response.isSuccess());
        assertEquals("no capacity", response.failureReason());
    }

    @Test
    void cancel_terminatesTheRecipientsSession_evenWhenTheMessageCarriedNone() throws Exception {
        GatedAgent worker = new GatedAgent();
        Delegation delegation = busWith(worker).delegate(AgentMessage.of("caller", "worker", "do it"));
        assertTrue(worker.running.await(5, TimeUnit.SECONDS));

        delegation.cancel().run();

        assertEquals(List.of(worker.ran.get().sessionId()), worker.terminated);
        assertTrue(delegation.response().isCancelled());
        worker.release.countDown();
    }

    @Test
    void delegate_toAnUnknownRecipient_failsAtOnce_likeRequest() {
        LocalMessageBus bus = new LocalMessageBus(new AgentRegistry());

        assertThrows(NoSuchElementException.class, () -> bus.delegate(AgentMessage.of("caller", "ghost", "do it")));
    }
}
