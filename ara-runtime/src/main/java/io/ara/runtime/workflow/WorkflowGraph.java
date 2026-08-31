package io.ara.runtime.workflow;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The D1 graph model: nodes and edges, nothing else.
 *
 * <p>None of ADR-052 D5's ten build-time checks (reachability, dead-end nodes, HITL
 * presence, and so on) live here yet — those are meaningful once the facade (D2) exists
 * to construct graphs a caller can get wrong in the ways those checks catch. What this
 * constructor does enforce is referential integrity: an edge naming a node that doesn't
 * exist is not a design question to defer, it's a bug in whoever built the graph, and
 * every one of the other checks in D5 assumes it can't happen.
 */
public record WorkflowGraph(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {

    public WorkflowGraph {
        Objects.requireNonNull(nodes, "nodes must not be null");
        Objects.requireNonNull(edges, "edges must not be null");
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);

        Map<String, WorkflowNode> byId = nodes.stream()
                .collect(Collectors.toMap(WorkflowNode::id, n -> n, (a, b) -> {
                    throw new IllegalArgumentException("duplicate node id: " + a.id());
                }));
        for (WorkflowEdge edge : edges) {
            if (!byId.containsKey(edge.from())) {
                throw new IllegalArgumentException("edge " + edge + " references unknown node: " + edge.from());
            }
            if (!byId.containsKey(edge.to())) {
                throw new IllegalArgumentException("edge " + edge + " references unknown node: " + edge.to());
            }
        }
    }

    WorkflowNode node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown node: " + id));
    }

    /** Incoming edges, in declaration order — the order a join composes its input in. */
    List<WorkflowEdge> in(String id) {
        return edges.stream().filter(e -> e.to().equals(id)).toList();
    }

    List<WorkflowEdge> out(String id) {
        return edges.stream().filter(e -> e.from().equals(id)).toList();
    }
}
