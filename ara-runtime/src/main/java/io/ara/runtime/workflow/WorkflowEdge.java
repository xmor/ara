package io.ara.runtime.workflow;

import java.util.Objects;

/**
 * A directed edge between two node ids in a {@link WorkflowGraph}.
 *
 * <p>{@code back} is the only place cycles are expressed. A {@code back} edge carries
 * OR-merge semantics in {@link DataflowScheduler}: its target fires as soon as a single
 * token arrives on it, instead of waiting on every other incoming edge the way a forward
 * edge does. That distinction — not a separate "is this a cycle" check anywhere else — is
 * what lets {@code DataflowScheduler} treat cycles as ordinary occurrences (ADR-052 D1).
 */
public record WorkflowEdge(String from, String to, boolean back) {

    public WorkflowEdge {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
    }

    public static WorkflowEdge of(String from, String to) {
        return new WorkflowEdge(from, to, false);
    }

    public static WorkflowEdge back(String from, String to) {
        return new WorkflowEdge(from, to, true);
    }

    @Override
    public String toString() {
        return from + (back ? "~>" : "->") + to;
    }
}
