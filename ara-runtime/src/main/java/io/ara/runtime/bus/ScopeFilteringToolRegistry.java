package io.ara.runtime.bus;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AgentView;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.TaskScopes;
import io.ara.core.auth.ScopeSet;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.core.tool.ToolSpec;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.runtime.auth.ScopeVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
/**
 * {@link ToolRegistry} decorator that filters by {@link AraTool#requiredScopes()} against
 * the caller's authority (ADR-033 Fase 3 §3.2).
 *
 * <p><b>Deviation from the ADR-033 implementation plan</b>: the plan describes this
 * filtering as an update to {@code DelegatingToolRegistry}. That class already exists in
 * this codebase with an unrelated responsibility — injecting {@link AgentDelegationTool}
 * into a registry, nothing to do with scopes — so bolting scope-filtering onto it would
 * conflate two independent concerns into one decorator. This is a new, separate decorator
 * instead; the two compose freely, in either order.
 *
 * <p>Two construction forms, two sources of authority:
 * <ul>
 *   <li>{@link #ScopeFilteringToolRegistry(ToolRegistry, AgentView)} — a fixed
 *       {@link AgentView#effectiveScopes()} for every call. Suits a deployment whose
 *       scopes never vary within the agent; the original form.</li>
 *   <li>{@link #ScopeFilteringToolRegistry(ToolRegistry)} — the authority is read from
 *       each {@link AgentTask}: {@link AgentTask#executionContext()}'s effective scopes
 *       first (the on-behalf-of intersection, already attenuated at every delegation
 *       hop), then the bare {@link RunContext#SCOPES_KEY} set, then empty. The same
 *       agent then serves callers with different authority with different catalogs,
 *       without rebuilding its wiring: the wiring stays session-pinned, only the answer
 *       to "what may this caller use" becomes per-task. That one precedence is {@link
 *       TaskScopes}' — shared with the delegation pre-check so the two scope readers
 *       cannot answer differently about the same task.</li>
 * </ul>
 *
 * <p>Two enforcement points, matching {@link LocalMessageBus}'s own split between
 * discovery and dispatch:
 * <ul>
 *   <li>{@link #resolveEnabled} silently drops a tool the caller's scopes don't
 *       satisfy — this is the catalog an LLM sees, so an unauthorized tool should
 *       never appear as something to attempt, not surface as a confusing runtime error.</li>
 *   <li>{@link #execute(String, String)}/{@link #execute(String, String, AgentTask)} check
 *       again and throw {@link io.ara.core.auth.AuthorizationException} — defense in depth
 *       for a call that reaches {@code execute} without going through {@link
 *       #resolveEnabled} first (nothing in the {@link ToolRegistry} contract requires it).</li>
 * </ul>
 *
 * <p><b>Task-scoped form: fail-closed, and loud about the likely misconfiguration.</b>
 * A tool no caller can be proven to satisfy is hidden, so a tool that declares scopes
 * disappears from the catalog of a task that carries no scope channel at all. That is
 * the intended security default, but it is also the symptom of an integration that
 * declares scopes on tools and forgets to seed the task; a tool vanishing with no trace
 * is undebuggable, so the first time a tool is hidden for <em>that</em> reason this
 * registry logs one warning per tool id. Insufficient scopes on a task that does carry
 * a channel is ordinary denial and stays silent. The catalog the model sees is
 * unaffected by logging: only operators read the warning.
 *
 * <p>{@link #findById}/{@link #all()} are pass-through, deliberately unfiltered: they are
 * used for tool <em>definition</em> lookup (schema, description), not authorization to
 * call one — the same reasoning {@code AgentView.all()} uses for agent discovery.
 */
