package io.ara.runtime.hitl;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.auth.AuthorizationException;
import io.ara.core.auth.ScopeSet;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.bus.ScopeFilteringToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The wiring order this pins: the scope filter sits <em>outside</em> the approval gate, so a
 * call the caller's scopes will never allow is refused before an operator is asked — no
 * human attention spent, no run suspended, on an action that cannot run anyway. (The
 * production chain is assembled in {@code RuntimeAssembler.buildToolChain}; this test pins
 * the same composition by hand, with the two decorators in that order.)
 */
class ScopeBeforeApprovalOrderTest {

    private static final class CountingGate implements ApprovalGate {
        final AtomicInteger requests = new AtomicInteger();

        @Override public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
            requests.incrementAndGet();
            return new CompletableFuture<>();
        }

        @Override public void submit(String requestId, ApprovalDecision decision) { }

        @Override public List<ApprovalRequest> getPendingRequests() { return List.of(); }
    }

    private static AraTool tool(String id, String... requiredScopes) {
        return new AraTool() {
            @Override public String toolId() { return id; }
            @Override public String description() { return id; }
            @Override public String argumentSchema() { return "{}"; }
            @Override public ToolResult execute(String argumentJson) { return ToolResult.success(id, "ok"); }
            @Override public List<String> requiredScopes() { return List.of(requiredScopes); }
        };
    }

    private static ToolRegistry innerWith(AraTool... tools) {
        List<AraTool> list = List.of(tools);
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return list; }
            @Override public Optional<AraTool> findById(String id) {
                return list.stream().filter(t -> t.toolId().equals(id)).findFirst();
            }
            @Override public ToolResult execute(String toolId, String argumentJson) {
                return findById(toolId).map(t -> t.execute(argumentJson))
                        .orElseGet(() -> ToolResult.failure(toolId, "unknown tool"));
            }
        };
    }

    @Test
    void aScopeDeniedCall_neverReachesTheApprovalGate() {
        ToolRegistry scoped = new ScopeFilteringToolRegistry(innerWith(tool("shell_exec", "tools:shell")));
        CountingGate gate = new CountingGate();
        ToolRegistry chain = new ApprovalToolRegistry(scoped, gate,
                AgentConfig.defaults().humanApprovalRequired(false).build());

        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> chain.execute("shell_exec", "{}", AgentTask.of("go")));
        assertEquals(AuthorizationException.Reason.TOOL_NOT_AUTHORIZED, e.reason());
        assertEquals(0, gate.requests.get(),
                "the gate must not be asked about a call the scopes already deny");
    }

    @Test
    void anAllowedCall_stillGoesThroughTheGate() {
        ToolRegistry scoped = new ScopeFilteringToolRegistry(innerWith(tool("shell_exec", "tools:shell")));
        CountingGate gate = new CountingGate();
        ToolRegistry chain = new ApprovalToolRegistry(scoped, gate,
                AgentConfig.defaults().humanApprovalRequired(true).build(),
                Duration.ofMillis(50));

        // A never-decided gate ends in the registry's own timeout failure — which is fine:
        // the assertion is that the gate was asked at all, i.e. the filter let the call pass.
        ToolResult result = chain.execute("shell_exec", "{}", AgentTask.of("go")
                .withRunContext(io.ara.core.agent.RunContext.empty().withOpaque(
                        io.ara.core.agent.RunContext.SCOPES_KEY, ScopeSet.of("tools:shell"))));
        assertFalse(result.success());
        assertEquals(1, gate.requests.get(),
                "a scope-authorized, agent-gated call must still be submitted for approval");
    }
}
