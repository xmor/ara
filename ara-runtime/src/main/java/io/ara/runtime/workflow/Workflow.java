package io.ara.runtime.workflow;

import io.ara.core.agent.AgentChain;
import io.ara.core.budget.RunBudget;
import io.ara.core.budget.Spend;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.function.BinaryOperator;
import java.util.function.Function;

/**
 * The builder facade ADR-054 D7 places in {@code ara-runtime} package {@code workflow}: a
 * fluent way to declare a {@link WorkflowGraph} and its run governor without constructing
 * a {@link DataflowScheduler} by hand. This is the {@code .budget(RunBudget.of()...)}
 * entry point ADR-054 D6 writes against.
 *
 * <p>It is deliberately <em>not</em> the {@code AgentPipeline.Builder → WorkflowGraph}
 * compiler (that is the larger ADR-052 D2 job, and needs agent-shaped nodes): nodes here
 * are still plain functions, exactly what {@link DataflowScheduler} takes. What this adds
 * is one place for the graph shape, the per-node {@link WorkflowNode#cost()}, the
 * occurrence cap, and the {@link RunBudget} to be declared together — and, per D7's FF-6,
 * it adds no {@code case}/{@code instanceof} on node type anywhere: it only assembles.
 *
 * <p>A {@link Workflow} is reusable — every {@link #run} builds a fresh single-use
 * scheduler, the invariant {@link DataflowScheduler} documents.
 *
 * <pre>{@code
 * Workflow wf = Workflow.of()
 *         .node("plan",  in -> planFor(in))
 *         .node("exec",  in -> execute(in)).cost("exec", out -> Spend.of(price(out), tokens(out), 1))
 *         .edge("plan", "exec")
 *         .maxOccurrences(50)
 *         .budget(RunBudget.of().maxTokens(200_000).maxCost(2.00).maxActivations(500))
 *         .build();
 *
 * WorkflowResult result = wf.run("goal", pool);
 * }</pre>
 */
public final class Workflow {

    /** Default per-node occurrence cap when {@link Builder#maxOccurrences} is not set. */
    public static final int DEFAULT_MAX_OCCURRENCES = 100;

    private final WorkflowGraph graph;
    private final int maxOccurrences;
    private final RunBudget budget;   // nullable — an ungoverned run

    private Workflow(WorkflowGraph graph, int maxOccurrences, RunBudget budget) {
        this.graph = graph;
        this.maxOccurrences = maxOccurrences;
        this.budget = budget;
    }

    public static Builder of() {
        return new Builder();
    }

    /** Runs the workflow from its entry node(s). */
    public WorkflowResult run(String input, ExecutorService pool) {
        return new DataflowScheduler(graph, maxOccurrences, budget).run(input, pool);
    }

    /** Resumes the workflow from a prior journal (ADR-052 D1) — see {@link DataflowScheduler#run(String, ExecutorService, List)}. */
    public WorkflowResult run(String input, ExecutorService pool, List<JournalEntry> priorJournal) {
        return new DataflowScheduler(graph, maxOccurrences, budget).run(input, pool, priorJournal);
    }

    public WorkflowGraph graph() {
        return graph;
    }

    public int maxOccurrences() {
        return maxOccurrences;
    }

    /** The run governor, if one was declared. */
    public Optional<RunBudget> budget() {
        return Optional.ofNullable(budget);
    }

    /** Fluent builder for {@link Workflow}. Node and edge declaration order is preserved. */
    public static final class Builder {

        private final Map<String, WorkflowNode> nodes = new LinkedHashMap<>();
        private final List<WorkflowEdge> edges = new ArrayList<>();
        private final Set<String> terminals = new LinkedHashSet<>();
        private final Map<String, BinaryOperator<Object>> reducers = new LinkedHashMap<>();
        private int maxOccurrences = DEFAULT_MAX_OCCURRENCES;
        private RunBudget budget;

        private Builder() {}

        /** Adds a plain node: {@code body} maps its composed input to its output. */
        public Builder node(String id, Function<String, String> body) {
            return put(WorkflowNode.of(id, body));
        }

        /**
         * Adds a routing node: {@code selector} picks, from the node's output, the subset
         * of outgoing edge targets to activate.
         */
        public Builder routingNode(String id, Function<String, String> body, Function<String, Set<String>> selector) {
            return put(WorkflowNode.routing(id, body, selector));
        }

        /**
         * Attaches a {@link WorkflowNode#cost()} function to an already-added node: its
         * output to the {@link Spend} the occurrence drew, charged to the {@link #budget}
         * (ADR-054 D6). Without one a node still counts as one activation but adds nothing
         * on the token / money axes.
         */
        public Builder cost(String id, Function<String, Spend> cost) {
            return replace(id, requireNode(id).withCost(cost));
        }

