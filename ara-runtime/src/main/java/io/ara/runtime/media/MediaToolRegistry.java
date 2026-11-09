package io.ara.runtime.media;

import io.ara.core.agent.AgentTask;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolCallNormalizer;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.core.tool.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ToolRegistry} decorator that adds {@link ViewMediaTool} to an agent whose runtime has a
 * media store and that lists {@code view_media} among its enabled tools.
 *
 * <p>Two conditions, both deliberate. The runtime only wraps a registry with this when it has a
 * store (without one there is no media to show, and {@code MediaAccess} is absent). And the tool is
 * added only on an <em>explicit</em> listing — never in the "all tools" mode — so an agent that
 * existed before media in tool results sees the same tool catalog, and therefore the same prompt,
 * as it did. It sits beside {@code DelegatingToolRegistry} rather than inside it because that one
 * is about delegation: reusing its opt-in ({@code delegate_task} listed) would tie two unrelated
 * choices together.
 *
 * <p>Thread-safety: immutable apart from the wrapped registry's own contract.
 */
public final class MediaToolRegistry implements ToolRegistry {

    private final ToolRegistry inner;
    private final AraTool viewMedia = new ViewMediaTool();

    public MediaToolRegistry(ToolRegistry inner) {
        this.inner = Objects.requireNonNull(inner, "inner must not be null");
    }

    @Override
    public List<AraTool> resolveEnabled(List<String> enabledToolIds) {
        return withViewMedia(enabledToolIds, inner.resolveEnabled(enabledToolIds));
    }

    @Override
    public List<AraTool> resolveEnabled(List<String> enabledToolIds, AgentTask task) {
        return withViewMedia(enabledToolIds, inner.resolveEnabled(enabledToolIds, task));
    }

    private List<AraTool> withViewMedia(List<String> enabledToolIds, List<AraTool> resolved) {
        boolean listed = enabledToolIds != null && enabledToolIds.contains(ViewMediaTool.TOOL_ID);
        boolean present = resolved.stream().anyMatch(t -> ViewMediaTool.TOOL_ID.equals(t.toolId()));
        if (!listed || present) {
            return resolved;
        }
        List<AraTool> tools = new ArrayList<>(resolved);
        tools.add(viewMedia);
        return List.copyOf(tools);
    }

    @Override
    public List<AraTool> all() {
        List<AraTool> tools = new ArrayList<>(inner.all());
        if (tools.stream().noneMatch(t -> ViewMediaTool.TOOL_ID.equals(t.toolId()))) {
            tools.add(viewMedia);
        }
        return List.copyOf(tools);
    }

    @Override
    public Optional<AraTool> findById(String toolId) {
        String id = ToolCallNormalizer.stripNamespace(toolId);
        return ViewMediaTool.TOOL_ID.equals(id) ? Optional.of(viewMedia) : inner.findById(id);
    }

    @Override
    public Optional<ToolSpec> specFor(String toolId) {
        String id = ToolCallNormalizer.stripNamespace(toolId);
        return ViewMediaTool.TOOL_ID.equals(id) ? Optional.empty() : inner.specFor(id);
    }

    @Override
    public Optional<ApprovalRequest> approvalRequestFor(String toolId, String argumentJson, AgentTask task) {
        String id = ToolCallNormalizer.stripNamespace(toolId);
        return ViewMediaTool.TOOL_ID.equals(id) ? Optional.empty() : inner.approvalRequestFor(id, argumentJson, task);
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson) {
        String id = ToolCallNormalizer.stripNamespace(toolId);
        return ViewMediaTool.TOOL_ID.equals(id)
                ? viewMedia.execute(ToolCallNormalizer.normalizeArgs(argumentJson))
                : inner.execute(id, argumentJson);
    }

    @Override
    public ToolResult execute(String toolId, String argumentJson, AgentTask task) {
        String id = ToolCallNormalizer.stripNamespace(toolId);
        return ViewMediaTool.TOOL_ID.equals(id)
                ? viewMedia.execute(ToolCallNormalizer.normalizeArgs(argumentJson), task)
                : inner.execute(id, argumentJson, task);
    }

    @Override
    public Runnable wrapForPropagation(Runnable task) {
        return inner.wrapForPropagation(task);
    }
}
