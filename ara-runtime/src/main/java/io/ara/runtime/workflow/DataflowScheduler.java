package io.ara.runtime.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;

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

    private final Map<WorkflowEdge, Deque<String>> tokens = new LinkedHashMap<>();
    private final Set<WorkflowEdge> dead = new LinkedHashSet<>();
    private final Map<String, Integer> occurrence = new HashMap<>();
    private final List<JournalEntry> journal = new ArrayList<>();

    /**
     * @param maxOccurrences per-node cap on how many times a node may fire in one run;
     *                       exceeding it fails the run instead of spinning forever on a
     *                       malformed cycle. ADR-052 D5's controllo n. 8 turns a missing
     *                       {@code maxVisits} into a build-time error once the facade
     *                       exists; this is the runtime backstop that holds regardless.
     */
    public DataflowScheduler(WorkflowGraph graph, int maxOccurrences) {
        this.graph = graph;
        this.maxOccurrences = maxOccurrences;
    }

    public WorkflowResult run(String initialInput, ExecutorService pool) {
        return run(initialInput, pool, List.of());
    }

    /**
     * Resumes from a prior journal: its entries are replayed — tokens deposited on the
     * edges they selected, the rest marked dead, occurrences restored — without
     * re-executing the nodes they belong to. The run then proceeds normally from
     * wherever that leaves the graph.
     *
     * <p>A node that was in flight when the prior run stopped has no journal entry, so
     * it fires again here: replay can't tell "in flight when we stopped" apart from
     * "never started". That's the one uncomfortable point of D1's resume story per
     * ADR-052, and it's deliberately not papered over here — the fix is a declared
     * {@code onUncertainResume} policy per node, which is the next increment's job, not
     * this one's.
     */
    public WorkflowResult run(String initialInput, ExecutorService pool, List<JournalEntry> priorJournal) {
        graph.edges().forEach(e -> tokens.put(e, new ArrayDeque<>()));
        replay(priorJournal);

        List<String> entryNodes = graph.nodes().stream()
                .map(WorkflowNode::id)
                .filter(id -> graph.in(id).isEmpty())
                .toList();
        if (entryNodes.isEmpty()) {
            return new WorkflowResult(journal, false, "no entry node (every node has an incoming edge)");
        }

        Map<String, String> pendingSeed = new LinkedHashMap<>();
        entryNodes.stream()
                .filter(id -> occurrence.getOrDefault(id, 0) == 0)
                .forEach(id -> pendingSeed.put(id, initialInput));

        return drive(pendingSeed, pool);
    }

    private void replay(List<JournalEntry> priorJournal) {
        for (JournalEntry entry : priorJournal) {
            journal.add(entry);
            occurrence.merge(entry.nodeId(), 1, Integer::sum);
            for (WorkflowEdge edge : graph.out(entry.nodeId())) {
                if (entry.selectedTargets().contains(edge.to())) {
                    tokens.get(edge).addLast(entry.output());
                } else {
                    markDead(edge);
                }
            }
            consumeTokens(entry.nodeId());
        }
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
                    return new WorkflowResult(journal, false, "maxOccurrences exceeded on " + id);
                }
                consumeTokens(id);
                running.add(id);
                inFlight++;
                String firingInput = input;
                completion.submit(() -> fire(node, occ, firingInput));
            }

            if (inFlight == 0) {
                return new WorkflowResult(journal, true, null);
            }

            // Wait for the FIRST to finish, not all of them — the difference from BSP.
            Fired fired;
            try {
                fired = completion.take().get();
            } catch (Exception e) {
                return new WorkflowResult(journal, false, "node execution failed: " + e);
            }
            inFlight--;
            running.remove(fired.nodeId());

            journal.add(new JournalEntry(fired.nodeId(), fired.occurrence(), fired.input(), fired.output(), fired.selectedTargets()));
            for (WorkflowEdge edge : graph.out(fired.nodeId())) {
                if (fired.selectedTargets().contains(edge.to())) {
                    tokens.get(edge).addLast(fired.output());
                } else {
                    markDead(edge);
                }
            }
        }
    }

    private record Fired(String nodeId, int occurrence, String input, String output, List<String> selectedTargets) {}

    private Fired fire(WorkflowNode node, int occurrence, String input) {
        String output = node.body().apply(input);
        List<String> selected = node.selector() == null
                ? graph.out(node.id()).stream().map(WorkflowEdge::to).toList()
                : graph.out(node.id()).stream().map(WorkflowEdge::to)
                        .filter(node.selector().apply(output)::contains).toList();
        return new Fired(node.id(), occurrence, input, output, selected);
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
