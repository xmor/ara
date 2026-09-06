package io.ara.runtime.bus;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.DelegateStateAccess;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.SessionStore;
import io.ara.core.auth.ExecutionContext;
import io.ara.core.auth.ScopeSet;
import io.ara.core.common.AgentId;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.agent.AgentRegistry;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 5 §5.3 DONE-WHEN, verbatim (`docs/adr/ADR-033-implementation-plan.md`,
 * `ara-private`): "catena A[ops,hr] → B[finance,ops] → C[ops]. Verificare che C veda
 * effectiveScopes=[ops] e non possa accedere a risorse finance."
 *
 * <p>{@link io.ara.core.auth.ScopeSetTest#intersect_composesAcrossADelegationChain} and
 * {@link io.ara.core.auth.ExecutionContextTest#delegate_composesAcrossAThreeHopChain_matchingTheAdrExample}
 * already prove the intersection math in isolation. This test proves the *wiring*: two
 * real {@link LocalMessageBus} hops, two real {@link AgentDelegationTool} instances, and
 * an {@link ExecutionContext} that actually survives both — not a mock of any of them.
 */
class ExecutionContextDelegationChainTest {

    private static AraAgent echoingLeaf(AgentId id, List<String> grantedScopes, AtomicReference<ExecutionContext> seen) {
        AgentConfig config = AgentConfig.defaults().agentId(id).agentType("t")
                .grantedScopes(grantedScopes).build();
        return new AraAgent() {
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return config; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                seen.set(task.runContext().opaque(RunContext.EXECUTION_CONTEXT_KEY, ExecutionContext.class));
                return AgentResponse.success(task.taskId(), id, "leaf reached", 1, 0, 0.0, Duration.ZERO, List.of());
            }
            @Override public void terminate() {}
        };
    }

    @Test
    void threeHopChain_CSeesOpsOnly_evenThoughAAndBCouldMore() {
        AgentRegistry registry = new AgentRegistry();
        AtomicReference<ExecutionContext> seenAtC = new AtomicReference<>();
        // C's own grantedScopes is deliberately left empty and irrelevant here: the
        // narrowing to "ops" comes entirely from each hop's AgentDelegationTool
        // (ownGrantedScopes ctor param), never from LocalMessageBus re-intersecting
        // against the recipient's own ceiling — a leaf agent that never delegates
        // further, like C, correctly needs no grantedScopes of its own to be reachable.
        registry.register(echoingLeaf(AgentId.of("C"), List.of(), seenAtC));

        LocalMessageBus bus = new LocalMessageBus(registry);

        // B → C: B's own ceiling is [finance, ops].
        AgentDelegationTool delegateToC = new AgentDelegationTool(
                bus, "B", Duration.ofSeconds(5), DelegateStateAccess.OVERLAY,
                SessionStore.noop(), ScopeSet.of("finance", "ops"));

        AgentConfig bConfig = AgentConfig.defaults().agentId(AgentId.of("B")).agentType("t")
                .grantedScopes(List.of("finance", "ops")).build();
        registry.register(new AraAgent() {
            @Override public AgentId agentId() { return AgentId.of("B"); }
            @Override public AgentConfig config() { return bConfig; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                ToolResult r = delegateToC.execute("{\"agent_id\":\"C\",\"task\":\"go\"}", task);
                return r.isSuccess()
                        ? AgentResponse.success(task.taskId(), AgentId.of("B"), r.output(), 1, 0, 0.0, Duration.ZERO, List.of())
                        : AgentResponse.failure(task.taskId(), AgentId.of("B"), r.error(), Duration.ZERO);
            }
            @Override public void terminate() {}
        });

        // A → B: A's own ceiling is [ops, hr] — never had finance to begin with.
        AgentDelegationTool delegateToB = new AgentDelegationTool(
                bus, "A", Duration.ofSeconds(5), DelegateStateAccess.OVERLAY,
                SessionStore.noop(), ScopeSet.of("ops", "hr"));

        AgentTask initial = AgentTask.of("start").withRunContext(
                RunContext.empty().withOpaque(RunContext.EXECUTION_CONTEXT_KEY,
                        ExecutionContext.ofAgent("A", ScopeSet.of("ops", "hr"))));

        ToolResult result = delegateToB.execute("{\"agent_id\":\"B\",\"task\":\"go\"}", initial);

        assertTrue(result.isSuccess(), result.error());
        ExecutionContext atC = seenAtC.get();
        assertNotNull(atC, "C must have received an ExecutionContext through both bus hops");
        assertEquals(ScopeSet.of("ops"), atC.effectiveScopes());
        assertFalse(atC.effectiveScopes().scopes().contains("finance"),
                "C cannot see finance even though B could");
        assertFalse(atC.effectiveScopes().scopes().contains("hr"),
                "C cannot see hr even though A could");
    }

    /**
     * Regression test for a real bug found end-to-end (not in this file): {@code
     * LocalMessageBus.resolveExecutionContext} used to re-intersect the incoming
     * {@code ExecutionContext} against {@code recipient.config().grantedScopes()} — a
     * leaf recipient with no reason to declare a ceiling of its own (it never delegates
     * further) saw its caller's legitimately-attenuated authority silently zeroed before
     * its own {@code requiredScopes} check ever ran. Fixed by relabeling the actor
     * without a second intersection — {@code recipient}'s own ceiling only ever matters
     * once {@code recipient} itself becomes a sender.
     */
    @Test
    void leafRecipientWithNoGrantedScopesOfItsOwn_stillReceivesTheCallersFullAttenuatedGrant() {
        AgentRegistry registry = new AgentRegistry();
        AtomicReference<ExecutionContext> seenAtLeaf = new AtomicReference<>();
        registry.register(echoingLeaf(AgentId.of("leaf"), List.of(), seenAtLeaf));

        LocalMessageBus bus = new LocalMessageBus(registry);
        AgentDelegationTool delegateToLeaf = new AgentDelegationTool(
                bus, "caller", Duration.ofSeconds(5), DelegateStateAccess.OVERLAY,
                SessionStore.noop(), ScopeSet.of("finance:read"));

        AgentTask initial = AgentTask.of("start").withRunContext(
                RunContext.empty().withOpaque(RunContext.EXECUTION_CONTEXT_KEY,
                        ExecutionContext.ofAgent("caller", ScopeSet.of("finance:read"))));

        ToolResult result = delegateToLeaf.execute("{\"agent_id\":\"leaf\",\"task\":\"go\"}", initial);

        assertTrue(result.isSuccess(), result.error());
        assertEquals(ScopeSet.of("finance:read"), seenAtLeaf.get().effectiveScopes(),
                "the leaf's own empty grantedScopes must not zero out the caller's grant");
    }
}
