package io.ara.runtime.bus;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AgentView;
import io.ara.core.agent.RunContext;
import io.ara.core.auth.AuthorizationException;
import io.ara.core.auth.ExecutionContext;
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

    // ── Task-scoped form ───────────────────────────────────────────────────────

    private static List<String> ids(List<AraTool> tools) {
        return tools.stream().map(AraTool::toolId).toList();
    }

    private static AgentTask taskWithScopes(String... scopes) {
        return AgentTask.of("go").withRunContext(
                RunContext.empty().withOpaque(RunContext.SCOPES_KEY, ScopeSet.of(scopes)));
    }

    private static AgentTask taskWithExecutionContext(ExecutionContext ctx) {
        return AgentTask.of("go").withExecutionContext(ctx);
    }

    @Test
    void taskScoped_readsTheExecutionContextWhenPresent_evenWhenABareScopeSetAlsoTravels() {
        ToolRegistry inner = innerWith(tool("open"), tool("shell_exec", "tools:shell"));
        ToolRegistry scoped = new ScopeFilteringToolRegistry(inner);

        // actor ∩ subject = {misc} — the bare scope set would grant shell; the context wins.
        // withRunContext replaces the whole RunContext, so the context must be attached last.
        AgentTask task = AgentTask.of("go")
                .withRunContext(RunContext.empty().withOpaque(RunContext.SCOPES_KEY, ScopeSet.of("tools:shell")))
                .withExecutionContext(
                        ExecutionContext.ofUserDelegation("agent", ScopeSet.of("tools:shell", "misc"),
                                "user-1", ScopeSet.of("misc")));

        assertEquals(List.of("open"), ids(scoped.resolveEnabled(null, task)));
    }

    @Test
    void taskScoped_onBehalfOf_effectiveScopesAreTheIntersection() {
        ToolRegistry inner = innerWith(tool("a", "s:a"), tool("b", "s:b"));
        ToolRegistry scoped = new ScopeFilteringToolRegistry(inner);

        AgentTask task = taskWithExecutionContext(
                ExecutionContext.ofUserDelegation("agent", ScopeSet.of("s:a", "s:b"), "user-1", ScopeSet.of("s:a")));

        assertEquals(List.of("a"), ids(scoped.resolveEnabled(null, task)));
    }

    @Test
    void taskScoped_readsTheBareScopeSetWhenThereIsNoExecutionContext() {
        ToolRegistry inner = innerWith(tool("open"), tool("shell_exec", "tools:shell"));
        ToolRegistry scoped = new ScopeFilteringToolRegistry(inner);

        assertEquals(List.of("open", "shell_exec"), ids(scoped.resolveEnabled(null, taskWithScopes("tools:shell"))));
        assertEquals(List.of("open"), ids(scoped.resolveEnabled(null, taskWithScopes("misc"))));
    }

    @Test
    void taskScoped_aTaskWithNoChannelSeesOnlyUngatedTools_failClosedButNothingElse() {
        ToolRegistry inner = innerWith(tool("open"), tool("shell_exec", "tools:shell"));
        ToolRegistry scoped = new ScopeFilteringToolRegistry(inner);

        assertEquals(List.of("open"), ids(scoped.resolveEnabled(null, AgentTask.of("go"))));
    }

    @Test
    void taskScoped_theCallerIndependentFormIsUnfiltered_evenForTheSameInstance() {
        ToolRegistry inner = innerWith(tool("open"), tool("shell_exec", "tools:shell"));
        ToolRegistry scoped = new ScopeFilteringToolRegistry(inner);

        assertEquals(2, scoped.resolveEnabled(null).size());
    }

    @Test
    void taskScoped_executeWithoutATask_refusesAGatedTool_andForwardsAnUngatedOne() {
        ToolRegistry inner = innerWith(tool("open"), tool("shell_exec", "tools:shell"));
        ToolRegistry scoped = new ScopeFilteringToolRegistry(inner);

        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> scoped.execute("shell_exec", "{}"));
        assertEquals(AuthorizationException.Reason.TOOL_NOT_AUTHORIZED, e.reason());
        assertDoesNotThrow(() -> assertTrue(scoped.execute("open", "{}").success()));
    }

    @Test
    void taskScoped_executeWithTask_checksAgainstTheSameResolutionAsTheCatalog() {
        ToolRegistry inner = innerWith(tool("shell_exec", "tools:shell"));
        ToolRegistry scoped = new ScopeFilteringToolRegistry(inner);

        assertDoesNotThrow(() -> assertTrue(scoped.execute("shell_exec", "{}", taskWithScopes("tools:shell")).success()));
        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> scoped.execute("shell_exec", "{}", AgentTask.of("go")));
        assertEquals(AuthorizationException.Reason.TOOL_NOT_AUTHORIZED, e.reason());
    }

    @Test
    void taskScoped_anEmptyChannelIsARealDenial_notAMissingOne() {
        ToolRegistry inner = innerWith(tool("open"), tool("shell_exec", "tools:shell"));
        ToolRegistry scoped = new ScopeFilteringToolRegistry(inner);

        // An explicitly empty execution context carries a channel: deny, don't warn-as-missing.
        AgentTask task = taskWithExecutionContext(ExecutionContext.ofAgent("agent", ScopeSet.EMPTY));

        assertEquals(List.of("open"), ids(scoped.resolveEnabled(null, task)));
    }
}
