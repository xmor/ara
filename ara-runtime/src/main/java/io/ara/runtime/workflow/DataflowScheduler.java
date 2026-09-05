package io.ara.runtime.workflow;

import io.ara.core.agent.AgentChain;
import io.ara.core.budget.RunBudget;
import io.ara.core.budget.Spend;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BinaryOperator;

/**
 * The scheduler ADR-052 D1 decides on: dataflow with a per-node journal, not superstep
 * (BSP, the model LangGraph and Microsoft's frameworks converge on).
 *
 * <p>A superstep scheduler synchronizes on a global step boundary — every node ready at
 * step N runs, then every node ready at step N+1 runs, and so on. That boundary is where
 * the category's recurring defect lives: on a fan-in whose branches have uneven depth,
 * the short branch's token lands at step N and the long branch's at step N+1, so the join
 * fires once per branch instead of once total, the second time with a partial input (see
 * {@code docs/analysis/spike-adr-052-dataflow/} in ara-private for a side-by-side
 * reproduction of this against the scheduler here, on the same graph).
 *
 * <p>D1's activation rule sidesteps the whole category by asking a different question:
 * not "when did a token arrive" but "which edges carry one". Every node ready by that
 * rule is submitted the instant it's ready, with no step boundary anywhere.
 *
 * <p><b>Concurrency invariant.</b> Every mutation of scheduler state — token deques, the
 * dead-edge set, occurrence counters, the journal — happens on the thread that called
 * {@link #run}, never inside a worker. Workers only evaluate {@link WorkflowNode#body()}
 * and {@link WorkflowNode#selector()}, pure functions of their input. That's what lets
 * this stay lock-free: a sequential control flow around the state eliminates the whole
 * class of races a naive concurrent map would need locking to avoid, and no workflow this
 * targets (tens of nodes, sub-minute bodies) is large enough for that to cost anything
 * real (coding guideline A1).
 *
 * <p>Not a bounded resource pool, and not meant to be one: this class is single-run,
 * single-use — construct one per {@link #run} call.
 */
public final class DataflowScheduler {

    private final WorkflowGraph graph;
    private final int maxOccurrences;
    private final RunBudget budget;   // null = no global cost governor for this run

    private final Map<WorkflowEdge, Deque<String>> tokens = new LinkedHashMap<>();
    private final Set<WorkflowEdge> dead = new LinkedHashSet<>();
    private final Map<String, Integer> occurrence = new HashMap<>();
    private final List<JournalEntry> journal = new ArrayList<>();
    // ADR-052 D3: every WorkflowNode.Write recorded so far, merged through the graph's
    // declared reducers. Mutated only from the thread that called run() — same invariant
    // as every other field above.
    private final Map<String, Object> sharedState = new LinkedHashMap<>();

    /**
     * @param maxOccurrences per-node cap on how many times a node may fire in one run;
     *                       exceeding it fails the run instead of spinning forever on a
     *                       malformed cycle. ADR-052 D5's controllo n. 8 turns a missing
     *                       {@code maxVisits} into a build-time error once the facade
     *                       exists; this is the runtime backstop that holds regardless.
     */
    public DataflowScheduler(WorkflowGraph graph, int maxOccurrences) {
        this(graph, maxOccurrences, null);
    }

    /**
     * @param budget the run's single cost governor (ADR-054 D6), charged once per node
     *               occurrence — one journal entry, one {@link RunBudget#charge}. A node's
     *               {@link WorkflowNode#cost()} supplies the money / token spend; every
     *               occurrence counts as one activation regardless. When a charge reports
     *               a breach the run stops with a {@link WorkflowResult#failureReason()}
     *               naming both the axis and the node that was firing. {@code null} leaves
     *               the run ungoverned (only the per-node {@code maxOccurrences} backstop
     *               applies).
     */
    public DataflowScheduler(WorkflowGraph graph, int maxOccurrences, RunBudget budget) {
        this.graph = graph;
        this.maxOccurrences = maxOccurrences;
        this.budget = budget;
    }

    public WorkflowResult run(String initialInput, ExecutorService pool) {
        return run(initialInput, pool, List.of());
    }

