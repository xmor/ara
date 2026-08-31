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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-052 D1's gate of Fase 1, ported from the standalone spike
 * ({@code docs/analysis/spike-adr-052-dataflow/} in ara-private) to production code: same
 * 19 assertions, now exercising {@link DataflowScheduler} instead of the spike's copy of
 * it. No facade, no build-time checks, no {@code AraAgent} — those are later increments;
 * this only has to prove the activation rule holds.
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
        WorkflowGraph conditional = new WorkflowGraph(
                List.of(WorkflowNode.routing("split", in -> "S", out -> Set.of("a")),
                        echo("a", "A"), echo("b", "B"), echo("c", "C"), join("join")),
                List.of(WorkflowEdge.of("split", "a"), WorkflowEdge.of("split", "b"),
                        WorkflowEdge.of("a", "join"), WorkflowEdge.of("b", "c"), WorkflowEdge.of("c", "join")));

        WorkflowResult result = run(conditional);

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

        // "Crash": keep only the journal entries up to and including 'a'.
        int cutoff = 0;
        for (JournalEntry entry : full.journal()) {
            cutoff++;
            if (entry.nodeId().equals("a")) {
                break;
            }
        }
        List<JournalEntry> truncated = full.journal().subList(0, cutoff);

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
        assertEquals(resumed.journal().size(), resumed.order().stream().distinct().count(),
                "every node occurrence must have exactly one journal entry: " + resumed.order());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private WorkflowResult run(WorkflowGraph graph) {
        return new DataflowScheduler(graph, 10).run("start", pool);
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
