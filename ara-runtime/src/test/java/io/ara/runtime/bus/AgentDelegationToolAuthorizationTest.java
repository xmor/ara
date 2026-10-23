package io.ara.runtime.bus;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.DelegateStateAccess;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.SessionStore;
import io.ara.core.auth.ScopeSet;
import io.ara.core.bus.AgentMessage;
import io.ara.core.common.AgentId;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.agent.AgentRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 3 §3.3 — {@link AgentDelegationTool}'s two new gates: a discovery-time
 * {@code AgentView} visibility pre-check, and populating {@link
 * AgentMessage#senderScopes()} from this agent's own granted scopes so {@link
 * LocalMessageBus}'s Fase 2 authorization check has something real to check against
 * on a delegation's first hop (not just the attenuated value ADR-0077 already threads
 * onward for a <em>second</em> hop).
 */
class AgentDelegationToolAuthorizationTest {

    private final AtomicReference<AgentMessage> sent = new AtomicReference<>();

    private final io.ara.core.bus.MessageBus capturingBus = new io.ara.core.bus.MessageBus() {
        @Override public void send(AgentMessage message) { sent.set(message); }
        @Override public AgentMessage request(AgentMessage message, Duration timeout) {
            sent.set(message);
            return AgentMessage.reply(message, message.recipientId(), "done");
        }
    };

    @Test
    void firstHop_populatesSenderScopesFromOwnGrantedScopes_notJustTheAttenuatedValue() {
        // No incoming ADR-0077 attenuation yet (a plain AgentTask.of(...)) — the first
        // hop's senderScopes must still be this agent's own granted scopes, not EMPTY.
        AgentDelegationTool tool = new AgentDelegationTool(capturingBus, "caller", Duration.ofSeconds(1),
                DelegateStateAccess.OVERLAY, SessionStore.noop(), ScopeSet.of("finance:read"));

        tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", AgentTask.of("go"));

        assertEquals(ScopeSet.of("finance:read"), sent.get().senderScopes());
    }

    @Test
    void secondHop_populatesSenderScopesFromTheAttenuatedIntersection() {
        AgentTask callerTask = AgentTask.of("parent")
                .withAttachment(RunContext.SCOPES_KEY, ScopeSet.of("finance:read", "ops"));
        AgentDelegationTool tool = new AgentDelegationTool(capturingBus, "caller", Duration.ofSeconds(1),
                DelegateStateAccess.OVERLAY, SessionStore.noop(), ScopeSet.of("finance:read", "admin"));

        tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", callerTask);

        assertEquals(ScopeSet.of("finance:read"), sent.get().senderScopes(),
                "{finance:read,ops} ∩ {finance:read,admin} = {finance:read}");
    }

    @Test
    void noAgentView_skipsTheVisibilityPreCheck_todaysBehaviorUnchanged() {
        AgentDelegationTool tool = new AgentDelegationTool(capturingBus, "caller", Duration.ofSeconds(1),
                DelegateStateAccess.OVERLAY, SessionStore.noop(), ScopeSet.EMPTY, null);

        ToolResult result = tool.execute("{\"agent_id\":\"anyone\",\"task\":\"do it\"}", AgentTask.of("go"));

        assertTrue(result.success());
    }

    @Test
    void withAgentView_deniesDelegationToAnAgentNotVisible() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(agent("worker", null, "finance"));   // requires no scope, but only visible to "finance"

        AgentDelegationTool tool = new AgentDelegationTool(capturingBus, "caller", Duration.ofSeconds(1),
                DelegateStateAccess.OVERLAY, SessionStore.noop(), ScopeSet.EMPTY,
                registry.viewFor(ScopeSet.of("ops")));

        ToolResult result = tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", AgentTask.of("go"));

