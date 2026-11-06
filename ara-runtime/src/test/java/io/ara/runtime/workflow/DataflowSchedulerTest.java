package io.ara.runtime.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-052 D1's gate of Fase 1, ported from a standalone spike to production code: the
 * same 19 assertions, now exercising {@link DataflowScheduler} instead of the spike's copy
 * of it, plus the second half of D1 the spike deliberately left untested — {@code
 * onUncertainResume} and the {@link NodeOutcome.Failed}/{@link NodeOutcome.Suspended}
 * outcomes the two-phase journal exists to make resumable-or-not, on purpose. No facade,
 * no build-time checks, no {@code AraAgent} — those are later increments.
 */
class DataflowSchedulerTest {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void simpleDiamond_joinFiresOnce_withBothInputs() {
        WorkflowGraph diamond = new WorkflowGraph(
                List.of(echo("split", "S"), echo("a", "A"), echo("b", "B"), join("join")),
                List.of(WorkflowEdge.of("split", "a"), WorkflowEdge.of("split", "b"),
                        WorkflowEdge.of("a", "join"), WorkflowEdge.of("b", "join")));

        WorkflowResult result = run(diamond);

        assertEquals(1, result.firedTimes("join"), "join should fire exactly once: " + result.order());
        assertEquals("A | B", result.firstOf("join").input());
    }

    /**
     * The test that substantiates ADR-052's whole thesis: same graph, same nodes, same
     * latencies, only the scheduler differs. On branches of uneven depth, D1's rule fires
     * the join once with both inputs; the edge-triggered comparison fires it twice, the
     * first time with a partial input — see {@link SuperstepScheduler}.
     */
    @Test
    void unevenBranches_dataflowFiresJoinOnce_supersteFiresTwice() throws Exception {
        WorkflowGraph uneven = unevenBranchesGraph();

        WorkflowResult dataflow = run(uneven);
        assertEquals(1, dataflow.firedTimes("join"),
                "D1 should fire the join once: " + dataflow.order());
        assertEquals("A | C", dataflow.firstOf("join").input());

        WorkflowResult superstep = new SuperstepScheduler(uneven).run("start", pool);
        assertEquals(2, superstep.firedTimes("join"),
                "the edge-triggered comparison is expected to fire the join twice — "
                        + "that's the defect D1 exists to avoid, not a bug in the fixture: "
                        + superstep.order());
    }

    @Test
    void nestedFanOut_joinFiresOnce_withAllThreeInputs() {
        WorkflowGraph nested = nestedFanOutGraph();

        WorkflowResult result = run(nested);

        assertEquals(1, result.firedTimes("join"), "join should fire once with 3 inputs: " + result.order());
        assertEquals("C | D | B", result.firstOf("join").input());
    }

    @Test
    void deadBranch_joinDoesNotWaitForIt_andDeadnessPropagatesTransitively() {
        WorkflowResult result = run(conditionalBranchGraph(null));

        assertEquals(1, result.firedTimes("join"), "join should not wait on the dead branch: " + result.order());
        assertEquals("A", result.firstOf("join").input(), "join should see only the live branch");
        assertTrue(result.firedTimes("b") == 0 && result.firedTimes("c") == 0,
                "transitive deadness: b and c should never fire — journal " + result.order());
    }

    @Test
    void cyclesAsOccurrences_outerLoopThreeTimes_withANestedInnerLoop() {
        AtomicInteger outer = new AtomicInteger();
        AtomicInteger inner = new AtomicInteger();
        WorkflowGraph loops = new WorkflowGraph(
                List.of(echo("start", "S"),
                        WorkflowNode.of("L", in -> "L" + outer.incrementAndGet()),
                        WorkflowNode.routing("I", in -> "I" + inner.incrementAndGet(),
                                out -> inner.get() % 2 == 0 ? Set.of("E") : Set.of("I")),
                        WorkflowNode.routing("E", in -> "E",
                                out -> outer.get() < 3 ? Set.of("L") : Set.of("done")),
                        echo("done", "DONE")),
                List.of(WorkflowEdge.of("start", "L"), WorkflowEdge.of("L", "I"),
                        WorkflowEdge.back("I", "I"),
                        WorkflowEdge.of("I", "E"),
                        WorkflowEdge.back("E", "L"),
                        WorkflowEdge.of("E", "done")));

        WorkflowResult result = run(loops);

        assertTrue(result.ok(), "run should terminate: " + result.failureReason());
        assertEquals(3, result.firedTimes("L"), "outer loop should run 3 times: " + result.order());
        assertTrue(result.firedTimes("I") >= 6,
                "OR-merge on the back edge should fire without waiting on the forward edge: " + result.order());
        assertEquals(1, result.firedTimes("done"), "the nested cycle should need no special-casing");
    }

