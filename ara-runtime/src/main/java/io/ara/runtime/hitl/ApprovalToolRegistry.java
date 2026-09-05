package io.ara.runtime.hitl;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.RunState;
import io.ara.core.autonomy.AutonomyPolicy;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.hitl.ApprovalTimeoutException;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.core.tool.ToolSpec;
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
 * <p>Inserted into the registry chain whenever an {@link ApprovalGate} is configured on
 * the runtime. A call is gated when <em>either</em> the agent's
 * {@link AgentConfig#humanApprovalRequired()} is {@code true} <em>or</em> the tool's
 * {@link ToolSpec#approvalRequired()} is (ADR-0067 D6) — the agent flag can only add a
 * gate, never remove one the tool's own classification already requires. When neither
 * asks for a gate the call is dispatched straight to the delegate. When gated, it:
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
 *
 * <p><b>Autonomy track record (ADR-0073 D2, optional third disjunct).</b> When an
 * {@link AutonomyPolicy} is supplied, a call also gates when the policy says the action
 * must escalate for its {@code task_class} — either because the current autonomy level's
 * risk floor rejects the action's reversibility, or because the action's confidence is
 * below that level's threshold (both checks are part of ADR-0073 D2's escalation rule,
 * additive to the two gates above and never able to remove them). The {@code task_class}
 * and confidence are read from the call's {@link RunState} under the configured keys
 * (defaults {@value #DEFAULT_TASK_CLASS_KEY} / {@value #DEFAULT_CONFIDENCE_KEY}, matching
 * an {@code IntentRouter} write). This can only be evaluated on the
 * {@link #execute(String, String, AgentTask)} path: a call with no task carries no
 * {@link RunState}, and a call whose {@code task_class} is absent is treated as
 * "not measurable here" — a deliberate, stated gap in ADR-0073, not a bug — so neither
 * check adds a gate; the two floors above still apply.
 */
public final class ApprovalToolRegistry implements ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ApprovalToolRegistry.class);

    /** Time an operator has to decide before an approval request expires, when not overridden. */
    public static final Duration DEFAULT_APPROVAL_TIMEOUT = Duration.ofMinutes(30);

    /** Default {@link RunState} key the {@code task_class} is read from (an {@code IntentRouter} label). */
    public static final String DEFAULT_TASK_CLASS_KEY = "intent";

    /** Default {@link RunState} key the action confidence is read from. */
    public static final String DEFAULT_CONFIDENCE_KEY = "confidence";

    private final ToolRegistry delegate;
    private final ApprovalGate gate;
    private final String agentId;
    private final boolean agentForcesApproval;
    private final Duration approvalTimeout;
    private final AutonomyPolicy autonomyPolicy;   // nullable — ADR-0073 D2 third disjunct, off when unset
    private final String taskClassStateKey;
    private final String confidenceStateKey;

    public ApprovalToolRegistry(ToolRegistry delegate, ApprovalGate gate, AgentConfig config) {
        this(delegate, gate, config, DEFAULT_APPROVAL_TIMEOUT);
    }

    public ApprovalToolRegistry(ToolRegistry delegate, ApprovalGate gate, AgentConfig config,
                                Duration approvalTimeout) {
        this(delegate, gate, config, approvalTimeout, null,
                DEFAULT_TASK_CLASS_KEY, DEFAULT_CONFIDENCE_KEY);
    }

    /**
     * With an autonomy track-record policy (ADR-0073 D2), reading {@code task_class} and
     * confidence from the default {@link RunState} keys. Pass
     * {@link #DEFAULT_APPROVAL_TIMEOUT} for {@code approvalTimeout} to keep the default.
     */
    public ApprovalToolRegistry(ToolRegistry delegate, ApprovalGate gate, AgentConfig config,
                                Duration approvalTimeout, AutonomyPolicy autonomyPolicy) {
        this(delegate, gate, config, approvalTimeout, autonomyPolicy,
                DEFAULT_TASK_CLASS_KEY, DEFAULT_CONFIDENCE_KEY);
    }

    public ApprovalToolRegistry(ToolRegistry delegate, ApprovalGate gate, AgentConfig config,
                                Duration approvalTimeout, AutonomyPolicy autonomyPolicy,
                                String taskClassStateKey, String confidenceStateKey) {
        this.delegate            = Objects.requireNonNull(delegate, "delegate must not be null");
        this.gate                = Objects.requireNonNull(gate, "gate must not be null");
        Objects.requireNonNull(config, "config must not be null");
        this.agentId             = config.agentId().value();
        this.agentForcesApproval = config.humanApprovalRequired();
        this.approvalTimeout     = Objects.requireNonNull(approvalTimeout, "approvalTimeout must not be null");
        this.autonomyPolicy      = autonomyPolicy;   // nullable by design
        this.taskClassStateKey   = Objects.requireNonNull(taskClassStateKey, "taskClassStateKey must not be null");
        this.confidenceStateKey  = Objects.requireNonNull(confidenceStateKey, "confidenceStateKey must not be null");
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
    public Optional<ToolSpec> specFor(String toolId) {
        return delegate.specFor(toolId);
    }

    @Override
    public List<AraTool> all() {
        return delegate.all();
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson) {
        return executeWithApproval(toolId, argumentJson, null,
                (id, args) -> delegate.execute(id, args));
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson, AgentTask task) {
        return executeWithApproval(toolId, argumentJson, task,
                (id, args) -> delegate.execute(id, args, task));
    }

    @Override
    public Runnable wrapForPropagation(Runnable task) {
        return delegate.wrapForPropagation(task);
    }

    private ToolResult executeWithApproval(String toolId, String argumentJson, AgentTask task,
                                           ToolExecutor executor) {
        // ADR-0067 D6: gate when the agent forces it OR the tool's own classification
        // requires it — never a condition the agent flag alone can switch off.
        // ADR-0073 D2: plus a third disjunct — the autonomy track record for this
        // task_class asks to escalate (level floor / confidence threshold). Additive.
        boolean needsGate = agentForcesApproval
                || delegate.specFor(toolId).map(ToolSpec::approvalRequired).orElse(false)
                || autonomyEscalates(toolId, task);
        if (!needsGate) {
            return executor.execute(toolId, argumentJson);
        }

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

    /**
     * The autonomy track record for this call's {@code task_class} asks to escalate — its
     * risk floor rejects the action, or its confidence threshold is not met (ADR-0073 D2).
     * Returns {@code false} (adds no gate) when there is no policy, no task (hence no
     * {@link RunState}), no {@link ToolSpec} to classify the action, or no {@code task_class}
     * in state — the last is "not measurable here", a deliberate, stated gap in ADR-0073,
     * not an escalation.
     */
    private boolean autonomyEscalates(String toolId, AgentTask task) {
        if (autonomyPolicy == null || task == null) {
            return false;
        }
        Optional<ToolSpec> spec = delegate.specFor(toolId);
        if (spec.isEmpty()) {
            return false;
        }
        RunState state = task.runContext().state();
        String taskClass = state.get(taskClassStateKey, String.class).filter(s -> !s.isBlank()).orElse(null);
        if (taskClass == null) {
            return false;
        }
        double confidence = state.get(confidenceStateKey, Number.class).map(Number::doubleValue).orElse(0.0);
        return autonomyPolicy.escalate(taskClass, spec.get(), confidence);
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