        assertFalse(result.success());
        assertTrue(result.error().contains("worker"), result.error());
    }

    @Test
    void withAgentView_permitsDelegationToAVisibleAgent() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(agent("worker", null, "ops"));

        AgentDelegationTool tool = new AgentDelegationTool(capturingBus, "caller", Duration.ofSeconds(1),
                DelegateStateAccess.OVERLAY, SessionStore.noop(), ScopeSet.EMPTY,
                registry.viewFor(ScopeSet.of("ops")));

        ToolResult result = tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", AgentTask.of("go"));

        assertTrue(result.success());
    }

    // ── ADR-033 §3.3, task-scoped form: the pre-check's authority comes per task ──

    @Test
    void withViewSource_theTasksScopesDecide_notTheWiringTimeView() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(agent("worker", null, "ops"));

        AgentDelegationTool tool = new AgentDelegationTool(capturingBus, "caller", Duration.ofSeconds(1),
                DelegateStateAccess.OVERLAY, SessionStore.noop(), ScopeSet.EMPTY, null,
                null, id -> null, task -> registry.viewFor(io.ara.core.agent.TaskScopes.of(task).scopes()));

        // The task carries {ops} through the bare scope channel → the recipient is visible.
        ToolResult allowed = tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", AgentTask.of("go")
                .withAttachment(RunContext.SCOPES_KEY, ScopeSet.of("ops")));
        assertTrue(allowed.success(), allowed.error());

        // The task carries only {finance} → the {ops}-gated recipient is not found.
        ToolResult denied = tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", AgentTask.of("go")
                .withAttachment(RunContext.SCOPES_KEY, ScopeSet.of("finance")));
        assertFalse(denied.success());
        assertTrue(denied.error().contains("Agent not found: worker"), denied.error());
    }

    @Test
    void withViewSource_nullViewForTheTask_skipsThePreCheck_andTheBusDecides() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(agent("worker", null, "ops"));

        // "null" from the source = no scope channel on this task = no authority to judge
        // by: the pre-check defers, and the bus's dispatch-time check names the reason.
        AgentDelegationTool tool = new AgentDelegationTool(capturingBus, "caller", Duration.ofSeconds(1),
                DelegateStateAccess.OVERLAY, SessionStore.noop(), ScopeSet.EMPTY, null,
                null, id -> null, task -> {
                    var resolved = io.ara.core.agent.TaskScopes.of(task);
                    return resolved.channelPresent() ? registry.viewFor(resolved.scopes()) : null;
                });

        ToolResult result = tool.execute("{\"agent_id\":\"anyone\",\"task\":\"do it\"}", AgentTask.of("go"));

        assertTrue(result.success(), "the recipient declares no scopes, so the bus lets it through");
    }

    @Test
    void withViewSource_anEmptyButPresentChannel_deniesGatedRecipients() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(agent("worker", null, "ops"));

        AgentDelegationTool tool = new AgentDelegationTool(capturingBus, "caller", Duration.ofSeconds(1),
                DelegateStateAccess.OVERLAY, SessionStore.noop(), ScopeSet.EMPTY, null,
                null, id -> null, task -> registry.viewFor(io.ara.core.agent.TaskScopes.of(task).scopes()));

        // A channel that is present but empty is a legitimate "this caller has no
        // authority" — not a missing channel: the pre-check denies, fail-closed.
        ToolResult result = tool.execute("{\"agent_id\":\"worker\",\"task\":\"do it\"}", AgentTask.of("go")
                .withAttachment(RunContext.SCOPES_KEY, ScopeSet.EMPTY));

        assertFalse(result.success());
        assertTrue(result.error().contains("Agent not found: worker"), result.error());
    }

    @Test
    void fixedAgentView_winsOverViewSource_mirroringTheToolFilter() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(agent("worker", null, "ops"));
        registry.register(agent("other", null, "finance"));

        // Fixed view built for {ops}: it must decide even though the task-scoped source
        // would answer differently for the {finance} task below.
        AgentDelegationTool tool = new AgentDelegationTool(capturingBus, "caller", Duration.ofSeconds(1),
                DelegateStateAccess.OVERLAY, SessionStore.noop(), ScopeSet.EMPTY,
                registry.viewFor(ScopeSet.of("ops")),
                null, id -> null, task -> registry.viewFor(io.ara.core.agent.TaskScopes.of(task).scopes()));

        ToolResult result = tool.execute("{\"agent_id\":\"other\",\"task\":\"do it\"}", AgentTask.of("go")
                .withAttachment(RunContext.SCOPES_KEY, ScopeSet.of("finance")));

        assertFalse(result.success());
        assertTrue(result.error().contains("Agent not found: other"), result.error());
    }

    // ── End-to-end, via a real LocalMessageBus (the plan's own Fase 3 test scenario) ──
    //
    // "Agente A con scope ["ops"] non riesce a delegare ad agente B con
    // requiredScopes=["finance"]. Agente C con ["ops","finance"] riesce."

    private static AraAgent echoAgent(String id, String requiredScope) {
        AgentId agentId = AgentId.of(id);
        AgentConfig config = AgentConfig.defaults()
                .agentId(agentId).agentType("t")
                .requiredScopes(requiredScope == null ? List.of() : List.of(requiredScope))
                .build();
        return new AraAgent() {
            @Override public AgentId agentId() { return agentId; }
            @Override public AgentConfig config() { return config; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.success(task.taskId(), agentId, "handled", 1, 0, 0.0, Duration.ZERO, List.of());
            }
            @Override public void terminate() {}
        };
    }

    @Test
    void endToEnd_anAgentLackingTheRequiredScope_cannotDelegateToARestrictedRecipient() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(echoAgent("B", "finance"));
        LocalMessageBus bus = new LocalMessageBus(registry);

        AgentDelegationTool agentA = new AgentDelegationTool(bus, "A", Duration.ofSeconds(2),
                DelegateStateAccess.OVERLAY, SessionStore.noop(), ScopeSet.of("ops"));

        ToolResult result = agentA.execute("{\"agent_id\":\"B\",\"task\":\"do it\"}", AgentTask.of("go"));

        assertFalse(result.success());
        assertTrue(result.error().contains("AGENT_NOT_AUTHORIZED"), result.error());
    }

    @Test
    void endToEnd_anAgentHoldingTheRequiredScope_delegatesSuccessfully() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(echoAgent("B", "finance"));
        LocalMessageBus bus = new LocalMessageBus(registry);

        AgentDelegationTool agentC = new AgentDelegationTool(bus, "C", Duration.ofSeconds(2),
                DelegateStateAccess.OVERLAY, SessionStore.noop(), ScopeSet.of("ops", "finance"));

        ToolResult result = agentC.execute("{\"agent_id\":\"B\",\"task\":\"do it\"}", AgentTask.of("go"));

        assertTrue(result.success());
        assertTrue(result.output().contains("handled"), result.output());
    }

    private static AraAgent agent(String id, String requiredScope, String visibleToScope) {
        AgentId agentId = AgentId.of(id);
        AgentConfig config = AgentConfig.defaults()
                .agentId(agentId).agentType("t")
                .requiredScopes(requiredScope == null ? List.of() : List.of(requiredScope))
                .visibleToScopes(visibleToScope == null ? List.of() : List.of(visibleToScope))
                .build();
        return new AraAgent() {
            @Override public AgentId agentId() { return agentId; }
            @Override public AgentConfig config() { return config; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.success(task.taskId(), agentId, "ok", 1, 0, 0.0, Duration.ZERO, List.of());
            }
            @Override public void terminate() {}
        };
    }
}
