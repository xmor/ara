package io.ara.runtime.bus;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AgentView;
import io.ara.core.agent.AsyncDelegation;
import io.ara.core.agent.AsyncSupport;
import io.ara.core.agent.DelegateStateAccess;
import io.ara.core.agent.SessionStore;
import io.ara.core.auth.ScopeSet;
import io.ara.core.bus.MessageBus;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolCallNormalizer;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.core.tool.ToolSpec;
import io.ara.core.hitl.ApprovalRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * {@link ToolRegistry} decorator that injects the {@link AgentDelegationTool}
 * into any existing registry without modifying it.
 *
 * <p>All calls are forwarded to the wrapped {@code delegate} registry first.
 * {@code delegate_task} is resolved from this wrapper layer regardless of
 * whether the inner registry contains it.
 *
 * <p>Usage in the composition root:
 * <pre>{@code
 * ToolRegistry base = new DemoToolRegistry();   // echo, calculator, …
 * ToolRegistry rich = new DelegatingToolRegistry(base, messageBus, agentId);
 * // rich resolves: echo, calculator, delegate_task
 * }</pre>
 */
public final class DelegatingToolRegistry implements ToolRegistry {

    private final ToolRegistry         inner;
    /**
     * The tools this decorator adds on top of {@code inner}, by id: {@code delegate_task}
     * always, and — only for an agent that may delegate in the background — {@code await_tasks}
     * and {@code cancel_task}, which exist for nothing else. They travel together because the
     * two are only meaningful beside the {@code wait} parameter of the first.
     */
    private final Map<String, AraTool> ownTools = new LinkedHashMap<>();

    /**
     * @param inner    the registry to delegate all non-bus tool calls to
     * @param bus      the local message bus used by {@link AgentDelegationTool}
     * @param selfId   the id of the agent this registry belongs to
     */
    public DelegatingToolRegistry(ToolRegistry inner, MessageBus bus, String selfId) {
        this(inner, bus, selfId, Duration.ofSeconds(AgentDelegationTool.DEFAULT_TIMEOUT_SEC), DelegateStateAccess.OVERLAY);
    }

    /**
     * @param inner        the registry to delegate all non-bus tool calls to
     * @param bus          the local message bus used by {@link AgentDelegationTool}
     * @param selfId       the id of the agent this registry belongs to
     * @param stateAccess  what a delegated sub-agent sees of and can do to this agent's {@code RunState}
     */
    public DelegatingToolRegistry(ToolRegistry inner, MessageBus bus, String selfId, DelegateStateAccess stateAccess) {
        this(inner, bus, selfId, Duration.ofSeconds(AgentDelegationTool.DEFAULT_TIMEOUT_SEC), stateAccess);
    }

    /**
     * @param inner        the registry to delegate all non-bus tool calls to
     * @param bus          the local message bus used by {@link AgentDelegationTool}
     * @param selfId       the id of the agent this registry belongs to
     * @param stateAccess  what a delegated sub-agent sees of and can do to this agent's {@code RunState}
     * @param sessionStore used to persist a delegate's own {@code RunState}/history under its derived session
     */
    public DelegatingToolRegistry(ToolRegistry inner, MessageBus bus, String selfId, DelegateStateAccess stateAccess,
                                   SessionStore sessionStore) {
        this(inner, bus, selfId, Duration.ofSeconds(AgentDelegationTool.DEFAULT_TIMEOUT_SEC), stateAccess, sessionStore);
    }

    /**
     * @param inner    the registry to delegate all non-bus tool calls to
     * @param bus      the local message bus used by {@link AgentDelegationTool}
     * @param selfId   the id of the agent this registry belongs to
     * @param timeout  reply timeout for delegated tasks
     */
    public DelegatingToolRegistry(ToolRegistry inner, MessageBus bus, String selfId, Duration timeout) {
        this(inner, bus, selfId, timeout, DelegateStateAccess.OVERLAY);
    }

    /**
     * @param inner        the registry to delegate all non-bus tool calls to
     * @param bus          the local message bus used by {@link AgentDelegationTool}
     * @param selfId       the id of the agent this registry belongs to
     * @param timeout      reply timeout for delegated tasks
     * @param stateAccess  what a delegated sub-agent sees of and can do to this agent's {@code RunState}
     */
    public DelegatingToolRegistry(ToolRegistry inner, MessageBus bus, String selfId, Duration timeout,
                                   DelegateStateAccess stateAccess) {
        this(inner, bus, selfId, timeout, stateAccess, SessionStore.noop());
    }

