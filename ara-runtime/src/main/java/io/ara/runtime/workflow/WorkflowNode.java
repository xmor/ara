package io.ara.runtime.workflow;

import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * One unit of work in a {@link WorkflowGraph}: how to compute its output from its
 * composed input, which of its outgoing edges to activate, and what resuming should do
 * if this node was in flight when a prior run stopped.
 *
 * <p>D1 keeps this deliberately opaque — {@code body} is a plain function, not an
 * {@code io.ara.core.agent.AraAgent}. Wiring an agent-shaped node (routing through
 * {@code AraAgent.execute}, {@code RunState} writes, tool calls) is ADR-052 D2's job:
 * D1 only has to prove the scheduler's activation rule holds, and an opaque function is
 * the smallest thing that can exercise it without dragging in a facade this increment
 * deliberately does not build yet.
 *
 * @param id                 the node's identifier, unique within its {@link WorkflowGraph}
 * @param body               input (composed from incoming edges) to output; throwing
 *                           {@link WorkflowNodeSuspendedException} records a {@link
 *                           NodeOutcome.Suspended} instead of a {@link NodeOutcome.Failed}
 * @param selector           output to the subset of outgoing edges to activate;
 *                           {@code null} means "activate every outgoing edge" — the
 *                           common case for a non-routing node
 * @param onUncertainResume  what {@link DataflowScheduler} should do with this node on
 *                           resume if it was started but never finished in the prior run
 */
public record WorkflowNode(
        String id,
        Function<String, String> body,
        Function<String, Set<String>> selector,
        UncertainResumePolicy onUncertainResume
) {

    public WorkflowNode {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(onUncertainResume, "onUncertainResume must not be null");
    }

    public static WorkflowNode of(String id, Function<String, String> body) {
        return new WorkflowNode(id, body, null, UncertainResumePolicy.RETRY);
    }

    public static WorkflowNode routing(String id, Function<String, String> body, Function<String, Set<String>> selector) {
        return new WorkflowNode(id, body, Objects.requireNonNull(selector, "selector must not be null"), UncertainResumePolicy.RETRY);
    }

    /** Returns a copy of this node with its {@link #onUncertainResume} policy replaced. */
    public WorkflowNode withOnUncertainResume(UncertainResumePolicy policy) {
        return new WorkflowNode(id, body, selector, policy);
    }
}