    /**
     * Resumes from a prior journal: {@link JournalEntry.Started} entries restore
     * occurrence counters and consume the tokens that enabled them; a matching {@link
     * JournalEntry.Finished} beyond that deposits tokens on the edges it selected (or
     * marks the rest dead) without re-executing the node. The run then proceeds normally
     * from wherever that leaves the graph.
     *
     * <p>A {@code Started} entry with no matching {@code Finished} is a node that was in
     * flight when the prior run stopped — replay cannot tell that apart from "crashed one
     * instruction before writing its own outcome", which is exactly why it isn't asked
     * to: it defers to that node's declared {@link WorkflowNode#onUncertainResume()}.
     * {@link UncertainResumePolicy#RETRY} re-fires it with its recorded input;
     * {@link UncertainResumePolicy#FAIL} and {@link UncertainResumePolicy#SUSPEND} stop
     * the resume rather than guess, naming the node in {@link WorkflowResult#failureReason()}.
     *
     * <p>A prior journal ending on a {@link NodeOutcome.Failed} or {@link
     * NodeOutcome.Suspended} entry stops the resume the same way — replaying past a
     * recorded failure, or a suspension nothing has decided on, would fabricate progress
     * that never happened.
     */
    public WorkflowResult run(String initialInput, ExecutorService pool, List<JournalEntry> priorJournal) {
        graph.edges().forEach(e -> tokens.put(e, new ArrayDeque<>()));

        Map<String, String> pendingSeed = new LinkedHashMap<>();
        Optional<WorkflowResult> stoppedDuringReplay = replay(priorJournal, pendingSeed);
        if (stoppedDuringReplay.isPresent()) {
            return stoppedDuringReplay.get();
        }

        List<String> entryNodes = graph.nodes().stream()
                .map(WorkflowNode::id)
                .filter(id -> graph.in(id).isEmpty())
                .toList();
        if (entryNodes.isEmpty()) {
            return new WorkflowResult(journal, false, "no entry node (every node has an incoming edge)", sharedState);
        }
        entryNodes.stream()
                .filter(id -> occurrence.getOrDefault(id, 0) == 0)
                .forEach(id -> pendingSeed.put(id, initialInput));

        return drive(pendingSeed, pool);
    }

    /**
     * @return the resume's final result if something in the prior journal (an uncertain
     *         node's policy, or an already-failed/suspended entry) means the run must
     *         stop here instead of proceeding to {@link #drive}
     */
    private Optional<WorkflowResult> replay(List<JournalEntry> priorJournal, Map<String, String> pendingSeed) {
        Set<String> finishedKeys = new HashSet<>();
        for (JournalEntry entry : priorJournal) {
            if (entry instanceof JournalEntry.Finished) {
                finishedKeys.add(entry.nodeId() + "#" + entry.occurrence());
            }
        }

        List<JournalEntry.Started> uncertain = new ArrayList<>();
        for (JournalEntry entry : priorJournal) {
            journal.add(entry);
            if (entry instanceof JournalEntry.Started started) {
                occurrence.merge(started.nodeId(), 1, Integer::sum);
                consumeTokens(started.nodeId());
                if (!finishedKeys.contains(started.nodeId() + "#" + started.occurrence())) {
                    uncertain.add(started);
                }
            } else if (entry instanceof JournalEntry.Finished finished) {
                Optional<WorkflowResult> stopped = applyReplayedOutcome(finished);
                if (stopped.isPresent()) {
                    return stopped;
                }
            }
        }

        for (JournalEntry.Started started : uncertain) {
            Optional<WorkflowResult> stopped = handleUncertain(started, pendingSeed);
            if (stopped.isPresent()) {
                return stopped;
            }
        }
        return Optional.empty();
    }

    private Optional<WorkflowResult> applyReplayedOutcome(JournalEntry.Finished finished) {
        return switch (finished.outcome()) {
            case NodeOutcome.Completed completed -> {
                Optional<WorkflowResult> overspent = charge(finished.nodeId(), finished.occurrence(), completed);
                if (overspent.isPresent()) {
                    yield overspent;
                }
                Optional<WorkflowResult> collided = applyWrite(finished.nodeId(), graph.node(finished.nodeId()).write(), completed);
                if (collided.isPresent()) {
                    yield collided;
                }
                for (WorkflowEdge edge : graph.out(finished.nodeId())) {
                    if (completed.selectedTargets().contains(edge.to())) {
                        tokens.get(edge).addLast(completed.content());
                    } else {
                        markDead(edge);
                    }
                }
                yield Optional.empty();
            }
            case NodeOutcome.Failed failed -> Optional.of(new WorkflowResult(journal, false,
                    entryLabel(finished) + " had already failed in the prior run: " + failed.reason(), sharedState));
            case NodeOutcome.Suspended suspended -> Optional.of(new WorkflowResult(journal, false,
                    entryLabel(finished) + " is suspended awaiting a decision — resuming past a suspension "
                            + "isn't supported until ADR-052 D6: " + suspended.reason(), sharedState));
        };
    }

