package io.ara.runtime.wiring;

import io.ara.core.agent.AgentTask;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.core.tool.ToolSpec;
import io.ara.core.hitl.ApprovalRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Merges MCP-leased {@link AraTool}s with a {@code base} registry (the agent's usual,
 * stateless tool registry) — ADR-039. MCP-owned tool ids take precedence; anything else
 * falls through to {@code base} unchanged, including {@code base}'s own decorators
 * (telemetry, delegation, …).
 */
final class CompositeToolRegistry implements ToolRegistry {

    private final Map<String, AraTool> mcpTools;
    private final ToolRegistry base;

    CompositeToolRegistry(List<AraTool> mcpTools, ToolRegistry base) {
        Map<String, AraTool> byId = new LinkedHashMap<>();
        mcpTools.forEach(t -> byId.put(t.toolId(), t));
        this.mcpTools = Map.copyOf(byId);
        this.base = base;
    }

    @Override
    public List<AraTool> resolveEnabled(List<String> enabledToolIds) {
        return composite(enabledToolIds, base.resolveEnabled(remainingIds(enabledToolIds)));
    }

    /**
     * The same split as the caller-independent form, with the task forwarded to {@code base}
     * for the ids this composite does not own: MCP-leased tools are resolved here, and the
     * scope filter that may wrap this composite sees them all as one catalog anyway.
     */
    @Override
    public List<AraTool> resolveEnabled(List<String> enabledToolIds, AgentTask task) {
        return composite(enabledToolIds, base.resolveEnabled(remainingIds(enabledToolIds), task));
    }

    private List<String> remainingIds(List<String> enabledToolIds) {
        List<String> remaining = new ArrayList<>();
        for (String id : enabledToolIds) {
            if (!mcpTools.containsKey(id)) {
                remaining.add(id);
            }
        }
        return remaining;
    }

    private List<AraTool> composite(List<String> enabledToolIds, List<AraTool> fromBase) {
        List<AraTool> result = new ArrayList<>();
        for (String id : enabledToolIds) {
            AraTool mcp = mcpTools.get(id);
            if (mcp != null) {
                result.add(mcp);
            }
        }
        result.addAll(fromBase);
        return List.copyOf(result);
    }

    @Override
    public Optional<AraTool> findById(String toolId) {
        AraTool mcp = mcpTools.get(toolId);
        return mcp != null ? Optional.of(mcp) : base.findById(toolId);
    }

    /**
     * Forwarded to {@code base} for the tools it owns, so the per-tool approval gate (ADR-0067
     * D6) sees their classification. An MCP-leased tool has none — the server describes what a
     * tool takes, not what it does to the world — and it takes precedence here as in {@link
     * #findById}, so an id it shadows is not classified with the shadowed tool's spec.
     */
    @Override
    public Optional<ToolSpec> specFor(String toolId) {
        return mcpTools.containsKey(toolId) ? Optional.empty() : base.specFor(toolId);
    }

    /** Forwarded like {@link #specFor}: an MCP tool shadows a base tool of the same id, so it gets the base's answer for neither. */
    @Override
    public Optional<ApprovalRequest> approvalRequestFor(String toolId, String argumentJson, AgentTask task) {
        return mcpTools.containsKey(toolId) ? Optional.empty() : base.approvalRequestFor(toolId, argumentJson, task);
    }

    /** {@code base}'s full catalog plus every MCP-leased tool; MCP-owned ids take precedence, matching {@link #findById}. */
    @Override
    public List<AraTool> all() {
        Map<String, AraTool> byId = new LinkedHashMap<>();
        base.all().forEach(t -> byId.put(t.toolId(), t));
        byId.putAll(mcpTools);
        return List.copyOf(byId.values());
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson) {
        AraTool mcp = mcpTools.get(toolId);
        return mcp != null ? mcp.execute(argumentJson) : base.execute(toolId, argumentJson);
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson, AgentTask task) {
        AraTool mcp = mcpTools.get(toolId);
        return mcp != null ? mcp.execute(argumentJson, task) : base.execute(toolId, argumentJson, task);
    }

    @Override
    public Runnable wrapForPropagation(Runnable task) {
        return base.wrapForPropagation(task);
    }
}
