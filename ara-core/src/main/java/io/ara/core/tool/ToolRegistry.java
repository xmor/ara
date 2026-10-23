package io.ara.core.tool;

import io.ara.core.agent.AgentTask;
import io.ara.core.hitl.ApprovalRequest;

import java.util.List;
import java.util.Optional;

/**
 * Registry that manages the lifecycle and dispatch of {@link AraTool} instances.
 *
 * <p>The agent runtime uses this interface to discover which tools are available,
 * validate tool call requests from the LLM, and execute them. Concrete
 * implementations live in {@code ara-runtime} (e.g. {@code DelegatingToolRegistry})
 * and {@code ara-adapters} (MCP-backed tools).
 */
public interface ToolRegistry {

    /**
     * Returns all tools currently enabled for the given list of tool identifiers.
     *
     * @param enabledToolIds the identifiers declared in {@code AgentConfig.enabledTools()}
     * @return an immutable list of matching tools; empty if none match
     */
    List<AraTool> resolveEnabled(List<String> enabledToolIds);

    /**
     * Returns the tools enabled for this execution — the caller-aware variant of
     * {@link #resolveEnabled(List)}.
     *
     * <p>Default: delegates to the one-argument form. The overload exists so a registry
     * that knows <em>who</em> is running, and therefore what that caller may use, can
     * answer differently per task without forcing every existing registry to change.
     * A registry that filters by caller MUST do it here and leave the one-argument form
     * alone: that form is the caller-independent answer ("what is enabled for this
     * agent"), which discovery and listing surfaces depend on precisely because they
     * have no task to check against.
     *
     * @param enabledToolIds the identifiers declared in {@code AgentConfig.enabledTools()}
     * @param task           the task this resolution belongs to
     * @return an immutable list of matching tools; empty if none match
     */
    default List<AraTool> resolveEnabled(List<String> enabledToolIds, AgentTask task) {
        return resolveEnabled(enabledToolIds);
    }

    /**
     * Returns every tool this registry can resolve, independent of any particular
     * agent's {@code enabledTools()} selection — the full catalog, not a per-agent
     * subset. {@link #resolveEnabled}/{@link #findById} answer "can this specific id
     * be resolved"; this answers "what exists".
     *
     * <p>Intended for discovery/listing surfaces (e.g. an AgentOS-compat {@code GET
     * /registry?resource_type=tool} route) that need to enumerate the catalog rather
     * than resolve a caller-supplied set of ids.
     *
     * <p>Default: empty. A registry backed by a source with no practical "list
     * everything" operation (or one that legitimately doesn't know its own full
     * catalog upfront) is not forced to implement this — it simply reports nothing,
     * the same way {@link #resolveEnabled} reports nothing for an id it can't
     * resolve, rather than throwing.
     *
     * @return an immutable list of every tool known to this registry; empty if none
     *         or if this registry does not support enumeration
     */
    default List<AraTool> all() {
        return List.of();
    }

    /**
     * Looks up a single tool by its identifier.
     *
     * @param toolId the tool identifier
     * @return an optional containing the tool, or empty if not found
     */
    Optional<AraTool> findById(String toolId);

    /**
     * The risk classification of the tool identified by {@code toolId} (ADR-0067 D5), if
     * this registry carries one.
     *
     * <p>Default: empty. Additive by construction — a new default method on an interface
     * breaks no existing implementor. A registry that classifies its tools overrides this;
     * one that does not keeps returning empty for every id, and gating stays governed only
     * by the calling agent's {@code humanApprovalRequired()} flag, exactly as before.
     *
     * @param toolId the tool identifier
     * @return the {@link ToolSpec} for that tool, or empty if this registry has none
     */
    default Optional<ToolSpec> specFor(String toolId) {
        return Optional.empty();
    }

    /**
     * The approval a human must give before this call may run, or empty when it may run
     * straight away (ADR-048 D2). A pure question: it registers nothing with any gate and has
     * no effect, so it can be asked before a call is dispatched — which is what lets a run
     * suspend <em>before</em> executing anything, instead of parking a thread inside the call.
     *
     * <p>Default: empty — a registry that does not gate asks nobody. A registry that wraps
     * another MUST forward this, as it forwards {@link #specFor}: one decorator that drops it
     * turns suspension off for every agent built on top of it, silently, because "empty" reads
     * as "no approval needed".
     *
     * @param task the task the call belongs to, or {@code null} when there is none
     */
    default Optional<ApprovalRequest> approvalRequestFor(String toolId, String argumentJson, AgentTask task) {
        return Optional.empty();
    }

    /**
     * Executes the tool identified by {@code toolId} with the given JSON arguments.
     *
     * @param toolId       the tool to execute
     * @param argumentJson the JSON-serialised arguments matching the tool's schema
     * @return a {@link ToolResult} containing the output or an error description
     */
    ToolResult execute(String toolId, String argumentJson);

    /**
     * Executes the tool identified by {@code toolId} with the given JSON arguments,
     * forwarding the current {@link AgentTask} so the tool can read its
     * {@link AgentTask#runContext()} opaque values (ADR-037, ADR-041 rev. 2).
     *
     * <p>Default: DEGRADES to the 2-argument {@link #execute(String, String)},
     * silently dropping the task. A registry that wants to forward the task to its
     * tools MUST override this method rather than relying on the default — the
     * default exists so that pre-ADR-037 registries keep compiling and behaving
     * exactly as before, not as a way to reach tools with the task unintentionally.
     *
     * @param toolId       the tool to execute
     * @param argumentJson the JSON-serialised arguments matching the tool's schema
     * @param task         the current task; forwarded to the tool when the registry
     *                     overrides this method
     * @return a {@link ToolResult} containing the output or an error description
     */
    default ToolResult execute(String toolId, String argumentJson, AgentTask task) {
        return execute(toolId, argumentJson);
    }

    /**
     * Wraps {@code task} so any ambient tracing context active when this method is called
     * is restored for the duration of {@code task} — see {@code AraTelemetry.propagate}.
     *
     * <p>Strategies that dispatch tool calls on a new thread (e.g. {@code ReactStrategy}
     * running parallel tool calls on virtual threads) MUST wrap the dispatched
     * {@code Runnable} with this method before starting the thread, otherwise the
     * resulting {@code tool.execute} span appears as an unrelated root span instead of a
     * child of the caller's span. Exposed here — rather than requiring strategies to hold
     * their own {@code AraTelemetry} reference — because {@code ToolRegistry} is already
     * the layer that knows whether telemetry is wired in (see {@code TelemetryToolRegistry}).
     *
     * <p>Default: returns {@code task} unchanged (no telemetry wired in).
     *
     * @param task the work to run, possibly on another thread
     * @return a {@link Runnable} that restores the captured context before running {@code task}
     */
    default Runnable wrapForPropagation(Runnable task) {
        return task;
    }

    /** Returns a no-op registry that holds no tools. Useful when an agent needs no tool access. */
    static ToolRegistry empty() {
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(); }
            @Override public Optional<AraTool> findById(String toolId) { return Optional.empty(); }
            @Override public ToolResult execute(String toolId, String argumentJson) {
                return ToolResult.failure(toolId, "No tool registry configured");
            }
        };
    }
}