public final class ScopeFilteringToolRegistry implements ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ScopeFilteringToolRegistry.class);

    private final ToolRegistry inner;
    private final AgentView    agentView;

    /** One warning per hidden tool, not one per execution: bounded by the catalog size. */
    private final Set<String> warnedAboutMissingChannel = ConcurrentHashMap.newKeySet();

    /**
     * Fixed-authority form: every call is checked against {@code agentView}'s effective
     * scopes, whatever task it carries.
     */
    public ScopeFilteringToolRegistry(ToolRegistry inner, AgentView agentView) {
        this.inner     = Objects.requireNonNull(inner,     "inner must not be null");
        this.agentView = Objects.requireNonNull(agentView, "agentView must not be null");
    }

    /**
     * Task-scoped form: the caller's authority comes from each task, never from an
     * {@link AgentView} captured at wiring time.
     */
    public ScopeFilteringToolRegistry(ToolRegistry inner) {
        this.inner     = Objects.requireNonNull(inner, "inner must not be null");
        this.agentView = null;
    }

    /**
     * Caller-independent resolution, as before: with a fixed view it filters against it;
     * without one there is no caller to check, so it forwards unfiltered. Discovery and
     * listing surfaces ("what is enabled for this agent") must call this form; only the
     * task-aware overload can answer "what may <em>this</em> caller use".
     */
    @Override
    public List<AraTool> resolveEnabled(List<String> enabledToolIds) {
        List<AraTool> resolved = inner.resolveEnabled(enabledToolIds);
        return agentView == null ? resolved : keepGranted(resolved, agentView.effectiveScopes());
    }

    @Override
    public List<AraTool> resolveEnabled(List<String> enabledToolIds, AgentTask task) {
        List<AraTool> resolved = inner.resolveEnabled(enabledToolIds, task);
        if (agentView != null) {
            return keepGranted(resolved, agentView.effectiveScopes());
        }

        Caller caller = callerOf(task);
        return resolved.stream()
                .filter(tool -> {
                    boolean granted = caller.scopes().grants(ScopeSet.of(tool.requiredScopes()));
                    if (!granted && !caller.channelPresent()) {
                        warnAboutMissingChannel(tool);
                    }
                    return granted;
                })
                .toList();
    }

    @Override
    public Optional<AraTool> findById(String toolId) {
        return inner.findById(toolId);
    }

    @Override
    public List<AraTool> all() {
        return inner.all();
    }

    /**
     * Forwarded, like {@link #findById}: the tool's classification lives on the registry this
     * one wraps, and {@code ApprovalToolRegistry} reads it through the whole chain to decide
     * whether a call is gated (ADR-0067 D6). Not forwarding it silently turns the per-tool
     * gate off for every agent built on top of this decorator.
     */
    @Override
    public Optional<ToolSpec> specFor(String toolId) {
        return inner.specFor(toolId);
    }

    /** Forwarded for the same reason as {@link #specFor}; see {@link ToolRegistry#approvalRequestFor}. */
    @Override
    public Optional<ApprovalRequest> approvalRequestFor(String toolId, String argumentJson, AgentTask task) {
        return inner.approvalRequestFor(toolId, argumentJson, task);
    }

    /**
     * Without a call there is no task to read authority from. The fixed-authority form
     * checks against its view; the task-scoped form cannot verify anything, so it refuses
     * a tool that declares scopes and forwards the rest. Resolutions are catalog reads and
     * stay unfiltered in the same situation: an action needs an authority to verify, a
     * definition lookup does not.
     */
    @Override
    public ToolResult execute(String toolId, String argumentJson) {
        if (agentView == null) {
            inner.findById(toolId).ifPresent(tool -> ScopeVerifier.checkTool(tool, ScopeSet.EMPTY));
        } else {
            checkAuthorized(toolId, agentView.effectiveScopes());
        }
        return inner.execute(toolId, argumentJson);
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson, AgentTask task) {
        ScopeSet effective = agentView != null ? agentView.effectiveScopes() : callerOf(task).scopes();
        checkAuthorized(toolId, effective);
        return inner.execute(toolId, argumentJson, task);
    }

    @Override
    public Runnable wrapForPropagation(Runnable task) {
        return inner.wrapForPropagation(task);
    }

    private static List<AraTool> keepGranted(List<AraTool> resolved, ScopeSet effective) {
        return resolved.stream()
                .filter(tool -> effective.grants(ScopeSet.of(tool.requiredScopes())))
                .toList();
    }

    /**
     * The authority a task carries, and whether it carried any at all — the two are not
     * the same: an empty scope set that came from a channel is a legitimate denial, while
     * no channel is usually an integration mistake. The resolution lives in {@link
     * TaskScopes} so the delegation pre-check and this filter read one shared precedence
     * (execution context first, then the bare scope key) and can never drift apart.
     */
    private static Caller callerOf(AgentTask task) {
        TaskScopes.Resolved resolved = TaskScopes.of(task);
        return new Caller(resolved.scopes(), resolved.channelPresent());
    }

    private void checkAuthorized(String toolId, ScopeSet effective) {
        inner.findById(toolId).ifPresent(tool -> ScopeVerifier.checkTool(tool, effective));
    }

    /**
     * A gated tool hidden because the task carried no scope channel is much more often a
     * seeding omission than a real denial: say it once, in the log — never in the catalog
     * the model reads.
     */
    private void warnAboutMissingChannel(AraTool tool) {
        if (!warnedAboutMissingChannel.add(tool.toolId())) {
            return;
        }
        log.warn("Hiding tool [{}] from the catalog: it requires scopes {} but the task carries no "
                        + "scope channel. Seed the task with an execution context or an explicit scope "
                        + "set, otherwise this tool stays unavailable to every caller.",
                tool.toolId(), tool.requiredScopes());
    }

    private record Caller(ScopeSet scopes, boolean channelPresent) {}
}
