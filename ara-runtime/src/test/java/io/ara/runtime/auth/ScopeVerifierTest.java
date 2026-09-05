package io.ara.runtime.auth;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.auth.AuthorizationException;
import io.ara.core.auth.ScopeSet;
import io.ara.core.common.AgentId;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ADR-033 Fase 2 — {@link ScopeVerifier} is the first code path that actually throws
 * {@link AuthorizationException}; Fase 1 only defined the types.
 */
class ScopeVerifierTest {

    private static AraAgent agentWith(String requiredScope, String visibleToScope) {
        AgentId id = AgentId.of("target");
        AgentConfig config = AgentConfig.defaults()
                .agentId(id)
                .agentType("t")
                .requiredScopes(requiredScope == null ? List.of() : List.of(requiredScope))
                .visibleToScopes(visibleToScope == null ? List.of() : List.of(visibleToScope))
                .build();
        return new AraAgent() {
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return config; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.success(task.taskId(), id, "ok", 1, 0, 0.0, Duration.ZERO, List.of());
            }
            @Override public void terminate() {}
        };
    }

    @Test
    void unconfiguredAgent_isVisibleAndAuthorized_regardlessOfCallerScopes() {
        AraAgent agent = agentWith(null, null);
        assertDoesNotThrow(() -> ScopeVerifier.checkVisible(agent, ScopeSet.EMPTY));
        assertDoesNotThrow(() -> ScopeVerifier.checkAuthorized(agent, ScopeSet.EMPTY));
    }

    @Test
    void checkVisible_deniesACallerSharingNoScope() {
        AraAgent agent = agentWith(null, "finance");
        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> ScopeVerifier.checkVisible(agent, ScopeSet.of("ops")));
        assertEquals(AuthorizationException.Reason.AGENT_NOT_VISIBLE, e.reason());
        assertEquals("target", e.targetId());
    }

    @Test
    void checkVisible_permitsACallerSharingTheScope() {
        AraAgent agent = agentWith(null, "finance");
        assertDoesNotThrow(() -> ScopeVerifier.checkVisible(agent, ScopeSet.of("finance", "ops")));
    }

    @Test
    void checkAuthorized_deniesACallerMissingARequiredScope() {
        AraAgent agent = agentWith("finance:write", null);
        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> ScopeVerifier.checkAuthorized(agent, ScopeSet.of("finance:read")));
        assertEquals(AuthorizationException.Reason.AGENT_NOT_AUTHORIZED, e.reason());
        assertEquals(ScopeSet.of("finance:write"), e.required());
        assertEquals(ScopeSet.of("finance:read"), e.effective());
    }

    @Test
    void checkAuthorized_permitsACallerHoldingEveryRequiredScope() {
        AraAgent agent = agentWith("finance:write", null);
        assertDoesNotThrow(() -> ScopeVerifier.checkAuthorized(agent, ScopeSet.of("finance:write", "ops")));
    }

    @Test
    void checkTool_deniesAndPermitsSymmetricallyToCheckAuthorized() {
        AraTool tool = new AraTool() {
            @Override public String toolId() { return "shell_exec"; }
            @Override public String description() { return ""; }
            @Override public String argumentSchema() { return "{}"; }
            @Override public ToolResult execute(String argumentJson) { return ToolResult.success("shell_exec", ""); }
            @Override public List<String> requiredScopes() { return List.of("tools:shell"); }
        };

        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> ScopeVerifier.checkTool(tool, ScopeSet.EMPTY));
        assertEquals(AuthorizationException.Reason.TOOL_NOT_AUTHORIZED, e.reason());
        assertEquals("shell_exec", e.targetId());

        assertDoesNotThrow(() -> ScopeVerifier.checkTool(tool, ScopeSet.of("tools:shell")));
    }
}
