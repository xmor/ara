package io.ara.runtime.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.DelegateStateAccess;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.RunState;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;
import io.ara.core.budget.HierarchicalBudget;
import io.ara.core.budget.Spend;
import io.ara.core.bus.AgentMessage;
import io.ara.core.bus.MessageBus;
import io.ara.core.common.Budget;
import io.ara.core.common.Money;

/**
 * Verifies {@link AgentDelegationTool} enforces {@link DelegateStateAccess} on the
 * outgoing {@link RunContext}'s {@code RunState} — SHARED (same reference), OVERLAY
 * (read-through, write-private), ISOLATED (nothing of the caller's).
 */
class AgentDelegationStateAccessTest {

    /** Captures the outgoing message; replies immediately without a real recipient agent. */
    private static MessageBus capturingBus(AtomicReference<AgentMessage> sent) {
        return new MessageBus() {
            @Override public void send(AgentMessage message) { sent.set(message); }
            @Override public AgentMessage request(AgentMessage message, Duration timeout) {
                sent.set(message);
                return AgentMessage.reply(message, message.recipientId(), "done");
            }
        };
    }

    private static AgentTask taskWithState(RunState state) {
        return AgentTask.of("parent task").withRunContext(RunContext.empty().withState(state));
    }

    @Test
    void shared_forwardsTheSameRunStateReference() {
        AtomicReference<AgentMessage> sent = new AtomicReference<>();
        AgentDelegationTool tool = new AgentDelegationTool(
                capturingBus(sent), "caller", Duration.ofSeconds(5), DelegateStateAccess.SHARED);
        RunState parentState = RunState.inMemory();

        tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", taskWithState(parentState));

        assertSame(parentState, sent.get().runContext().state(), "SHARED must forward the identical instance");
    }

    @Test
    void shared_delegateWritesAreVisibleToParent() {
        AtomicReference<AgentMessage> sent = new AtomicReference<>();
        AgentDelegationTool tool = new AgentDelegationTool(
                capturingBus(sent), "caller", Duration.ofSeconds(5), DelegateStateAccess.SHARED);
        RunState parentState = RunState.inMemory();

        tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", taskWithState(parentState));
        sent.get().runContext().state().put("fromDelegate", "yes"); // simulates the delegate writing during its run

        assertEquals("yes", parentState.get("fromDelegate", String.class).orElseThrow());
    }

    @Test
    void overlay_delegateReadsParentState() {
        AtomicReference<AgentMessage> sent = new AtomicReference<>();
        AgentDelegationTool tool = new AgentDelegationTool(
                capturingBus(sent), "caller", Duration.ofSeconds(5), DelegateStateAccess.OVERLAY);
        RunState parentState = RunState.inMemory();
        parentState.put("seed", "from-parent");

        tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", taskWithState(parentState));

        assertEquals("from-parent", sent.get().runContext().state().get("seed", String.class).orElseThrow());
    }

    @Test
    void overlay_delegateWritesNeverReachParentState() {
        AtomicReference<AgentMessage> sent = new AtomicReference<>();
        AgentDelegationTool tool = new AgentDelegationTool(
                capturingBus(sent), "caller", Duration.ofSeconds(5), DelegateStateAccess.OVERLAY);
        RunState parentState = RunState.inMemory();

        tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", taskWithState(parentState));
        sent.get().runContext().state().put("delegateOnly", "x"); // simulates the delegate writing during its run

        assertTrue(parentState.get("delegateOnly", String.class).isEmpty(),
                "OVERLAY writes must stay on the delegate's private layer");
    }

    @Test
    void isolated_delegateNeverSeesParentKeys() {
        AtomicReference<AgentMessage> sent = new AtomicReference<>();
        AgentDelegationTool tool = new AgentDelegationTool(
                capturingBus(sent), "caller", Duration.ofSeconds(5), DelegateStateAccess.ISOLATED);
        RunState parentState = RunState.inMemory();
        parentState.put("secret", "s");

        tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", taskWithState(parentState));

        assertTrue(sent.get().runContext().state().get("secret", String.class).isEmpty());
    }