    private Optional<WorkflowResult> handleUncertain(JournalEntry.Started started, Map<String, String> pendingSeed) {
        UncertainResumePolicy policy = graph.node(started.nodeId()).onUncertainResume();
        return switch (policy) {
            case RETRY -> {
                pendingSeed.put(started.nodeId(), started.input());
                yield Optional.empty();
            }
            case FAIL -> Optional.of(new WorkflowResult(journal, false,
                    entryLabel(started) + " was in flight when the prior run stopped; its onUncertainResume policy is FAIL", sharedState));
            case SUSPEND -> Optional.of(new WorkflowResult(journal, false,
                    entryLabel(started) + " was in flight when the prior run stopped; its onUncertainResume policy is SUSPEND", sharedState));
        };
    }

    private static String entryLabel(JournalEntry entry) {
        return "node " + entry.nodeId() + "#" + entry.occurrence();
    }

    private WorkflowResult drive(Map<String, String> pendingSeed, ExecutorService pool) {
        ExecutorCompletionService<Fired> completion = new ExecutorCompletionService<>(pool);
        Set<String> running = new HashSet<>();
        int inFlight = 0;

        while (true) {
            // Submit everything ready right now — no step boundary to wait for.
            for (WorkflowNode node : graph.nodes()) {
                String id = node.id();
                if (running.contains(id)) {
                    continue;
                }
                String input = pendingSeed.remove(id);
                if (input == null) {
                    input = enablingInput(id);
                }
                if (input == null) {
                    continue;
                }

                int occ = occurrence.merge(id, 1, Integer::sum) - 1;
                if (occ >= maxOccurrences) {
                    return new WorkflowResult(journal, false, "maxOccurrences exceeded on " + id, sharedState);
                }
                consumeTokens(id);
                journal.add(new JournalEntry.Started(id, occ, input));
                running.add(id);
                inFlight++;
                String firingInput = input;
                completion.submit(() -> fire(node, occ, firingInput, pool));
            }

            if (inFlight == 0) {
                return new WorkflowResult(journal, true, null, sharedState);
            }

            // Wait for the FIRST to finish, not all of them — the difference from BSP.
            Fired fired;
            try {
                fired = completion.take().get();
            } catch (Exception e) {
                return new WorkflowResult(journal, false, "node execution failed: " + e, sharedState);
            }
            inFlight--;
            running.remove(fired.nodeId());
            journal.add(new JournalEntry.Finished(fired.nodeId(), fired.occurrence(), fired.input(), fired.outcome()));

            // ADR-054 D6: one journal entry, one charge. The occurrence already ran, so a
            // breach stops the run on this entry rather than mid-node — naming the axis
            // and the node, the "fallisce nominando il costrutto che ha sforato" behaviour.
            NodeOutcome.Completed completedForCharge =
                    fired.outcome() instanceof NodeOutcome.Completed c ? c : null;
            Optional<WorkflowResult> overspent = charge(fired.nodeId(), fired.occurrence(), completedForCharge);
            if (overspent.isPresent()) {
                return overspent.get();
            }

            // ADR-052 D4: journal and collect every dynamic child before the parent's own
            // outcome is processed below — a child is never a real WorkflowGraph node, so
            // it never goes through the normal per-node loop this method's caller runs.
            WorkflowNode.MapOverSpec spec = graph.node(fired.nodeId()).mapOver();
            for (MapOverChildResult child : fired.mapOverChildren()) {
                journal.add(new JournalEntry.Started(child.childId(), 0, child.input()));
                journal.add(new JournalEntry.Finished(child.childId(), 0, child.input(), child.outcome()));
                if (child.outcome() instanceof NodeOutcome.Completed childCompleted && spec.collectInto() != null) {
                    Optional<WorkflowResult> collided = applyWrite(child.childId(),
                            new WorkflowNode.Write(spec.collectInto(), out -> List.of(out)), childCompleted);
                    if (collided.isPresent()) {
                        return collided.get();
                    }
                }
            }

            if (completedForCharge != null) {
                Optional<WorkflowResult> collided = applyWrite(fired.nodeId(), graph.node(fired.nodeId()).write(), completedForCharge);
                if (collided.isPresent()) {
                    return collided.get();
                }
            }

            // Fail-fast: the first Failed or Suspended outcome stops the run immediately,
            // even with other nodes still in flight. Deciding whether a workflow should
            // instead tolerate partial failure is ADR-052 D4's job (AgentChain.FailurePolicy,
            // reused rather than reinvented) — D1 only has to behave safely, not flexibly.
            switch (fired.outcome()) {
                case NodeOutcome.Completed completed -> {
                    for (WorkflowEdge edge : graph.out(fired.nodeId())) {
                        if (completed.selectedTargets().contains(edge.to())) {
                            tokens.get(edge).addLast(completed.content());
                        } else {
                            markDead(edge);
                        }
                    }
                }
                case NodeOutcome.Failed failed -> {
                    return new WorkflowResult(journal, false,
                            "node " + fired.nodeId() + "#" + fired.occurrence() + " failed: " + failed.reason(), sharedState);
                }
                case NodeOutcome.Suspended suspended -> {
                    return new WorkflowResult(journal, false,
                            "node " + fired.nodeId() + "#" + fired.occurrence() + " suspended: " + suspended.reason(), sharedState);
                }
            }
        }
    }

