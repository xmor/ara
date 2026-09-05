package io.ara.runtime.telemetry;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.RunContext;
import io.ara.core.auth.ScopeSet;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.runtime.bus.AgentDelegationTool;
import io.ara.core.telemetry.Span;
import io.ara.core.telemetry.SpanStatus;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link ToolRegistry} decorator that records an OpenTelemetry span for every
 * {@link #execute} call.
 *
 * <p>Emits a {@code tool.execute} span with:
 * <ul>
 *   <li>{@code tool.id} — the tool identifier</li>
 *   <li>{@code tool.success} — whether the {@link ToolResult} was successful</li>
 *   <li>{@code task.id}, {@code session.id} — only on the task-scoped {@link
 *       #execute(String, String, AgentTask)} overload, which is the one every built-in
 *       strategy actually calls; the legacy 2-arg overload has no task to read them
 *       from</li>
 *   <li>{@code tool.call_id} — the provider-native tool-call id (e.g. OpenAI
 *       function-calling), when the dispatching strategy attached one via {@link
 *       #TOOL_CALL_ID_ATTACHMENT_KEY}; absent for plan-driven dispatch, which has no
 *       such id</li>
 * </ul>
 *
 * <p>Automatically becomes a child of the ambient {@code agent.execute} span (see
 * {@code io.ara.runtime.agent.AgentInstance}) when both are active on the same thread,
 * since {@link AraTelemetry#spanBuilder} captures the current tracing context.
 *
 * <p>When {@link AraTelemetry#noop()} is used, this decorator adds no overhead beyond
 * a single interface dispatch.
 */
public final class TelemetryToolRegistry implements ToolRegistry {

    /**
     * {@link AgentTask#runContext()} opaque key a dispatching strategy can set (via
     * {@link AgentTask#withAttachment(String, Object)}, on a per-call derived task — see
     * {@code ReactStrategy}) to have the provider-native tool-call id show up as {@code
     * tool.call_id} on the {@code tool.execute} span. Purely a telemetry-internal wiring
     * detail — not a business attachment per ADR-037 — hence the namespaced key.
     */
    public static final String TOOL_CALL_ID_ATTACHMENT_KEY = "_ara.telemetry.tool_call_id";

    private final ToolRegistry delegate;
    private final AraTelemetry telemetry;

    public TelemetryToolRegistry(ToolRegistry delegate, AraTelemetry telemetry) {
        this.delegate  = Objects.requireNonNull(delegate,  "delegate must not be null");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
    }

    @Override
    public List<AraTool> resolveEnabled(List<String> enabledToolIds) {
        return delegate.resolveEnabled(enabledToolIds);
    }

    @Override
    public Optional<AraTool> findById(String toolId) {
        return delegate.findById(toolId);
    }

    @Override
    public List<AraTool> all() {
        return delegate.all();
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson) {
        Span span = telemetry.spanBuilder("tool.execute")
                .setAttribute("tool.id", toolId)
                .startSpan();
        return executeWithSpan(span, () -> delegate.execute(toolId, argumentJson));
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson, AgentTask task) {
        var spanBuilder = telemetry.spanBuilder("tool.execute")
                .setAttribute("tool.id", toolId)
                .setAttribute("task.id", task.taskId());
        // Task-scoped dispatch path — session id is available here (unlike the 2-arg
        // overload above, which has no task at all). Lets a tool.execute span be
        // correlated with the agent.execute span for the same task (which also carries
        // task.id) and with the logLlmIo I/O log lines for the same call.
        if (task.sessionId() != null) {
            spanBuilder.setAttribute("session.id", task.sessionId().value());
        }
        String toolCallId = task.runContext().opaque(TOOL_CALL_ID_ATTACHMENT_KEY, String.class);
        if (toolCallId != null && !toolCallId.isBlank()) {
            spanBuilder.setAttribute("tool.call_id", toolCallId);
        }
        // ADR-0077 D5: for a delegation call, whether the caller carried a scope set for
        // AgentDelegationTool to narrow across the hop. Computed from the task this
        // registry already holds — no change to AgentDelegationTool's own telemetry.
        if (AgentDelegationTool.TOOL_ID.equals(toolId)) {
            boolean attenuationActive =
                    task.runContext().opaque(RunContext.SCOPES_KEY, ScopeSet.class) != null;
            spanBuilder.setAttribute("delegate.scope_attenuation_active", attenuationActive);
        }
        Span span = spanBuilder.startSpan();
        return executeWithSpan(span, () -> delegate.execute(toolId, argumentJson, task));
    }

    @Override
    public Runnable wrapForPropagation(Runnable task) {
        return telemetry.propagate(task);
    }

    /** Runs {@code call} under {@code span}, recording success/failure and always closing it. */
    private ToolResult executeWithSpan(Span span, Supplier<ToolResult> call) {
        try (var scope = span.makeCurrent()) {
            ToolResult result = call.get();
            span.setAttribute("tool.success", result.success())
                .setStatus(result.success() ? SpanStatus.OK : SpanStatus.ERROR);
            return result;
        } catch (RuntimeException e) {
            span.recordException(e).setStatus(SpanStatus.ERROR);
            throw e;
        } finally {
            span.end();
        }
    }
}