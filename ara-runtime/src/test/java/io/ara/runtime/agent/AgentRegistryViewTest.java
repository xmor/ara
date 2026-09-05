package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AgentView;
import io.ara.core.agent.AraAgent;
import io.ara.core.auth.ScopeSet;
import io.ara.core.common.AgentId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 2 — {@link AgentRegistry#viewFor}/{@link AgentRegistry#unrestricted} and
 * the {@link FilteredAgentView} they build.
 */
class AgentRegistryViewTest {

    private static AraAgent agent(String id, String requiredScope, String visibleToScope, String description) {
        AgentId agentId = AgentId.of(id);
        AgentConfig config = AgentConfig.defaults()
                .agentId(agentId)
                .agentType("t")
                .description(description)
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

    @Test
    void unrestricted_seesEveryUnconfiguredAgent_todaysBehaviorUnchanged() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(agent("a", null, null, "agent a"));
        registry.register(agent("b", null, null, "agent b"));

        AgentView view = registry.unrestricted();

        assertEquals(2, view.all().size());
        assertTrue(view.findById(AgentId.of("a")).isPresent());
        assertTrue(view.findById(AgentId.of("b")).isPresent());
    }

    @Test
    void viewFor_findById_hidesAnAgentTheCallerCannotSee() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(agent("finance-bot", null, "finance", "handles finance"));

        AgentView opsView = registry.viewFor(ScopeSet.of("ops"));

        assertTrue(opsView.findById(AgentId.of("finance-bot")).isEmpty());
    }

    @Test
    void viewFor_findById_showsAnAgentTheCallerCanSeeAndInvoke() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(agent("finance-bot", "finance:write", "finance", "handles finance"));

        AgentView financeView = registry.viewFor(ScopeSet.of("finance", "finance:write"));

        assertTrue(financeView.findById(AgentId.of("finance-bot")).isPresent());
    }

    @Test
    void viewFor_findById_hidesAnAgentVisibleButNotAuthorized() {
        // Visible (shares "finance") but the caller lacks the write scope to invoke it.
        AgentRegistry registry = new AgentRegistry();
        registry.register(agent("finance-bot", "finance:write", "finance", "handles finance"));

        AgentView view = registry.viewFor(ScopeSet.of("finance"));

        assertTrue(view.findById(AgentId.of("finance-bot")).isEmpty());
    }

    @Test
    void all_filtersByVisibilityOnly_notByAuthorization() {
        // Not authorized to invoke it (missing "finance:write"), but still visible
        // (shares "finance") — all()/catalog() are discovery, not the invoke gate.
        AgentRegistry registry = new AgentRegistry();
        registry.register(agent("finance-bot", "finance:write", "finance", "handles finance"));

        AgentView view = registry.viewFor(ScopeSet.of("finance"));

        assertEquals(1, view.all().size());
        assertEquals("finance-bot", view.all().iterator().next().agentId().value());
    }

    @Test
    void catalog_listsOnlyVisibleAgents_withTheirDescription() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(agent("visible", null, "ops", "an ops agent"));
        registry.register(agent("hidden", null, "finance", "a finance agent"));

        String catalog = registry.viewFor(ScopeSet.of("ops")).catalog("anything");

        assertTrue(catalog.contains("visible"));
        assertTrue(catalog.contains("an ops agent"));
        assertTrue(!catalog.contains("hidden"));
    }

    @Test
    void effectiveScopes_returnsWhatTheViewWasBuiltWith() {
        AgentRegistry registry = new AgentRegistry();
        ScopeSet scopes = ScopeSet.of("ops", "finance");

        assertEquals(scopes, registry.viewFor(scopes).effectiveScopes());
        assertEquals(ScopeSet.EMPTY, registry.unrestricted().effectiveScopes());
    }

    @Test
    void viewFor_isBuiltFresh_reflectsRegistrationsMadeAfterTheCall() {
        AgentRegistry registry = new AgentRegistry();
        AgentView view = registry.unrestricted();
        assertTrue(view.all().isEmpty());

        registry.register(agent("late", null, null, "registered after the view"));

        assertEquals(1, view.all().size(), "the view reads the registry live, not a snapshot");
    }
}
