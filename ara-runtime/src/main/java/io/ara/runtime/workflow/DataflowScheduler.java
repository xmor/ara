package io.ara.runtime.workflow;

import io.ara.core.agent.AgentChain;
import io.ara.core.budget.RunBudget;
import io.ara.core.budget.Spend;
import io.ara.core.common.Money;

import java.time.Duration;
import java.time.Instant;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BinaryOperator;

/**
 * The scheduler ADR-052 D1 decides on: dataflow with a per-node journal, not superstep
 * (BSP, the model LangGraph and Microsoft's frameworks converge on).
 *
 * <p>A superstep scheduler synchronizes on a global step boundary — every node ready at
 * step N runs, then every node ready at step N+1 runs, and so on. That boundary is where
 * the category's recurring defect lives: on a fan-in whose branches have uneven depth,
 * the short branch's token lands at step N and the long branch's at step N+1, so the join
 * fires once per branch instead of once total, the second time with a partial input. This
 * was reproduced side by side against the scheduler here, on the same graph, before the
 * activation rule below replaced the superstep model.
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
    private final Instant deadline;   // null = no wall-clock bound on this run (P7/U20)

    private final Map<WorkflowEdge, Deque<String>> tokens = new LinkedHashMap<>();
    private final Set<WorkflowEdge> dead = new LinkedHashSet<>();
    private final Map<String, Integer> occurrence = new HashMap<>();
    private final List<JournalEntry> journal = new ArrayList<>();
    // ADR-052 D3: every WorkflowNode.Write recorded so far, merged through the graph's
    // declared reducers. Mutated only from the thread that called run() — same invariant
    // as every other field above.
    private final Map<String, Object> sharedState = new LinkedHashMap<>();
    // Canonical merge order for sharedState (ADR-052 D3): every write kept with its logical
    // clock, and each key re-folded in (clock, declaration order, occurrence, sub) order — so
    // the merged value never depends on which of two concurrent writers finished first.
    private final Map<String, List<Contribution>> contributions = new LinkedHashMap<>();
    // Lamport clock per token and per occurrence ("id#occ"): 1 + the highest clock among the
    // tokens an occurrence consumed. Causally later ⇒ strictly larger clock.
    private final Map<WorkflowEdge, Deque<Integer>> tokenClocks = new LinkedHashMap<>();
    private final Map<String, Integer> clocks = new HashMap<>();
    private final Map<String, Integer> declarationOrder = new HashMap<>();

    /** One write to a shared-state key, with the coordinates that fix its merge position. */
    private record Contribution(int clock, int declarationOrder, int occurrence, int sub, Object value) {}

    private static final java.util.Comparator<Contribution> CANONICAL =
            java.util.Comparator.comparingInt(Contribution::clock)
                    .thenComparingInt(Contribution::declarationOrder)
                    .thenComparingInt(Contribution::occurrence)
                    .thenComparingInt(Contribution::sub);

    /** A graph node's own write sorts after its mapOver children's, as it always has. */
    private static final int OWN_WRITE = Integer.MAX_VALUE;

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
        this(graph, maxOccurrences, budget, null);
    }

    /**
     * @param deadline wall-clock bound on the whole run (P7/U20, 2026-09-23): {@link
     *                 #drive} stops waiting past it and fails the run instead of blocking
     *                 forever on a node (or a {@code mapOver} child) that never completes —
     *                 one of the unbounded-wait holes named elsewhere in this codebase's own
     *                 hardening effort. {@code null} leaves the run unbounded (the original,
     *                 still-default behaviour — every existing caller of the other
     *                 constructors gets this). In-flight work past the deadline is
     *                 abandoned, not cancelled: a node body is an arbitrary {@code
     *                 Function<String,String>} this scheduler does not know is safely
     *                 interruptible, the same "abandon, don't force" choice {@code
     *                 ReactExecutionSupport.runBounded} (U1) already made.
     */
    public DataflowScheduler(WorkflowGraph graph, int maxOccurrences, RunBudget budget, Instant deadline) {
        this.graph = graph;
        this.maxOccurrences = maxOccurrences;
        this.budget = budget;
        this.deadline = deadline;
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
        graph.edges().forEach(e -> tokenClocks.put(e, new ArrayDeque<>()));
        for (int i = 0; i < graph.nodes().size(); i++) {
            declarationOrder.put(graph.nodes().get(i).id(), i);
        }

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
                // A mapOver child's Started (childId is "workerId[parent#occ.i]", never a real
                // graph node id) must not reach the uncertain-node machinery below: its policy
                // lookup graph.node(childId) throws for the very reason the Finished branch
                // was fixed to route children to applyReplayedChildOutcome — a child is not a
                // graph node, so graph.node() cannot be asked about it. Occurrence counting
                // does not apply either: children are always journaled with occurrence 0 and
                // consume no tokens (consumeTokens returns 0 for a child since no edge names
                // it). If its Finished is missing the parent occurrence that spawned it was
                // in flight at crash time — the parent's own Started entry is what carries
                // that uncertainty, via its own onUncertainResume, so the child adds nothing.
                if (isMapOverChild(started.nodeId())) {
                    continue;
                }
                occurrence.merge(started.nodeId(), 1, Integer::sum);
                clocks.put(started.nodeId() + "#" + started.occurrence(), consumeTokens(started.nodeId()) + 1);
                if (!finishedKeys.contains(started.nodeId() + "#" + started.occurrence())) {
                    uncertain.add(started);
                }
            } else if (entry instanceof JournalEntry.Finished finished) {
                Optional<WorkflowResult> stopped = isMapOverChild(finished.nodeId())
                        ? applyReplayedChildOutcome(finished)
                        : applyReplayedOutcome(finished);
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

    /**
     * Recognises a {@code mapOver} child's journal id ({@code workerId[parent#occ.i]}, built in
     * {@link #runMapOverChildren}) by the {@code '['} and {@code ']'} no real node id may
     * carry — {@code WorkflowGraph}'s constructor validates ids only for non-nullness via the
     * edges' referential integrity, but {@code Workflow.Builder.mapOver} requires the workerId
     * to be distinct from every declared node id, and a bracketed id could not be declared as
     * a node without colliding with the builder's own structural checks. Bounded by the same
     * rule: any journal whose child ids stop matching this shape was not written by this
     * scheduler's live path.
     */
    private static boolean isMapOverChild(String nodeId) {
        return nodeId.indexOf('[') >= 0 && nodeId.endsWith("]");
    }

    /**
     * Replays a {@code mapOver} child's journaled {@code Finished} — the resume-side
     * counterpart of the child-journalling loop in {@link #drive}. It must mirror that loop
     * exactly or a resumed run silently diverges from the one it continues: the same
     * "collided result wins" return and the same skip-children-with-no-collectInto. Children
     * never charge the budget here: the parent occurrence's single charge (one journal entry,
     * one charge, ADR-054 D6) already covered the whole fan-out in the live run — the parent's
     * replayed {@code Finished} re-charges it — so charging children again would double-count
     * the fan-out the second time the parent's own entry replays.
     */
    private Optional<WorkflowResult> applyReplayedChildOutcome(JournalEntry.Finished finished) {
        if (!(finished.outcome() instanceof NodeOutcome.Completed completed)) {
            // A failed/suspended child entry replays as nothing: in the live run a child
            // failure only ever surfaces through the parent's own Failed outcome, which is
            // what stops the run — the parent's entry is where the resume hears about it.
            return Optional.empty();
        }
        // childId is workerId + "[" + parentId + "#" + parentOccurrence + "." + sub + "]":
        // the parent lives INSIDE the brackets — the prefix is the workerId, which is not a
        // graph node (mapOver refuses to shadow one). Parsing the prefix as the parent was
        // this fix's own first draft's bug: it made mapOverSpecFor return null for every
        // real child, so replayed children silently contributed nothing.
        int bracket = finished.nodeId().indexOf('[');
        String coords = finished.nodeId().substring(bracket + 1, finished.nodeId().length() - 1);
        int hash = coords.indexOf('#');
        int lastDot = coords.lastIndexOf('.');
        if (hash < 0 || lastDot < hash) {
            return Optional.empty();   // not a child id this scheduler's live path writes
        }
        String parentId = coords.substring(0, hash);
        WorkflowNode.MapOverSpec spec = mapOverSpecFor(parentId);
        if (spec == null || spec.collectInto() == null) {
            return Optional.empty();
        }
        int parentOccurrence;
        int sub;
        try {
            parentOccurrence = Integer.parseInt(coords.substring(hash + 1, lastDot));
            sub = Integer.parseInt(coords.substring(lastDot + 1));
        } catch (NumberFormatException e) {
            return Optional.empty();   // same bound: unparseable coordinates, not our shape
        }
        return applyWrite(parentId, parentOccurrence, sub,
                new WorkflowNode.Write(spec.collectInto(), out -> List.of(out)), completed);
    }

    /**
     * Resolves the {@code MapOverSpec} of the {@code mapOver} source node {@code parentId}
     * belongs to. Returns {@code null} when the parent is unknown to this graph (a journal
     * from a different workflow) — callers then skip the child rather than crash.
     */
    private WorkflowNode.MapOverSpec mapOverSpecFor(String parentId) {
        if (declarationOrder.containsKey(parentId)) {
            return graph.node(parentId).mapOver();
        }
        return null;
    }

    private Optional<WorkflowResult> applyReplayedOutcome(JournalEntry.Finished finished) {
        return switch (finished.outcome()) {
            case NodeOutcome.Completed completed -> {
                Optional<WorkflowResult> overspent = charge(finished.nodeId(), finished.occurrence(), completed);
                if (overspent.isPresent()) {
                    yield overspent;
                }
                Optional<WorkflowResult> collided = applyWrite(finished.nodeId(), finished.occurrence(), OWN_WRITE,
                        graph.node(finished.nodeId()).write(), completed);
                if (collided.isPresent()) {
                    yield collided;
                }
                propagateCompletion(finished.nodeId(), finished.occurrence(), completed);
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
                clocks.put(id + "#" + occ, consumeTokens(id) + 1);
                journal.add(new JournalEntry.Started(id, occ, input));
                running.add(id);
                inFlight++;
                String firingInput = input;
                // Captured HERE, on the control thread that owns sharedState — never read from
                // the pool worker, which would race the control thread's own writes.
                Map<String, Object> readSnapshot = snapshotReads(node);
                final String fInput = firingInput;
                final Map<String, Object> fSnapshot = readSnapshot;
                final ExecutorService fPool = pool;
                completion.submit(() -> fire(node, occ, fInput, fSnapshot, fPool));
            }

            if (inFlight == 0) {
                return new WorkflowResult(journal, true, null, sharedState);
            }

            // Wait for the FIRST to finish, not all of them — the difference from BSP.
            Fired fired;
            try {
                fired = awaitNext(completion).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new WorkflowResult(journal, false, "run interrupted while waiting for a node to finish", sharedState);
            } catch (TimeoutException e) {
                return new WorkflowResult(journal, false, "deadline exceeded: " + e.getMessage(), sharedState);
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
            int childIndex = 0;
            for (MapOverChildResult child : fired.mapOverChildren()) {
                int sub = childIndex++;
                journal.add(new JournalEntry.Started(child.childId(), 0, child.input()));
                journal.add(new JournalEntry.Finished(child.childId(), 0, child.input(), child.outcome()));
                if (child.outcome() instanceof NodeOutcome.Completed childCompleted && spec != null && spec.collectInto() != null) {
                    Optional<WorkflowResult> collided = applyWrite(fired.nodeId(), fired.occurrence(), sub,
                            new WorkflowNode.Write(spec.collectInto(), out -> List.of(out)), childCompleted);
                    if (collided.isPresent()) {
                        return collided.get();
                    }
                }
            }

            if (completedForCharge != null) {
                Optional<WorkflowResult> collided = applyWrite(fired.nodeId(), fired.occurrence(), OWN_WRITE,
                        graph.node(fired.nodeId()).write(), completedForCharge);
                if (collided.isPresent()) {
                    return collided.get();
                }
            }

            // Fail-fast: the first Failed or Suspended outcome stops the run immediately,
            // even with other nodes still in flight. Deciding whether a workflow should
            // instead tolerate partial failure is ADR-052 D4's job (AgentChain.FailurePolicy,
            // reused rather than reinvented) — D1 only has to behave safely, not flexibly.
            switch (fired.outcome()) {
                case NodeOutcome.Completed completed ->
                        propagateCompletion(fired.nodeId(), fired.occurrence(), completed);
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

    /**
     * P7/U20, 2026-09-23: {@link #deadline}-bounded replacement for a bare {@code
     * completion.take()}, which blocked forever if no node ever finished — a hung node
     * body (or {@code mapOver} child, see {@link #runMapOverChildren}) meant the whole run
     * never returned. {@code null} {@link #deadline} keeps the original, still-default
     * unbounded behaviour.
     *
     * @throws TimeoutException the deadline is already past, or no node finished before it
     */
    private Future<Fired> awaitNext(ExecutorCompletionService<Fired> completion) throws InterruptedException, TimeoutException {
        if (deadline == null) {
            return completion.take();
        }
        long remainingMs = Duration.between(Instant.now(), deadline).toMillis();
        if (remainingMs <= 0) {
            throw new TimeoutException("deadline already passed");
        }
        Future<Fired> future = completion.poll(remainingMs, TimeUnit.MILLISECONDS);
        if (future == null) {
            throw new TimeoutException("no node finished within " + remainingMs + "ms");
        }
        return future;
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

    private Fired fire(WorkflowNode node, int occurrence, String input, Map<String, Object> readState, ExecutorService pool) {
        try {
            // node.run(input) is the single execution path (ADR-052 D2): for an opaque node
            // it is body(), for an agent-shaped one it executes the agent and hands back its
            // AgentResponse, so the scheduler never branches on the node's kind (FF-6).
            NodeOutput out = node.run(input, readState);
            String output = out.content();

            List<MapOverChildResult> children = List.of();
            if (node.mapOver() != null) {
                WorkflowNode.MapOverSpec spec = node.mapOver();
                List<String> elements = spec.elements().apply(output);
                if (elements.size() > spec.maxActivations()) {
                    return new Fired(node.id(), occurrence, input, new NodeOutcome.Failed(
                            "mapOver('" + node.id() + "') produced " + elements.size()
                                    + " element(s), exceeding maxActivations=" + spec.maxActivations()));
                }
                children = runMapOverChildren(node.id(), occurrence, spec, elements, pool, deadline);
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
            return new Fired(node.id(), occurrence, input, new NodeOutcome.Completed(output, selected, out.response()), children);
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
            String parentId, int parentOccurrence, WorkflowNode.MapOverSpec spec, List<String> elements,
            ExecutorService pool, Instant deadline) {

        List<MapOverChildTask> tasks = new ArrayList<>(elements.size());
        List<Future<MapOverChildResult>> futures = new ArrayList<>(elements.size());
        for (int i = 0; i < elements.size(); i++) {
            String childId = spec.workerId() + "[" + parentId + "#" + parentOccurrence + "." + i + "]";
            String elementInput = elements.get(i);
            MapOverChildTask task = new MapOverChildTask(childId, elementInput);
            tasks.add(task);
            futures.add(pool.submit(() -> {
                try {
                    String childOutput = spec.workerBody().apply(elementInput);
                    return new MapOverChildResult(childId, elementInput, new NodeOutcome.Completed(childOutput, List.of()));
                } catch (WorkflowNodeSuspendedException e) {
                    return new MapOverChildResult(childId, elementInput, new NodeOutcome.Suspended(e.getMessage()));
                } catch (RuntimeException e) {
                    return new MapOverChildResult(childId, elementInput, new NodeOutcome.Failed(String.valueOf(e.getMessage())));
                } finally {
                    // P7/U20: defensive interrupt-flag hygiene for a caller-supplied pool
                    // that might reuse platform threads (the two production callers —
                    // WorkflowStrategy, AgentPipeline — both use a virtual-thread-per-task
                    // executor, where this is moot: every task gets a brand-new Thread, so
                    // there is no prior task's flag to leak in the first place; Workflow's
                    // public run(..., ExecutorService) accepts any executor, though).
                    Thread.interrupted();
                }
            }));
        }
        List<MapOverChildResult> results = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            Future<MapOverChildResult> future = futures.get(i);
            MapOverChildTask task = tasks.get(i);
            try {
                // P7/U20: bounded by the same run deadline as drive()'s own wait — a
                // hung child no longer blocks this loop (and every other in-flight
                // child's result behind it) forever.
                results.add(deadline == null
                        ? future.get()
                        : future.get(Math.max(0, Duration.between(Instant.now(), deadline).toMillis()), TimeUnit.MILLISECONDS));
            } catch (InterruptedException e) {
                // E5: handle interrupts honestly — restore the interrupt flag and record failure
                Thread.currentThread().interrupt();
                results.add(new MapOverChildResult(task.childId(), task.elementInput(),
                        new NodeOutcome.Failed("execution error: " + e)));
            } catch (Exception e) {
                results.add(new MapOverChildResult(task.childId(), task.elementInput(),
                        new NodeOutcome.Failed("execution error: " + e)));
            }
        }
        return results;
    }

    /** Holds child task metadata for accurate error reporting during mapOver execution. */
    private static record MapOverChildTask(String childId, String elementInput) {}

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
            if (completed.response() != null) {
                // Agent-shaped node (ADR-052 D2): the AgentResponse is the source of truth
                // for what the occurrence drew — a caller-declared cost() on the same node
                // would only under- or over-state it. Money is re-based onto the budget's
                // currency when the response's differs, rather than letting Money.plus
                // surface a currency mismatch as a run failure.
                var response = completed.response();
                Money money = response.estimatedCost().currency().equals(budget.currency())
                        ? response.estimatedCost()
                        : Money.zero(budget.currency());
                spend = Spend.of(money, response.totalTokens(), 1);
            } else {
                var cost = graph.node(nodeId).cost();
                if (cost != null) {
                    spend = cost.apply(completed.content());
                }
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
     * ADR-052 D3, read side: an immutable copy of just the keys {@code node} declared in
     * {@link WorkflowNode#reads()}, as they stand when the node is dispatched. A declared key
     * nothing has written yet is simply absent — the agent sees an empty {@code Optional}, not
     * an error (whether some node can ever write it is a build-time question, control #7).
     *
     * <p>Every write a forward predecessor made has already been applied by now: writes land on
     * this same control thread when a node's {@code Finished} entry is processed, and an AND-join
     * only dispatches once all its forward predecessors have finished. A concurrent,
     * <em>non-ancestor</em> writer may or may not have landed — which is why a node should read
     * keys its ancestors write, the case control #7 checks for.
     */
    private Map<String, Object> snapshotReads(WorkflowNode node) {
        if (node.reads().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (String key : node.reads()) {
            if (sharedState.containsKey(key)) {
                snapshot.put(key, sharedState.get(key));
            }
        }
        return java.util.Collections.unmodifiableMap(snapshot);
    }

    /**
     * Applies {@code write} (ADR-052 D3) to {@link #sharedState}. A key written once holds
     * that value; a key written again is folded through {@link WorkflowGraph#reducers()}'s
     * entry for it, over <em>every</em> write so far in canonical order ({@link #CANONICAL}):
     * causal order first, and — between writers that are concurrent — declaration order,
     * never completion order. The same rule D1 applies to a join's inputs, applied to state,
     * so a non-commutative reducer ({@code concatLists}) gives the same result on every run.
     *
     * <p>A key written twice with no declared reducer is refused rather than guessed at —
     * the same ambiguity control #10 refuses for edges. {@code write} is nullable so a
     * caller with nothing to apply needs no null check of its own.
     *
     * @param nodeId     the graph node the write belongs to (a mapOver child's parent)
     * @param sub        a mapOver child's index, or {@link #OWN_WRITE} for the node's own write
     */
    private Optional<WorkflowResult> applyWrite(String nodeId, int occurrence, int sub,
                                                 WorkflowNode.Write write, NodeOutcome.Completed completed) {
        if (write == null) {
            return Optional.empty();
        }
        String key = write.key();
        List<Contribution> written = contributions.computeIfAbsent(key, k -> new ArrayList<>());
        BinaryOperator<Object> reducer = graph.reducers().get(key);
        if (!written.isEmpty() && reducer == null) {
            return Optional.of(new WorkflowResult(journal, false,
                    "node " + nodeId + " wrote key '" + key + "' but it already had a value and no reducer is "
                            + "declared for it (ADR-052 D3) — call reduce(\"" + key + "\", ...) to say how they combine",
                    sharedState));
        }
        written.add(new Contribution(clockOf(nodeId, occurrence), declarationOrder.getOrDefault(nodeId, Integer.MAX_VALUE),
                occurrence, sub, write.extractor().apply(completed.content())));
        written.sort(CANONICAL);
        Object merged = written.getFirst().value();
        for (int i = 1; i < written.size(); i++) {
            merged = reducer.apply(merged, written.get(i).value());
        }
        sharedState.put(key, merged);
        return Optional.empty();
    }

    private int clockOf(String nodeId, int occurrence) {
        return clocks.getOrDefault(nodeId + "#" + occurrence, 1);
    }

    private void deposit(WorkflowEdge edge, String content, int clock) {
        tokens.get(edge).addLast(content);
        tokenClocks.get(edge).addLast(clock);
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
     * <p>The composed input takes every forward edge's token in edge-declaration order —
     * never completion order, which is what makes the join deterministic under
     * concurrency — and combines them with the node's declared {@link
     * WorkflowNode#composer()}, falling back to joining them with {@code " | "} when none
     * is declared. That fallback is a placeholder, not a policy, and it is only ever
     * reached by a single-predecessor node: {@code Workflow.Builder}'s control #10 refuses
     * to build a multi-predecessor node that declares no composer, so nothing silently
     * relies on the placeholder to mean something.
     *
     * @return the composed input, or {@code null} if the node isn't ready yet
     */
    private String enablingInput(String id) {
        List<WorkflowEdge> in = graph.in(id);
        if (in.isEmpty()) {
            return null;
        }

        // One pass over the incoming edges instead of streaming `in` twice: a back edge
        // with a token fires immediately (OR-merge), the forward edges are collected for
        // the AND-join check below.
        List<WorkflowEdge> forward = new ArrayList<>(in.size());
        for (WorkflowEdge edge : in) {
            if (edge.back()) {
                if (!tokens.get(edge).isEmpty()) {
                    return tokens.get(edge).peekFirst();
                }
            } else {
                forward.add(edge);
            }
        }
        if (forward.isEmpty()) {
            return null;
        }

        // Ready when every forward edge carries a token or is dead, and at least one does.
        // The composed input keeps edge-declaration order; build it in the same pass.
        List<String> ordered = new ArrayList<>(forward.size());
        boolean anyToken = false;
        for (WorkflowEdge edge : forward) {
            if (!tokens.get(edge).isEmpty()) {
                anyToken = true;
                ordered.add(tokens.get(edge).peekFirst());
            } else if (!dead.contains(edge)) {
                return null; // neither a token nor dead: not ready yet
            }
        }
        if (!anyToken) {
            return null; // every forward edge dead: this node is dead too, never fires
        }

        java.util.function.Function<List<String>, String> composer = graph.node(id).composer();
        if (composer == null) {
            return String.join(" | ", ordered);
        }
        String composed = composer.apply(ordered);
        if (composed == null) {
            // Returning null here would read as "not ready yet" and stall the node forever —
            // a silent hang is the worst possible answer to a caller's bug, so it's loud.
            throw new IllegalStateException(
                    "composer for node '" + id + "' returned null for " + ordered.size() + " input(s)");
        }
        return composed;
    }

    /** Consumes the tokens that enabled {@code id}; returns the highest clock among them (0 if none). */
    private int consumeTokens(String id) {
        List<WorkflowEdge> in = graph.in(id);
        for (WorkflowEdge back : in) {
            if (back.back() && !tokens.get(back).isEmpty()) {
                tokens.get(back).pollFirst();
                Integer clock = tokenClocks.get(back).pollFirst();
                return clock == null ? 0 : clock;
            }
        }
        int max = 0;
        for (WorkflowEdge edge : in) {
            if (!edge.back() && !tokens.get(edge).isEmpty()) {
                tokens.get(edge).pollFirst();
                Integer clock = tokenClocks.get(edge).pollFirst();
                max = Math.max(max, clock == null ? 0 : clock);
            }
        }
        return max;
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
        boolean anyForward = false;
        boolean allForwardDead = true;
        for (WorkflowEdge in : graph.in(target)) {
            if (in.back()) continue;
            anyForward = true;
            if (!dead.contains(in)) {
                allForwardDead = false;
                break;
            }
        }
        if (anyForward && allForwardDead) {
            graph.out(target).forEach(this::markDead);
        }
    }

    /**
     * Fans a completed node's outcome out across its outgoing edges: every edge the node
     * selected receives the content as a token stamped with this occurrence's clock, and
     * every edge it did not select is marked dead (which {@link #markDead} then propagates
     * forward). A node that selects nothing therefore kills its whole fan-out, which is
     * what makes a conditional branch a real branch rather than a pass-through.
     *
     * <p>Called from two places that must agree: the live path in {@link #drive} and the
     * resume path in {@link #applyReplayedOutcome}. They are the same computation on the
     * same inputs — replaying a journalled completion has to leave exactly the edge state
     * the original run left, or a resumed workflow silently diverges from the execution it
     * is continuing, and the divergence only shows up as a wrong answer much later. One
     * method rather than two copies is what keeps that true as the rule evolves; it is not
     * a DRY preference, it is the invariant.
     *
     * <p>The clock is read once for all outgoing edges instead of per edge: it's a pure
     * lookup keyed by this occurrence, and neither {@link #deposit} nor {@link #markDead}
     * writes it, so hoisting it changes nothing but the number of map lookups.
     */
    private void propagateCompletion(String nodeId, int occurrence, NodeOutcome.Completed completed) {
        int clock = clockOf(nodeId, occurrence);
        for (WorkflowEdge edge : graph.out(nodeId)) {
            if (completed.selectedTargets().contains(edge.to())) {
                deposit(edge, completed.content(), clock);
            } else {
                markDead(edge);
            }
        }
    }
}
