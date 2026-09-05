package io.ara.runtime.workflow;

import io.ara.core.agent.AgentChain;
import io.ara.core.budget.Spend;

import java.util.List;
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
 * @param cost               output to the {@link Spend} this occurrence drew (money,
 *                           tokens, LLM calls), charged to the run's {@code RunBudget}
 *                           (ADR-054 D6); {@code null} means "declares no cost" — the
 *                           node still counts as one activation but adds nothing on the
 *                           token / money axes. D1 nodes are opaque functions with no
 *                           LLM call, so this stays opt-in until D2 makes nodes
 *                           agent-shaped and the {@code Spend} comes from {@code AgentResponse}.
 * @param write              output to a {@link Write}, merged into the run's shared
 *                           state under a declared reducer (ADR-052 D3); {@code null}
 *                           means "writes nothing" — most nodes.
 * @param mapOver            declares this node as a dynamic fan-out source (ADR-052 D4);
 *                           {@code null} means "an ordinary node" — almost all of them.
 */
public record WorkflowNode(
        String id,
        Function<String, String> body,
        Function<String, Set<String>> selector,
        UncertainResumePolicy onUncertainResume,
        Function<String, Spend> cost,
        Write write,
        MapOverSpec mapOver
) {

    public WorkflowNode {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(onUncertainResume, "onUncertainResume must not be null");
    }

    /** Backwards-compatible constructor: a node that declares no {@link #mapOver}. */
    public WorkflowNode(String id, Function<String, String> body, Function<String, Set<String>> selector,
                        UncertainResumePolicy onUncertainResume, Function<String, Spend> cost, Write write) {
        this(id, body, selector, onUncertainResume, cost, write, null);
    }

    /** Backwards-compatible constructor: a node that declares no {@link #write} or {@link #mapOver}. */
    public WorkflowNode(String id, Function<String, String> body, Function<String, Set<String>> selector,
                        UncertainResumePolicy onUncertainResume, Function<String, Spend> cost) {
        this(id, body, selector, onUncertainResume, cost, null, null);
    }

    /** Backwards-compatible constructor: a node that declares no {@link #cost}, {@link #write} or {@link #mapOver}. */
    public WorkflowNode(String id, Function<String, String> body,
                        Function<String, Set<String>> selector, UncertainResumePolicy onUncertainResume) {
        this(id, body, selector, onUncertainResume, null, null, null);
    }

    public static WorkflowNode of(String id, Function<String, String> body) {
        return new WorkflowNode(id, body, null, UncertainResumePolicy.RETRY, null, null, null);
    }

    public static WorkflowNode routing(String id, Function<String, String> body, Function<String, Set<String>> selector) {
        return new WorkflowNode(id, body, Objects.requireNonNull(selector, "selector must not be null"),
                UncertainResumePolicy.RETRY, null, null, null);
    }

    /** Returns a copy of this node with its {@link #onUncertainResume} policy replaced. */
    public WorkflowNode withOnUncertainResume(UncertainResumePolicy policy) {
        return new WorkflowNode(id, body, selector, policy, cost, write, mapOver);
    }

    /** Returns a copy of this node with a {@link #cost} function that maps its output to the {@link Spend} it drew. */
    public WorkflowNode withCost(Function<String, Spend> cost) {
        return new WorkflowNode(id, body, selector, onUncertainResume,
                Objects.requireNonNull(cost, "cost must not be null"), write, mapOver);
    }

    /** Returns a copy of this node with a {@link #write} that maps its output to a shared-state entry. */
    public WorkflowNode withWrite(Write write) {
        return new WorkflowNode(id, body, selector, onUncertainResume, cost,
                Objects.requireNonNull(write, "write must not be null"), mapOver);
    }

    /** Returns a copy of this node with a {@link #mapOver} spec (ADR-052 D4). */
    public WorkflowNode withMapOver(MapOverSpec mapOver) {
        return new WorkflowNode(id, body, selector, onUncertainResume, cost, write,
                Objects.requireNonNull(mapOver, "mapOver must not be null"));
    }

    /**
     * One node's contribution to the run's shared state (ADR-052 D3): when this node
     * completes, {@link #extractor()} maps its output to the value stored under {@link
     * #key()} — merged with whatever is already there via the {@link WorkflowGraph}'s
     * declared reducer for that key, if two or more nodes ever write the same one (see
     * {@code Workflow.Builder#reduce}). This is the declarative replacement for
     * {@code ara-graph}'s retired {@code SharedWorkspace}: a node never mutates a shared
     * object directly, it only declares what it contributes and how collisions resolve.
     */
    public record Write(String key, Function<String, Object> extractor) {
        public Write {
            Objects.requireNonNull(key, "key must not be null");
            Objects.requireNonNull(extractor, "extractor must not be null");
        }
    }

    /**
     * Dynamic fan-out (ADR-052 D4): when the node this is attached to completes, {@link
     * #elements()} reads a runtime-determined list from its output, and {@link
     * #workerBody()} — a plain function, the same shape every {@link WorkflowNode#body()}
     * is — runs once per element, each with <em>its own</em> input, never the same task
     * replicated (the limit {@code ParallelAgent} has). Each activation is individually
     * journaled ({@code workerId + "[" + occurrence + "." + index + "]"}) and, if {@link
     * #collectInto()} is set, contributes its output to that shared-state key (merged as
     * {@code List.of(output)} through whatever reducer is declared for it — {@link
     * Reducers#concatLists()} by default, registered automatically unless the caller
     * declared a different one).
     *
     * <p>{@link #maxActivations()} is mandatory, not optional: a fan-out whose degree
     * depends on an upstream node's output is an unbounded cost multiplier otherwise — the
     * same reasoning the Claude Agent SDK caps its own dynamic workflows on. Exceeding it
     * fails the run naming the construct, never silently truncates the list.
     *
     * <p>{@link #onPartialFailure()} reuses {@link AgentChain.FailurePolicy} rather than a
     * third failure-policy type — {@code FAIL_FAST}/{@code REQUIRE_ALL} both fail the
     * whole group the moment any child fails (this scheduler always waits for every child
     * before deciding, so the two are equivalent here — only {@link
     * AgentChain.FailurePolicy#apply} distinguishes their error-message shape, and there
     * is no {@code AgentResponse} here for that method to run against); {@code
     * PARTIAL_OK} keeps the successful outputs and only fails if every child did.
     */
    public record MapOverSpec(
            String workerId,
            Function<String, List<String>> elements,
            Function<String, String> workerBody,
            String collectInto,
            int maxActivations,
            AgentChain.FailurePolicy onPartialFailure
    ) {
        public MapOverSpec {
            Objects.requireNonNull(workerId, "workerId must not be null");
            Objects.requireNonNull(elements, "elements must not be null");
            Objects.requireNonNull(workerBody, "workerBody must not be null");
            Objects.requireNonNull(onPartialFailure, "onPartialFailure must not be null");
            if (maxActivations <= 0) {
                throw new IllegalArgumentException("maxActivations must be > 0, got " + maxActivations);
            }
        }
    }
}
