package io.ara.runtime.bus;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AgentView;
import io.ara.core.agent.DelegateStateAccess;
import io.ara.core.agent.SessionStore;
import io.ara.core.auth.ScopeSet;
import io.ara.core.bus.MessageBus;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolCallNormalizer;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    private final AgentDelegationTool  delegationTool;

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
        this.inner          = Objects.requireNonNull(inner,  "inner must not be null");
        this.delegationTool = new AgentDelegationTool(
                Objects.requireNonNull(bus,    "bus must not be null"),
                Objects.requireNonNull(selfId, "selfId must not be null"),
                timeout,
                Objects.requireNonNull(stateAccess, "stateAccess must not be null"),
                Objects.requireNonNull(sessionStore, "sessionStore must not be null"),
                ownGrantedScopes,
                agentView);
    }

    @Override
    public List<AraTool> resolveEnabled(List<String> enabledToolIds) {
        List<AraTool> tools = new ArrayList<>(inner.resolveEnabled(enabledToolIds));
        if (shouldAddDelegation(enabledToolIds, tools)) {
            tools.add(delegationTool);
        }
        return List.copyOf(tools);
    }

    /**
     * Determines whether the delegation tool should be added to the enabled set.
     *
     * <ul>
     *   <li>{@code null}  → "all tools" mode → add delegation.</li>
     *   <li>empty list    → agent explicitly declared no tools → do NOT add delegation.</li>
     *   <li>non-empty     → add only if {@code delegate_task} is explicitly listed.</li>
     *   <li>already present in {@code resolved} → skip to avoid duplicates.</li>
     * </ul>
     */
    private static boolean shouldAddDelegation(List<String> enabledToolIds, List<AraTool> resolved) {
        boolean alreadyPresent = resolved.stream()
                .anyMatch(t -> AgentDelegationTool.TOOL_ID.equals(t.toolId()));
        if (alreadyPresent) return false;

        if (enabledToolIds == null) return true;                              // all-tools mode
        if (enabledToolIds.isEmpty()) return false;                           // no-tools mode
        return enabledToolIds.contains(AgentDelegationTool.TOOL_ID);          // explicit opt-in
    }

    @Override
    public Optional<AraTool> findById(String toolId) {
        String id = ToolCallNormalizer.stripNamespace(toolId);
        if (AgentDelegationTool.TOOL_ID.equals(id)) {
            return Optional.of(delegationTool);
        }
        return inner.findById(id);
    }

    /** {@code inner}'s full catalog plus {@code delegate_task}, unless {@code inner} already declares it. */
    @Override
    public List<AraTool> all() {
        List<AraTool> tools = new ArrayList<>(inner.all());
        boolean alreadyPresent = tools.stream().anyMatch(t -> AgentDelegationTool.TOOL_ID.equals(t.toolId()));
        if (!alreadyPresent) tools.add(delegationTool);
        return List.copyOf(tools);
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson) {
        String id   = ToolCallNormalizer.stripNamespace(toolId);
        String args = ToolCallNormalizer.normalizeArgs(argumentJson);
        if (AgentDelegationTool.TOOL_ID.equals(id)) {
            return delegationTool.execute(args);
        }
        return inner.execute(id, args);
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson, AgentTask task) {
        String id   = ToolCallNormalizer.stripNamespace(toolId);
        String args = ToolCallNormalizer.normalizeArgs(argumentJson);
        if (AgentDelegationTool.TOOL_ID.equals(id)) {
            return delegationTool.execute(args, task);
        }
        return inner.execute(id, args, task);
    }

    @Override
    public Runnable wrapForPropagation(Runnable task) {
        return inner.wrapForPropagation(task);
    }
}