    @Test
    void isolated_delegateWritesNeverReachParentState() {
        AtomicReference<AgentMessage> sent = new AtomicReference<>();
        AgentDelegationTool tool = new AgentDelegationTool(
                capturingBus(sent), "caller", Duration.ofSeconds(5), DelegateStateAccess.ISOLATED);
        RunState parentState = RunState.inMemory();

        tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", taskWithState(parentState));
        sent.get().runContext().state().put("delegateOnly", "x");

        assertTrue(parentState.get("delegateOnly", String.class).isEmpty());
    }

    // ── session propagation + persistence under a real SessionStore ────────────

    private static AgentTask taskWithStateAndSession(RunState state, SessionId sessionId) {
        return AgentTask.of("parent task")
                .withRunContext(RunContext.empty().withState(state))
                .withSessionId(sessionId);
    }

    @Test
    void callerWithRealSession_derivesNamespacedSessionIdForTheMessage() {
        AtomicReference<AgentMessage> sent = new AtomicReference<>();
        AgentDelegationTool tool = new AgentDelegationTool(
                capturingBus(sent), "caller", Duration.ofSeconds(5), DelegateStateAccess.OVERLAY, SessionStore.noop());
        SessionId callerSession = SessionId.of("caller-session");

        tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}",
                taskWithStateAndSession(RunState.inMemory(), callerSession));

        assertEquals(AgentDelegationTool.delegateSessionId(callerSession, "worker"), sent.get().sessionId());
        assertEquals(SessionId.of("caller-session::deleg::worker"), sent.get().sessionId());
    }

    @Test
    void callerWithoutASession_propagatesNullSessionId() {
        AtomicReference<AgentMessage> sent = new AtomicReference<>();
        AgentDelegationTool tool = new AgentDelegationTool(
                capturingBus(sent), "caller", Duration.ofSeconds(5), DelegateStateAccess.OVERLAY, SessionStore.noop());

        tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}",
                taskWithStateAndSession(RunState.inMemory(), null));

        assertNull(sent.get().sessionId(), "no caller session means nothing to anchor a delegate session to");
    }

    @Test
    void overlay_withRealStoreAndSession_delegateWritesPersistUnderTheDerivedSession() {
        AtomicReference<AgentMessage> sent = new AtomicReference<>();
        SessionStore store = SessionStore.inMemory();
        AgentDelegationTool tool = new AgentDelegationTool(
                capturingBus(sent), "caller", Duration.ofSeconds(5), DelegateStateAccess.OVERLAY, store);
        SessionId callerSession = SessionId.of("caller-session");

        tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}",
                taskWithStateAndSession(RunState.inMemory(), callerSession));
        sent.get().runContext().state().put("fromDelegate", "yes"); // simulates the delegate writing during its run

        SessionId delegateSession = AgentDelegationTool.delegateSessionId(callerSession, "worker");
        assertEquals(Map.of("fromDelegate", "yes"), store.loadState(delegateSession),
                "OVERLAY's private layer must now be backed by the store, not a throwaway in-memory RunState");
    }

    @Test
    void isolated_withRealStoreAndSession_delegateWritesPersistUnderTheDerivedSession() {
        AtomicReference<AgentMessage> sent = new AtomicReference<>();
        SessionStore store = SessionStore.inMemory();
        AgentDelegationTool tool = new AgentDelegationTool(
                capturingBus(sent), "caller", Duration.ofSeconds(5), DelegateStateAccess.ISOLATED, store);
        SessionId callerSession = SessionId.of("caller-session");

        tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}",
                taskWithStateAndSession(RunState.inMemory(), callerSession));
        sent.get().runContext().state().put("fromDelegate", "yes");

        SessionId delegateSession = AgentDelegationTool.delegateSessionId(callerSession, "worker");
        assertEquals(Map.of("fromDelegate", "yes"), store.loadState(delegateSession));
    }

    @Test
    void shared_withRealSession_stillDerivesASessionIdForHistoryContinuity_whileRunStateStaysTheSameReference() {
        AtomicReference<AgentMessage> sent = new AtomicReference<>();
        SessionStore store = SessionStore.inMemory();
        AgentDelegationTool tool = new AgentDelegationTool(
                capturingBus(sent), "caller", Duration.ofSeconds(5), DelegateStateAccess.SHARED, store);
        SessionId callerSession = SessionId.of("caller-session");
        RunState parentState = RunState.inMemory();

        tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}",
                taskWithStateAndSession(parentState, callerSession));

        assertEquals(AgentDelegationTool.delegateSessionId(callerSession, "worker"), sent.get().sessionId());
        assertSame(parentState, sent.get().runContext().state(), "SHARED must still forward the identical instance");
    }

    @Test
    void defaultConstructors_withoutASessionStore_behaveExactlyAsInMemory() {
        AtomicReference<AgentMessage> sent = new AtomicReference<>();
        AgentDelegationTool tool = new AgentDelegationTool(
                capturingBus(sent), "caller", Duration.ofSeconds(5), DelegateStateAccess.OVERLAY); // 4-arg, no store
        SessionId callerSession = SessionId.of("caller-session");

        tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}",
                taskWithStateAndSession(RunState.inMemory(), callerSession));
        sent.get().runContext().state().put("fromDelegate", "yes");

        // A real, derived sessionId is still on the message (unconditional), but with the
        // default noop() store nothing was actually persisted anywhere.
        assertEquals(AgentDelegationTool.delegateSessionId(callerSession, "worker"), sent.get().sessionId());
    }

    @Test
    void twoAndThreeArgConstructors_defaultToOverlay() {
        AtomicReference<AgentMessage> sent = new AtomicReference<>();
        AgentDelegationTool tool = new AgentDelegationTool(capturingBus(sent), "caller"); // 2-arg
        RunState parentState = RunState.inMemory();
        parentState.put("seed", "v");

        tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", taskWithState(parentState));

        // OVERLAY: readable but not the same reference
        assertEquals("v", sent.get().runContext().state().get("seed", String.class).orElseThrow());
        assertNotSame(parentState, sent.get().runContext().state());
    }

    // ── ADR-0069 D3: the hierarchical budget node rides the opaque channel through the hop ──

    private static HierarchicalBudget budget() {
        return HierarchicalBudget.root(Budget.limited(Money.of("10.00", "EUR")), 5000, null, null);
    }

    private static void assertBudgetPropagates(DelegateStateAccess mode) {
        AtomicReference<AgentMessage> sent = new AtomicReference<>();
        AgentDelegationTool tool = new AgentDelegationTool(
                capturingBus(sent), "caller", Duration.ofSeconds(5), mode);
        HierarchicalBudget callerBudget = budget();
        AgentTask task = AgentTask.of("parent task").withRunContext(
                callerBudget.attachTo(RunContext.empty().withState(RunState.inMemory())));

        tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", task);

        HierarchicalBudget onMessage = HierarchicalBudget.from(sent.get().runContext()).orElseThrow();
        assertSame(callerBudget, onMessage, mode + ": the delegate gets the same budget node");
        onMessage.record(Spend.of(Money.of("1.00", "EUR"), 100, 1));
        assertEquals(Money.of("1.00", "EUR"), callerBudget.spent().money(),
                mode + ": the delegate's spend lands on the caller's node");
    }

    @Test
    void budgetNode_propagatesForSharedOverlayAndIsolated() {
        assertBudgetPropagates(DelegateStateAccess.SHARED);
        assertBudgetPropagates(DelegateStateAccess.OVERLAY);
        assertBudgetPropagates(DelegateStateAccess.ISOLATED);
    }
}
