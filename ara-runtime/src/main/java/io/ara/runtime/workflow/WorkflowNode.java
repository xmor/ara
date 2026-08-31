package io.ara.runtime.workflow;

import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * One unit of work in a {@link WorkflowGraph}: how to compute its output from its
 * composed input, and which of its outgoing edges to activate.
 *
 * <p>D1 keeps this deliberately opaque — {@code body} is a plain function, not an
 * {@code io.ara.core.agent.AraAgent}. Wiring an agent-shaped node (routing through
 * {@code AraAgent.execute}, {@code RunState} writes, tool calls) is ADR-052 D2's job:
 * D1 only has to prove the scheduler's activation rule holds, and an opaque function is
 * the smallest thing that can exercise it without dragging in a facade this increment
 * deliberately does not build yet.
 *
 * @param id       the node's identifier, unique within its {@link WorkflowGraph}
 * @param body     input (composed from incoming edges) to output
 * @param selector output to the subset of outgoing edges to activate; {@code null} means
 *                 "activate every outgoing edge" — the common case for a non-routing node
 */
public record WorkflowNode(String id, Function<String, String> body, Function<String, Set<String>> selector) {

    public WorkflowNode {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(body, "body must not be null");
    }

    public static WorkflowNode of(String id, Function<String, String> body) {
        return new WorkflowNode(id, body, null);
    }

    public static WorkflowNode routing(String id, Function<String, String> body, Function<String, Set<String>> selector) {
        return new WorkflowNode(id, body, Objects.requireNonNull(selector, "selector must not be null"));
    }
}