    /**
     * @param inner        the registry to delegate all non-bus tool calls to
     * @param bus          the local message bus used by {@link AgentDelegationTool}
     * @param selfId       the id of the agent this registry belongs to
     * @param timeout      reply timeout for delegated tasks
     * @param stateAccess  what a delegated sub-agent sees of and can do to this agent's {@code RunState}
     * @param sessionStore used to persist a delegate's own {@code RunState}/history under its derived session
     */
    public DelegatingToolRegistry(ToolRegistry inner, MessageBus bus, String selfId, Duration timeout,
                                   DelegateStateAccess stateAccess, SessionStore sessionStore) {
        this(inner, bus, selfId, timeout, stateAccess, sessionStore, ScopeSet.EMPTY, null);
    }

    /**
     * @param ownGrantedScopes this agent's own granted scopes — see {@link
     *                         AgentDelegationTool}'s own constructor Javadoc for how it
     *                         attenuates across a delegation hop (ADR-0077 D2) and doubles
     *                         as the {@code senderScopes} {@link LocalMessageBus} checks
     *                         at dispatch (ADR-033 Fase 2).
     * @param agentView        (ADR-033 Fase 3 §3.3) a discovery-time visibility/authorization
     *                         gate ahead of every delegation attempt; {@code null} skips it —
     *                         see {@link AgentDelegationTool}'s own constructor Javadoc.
     */
    public DelegatingToolRegistry(ToolRegistry inner, MessageBus bus, String selfId, Duration timeout,
                                   DelegateStateAccess stateAccess, SessionStore sessionStore,
                                   ScopeSet ownGrantedScopes, AgentView agentView) {
        this(inner, bus, selfId, timeout, stateAccess, sessionStore, ownGrantedScopes, agentView, null, id -> null);
    }

    /**
     * @param asyncDelegation  when not {@code null}, the agent may start background sub-tasks:
     *                         {@code delegate_task} accepts {@code "wait": false} and
     *                         {@code await_tasks} / {@code cancel_task} are added. See {@link
     *                         AgentDelegationTool}'s own constructor Javadoc.
     * @param recipientSupport what a recipient declares about background delegation, by id
     */
    public DelegatingToolRegistry(ToolRegistry inner, MessageBus bus, String selfId, Duration timeout,
                                   DelegateStateAccess stateAccess, SessionStore sessionStore,
                                   ScopeSet ownGrantedScopes, AgentView agentView,
                                   AsyncDelegation asyncDelegation, Function<String, AsyncSupport> recipientSupport) {
        this(inner, bus, selfId, timeout, stateAccess, sessionStore, ownGrantedScopes, agentView,
                asyncDelegation, recipientSupport, null);
    }

    /**
     * @param viewSource (ADR-033 §3.3, task-scoped form) the discovery-time pre-check's
     *                   authority, built per task; may return {@code null} for "no scope
     *                   channel on this task" (pre-check skipped). When a fixed {@code
     *                   agentView} is also given, the fixed one wins — see {@link
     *                   AgentDelegationTool}'s own constructor Javadoc.
     */
    public DelegatingToolRegistry(ToolRegistry inner, MessageBus bus, String selfId, Duration timeout,
                                   DelegateStateAccess stateAccess, SessionStore sessionStore,
                                   ScopeSet ownGrantedScopes, AgentView agentView,
                                   AsyncDelegation asyncDelegation, Function<String, AsyncSupport> recipientSupport,
                                   Function<AgentTask, AgentView> viewSource) {
        this.inner          = Objects.requireNonNull(inner,  "inner must not be null");
        AgentDelegationTool delegationTool = new AgentDelegationTool(
                Objects.requireNonNull(bus,    "bus must not be null"),
                Objects.requireNonNull(selfId, "selfId must not be null"),
                timeout,
                Objects.requireNonNull(stateAccess, "stateAccess must not be null"),
                Objects.requireNonNull(sessionStore, "sessionStore must not be null"),
                ownGrantedScopes,
                agentView,
                asyncDelegation,
                recipientSupport,
                viewSource);
        ownTools.put(AgentDelegationTool.TOOL_ID, delegationTool);
        if (asyncDelegation != null) {
            ownTools.put(AwaitTasksTool.TOOL_ID, new AwaitTasksTool());
            ownTools.put(CancelTaskTool.TOOL_ID, new CancelTaskTool());
        }
    }

