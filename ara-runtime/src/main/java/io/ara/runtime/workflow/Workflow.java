package io.ara.runtime.workflow;

import io.ara.core.agent.AgentChain;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.budget.RunBudget;
import io.ara.core.budget.Spend;
import io.ara.core.media.MediaRef;

import java.time.Instant;
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
 * The builder facade of the {@code workflow} package in {@code ara-runtime}: a fluent way
 * to declare a {@link WorkflowGraph} and its run governor together, without constructing a
 * {@link DataflowScheduler} by hand — the {@code .budget(RunBudget.of()...)} entry point.
 *
 * <p>It is deliberately <em>not</em> the {@code AgentPipeline.Builder → WorkflowGraph}
 * compiler (that larger job lives in {@code AgentPipeline}): a node here is either a plain
 * function ({@link Builder#node}) or an <b>agent-shaped</b> node ({@link Builder#agent}),
 * which runs a real {@code AraAgent} and captures its {@code AgentResponse} so token/cost
 * reach the journal. What this adds is one place for the graph shape, the per-node {@link
 * WorkflowNode#cost()}, the occurrence cap, and the {@link RunBudget} to be declared
 * together — and it adds no {@code case}/{@code instanceof} on node type anywhere: it only
 * assembles, so no pattern or node kind can grow a special case inside the scheduler.
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

    /** Runs the workflow from its entry node(s), with no wall-clock bound. */
    public WorkflowResult run(String input, ExecutorService pool) {
        RunBudget run = freshBudget();
        return withSpend(new DataflowScheduler(graph, maxOccurrences, run).run(input, pool), run);
    }

    /**
     * Like {@link #run(String, ExecutorService)}, bounded by {@code deadline} (P7/U20,
     * 2026-09-23) — see {@link DataflowScheduler}'s own deadline constructor Javadoc for
     * what happens past it. {@code null} is the same as the unbounded overload above.
     */
    public WorkflowResult run(String input, ExecutorService pool, Instant deadline) {
        RunBudget run = freshBudget();
        return withSpend(new DataflowScheduler(graph, maxOccurrences, run, deadline).run(input, pool), run);
    }

    /**
     * Runs the workflow on a request that carries attachments, the same way an agent's request does. They reach the nodes that declared {@link
     * Builder#readsRequestMedia}; with none declared the run is refused before any node starts — see {@link
     * DataflowScheduler#run(String, List, ExecutorService, List)}.
     */
    public WorkflowResult run(String input, List<MediaRef> media, ExecutorService pool) {
        return run(input, media, pool, List.of(), null);
    }

    /** Like {@link #run(String, List, ExecutorService)}, bounded by {@code deadline} (null is unbounded). */
    public WorkflowResult run(String input, List<MediaRef> media, ExecutorService pool, Instant deadline) {
        return run(input, media, pool, List.of(), deadline);
    }

    /** Like {@link #run(String, List, ExecutorService)}, resuming from a prior journal: the request is presented again, whole. */
    public WorkflowResult run(String input, List<MediaRef> media, ExecutorService pool, List<JournalEntry> priorJournal) {
        return run(input, media, pool, priorJournal, null);
    }

    /** The general form of the overloads with attachments; the others delegate to it. */
    public WorkflowResult run(String input, List<MediaRef> media, ExecutorService pool, List<JournalEntry> priorJournal,
                              Instant deadline) {
        RunBudget run = freshBudget();
        return withSpend(new DataflowScheduler(graph, maxOccurrences, run, deadline).run(input, media, pool, priorJournal), run);
    }

    /** Resumes the workflow from a prior journal — see {@link DataflowScheduler#run(String, ExecutorService, List)}. */
    public WorkflowResult run(String input, ExecutorService pool, List<JournalEntry> priorJournal) {
        RunBudget run = freshBudget();
        return withSpend(new DataflowScheduler(graph, maxOccurrences, run).run(input, pool, priorJournal), run);
    }

    /** Like {@link #run(String, ExecutorService, List)}, bounded by {@code deadline} — see {@link #run(String, ExecutorService, Instant)}. */
    public WorkflowResult run(String input, ExecutorService pool, List<JournalEntry> priorJournal, Instant deadline) {
        RunBudget run = freshBudget();
        return withSpend(new DataflowScheduler(graph, maxOccurrences, run, deadline).run(input, pool, priorJournal), run);
    }

    /**
     * The budget declared on the builder is a <em>template</em>: every run gets its own copy
     * with a zeroed tally, so a reusable {@code Workflow} — above all one hosted as an agent
     * that serves every call, possibly concurrently — never starts a run with another run's
     * spend already counted. Spend that should aggregate across runs belongs on a {@link
     * io.ara.core.budget.HierarchicalBudget} parent ({@code RunBudget.Builder#reportingTo}),
     * which {@link RunBudget#fresh()} keeps shared.
     */
    private RunBudget freshBudget() {
        return budget == null ? null : budget.fresh();
    }

    private static WorkflowResult withSpend(WorkflowResult result, RunBudget run) {
        return run == null ? result : result.withGovernedSpend(run.spent());
    }

    public WorkflowGraph graph() {
        return graph;
    }

    public int maxOccurrences() {
        return maxOccurrences;
    }

    /** The run governor declared on the builder — the template each run copies, never a live tally. */
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
         * Adds an <b>agent-shaped node</b>: the node's work is {@code agent},
         * executed as {@code agent.execute(AgentTask.of(input))}, and the node's outcome is
         * its {@link io.ara.core.agent.AgentResponse} — so token usage and cost are captured
         * in the journal and reach {@code WorkflowResult}/{@code WorkflowStrategy} instead
         * of vanishing into a plain string. A non-success response fails the node, the same
         * contract {@code AgentPipeline} gives a failed step.
         *
         * <p>Use the {@link #agent(String, AraAgent, Function)} overload when the agent's
         * task must carry something the raw input string does not — a {@code RunContext},
         * attachments, a session id.
         */
        public Builder agent(String id, AraAgent agent) {
            return agent(id, agent, null);
        }

        /**
         * See {@link #agent(String, AraAgent)}. {@code taskShaper} maps the node's composed
         * input to the agent's {@link AgentTask}; {@code null} means the plain
         * {@code AgentTask.of(input)}.
         */
        public Builder agent(String id, AraAgent agent, Function<String, AgentTask> taskShaper) {
            Objects.requireNonNull(agent, "agent must not be null");
            return put(WorkflowNode.agent(id, new AgentBinding(agent, taskShaper)));
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
         * output to the {@link Spend} the occurrence drew, charged to the run's single
         * {@link #budget}. Without one a node still counts as one activation but adds
         * nothing on the token / money axes.
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
         * state — the declarative replacement for {@code ara-graph}'s retired
         * {@code SharedWorkspace}. If two or more nodes ever write the same {@code key} in
         * one run, declare {@link #reduce} for it — a second write to a key with no
         * declared reducer fails the run rather than guessing.
         */
        public Builder writes(String id, String key, Function<String, Object> extractor) {
            return replace(id, requireNode(id).withWrite(new WorkflowNode.Write(key, extractor)));
        }

        /**
         * Declares the shared-state keys an already-added <b>agent-shaped</b> node reads
         * (the read side of {@link #writes}). At dispatch the node's agent gets an
         * immutable snapshot of exactly these keys through {@code task.runContext().state()};
         * anything else in the shared state is invisible to it. The value is never
         * interpolated into a prompt — the agent (or its {@code PromptShaper}) projects it
         * explicitly, the leak-safety rule {@code RunState} already sets.
         *
         * <p>Checked in {@link #build}: every key must be written by some node that
         * <em>precedes</em> this one (control #7), so a typo in a key fails the build instead
         * of quietly handing the agent an empty {@code Optional}. Declare {@link #reduce} for a
         * key several nodes write, and the reader after their join sees the reduced value.
         *
         * @throws IllegalArgumentException if {@code id} was never added, is not agent-shaped
         *                                  (an opaque body has no channel to receive state),
         *                                  or a key is null or blank
         */
        public Builder reads(String id, String... keys) {
            WorkflowNode node = requireNode(id);
            if (node.agent() == null) {
                throw new IllegalArgumentException("node '" + id + "' is not agent-shaped — only a node added with "
                        + "agent(...) can declare reads(...): an opaque body has no channel to receive shared state");
            }
            Set<String> declared = new LinkedHashSet<>(node.reads());
            for (String key : keys) {
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("reads('" + id + "', ...) has a null or blank key");
                }
                declared.add(key);
            }
            return replace(id, node.withReads(declared));
        }

        /**
         * Declares that an agent-shaped node is handed the attachments of the run's request, as {@code
         * AgentTask#media}, on every occurrence. Nothing else is: a node that does not declare it never sees them, and the engine
         * has no rule of its own about which nodes should.
         *
         * @throws IllegalArgumentException if {@code id} was never added, or is not agent-shaped (an opaque body has no channel
         *                                  to receive them)
         */
        public Builder readsRequestMedia(String id) {
            WorkflowNode node = requireNode(id);
            if (node.agent() == null) {
                throw new IllegalArgumentException("node '" + id + "' is not agent-shaped — only a node added with "
                        + "agent(...) can declare readsRequestMedia(): an opaque body has no channel to receive attachments");
            }
            return replace(id, node.withReadsRequestMedia());
        }

        /**
         * Declares how an already-added node combines the tokens of its several forward
         * incoming edges into its single input — the "declared way to compose them" that
         * control #10 refuses to invent on a caller's behalf.
         * The list arrives in edge-declaration order, never completion order.
         *
         * <p>It runs on the scheduler's control thread, so keep it cheap — shaping several
         * inputs into one, never the work of choosing between them. A judge that compares
         * N candidates does the comparing in its {@code body}, which runs on the pool; its
         * composer only hands it the N candidates in one piece (see {@code
         * io.ara.runtime.workflow.patterns.Tournament}).
         *
         * @throws IllegalArgumentException if {@code id} was never added via {@link #node}
         */
        public Builder composer(String id, Function<List<String>, String> composer) {
            return replace(id, requireNode(id).withComposer(composer));
        }

        /**
         * Compiles a declarative pattern spec into this builder: patterns are specs that
         * compile onto {@code WorkflowGraph}, never special node types in the scheduler.
         * Each spec carries its own build-time checks and adds only nodes, edges and the
         * per-node properties declared above — which is what keeps the scheduler free of
         * any {@code case}/{@code instanceof} on node type, so no pattern can grow a
         * special case inside it.
         */
        public Builder pattern(WorkflowPattern pattern) {
            Objects.requireNonNull(pattern, "pattern must not be null").compileInto(this);
            return this;
        }

        /**
         * Declares how two writes to the same shared-state {@code key} combine — e.g.
         * {@code reduce("findings", Reducers.concatLists())}. See {@link
         * Reducers} for common combinators.
         */
        public Builder reduce(String key, BinaryOperator<Object> reducer) {
            reducers.put(Objects.requireNonNull(key, "key must not be null"),
                    Objects.requireNonNull(reducer, "reducer must not be null"));
            return this;
        }

        /**
         * Declares {@code sourceId} as a dynamic fan-out source: once it
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

        /** A forward edge — AND-join semantics at the target. */
        public Builder edge(String from, String to) {
            edges.add(WorkflowEdge.of(from, to));
            return this;
        }

        /** A {@code back} edge — OR-merge semantics, the one place cycles are expressed. */
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

        /** The single run governor. Unset ⇒ the run is ungoverned. */
        public Builder budget(RunBudget budget) {
            this.budget = Objects.requireNonNull(budget, "budget must not be null");
            return this;
        }

        /**
         * Declares {@code ids} as intended exits — the pipeline ends normally once one of
         * them completes and selects no further edge. Purely declarative bookkeeping for
         * {@link #build}'s structural checks: a node with no outgoing edges
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

        // ── Structural build-time checks ─────────────────────────────────────────
        //
        // Of the structural controls this package names, what today's facade can check
        // is graph shape plus declared state keys (#7). The rest need what this
        // increment deliberately doesn't build: #4 (router shape / a mandatory else-arc)
        // presumes an IntentRouter-like abstraction at this layer, which doesn't exist
        // here (a routing node's selector is an arbitrary function — AgentPipeline's own
        // compiler enforces its equivalent separately, on its own richer step/router
        // model); #5 (HITL presence) and #6 (tool declaration) need agent-shaped nodes
        // carrying an AgentConfig's tags()/enabledTools(). Those nodes now exist
        // (Builder#agent): WorkflowNode#agent() exposes the binding, so the controls can
        // read binding.agent().config().tags()/enabledTools(). They are not written yet
        // because they are a separate increment — the node model was the blocker, and it
        // is removed; the checks themselves remain to come; #7 (state-key compatibility)
        // is checked below now that nodes can declare reads; #8 (termination: a back edge
        // needs a declared maxVisits) would require adding a required field to
        // WorkflowEdge, breaking every back edge already built (including AgentPipeline's
        // own compiler) for a guarantee the per-node maxOccurrences runtime backstop
        // already provides today, just coarser-grained.

        private void validateStructure(WorkflowGraph graph) {
            checkDeadEnds(graph);
            checkReachability(graph);
            // #9 before #10: a node with 2+ forward predecessors where one comes from
            // outside its own cycle always also has an ambiguous fan-in (#10 rejects any
            // node with more than one) — running the cycle check first gives that case the
            // more specific, actionable diagnostic instead of the generic fan-in one.
            checkJoinInCycle(graph);
            checkAmbiguousFanIn(graph);
            checkStateKeyCompatibility(graph);
        }

        /**
         * Control #7 — a node that {@link #reads} a shared-state key must be preceded by some
         * node that writes it. Nothing seeds the shared state from outside a run, so a key with
         * no writer upstream is empty every time: a typo, or a writer wired downstream of its
         * reader, that would otherwise surface only as an agent quietly working without data.
         *
         * <p>It also refuses a writer <em>concurrent</em> with the reader — neither precedes the
         * other: whether its write has landed when the reader starts would depend on timing.
         * Writers that are ancestors are fine however many there are and however they
         * interleave: the scheduler merges them in a canonical order (see {@code
         * DataflowScheduler#applyWrite}), and all of them have landed before the reader runs.
         *
         * <p>A writer is a node with a {@link WorkflowNode#write()} on that key, or a {@code
         * mapOver} source that {@code collectInto}s it (its children write as part of its
         * activation). "Precedes" means a path of at least one edge — so a node reading a key it
         * writes itself counts only when a cycle brings it back to itself, the loop-refinement
         * case, and not merely because it wrote the key on this very occurrence.
         */
        private void checkStateKeyCompatibility(WorkflowGraph graph) {
            for (WorkflowNode reader : graph.nodes()) {
                for (String key : new TreeSet<>(reader.reads())) {
                    List<String> writers = graph.nodes().stream()
                            .filter(n -> writesKey(n, key))
                            .map(WorkflowNode::id)
                            .toList();
                    if (writers.stream().noneMatch(w -> precedes(graph, w, reader.id()))) {
                        throw new IllegalStateException(
                                "node '" + reader.id() + "' reads state key '" + key + "' but no node that writes it "
                                        + "precedes it (control #7) — "
                                        + (writers.isEmpty()
                                                ? "nothing writes '" + key + "' at all; check the key name or add writes(...)"
                                                : "it is written only by " + writers
                                                        + ", none of which has a path to '" + reader.id() + "'"));
                    }
                    for (String writer : writers) {
                        if (!writer.equals(reader.id())
                                && !precedes(graph, writer, reader.id())
                                && !precedes(graph, reader.id(), writer)) {
                            throw new IllegalStateException(
                                    "node '" + reader.id() + "' reads state key '" + key + "', which '" + writer
                                            + "' also writes on a branch concurrent with it (control #7) — "
                                            + "whether that write has landed when '" + reader.id() + "' starts depends on "
                                            + "timing; connect '" + writer + "' upstream of '" + reader.id()
                                            + "' or give it a key of its own");
                        }
                    }
                }
            }
        }

        private static boolean writesKey(WorkflowNode node, String key) {
            return (node.write() != null && node.write().key().equals(key))
                    || (node.mapOver() != null && key.equals(node.mapOver().collectInto()));
        }

        /** Whether a path of at least one edge leads from {@code from} to {@code to}. */
        private static boolean precedes(WorkflowGraph graph, String from, String to) {
            Set<String> visited = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>();
            graph.out(from).forEach(e -> stack.push(e.to()));
            while (!stack.isEmpty()) {
                String id = stack.pop();
                if (id.equals(to)) {
                    return true;
                }
                if (visited.add(id)) {
                    graph.out(id).forEach(e -> stack.push(e.to()));
                }
            }
            return false;
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
                                    + " (control #3) — call terminal(\"" + node.id()
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
                                    + " (controls #1/#2) — this is the livelock AgentProof's benchmark "
                                    + "finds and maxOccurrences today only reports after the fact");
                }
            }
        }

        /**
         * Control #10 — a node with more than one <em>forward</em> (non-{@code back})
         * predecessor has no declared way to compose their outputs: the scheduler's join
         * concatenates
         * them with {@code " | "} (see {@code DataflowScheduler#enablingInput}'s own
         * Javadoc), a placeholder its own author calls out, not a policy.
         *
         * <p>Opting in is what {@link #composer} is for: a node that declares
         * one has said how its several inputs combine, so there is nothing left to guess
         * and nothing to refuse. Without one the node is still rejected — the default
         * stays "refuse rather than silently concatenate", which is the behaviour every
         * graph built before composers existed already relies on.
         */
        private void checkAmbiguousFanIn(WorkflowGraph graph) {
            for (WorkflowNode node : graph.nodes()) {
                if (node.composer() != null) {
                    continue;
                }
                long forwardPredecessors = graph.in(node.id()).stream().filter(e -> !e.back()).count();
                if (forwardPredecessors > 1) {
                    throw new IllegalStateException(
                            "node '" + node.id() + "' has " + forwardPredecessors + " forward predecessors with "
                                    + "no declared way to compose them (control #10) — the default join is a "
                                    + "placeholder concatenation, not a policy; declare composer(\"" + node.id()
                                    + "\", ...) to say how they combine");
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
                                        + " (control #9) — on the second lap it would wait for a token that "
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