    @Test
    void concurrentRuns_produceTheSameJoinInput_everyTime() {
        WorkflowGraph nested = nestedFanOutGraph();

        Set<String> distinctJoinInputs = new LinkedHashSet<>();
        for (int i = 0; i < 30; i++) {
            distinctJoinInputs.add(run(nested).firstOf("join").input());
        }

        assertEquals(1, distinctJoinInputs.size(),
                "the join's input must be identical on every run: observed " + distinctJoinInputs);
    }

    @Test
    void resume_replaysTheJournalWithoutReexecutingCompletedNodes() {
        WorkflowResult full = run(unevenBranchesGraph());
        List<JournalEntry> truncated = truncateAfterFinished(full, "a");

        // A fresh graph/counter map so these counts reflect ONLY what the resume itself
        // executes — reusing the graph from the full run would double-count nodes that
        // legitimately fire again during resume (b, c, join), masking whether split and a
        // (which must NOT fire again) actually stayed at zero.
        Map<String, AtomicInteger> resumeCalls = new ConcurrentHashMap<>();
        WorkflowGraph freshGraph = unevenBranchesGraph(resumeCalls);
        WorkflowResult resumed = new DataflowScheduler(freshGraph, 10).run("start", pool, truncated);

        assertTrue(resumed.ok() && resumed.firedTimes("join") == 1,
                "resume should complete the run: ok=" + resumed.ok() + " journal=" + resumed.order());
        assertTrue(resumeCalls.getOrDefault("split", new AtomicInteger()).get() == 0
                        && resumeCalls.getOrDefault("a", new AtomicInteger()).get() == 0,
                "split and a were already completed and must not run again during resume: " + resumeCalls);
        assertEquals(full.firstOf("join").input(), resumed.firstOf("join").input(),
                "resume must produce the same result as the uninterrupted run");
        List<String> finishedKeys = resumed.journal().stream()
                .filter(JournalEntry.Finished.class::isInstance)
                .map(e -> e.nodeId() + "#" + e.occurrence())
                .toList();
        assertEquals(finishedKeys.size(), finishedKeys.stream().distinct().count(),
                "no node occurrence should finish twice: " + resumed.order());
    }

    @Test
    void resume_replaysAConditionalCompletion_withTheSameDeadnessAsTheLiveRun() {
        WorkflowResult full = run(conditionalBranchGraph(null));
        // Crash the instant `split` finishes, so its Completed entry — the one carrying the
        // selection that kills b and c — is the last thing the resume has to replay. The
        // other resume test truncates after a plain `echo` node, which selects every target
        // and therefore never reaches the markDead branch at all: without this test nothing
        // pins the resume path to the live path for an unselected edge.
        List<JournalEntry> truncated = truncateAfterFinished(full, "split");

        Map<String, AtomicInteger> resumeCalls = new ConcurrentHashMap<>();
        WorkflowResult resumed = new DataflowScheduler(conditionalBranchGraph(resumeCalls), 10)
                .run("start", pool, truncated);

        assertTrue(resumed.ok(), "resume should complete the run: " + resumed.failureReason());
        assertEquals(0, resumeCalls.getOrDefault("split", new AtomicInteger()).get(),
                "split already completed and must not run again on resume: " + resumeCalls);
        assertEquals(0, resumed.firedTimes("b"),
                "resume must not revive the branch split did not select: " + resumed.order());
        assertEquals(0, resumed.firedTimes("c"),
                "nor anything reachable only through it: " + resumed.order());
        assertEquals(1, resumed.firedTimes("join"),
                "a revived edge would leave the join waiting on a branch that never runs: " + resumed.order());
        assertEquals(full.firstOf("join").input(), resumed.firstOf("join").input(),
                "replaying a conditional completion must leave the same edge state as the live run");
    }

