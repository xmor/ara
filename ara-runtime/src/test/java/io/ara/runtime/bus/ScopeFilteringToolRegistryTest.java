package io.ara.runtime.bus;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AgentView;
import io.ara.core.auth.AuthorizationException;
import io.ara.core.auth.ScopeSet;
import io.ara.core.common.AgentId;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 3 §3.2 — {@link ScopeFilteringToolRegistry}. See its own Javadoc for why
 * this is a new decorator rather than an update to {@code DelegatingToolRegistry}
 * (an unrelated, pre-existing class the ADR-033 implementation plan mis-assumed).
 */
class ScopeFilteringToolRegistryTest {

    private static AraTool tool(String id, String... requiredScopes) {
        return new AraTool() {
            @Override public String toolId() { return id; }
            @Override public String description() { return ""; }
            @Override public String argumentSchema() { return "{}"; }
            @Override public ToolResult execute(String argumentJson) { return ToolResult.success(id, "ok"); }
            @Override public List<String> requiredScopes() { return List.of(requiredScopes); }
        };
    }

    private static ToolRegistry innerWith(AraTool... tools) {
        List<AraTool> list = List.of(tools);
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> enabledToolIds) { return list; }
            @Override public Optional<AraTool> findById(String toolId) {
                return list.stream().filter(t -> t.toolId().equals(toolId)).findFirst();
            }
            @Override public List<AraTool> all() { return list; }
            @Override public ToolResult execute(String toolId, String argumentJson) {
                return findById(toolId).map(t -> t.execute(argumentJson))
                        .orElseGet(() -> ToolResult.failure(toolId, "unknown tool"));
            }
        };
    }

    private static AgentView viewWith(ScopeSet effective) {
        return new AgentView() {
            @Override public Optional<io.ara.core.agent.AraAgent> findById(AgentId id) { return Optional.empty(); }
            @Override public Collection<io.ara.core.agent.AraAgent> all() { return List.of(); }
            @Override public String catalog(String contextInput) { return ""; }
            @Override public ScopeSet effectiveScopes() { return effective; }
        };
    }

    @Test
    void resolveEnabled_dropsATheCallerCannotSatisfy() {
        ToolRegistry inner = innerWith(tool("open"), tool("shell_exec", "tools:shell"));
        ToolRegistry scoped = new ScopeFilteringToolRegistry(inner, viewWith(ScopeSet.EMPTY));

        List<AraTool> resolved = scoped.resolveEnabled(null);

        assertEquals(List.of("open"), resolved.stream().map(AraTool::toolId).toList());
    }

    @Test
    void resolveEnabled_keepsAToolTheCallerSatisfies() {
        ToolRegistry inner = innerWith(tool("shell_exec", "tools:shell"));
        ToolRegistry scoped = new ScopeFilteringToolRegistry(inner, viewWith(ScopeSet.of("tools:shell")));

        assertEquals(1, scoped.resolveEnabled(null).size());
    }

    @Test
    void execute_throwsForAToolTheCallerDoesNotSatisfy_evenBypassingResolveEnabled() {
        ToolRegistry inner = innerWith(tool("shell_exec", "tools:shell"));
        ToolRegistry scoped = new ScopeFilteringToolRegistry(inner, viewWith(ScopeSet.EMPTY));

        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> scoped.execute("shell_exec", "{}"));
        assertEquals(AuthorizationException.Reason.TOOL_NOT_AUTHORIZED, e.reason());
    }

    @Test
    void execute_succeedsForAnAuthorizedTool() {
        ToolRegistry inner = innerWith(tool("shell_exec", "tools:shell"));
        ToolRegistry scoped = new ScopeFilteringToolRegistry(inner, viewWith(ScopeSet.of("tools:shell")));

        ToolResult result = assertDoesNotThrow(() -> scoped.execute("shell_exec", "{}"));
        assertTrue(result.success());
    }

    @Test
    void findByIdAndAll_areUnfiltered_definitionLookupIsNotTheInvokeGate() {
        ToolRegistry inner = innerWith(tool("shell_exec", "tools:shell"));
        ToolRegistry scoped = new ScopeFilteringToolRegistry(inner, viewWith(ScopeSet.EMPTY));

        assertTrue(scoped.findById("shell_exec").isPresent());
        assertEquals(1, scoped.all().size());
    }

    @Test
    void execute_withTaskOverload_alsoEnforcesTheCheck() {
        ToolRegistry inner = innerWith(tool("shell_exec", "tools:shell"));
        ToolRegistry scoped = new ScopeFilteringToolRegistry(inner, viewWith(ScopeSet.EMPTY));

        assertThrows(AuthorizationException.class,
                () -> scoped.execute("shell_exec", "{}", AgentTask.of("go")));
    }
}