        /** Sets the {@link WorkflowNode#onUncertainResume()} policy of an already-added node. */
        public Builder onUncertainResume(String id, UncertainResumePolicy policy) {
            return replace(id, requireNode(id).withOnUncertainResume(policy));
        }

        /**
         * Attaches a {@link WorkflowNode.Write} to an already-added node: its output maps
         * through {@code extractor} to a value merged into {@code key} in the run's shared
         * state (ADR-052 D3) — the declarative replacement for {@code ara-graph}'s retired
         * {@code SharedWorkspace}. If two or more nodes ever write the same {@code key} in
         * one run, declare {@link #reduce} for it — a second write to a key with no
         * declared reducer fails the run rather than guessing.
         */
        public Builder writes(String id, String key, Function<String, Object> extractor) {
            return replace(id, requireNode(id).withWrite(new WorkflowNode.Write(key, extractor)));
        }

        /**
         * Declares how two writes to the same shared-state {@code key} combine (ADR-052
         * D3) — e.g. {@code reduce("findings", Reducers.concatLists())}. See {@link
         * Reducers} for common combinators.
         */
        public Builder reduce(String key, BinaryOperator<Object> reducer) {
            reducers.put(Objects.requireNonNull(key, "key must not be null"),
                    Objects.requireNonNull(reducer, "reducer must not be null"));
            return this;
        }

        /**
         * Declares {@code sourceId} as a dynamic fan-out source (ADR-052 D4): once it
         * completes, {@code elements} reads a runtime-determined list from its output, and
         * {@code workerBody} runs once per element — each with its own input, individually
         * journaled under {@code workerId}. Equivalent to {@link #mapOver(String, String,
         * Function, Function, String, int, AgentChain.FailurePolicy)} with no {@code
         * collectInto} — the children run and are journaled, but contribute nothing to
         * shared state.
         *
         * @throws IllegalArgumentException if {@code sourceId} was never added via {@link #node}
         */
        public Builder mapOver(String sourceId, String workerId, Function<String, List<String>> elements,
                               Function<String, String> workerBody,
                               int maxActivations, AgentChain.FailurePolicy onPartialFailure) {
            return mapOver(sourceId, workerId, elements, workerBody, null, maxActivations, onPartialFailure);
        }

        /**
         * See {@link #mapOver(String, String, Function, Function, int, AgentChain.FailurePolicy)}.
         * {@code collectInto}, when non-null, is where each child's output lands — as
         * {@code List.of(output)}, merged via {@link Reducers#concatLists()} unless {@link
         * #reduce} already declared a different reducer for that key.
         *
         * <p>{@code workerBody} is a plain function supplied directly, not a reference to
         * an already-{@link #node}-declared id: a node named purely to be a mapOver
         * template, with no incoming edges of its own, would otherwise read as a second
         * entry point to {@link DataflowScheduler} (every node with no incoming edges is
         * seeded and run) — {@code workerId} is bookkeeping for the journal, not a real
         * {@link WorkflowGraph} node.
         *
         * @throws IllegalArgumentException if {@code sourceId} was never added via {@link #node}
         */
        public Builder mapOver(String sourceId, String workerId, Function<String, List<String>> elements,
                               Function<String, String> workerBody, String collectInto,
                               int maxActivations, AgentChain.FailurePolicy onPartialFailure) {
            WorkflowNode source = requireNode(sourceId);
            var spec = new WorkflowNode.MapOverSpec(
                    Objects.requireNonNull(workerId, "workerId must not be null"),
                    elements, workerBody, collectInto, maxActivations, onPartialFailure);
            replace(sourceId, source.withMapOver(spec));
            if (collectInto != null) {
                reducers.putIfAbsent(collectInto, Reducers.concatLists());
            }
            return this;
        }

        /** A forward edge — AND-join semantics at the target (ADR-052 D1). */
        public Builder edge(String from, String to) {
            edges.add(WorkflowEdge.of(from, to));
            return this;
        }

        /** A {@code back} edge — OR-merge semantics, the one place cycles are expressed (ADR-052 D1). */
        public Builder backEdge(String from, String to) {
            edges.add(WorkflowEdge.back(from, to));
            return this;
        }

        /** Per-node occurrence cap — the runtime backstop against a malformed cycle. Must be positive. */
        public Builder maxOccurrences(int maxOccurrences) {
            if (maxOccurrences <= 0) {
                throw new IllegalArgumentException("maxOccurrences must be > 0, got " + maxOccurrences);
            }
            this.maxOccurrences = maxOccurrences;
            return this;
        }

