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
import java.util.Optional;
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
            return new WorkflowResult(journal, false, "no entry node (every node has an incoming edge)");
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
                    entryLabel(finished) + " had already failed in the prior run: " + failed.reason()));
            case NodeOutcome.Suspended suspended -> Optional.of(new WorkflowResult(journal, false,
                    entryLabel(finished) + " is suspended awaiting a decision — resuming past a suspension "
                            + "isn't supported until ADR-052 D6: " + suspended.reason()));
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
                    entryLabel(started) + " was in flight when the prior run stopped; its onUncertainResume policy is FAIL"));
            case SUSPEND -> Optional.of(new WorkflowResult(journal, false,
                    entryLabel(started) + " was in flight when the prior run stopped; its onUncertainResume policy is SUSPEND"));
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
                    return new WorkflowResult(journal, false, "maxOccurrences exceeded on " + id);
                }
                consumeTokens(id);
                journal.add(new JournalEntry.Started(id, occ, input));
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
            journal.add(new JournalEntry.Finished(fired.nodeId(), fired.occurrence(), fired.input(), fired.outcome()));

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
                            "node " + fired.nodeId() + "#" + fired.occurrence() + " failed: " + failed.reason());
                }
                case NodeOutcome.Suspended suspended -> {
                    return new WorkflowResult(journal, false,
                            "node " + fired.nodeId() + "#" + fired.occurrence() + " suspended: " + suspended.reason());
                }
            }
        }
    }

    private record Fired(String nodeId, int occurrence, String input, NodeOutcome outcome) {}

    private Fired fire(WorkflowNode node, int occurrence, String input) {
        try {
            String output = node.body().apply(input);
            List<String> selected = node.selector() == null
                    ? graph.out(node.id()).stream().map(WorkflowEdge::to).toList()
                    : graph.out(node.id()).stream().map(WorkflowEdge::to)
                            .filter(node.selector().apply(output)::contains).toList();
            return new Fired(node.id(), occurrence, input, new NodeOutcome.Completed(output, selected));
        } catch (WorkflowNodeSuspendedException e) {
            return new Fired(node.id(), occurrence, input, new NodeOutcome.Suspended(e.getMessage()));
        } catch (RuntimeException e) {
            return new Fired(node.id(), occurrence, input, new NodeOutcome.Failed(String.valueOf(e.getMessage())));
        }
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
