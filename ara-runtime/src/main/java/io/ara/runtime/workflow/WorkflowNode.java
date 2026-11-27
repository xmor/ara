package io.ara.runtime.workflow;

import io.ara.core.agent.AgentChain;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.RunState;
import io.ara.core.budget.Spend;
import io.ara.core.media.MediaRef;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * One unit of work in a {@link WorkflowGraph}: how to compute its output from its
 * composed input, which of its outgoing edges to activate, and what resuming should do
 * if this node was in flight when a prior run stopped.
 *
 * <p>The base node is deliberately opaque — {@code body} is a plain function, not an
 * {@code io.ara.core.agent.AraAgent}: the scheduler's activation rule had to be proven
 * first, and an opaque function is the smallest thing that can exercise it without
 * dragging in the agent machinery (routing through {@code AraAgent.execute},
 * {@code RunState} writes, tool calls). That machinery is now layered on top of it:
 * a node declared through {@link #agent(String, AgentBinding)} carries an
 * {@link AgentBinding}, and
 * {@link #run(String)} executes the agent and captures its {@link AgentResponse} — so
 * token usage and cost reach the journal instead of vanishing into a string.
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
 *                           tokens, LLM calls), charged to the run's {@code RunBudget};
 *                           {@code null} means "declares no cost" — the
 *                           node still counts as one activation but adds nothing on the
 *                           token / money axes. For an agent-shaped node this is ignored
 *                           in favour of the {@link AgentResponse}'s own totals: the
 *                           response is the source of truth once there is one, and a
 *                           caller-declared {@code cost} can only under- or over-state it.
 * @param write              output to a {@link Write}, merged into the run's shared
 *                           state under a declared reducer; {@code null} means
 *                           "writes nothing" — most nodes.
 * @param mapOver            declares this node as a dynamic fan-out source;
 *                           {@code null} means "an ordinary node" — almost all of them.
 * @param composer           how this node combines the tokens of its several forward
 *                           incoming edges into its single input, in edge-declaration
 *                           order (never completion order — that is what keeps a join
 *                           deterministic under concurrency). {@code null} means "not
 *                           declared", and a node with more than one forward predecessor
 *                           that declares none is refused at build time
 *                           ({@code Workflow.Builder}'s control #10) rather than silently
 *                           joined by the placeholder concatenation. Runs on the
 *                           scheduler's control thread, not the pool, so it must be
 *                           cheap: a composer is for <em>shaping</em> the several inputs
 *                           into one, never for the work of deciding between them — that
 *                           belongs in {@link #body()}, which does run on the pool.
 * @param agent              the agent this node runs, when it is agent-shaped;
 *                           {@code null} for an ordinary opaque node. When set,
 *                           {@link #run(String)} executes it and the resulting
 *                           {@link AgentResponse} — not {@link #body()} — is the node's
 *                           outcome, so tokens/cost are captured rather than lost.
 * @param readsRequestMedia  whether this node is handed the attachments of the run's request ({@code AgentTask#media}),
 *                           alongside the text it composes from its edges. Declared, never inferred: the engine has
 *                           no rule of its own about which nodes see a document. Agent-shaped nodes only — an opaque body has no
 *                           channel to receive them. {@code false} for almost every node.
 * @param reads              the shared-state keys this node's agent may read (the read
 *                           side of {@link Write}); empty means "reads nothing" —
 *                           most nodes. Only meaningful for an agent-shaped node: an opaque
 *                           {@link #body()} has no channel to receive state through, so
 *                           {@code Workflow.Builder#reads} refuses it. The agent sees an
 *                           <em>immutable snapshot</em> of just these keys, taken when the
 *                           node is dispatched, through {@code RunContext#state()} — never
 *                           the live shared map, and never auto-interpolated into a prompt.
 */
public record WorkflowNode(
        String id,
        Function<String, String> body,
        Function<String, Set<String>> selector,
        UncertainResumePolicy onUncertainResume,
        Function<String, Spend> cost,
        Write write,
        MapOverSpec mapOver,
        Function<List<String>, String> composer,
        AgentBinding agent,
        Set<String> reads,
        boolean readsRequestMedia
) {

    public WorkflowNode {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(onUncertainResume, "onUncertainResume must not be null");
        reads = reads == null ? Set.of() : Set.copyOf(reads);
    }

    /** Backwards-compatible constructor: a node that does not read the request's attachments. */
    public WorkflowNode(String id, Function<String, String> body, Function<String, Set<String>> selector,
                        UncertainResumePolicy onUncertainResume, Function<String, Spend> cost, Write write,
                        MapOverSpec mapOver, Function<List<String>, String> composer, AgentBinding agent,
                        Set<String> reads) {
        this(id, body, selector, onUncertainResume, cost, write, mapOver, composer, agent, reads, false);
    }

    /** Backwards-compatible constructor: a node that declares no {@link #reads}. */
    public WorkflowNode(String id, Function<String, String> body, Function<String, Set<String>> selector,
                        UncertainResumePolicy onUncertainResume, Function<String, Spend> cost, Write write,
                        MapOverSpec mapOver, Function<List<String>, String> composer, AgentBinding agent) {
        this(id, body, selector, onUncertainResume, cost, write, mapOver, composer, agent, Set.of(), false);
    }

    /** Backwards-compatible constructor: a node that is not agent-shaped (no {@link AgentBinding}). */
    public WorkflowNode(String id, Function<String, String> body, Function<String, Set<String>> selector,
                        UncertainResumePolicy onUncertainResume, Function<String, Spend> cost, Write write,
                        MapOverSpec mapOver, Function<List<String>, String> composer) {
        this(id, body, selector, onUncertainResume, cost, write, mapOver, composer, null);
    }

    /** Backwards-compatible constructor: a node that declares no {@link #composer}. */
    public WorkflowNode(String id, Function<String, String> body, Function<String, Set<String>> selector,
                        UncertainResumePolicy onUncertainResume, Function<String, Spend> cost, Write write,
                        MapOverSpec mapOver) {
        this(id, body, selector, onUncertainResume, cost, write, mapOver, null);
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

    /**
     * An agent-shaped node: {@code binding}'s agent is the node's work, and
     * its {@link AgentResponse} — tokens, cost, content — is what {@link #run(String)}
     * produces. The derived {@link #body()} runs the same agent, so a caller that reaches
     * for the raw function (there is none in the runtime today, but the accessor is public)
     * still gets the agent's content rather than a dead node; the scheduler never calls it
     * for an agent-shaped node, only {@link #run(String)}.
     */
    public static WorkflowNode agent(String id, AgentBinding binding) {
        Objects.requireNonNull(binding, "binding must not be null");
        return new WorkflowNode(id, input -> executeAgent(id, binding, input).content(), null,
                UncertainResumePolicy.RETRY, null, null, null, null, binding);
    }

    /** Returns a copy of this node with its {@link #onUncertainResume} policy replaced. */
    public WorkflowNode withOnUncertainResume(UncertainResumePolicy policy) {
        return new WorkflowNode(id, body, selector, policy, cost, write, mapOver, composer, agent, reads, readsRequestMedia);
    }

    /** Returns a copy of this node with a {@link #cost} function that maps its output to the {@link Spend} it drew. */
    public WorkflowNode withCost(Function<String, Spend> cost) {
        return new WorkflowNode(id, body, selector, onUncertainResume,
                Objects.requireNonNull(cost, "cost must not be null"), write, mapOver, composer, agent, reads, readsRequestMedia);
    }

    /** Returns a copy of this node with a {@link #write} that maps its output to a shared-state entry. */
    public WorkflowNode withWrite(Write write) {
        return new WorkflowNode(id, body, selector, onUncertainResume, cost,
                Objects.requireNonNull(write, "write must not be null"), mapOver, composer, agent, reads, readsRequestMedia);
    }

    /** Returns a copy of this node with a {@link #mapOver} spec. */
    public WorkflowNode withMapOver(MapOverSpec mapOver) {
        return new WorkflowNode(id, body, selector, onUncertainResume, cost, write,
                Objects.requireNonNull(mapOver, "mapOver must not be null"), composer, agent, reads, readsRequestMedia);
    }

    /**
     * Returns a copy of this node with a {@link #composer} — the declared way it combines
     * the tokens of several forward incoming edges, and the opt-in that makes
     * {@code Workflow.Builder}'s control #10 accept this node at all.
     */
    public WorkflowNode withComposer(Function<List<String>, String> composer) {
        return new WorkflowNode(id, body, selector, onUncertainResume, cost, write, mapOver,
                Objects.requireNonNull(composer, "composer must not be null"), agent, reads, readsRequestMedia);
    }

    /**
     * Returns a copy of this node that may read {@code keys} from the run's shared state.
     * Agent-shaped nodes only — see {@link #reads()}.
     *
     * @throws IllegalStateException if this node is not agent-shaped
     */
    public WorkflowNode withReads(Set<String> keys) {
        if (agent == null) {
            throw new IllegalStateException("node '" + id + "' is not agent-shaped: an opaque body has no channel "
                    + "to receive shared state through, so it cannot declare reads");
        }
        return new WorkflowNode(id, body, selector, onUncertainResume, cost, write, mapOver, composer, agent,
                Objects.requireNonNull(keys, "keys must not be null"), readsRequestMedia);
    }

    /**
     * Returns a copy of this node that is handed the attachments of the run's request.
     * Agent-shaped nodes only: the attachments reach the agent as {@code AgentTask#media}.
     *
     * @throws IllegalStateException if this node is not agent-shaped
     */
    public WorkflowNode withReadsRequestMedia() {
        if (agent == null) {
            throw new IllegalStateException("node '" + id + "' is not agent-shaped: an opaque body has no channel "
                    + "to receive the request's attachments, so it cannot declare that it reads them");
        }
        return new WorkflowNode(id, body, selector, onUncertainResume, cost, write, mapOver, composer, agent, reads, true);
    }

    /**
     * The single execution path the scheduler takes, whatever the node's kind. For an
     * opaque node it is
     * just {@link #body()}; for an agent-shaped one it executes the agent and returns its
     * {@link AgentResponse} alongside the content, so the scheduler can charge the budget
     * and {@code WorkflowStrategy} can report the token split without either knowing what
     * kind of node it is.
     *
     * <p>An agent whose {@link AgentResponse} is not a success throws, so the scheduler
     * records a {@link NodeOutcome.Failed} — the same contract {@code AgentPipeline} gives
     * a failed step, rather than a node that "completes" with a failure payload.
     */
    NodeOutput run(String input) {
        return run(input, Map.of());
    }

    /**
     * Like {@link #run(String)}, handing an agent-shaped node the snapshot of the shared
     * state it declared in {@link #reads()}. {@code readState} is what the
     * scheduler captured on its own thread when it dispatched this occurrence — the node
     * never sees the live map. An opaque node ignores it: it can't declare reads.
     */
    NodeOutput run(String input, Map<String, Object> readState) {
        return run(input, readState, List.of());
    }

    /**
     * Like {@link #run(String, Map)}, with the attachments of the run's request: an agent-shaped node that declared {@link
     * #readsRequestMedia()} hands them to its agent on the task; any other node ignores them (the scheduler passes the same
     * list to every node and leaves the decision to the node, which knows its own shape).
     */
    NodeOutput run(String input, Map<String, Object> readState, List<MediaRef> requestMedia) {
        return agent == null ? NodeOutput.of(body.apply(input))
                : executeAgent(id, agent, input, readState, readsRequestMedia ? requestMedia : List.of());
    }

    private static NodeOutput executeAgent(String id, AgentBinding binding, String input) {
        return executeAgent(id, binding, input, Map.of(), List.of());
    }

    private static NodeOutput executeAgent(String id, AgentBinding binding, String input, Map<String, Object> readState,
                                           List<MediaRef> media) {
        AgentTask task = binding.taskFor(input);
        if (!media.isEmpty()) {
            task = task.withMedia(media);
        }
        if (!readState.isEmpty()) {
            task = task.withRunContext(task.runContext().withState(readOnlyView(task.runContext().state(), readState)));
        }
        AgentResponse response = binding.agent().execute(task);
        if (!response.isSuccess()) {
            throw new IllegalStateException("node '" + id + "' agent '" + binding.agent().agentId().value()
                    + "' failed: " + response.failureReason());
        }
        return new NodeOutput(response.content(), response);
    }

    /**
     * The state the agent sees: the declared-read snapshot, read through when a key is
     * absent from the agent's own store, with writes going only to that own store — so the
     * snapshot (and the scheduler's shared map behind it) can never be mutated from inside
     * a node. A task that carried no state of its own ({@code RunState.noop()}) gets a
     * fresh per-occurrence store, so the agent's scratch writes are not silently dropped
     * — they simply never leave this occurrence; the only way a node contributes to shared
     * state is its declared {@link Write}.
     */
    private static RunState readOnlyView(RunState own, Map<String, Object> readState) {
        RunState base = RunState.inMemory();
        readState.forEach(base::put);
        return RunState.overlay(base, own == RunState.noop() ? RunState.inMemory() : own);
    }

    /**
     * One node's contribution to the run's shared state: when this node
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
     * Dynamic fan-out: when the node this is attached to completes, {@link
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