    /** @param mapOverChildren empty unless {@code node} declares a {@link WorkflowNode#mapOver()} (ADR-052 D4) */
    private record Fired(String nodeId, int occurrence, String input, NodeOutcome outcome,
                         List<MapOverChildResult> mapOverChildren) {
        Fired(String nodeId, int occurrence, String input, NodeOutcome outcome) {
            this(nodeId, occurrence, input, outcome, List.of());
        }
    }

    /** One dynamic fan-out activation's outcome (ADR-052 D4) — never a real {@link WorkflowGraph} node. */
    private record MapOverChildResult(String childId, String input, NodeOutcome outcome) {}

    private Fired fire(WorkflowNode node, int occurrence, String input, ExecutorService pool) {
        try {
            String output = node.body().apply(input);

            List<MapOverChildResult> children = List.of();
            if (node.mapOver() != null) {
                WorkflowNode.MapOverSpec spec = node.mapOver();
                List<String> elements = spec.elements().apply(output);
                if (elements.size() > spec.maxActivations()) {
                    return new Fired(node.id(), occurrence, input, new NodeOutcome.Failed(
                            "mapOver('" + node.id() + "') produced " + elements.size()
                                    + " element(s), exceeding maxActivations=" + spec.maxActivations()));
                }
                children = runMapOverChildren(node.id(), occurrence, spec, elements, pool);
                boolean anyChildFailed = children.stream().anyMatch(c -> !(c.outcome() instanceof NodeOutcome.Completed));
                // FAIL_FAST and REQUIRE_ALL both fail the whole group once any child has —
                // see MapOverSpec's own Javadoc for why the two collapse to one behaviour here.
                if (anyChildFailed && spec.onPartialFailure() != AgentChain.FailurePolicy.PARTIAL_OK) {
                    String reason = children.stream()
                            .filter(c -> !(c.outcome() instanceof NodeOutcome.Completed))
                            .map(c -> c.childId() + ": " + describeOutcome(c.outcome()))
                            .collect(java.util.stream.Collectors.joining("; "));
                    return new Fired(node.id(), occurrence, input,
                            new NodeOutcome.Failed("mapOver('" + node.id() + "') child(ren) failed: " + reason), children);
                }
                if (anyChildFailed && children.stream().noneMatch(c -> c.outcome() instanceof NodeOutcome.Completed)) {
                    // PARTIAL_OK still needs at least one success to have anything to report.
                    return new Fired(node.id(), occurrence, input,
                            new NodeOutcome.Failed("mapOver('" + node.id() + "') — every child failed"), children);
                }
            }

            List<String> selected = node.selector() == null
                    ? graph.out(node.id()).stream().map(WorkflowEdge::to).toList()
                    : graph.out(node.id()).stream().map(WorkflowEdge::to)
                            .filter(node.selector().apply(output)::contains).toList();
            return new Fired(node.id(), occurrence, input, new NodeOutcome.Completed(output, selected), children);
        } catch (WorkflowNodeSuspendedException e) {
            return new Fired(node.id(), occurrence, input, new NodeOutcome.Suspended(e.getMessage()));
        } catch (RuntimeException e) {
            return new Fired(node.id(), occurrence, input, new NodeOutcome.Failed(String.valueOf(e.getMessage())));
        }
    }

