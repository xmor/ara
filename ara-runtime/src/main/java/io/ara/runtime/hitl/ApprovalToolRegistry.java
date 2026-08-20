package io.ara.runtime.hitl;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.hitl.ApprovalTimeoutException;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ToolRegistry} decorator that routes every tool dispatch through an
 * {@link ApprovalGate} before calling the delegate.
 *
 * <p>When an agent has {@link AgentConfig#humanApprovalRequired()} set to {@code true}
 * and a gate is configured on the runtime, this decorator is inserted into the registry
 * chain. For every {@link #execute} call it:
 * <ol>
 *   <li>Creates an {@link ApprovalRequest} describing the tool call.</li>
 *   <li>Calls {@link ApprovalGate#requestApproval(ApprovalRequest)} and blocks on the
 *       returned future (cheap on a virtual thread).</li>
 *   <li>On {@link ApprovalDecision.Approved}: dispatches to the delegate with the
 *       original arguments.</li>
 *   <li>On {@link ApprovalDecision.Modified}: dispatches to the delegate with the
 *       modified payload (serialised as the new argument JSON).</li>
 *   <li>On {@link ApprovalDecision.Rejected}: returns a failed {@link ToolResult}
 *       without calling the delegate.</li>
 *   <li>On {@link ApprovalTimeoutException}: returns a failed {@link ToolResult}.</li>
 * </ol>
 *
 * <p>The approval timeout defaults to 30 minutes — configurable via the overloaded
 * constructor. This is deliberately generous: HITL is inherently asynchronous and
 * the virtual thread park is free.
 */
public final class ApprovalToolRegistry implements ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ApprovalToolRegistry.class);
    private static final Duration DEFAULT_APPROVAL_TIMEOUT = Duration.ofMinutes(30);

    private final ToolRegistry delegate;
    private final ApprovalGate gate;
    private final String agentId;
    private final Duration approvalTimeout;

    public ApprovalToolRegistry(ToolRegistry delegate, ApprovalGate gate, AgentConfig config) {
        this(delegate, gate, config, DEFAULT_APPROVAL_TIMEOUT);
    }

    public ApprovalToolRegistry(ToolRegistry delegate, ApprovalGate gate, AgentConfig config,
                                Duration approvalTimeout) {
        this.delegate        = Objects.requireNonNull(delegate, "delegate must not be null");
        this.gate            = Objects.requireNonNull(gate, "gate must not be null");
        this.agentId         = Objects.requireNonNull(config, "config must not be null").agentId().value();
        this.approvalTimeout = Objects.requireNonNull(approvalTimeout, "approvalTimeout must not be null");
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
        return executeWithApproval(toolId, argumentJson,
                (id, args) -> delegate.execute(id, args));
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson, AgentTask task) {
        return executeWithApproval(toolId, argumentJson,
                (id, args) -> delegate.execute(id, args, task));
    }

    @Override
    public Runnable wrapForPropagation(Runnable task) {
        return delegate.wrapForPropagation(task);
    }

    private ToolResult executeWithApproval(String toolId, String argumentJson,
                                           ToolExecutor executor) {
        ApprovalRequest request = ApprovalRequest.of(agentId, toolId, argumentJson, approvalTimeout);

        log.debug("Requesting approval for tool [{}] on agent [{}], requestId={}",
                toolId, agentId, request.requestId());

        ApprovalDecision decision;
        try {
            decision = gate.requestApproval(request).join();
        } catch (Exception e) {
            Throwable cause = unwrap(e);
            if (cause instanceof ApprovalTimeoutException) {
                log.warn("Approval timed out for tool [{}] on agent [{}], requestId={}",
                        toolId, agentId, request.requestId());
                return ToolResult.failure(toolId,
                        "Human approval timed out for action '" + toolId + "' (requestId=" + request.requestId() + ")");
            }
            log.error("Approval gate error for tool [{}] on agent [{}]: {}",
                    toolId, agentId, cause.getMessage(), cause);
            return ToolResult.failure(toolId,
                    "Approval gate error: " + cause.getMessage());
        }

        return switch (decision) {
            case ApprovalDecision.Approved ignored -> {
                log.debug("Approved: tool [{}] on agent [{}], requestId={}",
                        toolId, agentId, request.requestId());
                yield executor.execute(toolId, argumentJson);
            }
            case ApprovalDecision.Rejected r -> {
                log.info("Rejected: tool [{}] on agent [{}], reason='{}', requestId={}",
                        toolId, agentId, r.reason(), request.requestId());
                yield ToolResult.failure(toolId,
                        "Human rejected action '" + toolId + "': " + r.reason());
            }
            case ApprovalDecision.Modified m -> {
                String newArgs = m.newPayload() instanceof String s ? s : argumentJson;
                log.debug("Modified: tool [{}] on agent [{}], requestId={}",
                        toolId, agentId, request.requestId());
                yield executor.execute(toolId, newArgs);
            }
        };
    }

    private static Throwable unwrap(Exception e) {
        Throwable cause = e.getCause();
        return cause != null ? cause : e;
    }

    @FunctionalInterface
    private interface ToolExecutor {
        ToolResult execute(String toolId, String argumentJson);
    }
}