    @Test
    void uncertainResume_retryPolicy_reexecutesTheNodeThatWasInFlightAtCrashTime() {
        WorkflowResult full = run(linearGraph(UncertainResumePolicy.RETRY, null));
        List<JournalEntry> truncated = truncateAfterStarted(full, "slow");

        Map<String, AtomicInteger> resumeCalls = new ConcurrentHashMap<>();
        WorkflowGraph freshGraph = linearGraph(UncertainResumePolicy.RETRY, resumeCalls);
        WorkflowResult resumed = new DataflowScheduler(freshGraph, 10).run("start", pool, truncated);

        assertTrue(resumed.ok(), "RETRY should let the resume complete: " + resumed.failureReason());
        assertEquals(1, resumed.firedTimes("done"));
        assertTrue(resumeCalls.getOrDefault("slow", new AtomicInteger()).get() >= 1,
                "RETRY must re-execute the node that was only 'started' when the prior run stopped");
    }

    @Test
    void uncertainResume_failPolicy_stopsTheResumeInstead_ofGuessingWhatHappened() {
        WorkflowGraph graph = linearGraph(UncertainResumePolicy.FAIL, null);
        WorkflowResult full = run(graph);
        List<JournalEntry> truncated = truncateAfterStarted(full, "slow");

        WorkflowResult resumed = new DataflowScheduler(graph, 10).run("start", pool, truncated);

        assertFalse(resumed.ok(), "FAIL must not silently retry a node with an unknown outcome");
        assertTrue(resumed.failureReason().contains("slow") && resumed.failureReason().contains("FAIL"),
                "failureReason should name the node and its policy: " + resumed.failureReason());
        assertEquals(0, resumed.firedTimes("done"), "the run must not proceed past the uncertain node");
    }

    @Test
    void uncertainResume_suspendPolicy_stopsTheResumeAwaitingADecision() {
        WorkflowGraph graph = linearGraph(UncertainResumePolicy.SUSPEND, null);
        WorkflowResult full = run(graph);
        List<JournalEntry> truncated = truncateAfterStarted(full, "slow");

        WorkflowResult resumed = new DataflowScheduler(graph, 10).run("start", pool, truncated);

        assertFalse(resumed.ok());
        assertTrue(resumed.failureReason().contains("SUSPEND"), resumed.failureReason());
        assertEquals(0, resumed.firedTimes("done"));
    }

    /**
     * Resuming a run that crashed mid-{@code mapOver} used to throw {@code
     * IllegalArgumentException: unknown node: worker[plan#0.0]}: a child's journal entries
     * (childId {@code workerId[parent#occ.i]}) fell through to {@code graph.node()}, which
     * only knows real nodes — both for a child {@code Finished} (through
     * {@code applyReplayedOutcome}) and for a child {@code Started} with no matching {@code
     * Finished} (through the uncertain-node policy lookup). The resume now routes children
     * to their own replay: a child Completed entry re-deposits its {@code collectInto}
     * contribution; a child Started-only defers to the parent's own {@code
     * onUncertainResume}.
     *
     * <p>The mid-children truncation is a snapshot taken between the child journal writes
     * (drive journals every child's Started+Finished in one tight loop right after the
     * parent's Finished). A child whose entries the snapshot misses has no trace: it ran,
     * but the parent occurrence already has its journalled Finished and will not re-run, so
     * its contribution is lost — the journal-order tradeoff documented on
     * {@code applyReplayedChildOutcome}'s sibling comment in the scheduler. This test pins
     * the honest semantics: no crash, replayed children deposit exactly once each.
     */
    @Test
    void resume_midMapOver_replaysChildEntriesWithoutCrashing_andStillCollects() {
        WorkflowResult full = run(mapOverGraph("ok1,ok2,ok3"));

        // Crash snapshot taken after the second child's Finished: the replay re-deposits the
        // two journalled children's contributions, then the live path finishes the rest.
        List<JournalEntry> truncated = truncateAfterNthChildFinished(full, 2);

        WorkflowResult resumed = new DataflowScheduler(mapOverGraph("ok1,ok2,ok3"), 10)
                .run("start", pool, truncated);

        assertTrue(resumed.ok(), "resume mid-mapOver should complete: " + resumed.failureReason());
        @SuppressWarnings("unchecked")
        List<Object> findings = (List<Object>) resumed.state().get("findings");
        assertEquals(List.of("OK1", "OK2"), findings,
                "the two journalled children must collect exactly once each; the third's "
                        + "entries were lost with the crash and the parent does not re-run");

        // A child Started with no Finished (crash snapshot taken between the two journal
        // writes) must not reach the uncertain-node machinery either — its policy lookup
        // graph.node(childId) was the second crash site.
        List<JournalEntry> startedOnly = truncateAfterNthChildStarted(full, 1);
        WorkflowResult resumedFromStartedOnly = new DataflowScheduler(
                mapOverGraph("ok1,ok2,ok3"), 10).run("start", pool, startedOnly);
        assertTrue(resumedFromStartedOnly.ok(),
                "a child Started without Finished must not crash the resume: " + resumedFromStartedOnly.failureReason());
    }

