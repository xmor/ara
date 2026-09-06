package io.ara.runtime.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The declared fan-in composer (ADR-054 D1's enabling primitive): a node with several
 * forward predecessors combines their tokens the way it declares, in edge-declaration
 * order, and control #10 keeps refusing the ones that declare nothing.
 */
class WorkflowFanInComposerTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    /** A diamond whose join declares no composer: still refused, exactly as before. */
    @Test
    void multiplePredecessorsWithoutAComposer_isStillRefusedAtBuildTime() {
        Workflow.Builder builder = Workflow.of()
                .node("split", in -> in)
                .node("a", in -> in + "-a")
                .node("b", in -> in + "-b")
                .node("join", in -> in)
                .edge("split", "a").edge("split", "b")
                .edge("a", "join").edge("b", "join");

        IllegalStateException failure = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(failure.getMessage().contains("control #10"), failure.getMessage());
        assertTrue(failure.getMessage().contains("composer(\"join\""),
                () -> "the error should name the way out: " + failure.getMessage());
    }

    @Test
    void aDeclaredComposerReceivesEveryBranchInEdgeDeclarationOrder() {
        // 'b' sleeps so it finishes last — the composed order must still be a, b (the order
        // the edges were declared in), never the order the branches completed in.
        Workflow workflow = Workflow.of()
                .node("split", in -> in)
                .node("a", in -> "A")
                .node("b", in -> {
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "B";
                })
                .node("join", in -> in)
                .composer("join", branches -> String.join(",", branches))
                .edge("split", "a").edge("split", "b")
                .edge("a", "join").edge("b", "join")
                .terminal("join")
                .build();

        WorkflowResult result = workflow.run("go", pool);

        assertTrue(result.ok(), () -> "run failed: " + result.failureReason());
        assertEquals("A,B", lastOutputOf(result, "join"));
    }

    /** The composer fires once the barrier is satisfied — once, not once per branch. */
    @Test
    void theJoinStillFiresExactlyOnceOnUnevenBranchDepths() {
        Workflow workflow = Workflow.of()
                .node("split", in -> in)
                .node("short", in -> "S")
                .node("long1", in -> "L1")
                .node("long2", in -> "L2")
                .node("join", in -> in)
                .composer("join", branches -> String.join(",", branches))
                .edge("split", "short").edge("split", "long1")
                .edge("long1", "long2")
                .edge("short", "join").edge("long2", "join")
                .terminal("join")
                .build();

        WorkflowResult result = workflow.run("go", pool);

        assertTrue(result.ok(), () -> "run failed: " + result.failureReason());
        long joinOccurrences = result.journal().stream()
                .filter(e -> e instanceof JournalEntry.Finished && e.nodeId().equals("join"))
                .count();
        assertEquals(1, joinOccurrences, "uneven branch depths must not fire the join twice");
        assertEquals("S,L2", lastOutputOf(result, "join"));
    }

    /** A composer that returns null would stall the node forever; it fails loudly instead. */
    @Test
    void aComposerReturningNull_failsLoudlyRatherThanStalling() {
        Workflow workflow = Workflow.of()
                .node("split", in -> in)
                .node("a", in -> "A")
                .node("b", in -> "B")
                .node("join", in -> in)
                .composer("join", branches -> null)
                .edge("split", "a").edge("split", "b")
                .edge("a", "join").edge("b", "join")
                .terminal("join")
                .build();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> workflow.run("go", pool));
        assertTrue(failure.getMessage().contains("composer for node 'join'"), failure.getMessage());
    }

    /** A single-predecessor node is untouched by any of this — the placeholder join still applies. */
    @Test
    void aSinglePredecessorNodeNeedsNoComposer() {
        Workflow workflow = Workflow.of()
                .node("first", in -> in + "-1")
                .node("second", in -> in + "-2")
                .edge("first", "second")
                .terminal("second")
                .build();

        WorkflowResult result = workflow.run("go", pool);

        assertTrue(result.ok(), () -> "run failed: " + result.failureReason());
        assertEquals("go-1-2", lastOutputOf(result, "second"));
    }

    private static String lastOutputOf(WorkflowResult result, String nodeId) {
        List<JournalEntry> entries = result.journal();
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i) instanceof JournalEntry.Finished finished
                    && finished.nodeId().equals(nodeId)
                    && finished.outcome() instanceof NodeOutcome.Completed completed) {
                return completed.content();
            }
        }
        throw new AssertionError("no completed entry for node " + nodeId);
    }
}