        /** The single run governor (ADR-054 D6). Unset ⇒ the run is ungoverned. */
        public Builder budget(RunBudget budget) {
            this.budget = Objects.requireNonNull(budget, "budget must not be null");
            return this;
        }

        /**
         * Declares {@code ids} as intended exits — the pipeline ends normally once one of
         * them completes and selects no further edge. Purely declarative bookkeeping for
         * {@link #build}'s structural checks (ADR-052 D5): a node with no outgoing edges
         * that was never declared terminal is a dead end (control #3), and a declared
         * terminal is the "exit" every other node must have a path to (controls #1/#2).
         */
        public Builder terminal(String... ids) {
            for (String id : ids) {
                terminals.add(Objects.requireNonNull(id, "terminal id must not be null"));
            }
            return this;
        }

        public Workflow build() {
            if (nodes.isEmpty()) {
                throw new IllegalStateException("a workflow needs at least one node");
            }
            for (String terminal : terminals) {
                if (!nodes.containsKey(terminal)) {
                    throw new IllegalStateException("terminal('" + terminal + "') names a node that was never added");
                }
            }
            // WorkflowGraph enforces referential integrity of the edges.
            WorkflowGraph graph = new WorkflowGraph(List.copyOf(nodes.values()), List.copyOf(edges), Map.copyOf(reducers));
            validateStructure(graph);
            return new Workflow(graph, maxOccurrences, budget);
        }

        // ── ADR-052 D5: structural build-time checks ────────────────────────────
        //
        // Four of the ten controls the ADR names are checkable on today's facade — pure
        // graph shape, nothing more. The other six need a node model this increment
        // deliberately doesn't build: #4 (router shape / a mandatory else-arc) presumes an
        // IntentRouter-like abstraction at this layer, which doesn't exist here (a routing
        // node's selector is an arbitrary function — AgentPipeline's own compiler enforces
        // its equivalent separately, on its own richer step/router model); #5 (HITL
        // presence) and #6 (tool declaration) need agent-shaped nodes carrying an
        // AgentConfig's tags()/enabledTools(), which WorkflowNode does not — it stays a
        // plain Function<String,String> (see WorkflowNode's own Javadoc); #7 (state-key
        // compatibility) needs declared reads/writes per node, which don't exist before
        // ADR-052 D3 gives nodes a RunState channel to declare them against; #8
        // (termination: a back edge needs a declared maxVisits) would require adding a
        // required field to WorkflowEdge, breaking every back edge already built
        // (including AgentPipeline's own compiler) for a guarantee the per-node
        // maxOccurrences runtime backstop already provides today, just coarser-grained.

        private void validateStructure(WorkflowGraph graph) {
            checkDeadEnds(graph);
            checkReachability(graph);
            // #9 before #10: a node with 2+ forward predecessors where one comes from
            // outside its own cycle always also has an ambiguous fan-in (#10 rejects any
            // node with more than one) — running the cycle check first gives that case the
            // more specific, actionable diagnostic instead of the generic fan-in one.
            checkJoinInCycle(graph);
            checkAmbiguousFanIn(graph);
        }

        /**
         * Control #3 — a non-terminal node with no outgoing edges is an error, not an
         * implicit exit. Skipped when no terminal was ever declared: a caller who has not
         * opted into naming exits gets no new build-time behaviour at all — every {@link
         * Workflow} built before this control existed has at least one node with no
         * outgoing edges (that is how a run ends) and none of them called {@link #terminal}.
         */
        private void checkDeadEnds(WorkflowGraph graph) {
            if (terminals.isEmpty()) {
                return;
            }
            for (WorkflowNode node : graph.nodes()) {
                if (graph.out(node.id()).isEmpty() && !terminals.contains(node.id())) {
                    throw new IllegalStateException(
                            "node '" + node.id() + "' has no outgoing edges but was never declared terminal(...) "
                                    + "(ADR-052 D5 control #3) — call terminal(\"" + node.id()
                                    + "\") if ending the run there is intended");
                }
            }
        }

        /**
         * Controls #1/#2 collapsed into one pass: AgentGraph.validate()'s original check
         * (some path from the entry reaches an exit) is subsumed by the stronger AgentProof
         * one (every node, not just the entry, reaches an exit) — if every node can, the
         * entry trivially can too, so there is nothing #1 catches that #2 does not.
         * Skipped entirely when no terminal was declared: with nothing named as an exit,
         * {@link #checkDeadEnds} is the only reachability-adjacent signal there is to give.
         */
        private void checkReachability(WorkflowGraph graph) {
            if (terminals.isEmpty()) {
                return;
            }
            Set<String> canReachATerminal = new HashSet<>(terminals);
            boolean changed = true;
            while (changed) {
                changed = false;
                for (WorkflowEdge edge : graph.edges()) {
                    if (canReachATerminal.contains(edge.to()) && canReachATerminal.add(edge.from())) {
                        changed = true;
                    }
                }
            }
            for (WorkflowNode node : graph.nodes()) {
                if (!canReachATerminal.contains(node.id())) {
                    throw new IllegalStateException(
                            "node '" + node.id() + "' has no path to any declared terminal(...) node "
                                    + "(ADR-052 D5 controls #1/#2) — this is the livelock AgentProof's benchmark "
                                    + "finds and maxOccurrences today only reports after the fact");
                }
            }
        }

