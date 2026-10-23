package io.ara.runtime.interceptor;

import io.ara.core.agent.AgentExecutionContext;
import io.ara.core.agent.AgentTask;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.core.tool.ToolSpec;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.runtime.bus.AgentDelegationTool;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link ToolRegistry} decorator that fires {@link io.ara.core.agent.AgentInterceptor#beforeToolCall}
 * / {@link io.ara.core.agent.AgentInterceptor#afterToolCall} around every {@link #execute} call,
 * giving interceptors visibility into individual tool dispatches — not just the
 * task-level {@code "Executing"} phase that {@code AgentInstance} brackets.
 *
 * <p>{@code contextSupplier} is invoked fresh on every call (rather than captured once) so
 * each notification carries the working memory and state as they stand at that moment.
 *
 * <p>When {@code toolId} is {@link AgentDelegationTool#TOOL_ID}, this decorator also fires
 * the delegation-specific {@link io.ara.core.agent.AgentInterceptor#onDelegate} / {@link
 * io.ara.core.agent.AgentInterceptor#onDelegateReturn}, nested inside the generic
 * before/afterToolCall pair, using {@link AgentDelegationTool#parseDelegation} so
 * interceptors get the recipient agent id and sub-task without decoding the argument
 * JSON themselves. A payload that fails to parse is silently skipped here: {@code
 * delegate.execute(...)} will itself return a failed {@link ToolResult} for it, already
 * visible via {@code afterToolCall} — no delegation was actually attempted.
 *
 * <p>Mirrors {@code TelemetryToolRegistry} (the OTel equivalent). Like that class, this
 * decorator is called from concurrent virtual threads when {@code ReactStrategy} dispatches
 * several tool calls in parallel — {@link io.ara.runtime.interceptor.AgentInterceptorChain}
 * makes no thread-safety guarantee for the interceptors it holds beyond what each interceptor
 * provides itself, the same contract {@code TelemetryToolRegistry} already relies on.
 */
public final class InterceptingToolRegistry implements ToolRegistry {

    private final ToolRegistry delegate;
    private final AgentInterceptorChain interceptorChain;
    private final Supplier<AgentExecutionContext> contextSupplier;

    public InterceptingToolRegistry(ToolRegistry delegate, AgentInterceptorChain interceptorChain,
                                     Supplier<AgentExecutionContext> contextSupplier) {
        this.delegate         = Objects.requireNonNull(delegate,         "delegate must not be null");
        this.interceptorChain = Objects.requireNonNull(interceptorChain, "interceptorChain must not be null");
        this.contextSupplier  = Objects.requireNonNull(contextSupplier,  "contextSupplier must not be null");
    }

    @Override
    public List<AraTool> resolveEnabled(List<String> enabledToolIds) {
        return delegate.resolveEnabled(enabledToolIds);
    }

    /**
     * Forwarded unchanged: interceptors observe tool <em>calls</em>, and have no say in
     * which tools a caller may see. Dropping the task here would make the interception
     * chain silently widen the catalog.
     */
    @Override
    public List<AraTool> resolveEnabled(List<String> enabledToolIds, AgentTask task) {
        return delegate.resolveEnabled(enabledToolIds, task);
    }

    @Override
    public Optional<AraTool> findById(String toolId) {
        return delegate.findById(toolId);
    }

    @Override
    public List<AraTool> all() {
        return delegate.all();
    }

    /**
     * Forwarded, like {@link #findById}: the tool's classification lives on the registry this
     * one wraps, and {@code ApprovalToolRegistry} reads it through the whole chain to decide
     * whether a call is gated (ADR-0067 D6). Not forwarding it silently turns the per-tool
     * gate off for every agent built on top of this decorator.
     */
    @Override
    public Optional<ToolSpec> specFor(String toolId) {
        return delegate.specFor(toolId);
    }

    /** Forwarded for the same reason as {@link #specFor}; see {@link ToolRegistry#approvalRequestFor}. */
    @Override
    public Optional<ApprovalRequest> approvalRequestFor(String toolId, String argumentJson, AgentTask task) {
        return delegate.approvalRequestFor(toolId, argumentJson, task);
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson) {
        AgentExecutionContext execCtx = contextSupplier.get();
        Optional<AgentDelegationTool.DelegationRequest> delegation = delegationIfApplicable(toolId, argumentJson);
        interceptorChain.beforeToolCall(execCtx, toolId, argumentJson);
        delegation.ifPresent(req -> interceptorChain.onDelegate(execCtx, req.recipientAgentId(), req.task()));
        ToolResult result = delegate.execute(toolId, argumentJson);
        delegation.ifPresent(req -> interceptorChain.onDelegateReturn(execCtx, req.recipientAgentId(), result));
        interceptorChain.afterToolCall(execCtx, toolId, argumentJson, result);
        return result;
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson, AgentTask task) {
        AgentExecutionContext execCtx = contextSupplier.get();
        Optional<AgentDelegationTool.DelegationRequest> delegation = delegationIfApplicable(toolId, argumentJson);
        interceptorChain.beforeToolCall(execCtx, toolId, argumentJson);
        delegation.ifPresent(req -> interceptorChain.onDelegate(execCtx, req.recipientAgentId(), req.task()));
        ToolResult result = delegate.execute(toolId, argumentJson, task);
        delegation.ifPresent(req -> interceptorChain.onDelegateReturn(execCtx, req.recipientAgentId(), result));
        interceptorChain.afterToolCall(execCtx, toolId, argumentJson, result);
        return result;
    }

    @Override
    public Runnable wrapForPropagation(Runnable task) {
        return delegate.wrapForPropagation(task);
    }

    private static Optional<AgentDelegationTool.DelegationRequest> delegationIfApplicable(String toolId, String argumentJson) {
        if (!AgentDelegationTool.TOOL_ID.equals(toolId)) {
            return Optional.empty();
        }
        try {
            return Optional.of(AgentDelegationTool.parseDelegation(argumentJson));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