    /** {@code start -> plan -> mapOver(worker) -> collector}, elements as a parameter. The
     *  concatLists reducer for {@code findings} mirrors what {@code Workflow.Builder.mapOver}
     *  registers automatically — a graph built directly must declare it the same way. */
    private WorkflowGraph mapOverGraph(String elements) {
        return new WorkflowGraph(
                List.of(echo("start", "S", null),
                        WorkflowNode.of("plan", in -> elements)
                                .withMapOver(new WorkflowNode.MapOverSpec("worker",
                                        planOutput -> List.of(planOutput.split(",")),
                                        element -> element.toUpperCase(java.util.Locale.ROOT),
                                        "findings", 10, io.ara.core.agent.AgentChain.FailurePolicy.FAIL_FAST)),
                        echo("collector", "C", null)),
                List.of(WorkflowEdge.of("start", "plan"), WorkflowEdge.of("plan", "collector")),
                Map.of("findings", Reducers.concatLists()));
    }

    /** The journal prefix ending right after the {@code n}-th {@code worker[...]} Finished entry. */
    private static List<JournalEntry> truncateAfterNthChildFinished(WorkflowResult result, int n) {
        List<JournalEntry> journal = result.journal();
        int seen = 0;
        for (int i = 0; i < journal.size(); i++) {
            if (journal.get(i) instanceof JournalEntry.Finished f && f.nodeId().startsWith("worker[")) {
                seen++;
                if (seen == n) {
                    return journal.subList(0, i + 1);
                }
            }
        }
        throw new IllegalStateException("only " + seen + " child Finished entries, needed " + n + ": " + result.order());
    }

    /** The journal prefix ending right after the {@code n}-th {@code worker[...]} Started entry. */
    private static List<JournalEntry> truncateAfterNthChildStarted(WorkflowResult result, int n) {
        List<JournalEntry> journal = result.journal();
        int seen = 0;
        for (int i = 0; i < journal.size(); i++) {
            if (journal.get(i) instanceof JournalEntry.Started s && s.nodeId().startsWith("worker[")) {
                seen++;
                if (seen == n) {
                    return journal.subList(0, i + 1);
                }
            }
        }
        throw new IllegalStateException("only " + seen + " child Started entries, needed " + n + ": " + result.order());
    }

    @Test
    void nodeThrows_isRecordedAsFailed_andStopsTheRun() {
        WorkflowGraph graph = new WorkflowGraph(
                List.of(echo("start", "S"), WorkflowNode.of("boom", in -> { throw new RuntimeException("kaboom"); })),
                List.of(WorkflowEdge.of("start", "boom")));

        WorkflowResult result = run(graph);

        assertFalse(result.ok());
        assertTrue(result.failureReason().contains("kaboom"), result.failureReason());
    }

