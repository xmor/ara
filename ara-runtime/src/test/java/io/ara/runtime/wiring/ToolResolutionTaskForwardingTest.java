package io.ara.runtime.wiring;

import io.ara.core.agent.AgentTask;
import io.ara.core.auth.ScopeSet;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.agent.AgentRegistry;
import io.ara.runtime.bus.DelegatingToolRegistry;
import io.ara.runtime.bus.LocalMessageBus;
import io.ara.runtime.bus.ScopeFilteringToolRegistry;
import io.ara.runtime.interceptor.AgentInterceptorChain;
import io.ara.runtime.interceptor.InterceptingToolRegistry;
import io.ara.runtime.telemetry.TelemetryToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every decorator between a strategy and the registry that actually resolves tools must
 * forward the caller-aware {@link ToolRegistry#resolveEnabled(List, AgentTask)}: one that
 * silently drops the task hands every agent built on it the caller-independent catalog,
 * undoing the filter underneath — the same contract failure {@code ToolSpecForwardingTest}
 * pins for {@code specFor}/{@code approvalRequestFor}.
 *
 * <p>{@code AggregatingToolRegistry} is deliberately absent: it is a discovery view, not a
 * decorator — its {@code resolveEnabled} degrades to empty by design and has no delegate to
 * forward to.
 */
class ToolResolutionTaskForwardingTest {

    /** Records which task each resolution arrived with; answers with one tool either way. */
    private static final class RecordingRegistry implements ToolRegistry {
        final List<AgentTask> tasks = new ArrayList<>();

        @Override public List<AraTool> resolveEnabled(List<String> ids) {
            tasks.add(null);
            return List.of(tool("pay"));
        }

        @Override public List<AraTool> resolveEnabled(List<String> ids, AgentTask task) {
            tasks.add(task);
            return List.of(tool("pay"));
        }

        @Override public Optional<AraTool> findById(String id) {
            return "pay".equals(id) ? Optional.of(tool("pay")) : Optional.empty();
        }

        @Override public ToolResult execute(String toolId, String argumentJson) {
            return ToolResult.success(toolId, "ok");
        }
    }

    private static AraTool tool(String id) {
        return new AraTool() {
            @Override public String toolId() { return id; }
            @Override public String description() { return id; }
            @Override public String argumentSchema() { return "{}"; }
            @Override public ToolResult execute(String argumentJson) { return ToolResult.success(id, "ok"); }
        };
    }

    private static AgentTask task() {
        return AgentTask.of("go").withRunContext(
                io.ara.core.agent.RunContext.empty().withOpaque(
                        io.ara.core.agent.RunContext.SCOPES_KEY, ScopeSet.of("misc")));
    }

    private static void assertTaskReachesTheRegistry(ToolRegistry decorator, AgentTask task) {
        RecordingRegistry recorder = unwrap(decorator);
        List<AraTool> resolved = decorator.resolveEnabled(List.of("pay"), task);

        assertEquals(1, resolved.size());
        assertEquals(List.of(task), recorder.tasks,
                decorator.getClass().getSimpleName() + " must forward the task to its delegate");
    }

    private static RecordingRegistry unwrap(ToolRegistry decorator) {
        try {
            java.lang.reflect.Field field = null;
            for (Class<?> type = decorator.getClass(); type != null && field == null; type = type.getSuperclass()) {
                for (java.lang.reflect.Field f : type.getDeclaredFields()) {
                    if (f.getType() == ToolRegistry.class && java.lang.reflect.Modifier.isFinal(f.getModifiers())
                            && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        field = f;
                        break;
                    }
                }
            }
            if (field == null) {
                throw new IllegalStateException("no delegate field on " + decorator.getClass());
            }
            field.setAccessible(true);
            return (RecordingRegistry) field.get(decorator);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void approval_forwardsTheTask() {
        AgentTask task = task();
        assertTaskReachesTheRegistry(
                new io.ara.runtime.hitl.ApprovalToolRegistry(
                        new RecordingRegistry(), new io.ara.core.hitl.ApprovalGate() {
                            @Override public java.util.concurrent.CompletableFuture<io.ara.core.hitl.ApprovalDecision> requestApproval(
                                    io.ara.core.hitl.ApprovalRequest request) {
                                throw new UnsupportedOperationException("this test must never reach the gate");
                            }
                            @Override public void submit(String requestId, io.ara.core.hitl.ApprovalDecision decision) {
                                throw new UnsupportedOperationException();
                            }
                            @Override public List<io.ara.core.hitl.ApprovalRequest> getPendingRequests() {
                                return List.of();
                            }
                        },
                        io.ara.core.agent.AgentConfig.defaults().build()),
                task);
    }

    @Test
    void telemetry_forwardsTheTask() {
        AgentTask task = task();
        assertTaskReachesTheRegistry(new TelemetryToolRegistry(new RecordingRegistry(), AraTelemetry.noop()), task);
    }

    @Test
    void intercepting_forwardsTheTask() {
        AgentTask task = task();
        assertTaskReachesTheRegistry(
                new InterceptingToolRegistry(new RecordingRegistry(), new AgentInterceptorChain(List.of()), () -> null),
                task);
    }

    @Test
    void scopeFiltering_taskScoped_forwardsTheTask() {
        AgentTask task = task();
        assertTaskReachesTheRegistry(new ScopeFilteringToolRegistry(new RecordingRegistry()), task);
    }

    @Test
    void scopeFiltering_fixedView_forwardsTheTask_andFilters() {
        AgentTask task = task();
        RecordingRegistry recorder = new RecordingRegistry();
        ToolRegistry decorator = new ScopeFilteringToolRegistry(recorder, new io.ara.core.agent.AgentView() {
            @Override public Optional<io.ara.core.agent.AraAgent> findById(io.ara.core.common.AgentId id) { return Optional.empty(); }
            @Override public Collection<io.ara.core.agent.AraAgent> all() { return List.of(); }
            @Override public String catalog(String contextInput) { return ""; }
            @Override public ScopeSet effectiveScopes() { return ScopeSet.EMPTY; }
        });

        assertEquals(1, decorator.resolveEnabled(List.of("pay"), task).size(), "tool declares no scopes: kept");
        assertEquals(List.of(task), recorder.tasks);
    }

    @Test
    void delegating_forwardsTheTask() {
        AgentTask task = task();
        assertTaskReachesTheRegistry(
                new DelegatingToolRegistry(new RecordingRegistry(), new LocalMessageBus(new AgentRegistry()), "self"),
                task);
    }

    @Test
    void composite_forwardsTheTaskToTheBase() {
        AgentTask task = task();
        assertTaskReachesTheRegistry(new CompositeToolRegistry(List.of(), new RecordingRegistry()), task);
    }

    @Test
    void aggregating_degradesToEmpty_alsoWhenATaskArrives() {
        RecordingRegistry perAgent = new RecordingRegistry();
        ToolRegistry aggregating = new AggregatingToolRegistry(java.util.Map.of("agent-a", perAgent));

        assertTrue(aggregating.resolveEnabled(List.of("pay"), task()).isEmpty(),
                "a discovery view resolves nothing for a specific agent's selection");
        assertTrue(perAgent.tasks.isEmpty(),
                "and must not reach into a per-agent registry that was never wired for dispatch");
    }
}