    @Override
    public List<AraTool> resolveEnabled(List<String> enabledToolIds) {
        return withOwnTools(enabledToolIds, inner.resolveEnabled(enabledToolIds));
    }

    /**
     * Same rule as the caller-independent form, applied to whatever the inner registry
     * resolved for this task: this decorator's own tools (delegation, background task
     * controls) are not scope-gated, so they are added regardless of what the filter
     * underneath removed.
     */
    @Override
    public List<AraTool> resolveEnabled(List<String> enabledToolIds, AgentTask task) {
        return withOwnTools(enabledToolIds, inner.resolveEnabled(enabledToolIds, task));
    }

    private List<AraTool> withOwnTools(List<String> enabledToolIds, List<AraTool> resolved) {
        List<AraTool> tools = new ArrayList<>(resolved);
        if (shouldAddOwnTools(enabledToolIds)) {
            addMissingOwnTools(tools);
        }
        return List.copyOf(tools);
    }

    /**
     * Determines whether this decorator's tools should be added to the enabled set — decided by
     * {@code delegate_task} alone, the tool the others only make sense beside.
     *
     * <ul>
     *   <li>{@code null}  → "all tools" mode → add them.</li>
     *   <li>empty list    → agent explicitly declared no tools → do NOT add them.</li>
     *   <li>non-empty     → add them only if {@code delegate_task} is explicitly listed.</li>
     * </ul>
     */
    private static boolean shouldAddOwnTools(List<String> enabledToolIds) {
        if (enabledToolIds == null) return true;                              // all-tools mode
        if (enabledToolIds.isEmpty()) return false;                           // no-tools mode
        return enabledToolIds.contains(AgentDelegationTool.TOOL_ID);          // explicit opt-in
    }

    /** Adds each own tool {@code tools} does not already declare, so an inner registry's version wins. */
    private void addMissingOwnTools(List<AraTool> tools) {
        for (AraTool own : ownTools.values()) {
            boolean alreadyPresent = tools.stream().anyMatch(t -> own.toolId().equals(t.toolId()));
            if (!alreadyPresent) tools.add(own);
        }
    }

    @Override
    public Optional<AraTool> findById(String toolId) {
        String id = ToolCallNormalizer.stripNamespace(toolId);
        AraTool own = ownTools.get(id);
        if (own != null) {
            return Optional.of(own);
        }
        return inner.findById(id);
    }

    /**
     * Forwarded to {@code inner}, with the same namespace stripping as {@link #findById}: the
     * classification of the user's tools lives there, and {@code ApprovalToolRegistry} — which
     * wraps this registry — reads it to decide whether a call is gated (ADR-0067 D6). {@code
     * delegate_task} has no classification of its own: what it may do is decided by the
     * delegate's own tools, each gated in the delegate's chain.
     */
    @Override
    public Optional<ToolSpec> specFor(String toolId) {
        String id = ToolCallNormalizer.stripNamespace(toolId);
        return ownTools.containsKey(id) ? Optional.empty() : inner.specFor(id);
    }

    /**
     * Forwarded like {@link #specFor}, with the same namespace stripping and the same
     * exception: {@code delegate_task} is not gated here, its delegate's tools are.
     */
    @Override
    public Optional<ApprovalRequest> approvalRequestFor(String toolId, String argumentJson, AgentTask task) {
        String id = ToolCallNormalizer.stripNamespace(toolId);
        return ownTools.containsKey(id)
                ? Optional.empty()
                : inner.approvalRequestFor(id, argumentJson, task);
    }

    /** {@code inner}'s full catalog plus {@code delegate_task}, unless {@code inner} already declares it. */
    @Override
    public List<AraTool> all() {
        List<AraTool> tools = new ArrayList<>(inner.all());
        addMissingOwnTools(tools);
        return List.copyOf(tools);
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson) {
        String id   = ToolCallNormalizer.stripNamespace(toolId);
        String args = ToolCallNormalizer.normalizeArgs(argumentJson);
        AraTool own = ownTools.get(id);
        if (own != null) {
            return own.execute(args);
        }
        return inner.execute(id, args);
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson, AgentTask task) {
        String id   = ToolCallNormalizer.stripNamespace(toolId);
        String args = ToolCallNormalizer.normalizeArgs(argumentJson);
        AraTool own = ownTools.get(id);
        if (own != null) {
            return own.execute(args, task);
        }
        return inner.execute(id, args, task);
    }

    @Override
    public Runnable wrapForPropagation(Runnable task) {
        return inner.wrapForPropagation(task);
    }
}