    private static String describeOutcome(NodeOutcome outcome) {
        return switch (outcome) {
            case NodeOutcome.Failed f -> f.reason();
            case NodeOutcome.Suspended s -> "suspended: " + s.reason();
            case NodeOutcome.Completed c -> c.content();
        };
    }

    /**
     * Runs one activation of {@code spec.workerBody()} per element, concurrently, on
     * {@code pool} — the same pool the scheduler itself submits nodes to. Blocking here
     * (this already runs on a pool thread, inside {@link #fire}) to wait for all of them
     * is the same nested-blocking idiom {@code ReactExecutionSupport.dispatchBounded} uses
     * for parallel tool calls: cheap on virtual threads, and it keeps every mutation of
     * {@link #journal}/{@link #sharedState} on the single controlling thread that calls
     * {@link #run} — this method only computes, it never touches scheduler state.
     */
    private static List<MapOverChildResult> runMapOverChildren(
            String parentId, int parentOccurrence, WorkflowNode.MapOverSpec spec, List<String> elements, ExecutorService pool) {

        List<Future<MapOverChildResult>> futures = new ArrayList<>(elements.size());
        for (int i = 0; i < elements.size(); i++) {
            String childId = spec.workerId() + "[" + parentId + "#" + parentOccurrence + "." + i + "]";
            String elementInput = elements.get(i);
            futures.add(pool.submit(() -> {
                try {
                    String childOutput = spec.workerBody().apply(elementInput);
                    return new MapOverChildResult(childId, elementInput, new NodeOutcome.Completed(childOutput, List.of()));
                } catch (WorkflowNodeSuspendedException e) {
                    return new MapOverChildResult(childId, elementInput, new NodeOutcome.Suspended(e.getMessage()));
                } catch (RuntimeException e) {
                    return new MapOverChildResult(childId, elementInput, new NodeOutcome.Failed(String.valueOf(e.getMessage())));
                }
            }));
        }
        List<MapOverChildResult> results = new ArrayList<>(futures.size());
        for (Future<MapOverChildResult> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception e) {
                results.add(new MapOverChildResult("?", "", new NodeOutcome.Failed("execution error: " + e)));
            }
        }
        return results;
    }

    /**
     * Charges the run budget for one node occurrence (ADR-054 D6). The money / token spend
     * comes from the node's {@link WorkflowNode#cost()} applied to its output — zero for a
     * node that declares none, and zero for one that Failed or Suspended before producing
     * output — while the activation itself always counts as one. Returns a failing {@link
     * WorkflowResult} naming the axis and this node if the charge pushes any axis (or an
     * ancestor {@code HierarchicalBudget}) over its cap; empty when there is no budget or
     * it still fits.
     */
    private Optional<WorkflowResult> charge(String nodeId, int occurrence, NodeOutcome.Completed completed) {
        if (budget == null) {
            return Optional.empty();
        }
        Spend spend = Spend.zero(budget.currency());
        if (completed != null) {
            var cost = graph.node(nodeId).cost();
            if (cost != null) {
                spend = cost.apply(completed.content());
            }
        }
        RunBudget.Charge result = budget.charge(spend);
        if (result instanceof RunBudget.Charge.Exceeded ex) {
            return Optional.of(new WorkflowResult(journal, false,
                    "RunBudget exceeded on " + ex.axis() + " at node " + nodeId + "#" + occurrence + ": " + ex.detail(),
                    sharedState));
        }
        return Optional.empty();
    }

    /**
     * Applies {@code write} (ADR-052 D3) to {@link #sharedState}: a first write to a key
     * is stored as-is; a second one is merged through {@link WorkflowGraph#reducers()}'s
     * entry for that key. A key written twice with no declared reducer is the same
     * ambiguity {@code Workflow.Builder}'s D5 control #10 already refuses to guess at for
     * edges — refused here too, rather than silently picking last-write-wins or losing
     * one write. {@code write} is nullable so a caller with nothing to apply (the common
     * case) doesn't need its own null check.
     */
    private Optional<WorkflowResult> applyWrite(String nodeId, WorkflowNode.Write write, NodeOutcome.Completed completed) {
        if (write == null) {
            return Optional.empty();
        }
        Object value = write.extractor().apply(completed.content());
        String key = write.key();
        if (!sharedState.containsKey(key)) {
            sharedState.put(key, value);
            return Optional.empty();
        }
        BinaryOperator<Object> reducer = graph.reducers().get(key);
        if (reducer == null) {
            return Optional.of(new WorkflowResult(journal, false,
                    "node " + nodeId + " wrote key '" + key + "' but it already had a value and no reducer is "
                            + "declared for it (ADR-052 D3) — call reduce(\"" + key + "\", ...) to say how they combine",
                    sharedState));
        }
        sharedState.put(key, reducer.apply(sharedState.get(key), value));
        return Optional.empty();
    }

    /**
     * D1's activation rule. A node is ready when either:
     * <ul>
     *   <li>a {@code back} edge carries a token — OR-merge, the cycle case: fires without
     *       waiting on anything else, or</li>
     *   <li>every non-{@code back} incoming edge carries a token or is dead, and at least
     *       one carries a token — AND-join, the barrier. It's correct on branches of
     *       uneven depth precisely because it asks <em>which</em> edges hold a token,
     *       never <em>when</em> the token arrived.</li>
     * </ul>
     *
     * <p>The composed input joins every forward edge's token with {@code " | "}, in
     * edge-declaration order — never completion order, which is what makes the join
     * deterministic under concurrency. This is a placeholder join, not a policy: ADR-052
     * D2 replaces it with a declared {@code MergeStrategy} once nodes are agent-shaped
     * and have a real reason to compose differently.
     *
     * @return the composed input, or {@code null} if the node isn't ready yet
     */
    private String enablingInput(String id) {
        List<WorkflowEdge> in = graph.in(id);
        if (in.isEmpty()) {
            return null;
        }

        for (WorkflowEdge back : in.stream().filter(WorkflowEdge::back).toList()) {
            if (!tokens.get(back).isEmpty()) {
                return tokens.get(back).peekFirst();
            }
        }

        List<WorkflowEdge> forward = in.stream().filter(e -> !e.back()).toList();
        if (forward.isEmpty()) {
            return null;
        }

        boolean anyToken = false;
        for (WorkflowEdge edge : forward) {
            boolean hasToken = !tokens.get(edge).isEmpty();
            if (hasToken) {
                anyToken = true;
            } else if (!dead.contains(edge)) {
                return null; // neither a token nor dead: not ready yet
            }
        }
        if (!anyToken) {
            return null; // every forward edge dead: this node is dead too, never fires
        }

        return forward.stream()
                .filter(e -> !tokens.get(e).isEmpty())
                .map(e -> tokens.get(e).peekFirst())
                .reduce((a, b) -> a + " | " + b)
                .orElseThrow();
    }

    private void consumeTokens(String id) {
        List<WorkflowEdge> in = graph.in(id);
        for (WorkflowEdge back : in) {
            if (back.back() && !tokens.get(back).isEmpty()) {
                tokens.get(back).pollFirst();
                return;
            }
        }
        in.stream().filter(e -> !e.back())
                .forEach(e -> { if (!tokens.get(e).isEmpty()) tokens.get(e).pollFirst(); });
    }

    /**
     * Deadness is local, not a global fixpoint: marking one edge dead only ever looks at
     * its own target's incoming edges, and propagates forward from there. An edge is dead
     * once its source has completed without selecting it; a node is dead once every one
     * of its non-{@code back} incoming edges is dead, at which point its outgoing edges
     * are marked dead too. This is an O(edges) incremental closure over the whole run,
     * not a pass repeated to a fixpoint — the thing superstep schedulers need epochs for.
     */
    private void markDead(WorkflowEdge edge) {
        if (!dead.add(edge)) {
            return;
        }
        String target = edge.to();
        List<WorkflowEdge> forwardIn = graph.in(target).stream().filter(e -> !e.back()).toList();
        if (!forwardIn.isEmpty() && forwardIn.stream().allMatch(dead::contains)) {
            graph.out(target).forEach(this::markDead);
        }
    }
}