        /**
         * Control #10 — a node with more than one <em>forward</em> (non-{@code back})
         * predecessor has no declared way to compose their outputs: D1's join concatenates
         * them with {@code " | "} (see {@code DataflowScheduler#enablingInput}'s own
         * Javadoc), a placeholder its own author calls out, not a policy. Rejected outright
         * until ADR-052 D3 gives a node a declared {@code MergeStrategy} to opt in with.
         */
        private void checkAmbiguousFanIn(WorkflowGraph graph) {
            for (WorkflowNode node : graph.nodes()) {
                long forwardPredecessors = graph.in(node.id()).stream().filter(e -> !e.back()).count();
                if (forwardPredecessors > 1) {
                    throw new IllegalStateException(
                            "node '" + node.id() + "' has " + forwardPredecessors + " forward predecessors with "
                                    + "no declared way to compose them (ADR-052 D5 control #10) — D1's join is a "
                                    + "placeholder concatenation, not a policy; a declared merge strategy is "
                                    + "ADR-052 D3, not available yet");
                }
            }
        }

        /**
         * Control #9 — a node inside a cycle cannot have a forward incoming edge from
         * outside that cycle: on the second lap it would wait for a token from an edge
         * nothing will ever feed again (nothing outside a cycle re-fires once the cycle is
         * running). "Inside a cycle" means mutual reachability — the same strongly
         * connected component, computed here as the intersection of "reachable from" and
         * "reaches" for each node rather than a dedicated Tarjan's pass, since the graphs
         * this targets (tens of nodes) don't warrant one. A node whose only cycle is a bare
         * self-loop is skipped: its own upstream entry edge is the normal, once-only way
         * into that loop, not the hazard this control names. So is a node that has a
         * {@code back} edge of its own: {@code enablingInput}'s OR-merge fires it on that
         * token alone, every lap, never consulting a stale forward edge at all — the
         * hazard is specific to a node whose <em>only</em> way back in is forward AND-join,
         * which a spent, never-repeating external edge then permanently blocks.
         */
        private void checkJoinInCycle(WorkflowGraph graph) {
            for (WorkflowNode node : graph.nodes()) {
                Set<String> forward  = reachableFrom(graph, node.id(), false);
                Set<String> backward = reachableFrom(graph, node.id(), true);
                forward.retainAll(backward);
                if (forward.size() <= 1 || graph.in(node.id()).stream().anyMatch(WorkflowEdge::back)) {
                    continue;
                }
                for (WorkflowEdge incoming : graph.in(node.id())) {
                    if (!incoming.back() && !forward.contains(incoming.from())) {
                        throw new IllegalStateException(
                                "node '" + node.id() + "' is inside a cycle (" + String.join(", ", new TreeSet<>(forward))
                                        + ") but has a forward incoming edge from '" + incoming.from() + "', outside it "
                                        + "(ADR-052 D5 control #9) — on the second lap it would wait for a token that "
                                        + "never arrives; declare that edge backEdge(...) if it re-enters the cycle, "
                                        + "or restructure so the cycle is entered only once");
                    }
                }
            }
        }

        private static Set<String> reachableFrom(WorkflowGraph graph, String start, boolean reversed) {
            Set<String> visited = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>(List.of(start));
            while (!stack.isEmpty()) {
                String id = stack.pop();
                if (!visited.add(id)) {
                    continue;
                }
                for (WorkflowEdge edge : (reversed ? graph.in(id) : graph.out(id))) {
                    stack.push(reversed ? edge.from() : edge.to());
                }
            }
            return visited;
        }

        private Builder put(WorkflowNode node) {
            if (nodes.putIfAbsent(node.id(), node) != null) {
                throw new IllegalArgumentException("duplicate node id: " + node.id());
            }
            return this;
        }

        private Builder replace(String id, WorkflowNode node) {
            nodes.put(id, node);
            return this;
        }

        private WorkflowNode requireNode(String id) {
            WorkflowNode n = nodes.get(id);
            if (n == null) {
                throw new IllegalArgumentException("no node with id '" + id + "' — add it before configuring it");
            }
            return n;
        }
    }
}
