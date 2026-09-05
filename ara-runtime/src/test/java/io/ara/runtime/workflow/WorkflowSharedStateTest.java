package io.ara.runtime.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-052 D3 — {@code RunState}'s declarative replacement for {@code ara-graph}'s
 * retired {@code SharedWorkspace}: a {@link WorkflowNode.Write} declares what a node
 * contributes, a declared reducer says how two writes to the same key combine. The
 * ADR's own verification criterion for D3 is exactly the first two tests here: two
 * concurrent writers on the same key, a deterministic result, no write lost.
 */
class WorkflowSharedStateTest {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void twoNodesWritingTheSameKey_withADeclaredReducer_mergeDeterministically_noWriteLost() {
        // Two independent branches off a fan-out, neither converging on a shared
        // downstream edge (that would be D5 control #10's concern, an unrelated
        // question about how D1 composes *edge-carried* content) — the only thing in
        // common is the shared-state key they both write.
        Workflow wf = Workflow.of()
                .node("split", in -> "start")
                .node("left",  in -> "left-finding").writes("left", "findings", out -> List.of(out))
                .node("right", in -> "right-finding").writes("right", "findings", out -> List.of(out))
                .edge("split", "left").edge("split", "right")
                .reduce("findings", Reducers.concatLists())
                .build();

        WorkflowResult result = wf.run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        @SuppressWarnings("unchecked")
        List<Object> findings = (List<Object>) result.state().get("findings");
        assertEquals(2, findings.size(), "both writes must land — neither lost to the other");
        assertTrue(findings.contains("left-finding"));
        assertTrue(findings.contains("right-finding"));
    }

    @Test
    void aSingleWriter_needsNoDeclaredReducer() {
        Workflow wf = Workflow.of()
                .node("a", in -> "A").writes("a", "result", out -> out)
                .build();

        WorkflowResult result = wf.run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        assertEquals("A", result.state().get("result"));
    }

    @Test
    void twoWritersOnTheSameKey_withNoDeclaredReducer_failsTheRun_insteadOfGuessing() {
        Workflow wf = Workflow.of()
                .node("a", in -> "A").writes("a", "result", out -> out)
                .node("b", in -> "B").writes("b", "result", out -> out)
                .edge("a", "b")
                .build();

        WorkflowResult result = wf.run("go", pool);

        assertFalse(result.ok());
        assertTrue(result.failureReason().contains("result"), result.failureReason());
        assertTrue(result.failureReason().contains("reduce"), result.failureReason());
    }

    @Test
    void stringConcatReducer_joinsInOrder() {
        Workflow wf = Workflow.of()
                .node("a", in -> "A").writes("a", "log", out -> out)
                .node("b", in -> "B").writes("b", "log", out -> out)
                .edge("a", "b")
                .reduce("log", Reducers.concatStrings(","))
                .build();

        WorkflowResult result = wf.run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        assertEquals("A,B", result.state().get("log"));
    }

    @Test
    void lastWriteWinsReducer_keepsTheMostRecentWrite() {
        Workflow wf = Workflow.of()
                .node("a", in -> "A").writes("a", "status", out -> out)
                .node("b", in -> "B").writes("b", "status", out -> out)
                .edge("a", "b")
                .reduce("status", Reducers.lastWriteWins())
                .build();

        WorkflowResult result = wf.run("go", pool);

        assertTrue(result.ok(), result.failureReason());
        assertEquals("B", result.state().get("status"));
    }

    @Test
    void aNodeWithNoWrite_contributesNothingToState() {
        Workflow wf = Workflow.of().node("a", in -> "A").build();
        WorkflowResult result = wf.run("go", pool);
        assertTrue(result.ok());
        assertEquals(Map.of(), result.state());
    }

    @Test
    void aFailedRun_stillReportsWhateverWasWrittenBeforeItStopped() {
        Workflow wf = Workflow.of()
                .node("a", in -> "A").writes("a", "partial", out -> out)
                .node("b", in -> { throw new RuntimeException("boom"); })
                .edge("a", "b")
                .build();

        WorkflowResult result = wf.run("go", pool);

        assertFalse(result.ok());
        assertEquals("A", result.state().get("partial"), "a's write survives even though b failed after it");
    }
}