    @Test
    void nodeThrowsSuspended_isRecordedAsSuspended_notAsAFailure() {
        WorkflowGraph graph = new WorkflowGraph(
                List.of(echo("start", "S"),
                        WorkflowNode.of("gate", in -> { throw new WorkflowNodeSuspendedException("awaiting approval"); })),
                List.of(WorkflowEdge.of("start", "gate")));

        WorkflowResult result = run(graph);

        assertFalse(result.ok());
        assertTrue(result.failureReason().contains("suspended") && result.failureReason().contains("awaiting approval"),
                result.failureReason());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private WorkflowResult run(WorkflowGraph graph) {
        return new DataflowScheduler(graph, 10).run("start", pool);
    }

    /** {@code split} selects only {@code "a"}, so {@code split -> b -> c} dies when {@code split} finishes. */
    private WorkflowGraph conditionalBranchGraph(Map<String, AtomicInteger> callCounts) {
        WorkflowNode split = WorkflowNode.routing("split", in -> {
            if (callCounts != null) {
                callCounts.computeIfAbsent("split", k -> new AtomicInteger()).incrementAndGet();
            }
            return "S";
        }, out -> Set.of("a"));
        return new WorkflowGraph(
                List.of(split, echo("a", "A", callCounts), echo("b", "B", callCounts),
                        echo("c", "C", callCounts), join("join")),
                List.of(WorkflowEdge.of("split", "a"), WorkflowEdge.of("split", "b"),
                        WorkflowEdge.of("a", "join"), WorkflowEdge.of("b", "c"), WorkflowEdge.of("c", "join")));
    }

    /** {@code split -> a -> join} (short) versus {@code split -> b -> c -> join} (long). */
    private WorkflowGraph unevenBranchesGraph() {
        return unevenBranchesGraph(null);
    }

    private WorkflowGraph unevenBranchesGraph(Map<String, AtomicInteger> callCounts) {
        return new WorkflowGraph(
                List.of(echo("split", "S", callCounts), delayedEcho("a", "A", 10, callCounts),
                        delayedEcho("b", "B", 60, callCounts), delayedEcho("c", "C", 10, callCounts),
                        join("join")),
                List.of(WorkflowEdge.of("split", "a"), WorkflowEdge.of("split", "b"),
                        WorkflowEdge.of("a", "join"), WorkflowEdge.of("b", "c"), WorkflowEdge.of("c", "join")));
    }

    /** {@code split -> {a, b}}, {@code a -> {c, d}}, and {@code c, d, b -> join}. */
    private WorkflowGraph nestedFanOutGraph() {
        return new WorkflowGraph(
                List.of(echo("split", "S"), delayedEcho("a", "A", 5, null), delayedEcho("b", "B", 40, null),
                        delayedEcho("c", "C", 5, null), delayedEcho("d", "D", 20, null), join("join")),
                List.of(WorkflowEdge.of("split", "a"), WorkflowEdge.of("split", "b"),
                        WorkflowEdge.of("a", "c"), WorkflowEdge.of("a", "d"),
                        WorkflowEdge.of("c", "join"), WorkflowEdge.of("d", "join"), WorkflowEdge.of("b", "join")));
    }

    /** {@code start -> slow -> done}, where {@code slow}'s resume policy is the parameter under test. */
    private WorkflowGraph linearGraph(UncertainResumePolicy policy, Map<String, AtomicInteger> callCounts) {
        WorkflowNode slow = delayedEcho("slow", "SLOW", 30, callCounts).withOnUncertainResume(policy);
        return new WorkflowGraph(
                List.of(echo("start", "S"), slow, WorkflowNode.of("done", in -> "DONE[" + in + "]")),
                List.of(WorkflowEdge.of("start", "slow"), WorkflowEdge.of("slow", "done")));
    }

    /** The journal prefix ending right after {@code nodeId}'s {@link JournalEntry.Finished} entry. */
    private static List<JournalEntry> truncateAfterFinished(WorkflowResult result, String nodeId) {
        List<JournalEntry> journal = result.journal();
        for (int i = 0; i < journal.size(); i++) {
            if (journal.get(i) instanceof JournalEntry.Finished finished && finished.nodeId().equals(nodeId)) {
                return journal.subList(0, i + 1);
            }
        }
        throw new IllegalStateException(nodeId + " never finished in this journal: " + result.order());
    }

    /**
     * The journal prefix ending right after {@code nodeId}'s {@link JournalEntry.Started}
     * entry, dropping its {@code Finished} entry (and anything after) — simulating a crash
     * while that node was in flight.
     */
    private static List<JournalEntry> truncateAfterStarted(WorkflowResult result, String nodeId) {
        List<JournalEntry> journal = result.journal();
        for (int i = 0; i < journal.size(); i++) {
            if (journal.get(i) instanceof JournalEntry.Started started && started.nodeId().equals(nodeId)) {
                return journal.subList(0, i + 1);
            }
        }
        throw new IllegalStateException(nodeId + " never started in this journal: " + result.order());
    }

    private static WorkflowNode echo(String id, String output) {
        return echo(id, output, null);
    }

    private static WorkflowNode echo(String id, String output, Map<String, AtomicInteger> callCounts) {
        return WorkflowNode.of(id, in -> {
            if (callCounts != null) {
                callCounts.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            }
            return output;
        });
    }

    private static WorkflowNode delayedEcho(String id, String output, long delayMillis, Map<String, AtomicInteger> callCounts) {
        return WorkflowNode.of(id, in -> {
            if (callCounts != null) {
                callCounts.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            }
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return output;
        });
    }

    private static WorkflowNode join(String id) {
        return WorkflowNode.of(id, in -> "JOIN[" + in + "]");
    }
}
