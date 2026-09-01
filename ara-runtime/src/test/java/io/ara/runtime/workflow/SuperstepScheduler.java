package io.ara.runtime.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Test-only fixture, not production code: an edge-triggered (Pregel/LangGraph-style)
 * scheduler on the same {@link WorkflowGraph} model, used solely to reproduce the defect
 * ADR-052 D1 exists to fix — see {@code unevenBranches_dataflowFiresJoinOnce_supersteWouldFireTwice}
 * in {@link DataflowSchedulerTest}.
 *
 * <p>It synchronizes on a global step boundary: every node ready this step runs, and
 * whatever they write becomes next step's frontier. On a fan-in with branches of uneven
 * depth, the short branch's output lands in this step's frontier and the long branch's in
 * a later step, so the join fires once per step it receives input in, the first time with
 * a partial one — the same defect reported against LangGraph without {@code defer=True}
 * (langgraphjs#1535). Keeping this comparison in the suite means a future change to
 * {@link DataflowScheduler} that accidentally reintroduces the defect fails a test, not
 * just an assertion count.
 */
final class SuperstepScheduler {

    private final WorkflowGraph graph;

    SuperstepScheduler(WorkflowGraph graph) {
        this.graph = graph;
    }

    WorkflowResult run(String initialInput, ExecutorService pool) throws Exception {
        List<JournalEntry> journal = new ArrayList<>();
        Map<String, Integer> occurrence = new HashMap<>();
        Map<String, String> seed = new LinkedHashMap<>();
        graph.nodes().stream().map(WorkflowNode::id).filter(id -> graph.in(id).isEmpty())
                .forEach(id -> seed.put(id, initialInput));
        Map<String, String> frontier = seed;

        for (int step = 0; step < 20 && !frontier.isEmpty(); step++) {
            Map<String, String> next = new LinkedHashMap<>();
            List<Future<JournalEntry.Finished>> firing = new ArrayList<>();
            for (Map.Entry<String, String> ready : frontier.entrySet()) {
                String id = ready.getKey();
                String input = ready.getValue();
                int occ = occurrence.merge(id, 1, Integer::sum) - 1;
                firing.add(pool.submit(() -> fire(id, occ, input)));
            }
            for (Future<JournalEntry.Finished> future : firing) {
                JournalEntry.Finished entry = future.get();
                journal.add(entry);
                // Edge-triggered: whoever receives a write starts on the NEXT step.
                NodeOutcome.Completed completed = (NodeOutcome.Completed) entry.outcome();
                completed.selectedTargets().forEach(target ->
                        next.merge(target, completed.content(), (a, b) -> a + " | " + b));
            }
            frontier = next;
        }
        return new WorkflowResult(journal, true, null);
    }

    private JournalEntry.Finished fire(String id, int occurrence, String input) {
        WorkflowNode node = graph.node(id);
        String output = node.body().apply(input);
        List<String> selected = node.selector() == null
                ? graph.out(id).stream().map(WorkflowEdge::to).toList()
                : graph.out(id).stream().map(WorkflowEdge::to).filter(node.selector().apply(output)::contains).toList();
        return new JournalEntry.Finished(id, occurrence, input, new NodeOutcome.Completed(output, selected));
    }
}
