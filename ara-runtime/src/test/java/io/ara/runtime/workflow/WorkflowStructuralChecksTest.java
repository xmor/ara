package io.ara.runtime.workflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-052 D5 — the four build-time structural checks the current facade can support
 * without agent-shaped nodes, a declared {@code RunState} channel, or a required
 * per-edge {@code maxVisits} (controls #1/#2, #3, #9, #10 — see {@code
 * Workflow.Builder}'s own Javadoc for why the other six are not here yet).
 */
class WorkflowStructuralChecksTest {

    // ── #3 dead-end (opt-in: only once a terminal is declared) ─────────────────

    @Test
    void noTerminalDeclared_deadEndIsNotAnError_backwardCompatible() {
        // Every Workflow built before this control existed ends on some node with no
        // outgoing edges and never called terminal() — this must keep building.
        Workflow wf = Workflow.of().node("a", in -> "A").node("b", in -> "B").edge("a", "b").build();
        assertTrue(wf.graph().nodes().size() == 2);
    }

    @Test
    void aNonTerminalDeadEnd_isRejected_onceATerminalIsDeclared() {
        Workflow.Builder b = Workflow.of()
                .node("a", in -> "A").node("b", in -> "B").node("c", in -> "C")
                .edge("a", "b").edge("a", "c")
                .terminal("b");   // "c" is also a dead end, but never declared terminal

        IllegalStateException e = assertThrows(IllegalStateException.class, b::build);
        assertTrue(e.getMessage().contains("'c'"), e.getMessage());
        assertTrue(e.getMessage().contains("terminal"), e.getMessage());
    }

    @Test
    void everyDeadEndDeclaredTerminal_builds() {
        Workflow wf = Workflow.of()
                .node("a", in -> "A").node("b", in -> "B").node("c", in -> "C")
                .edge("a", "b").edge("a", "c")
                .terminal("b", "c")
                .build();
        assertTrue(wf.graph().nodes().size() == 3);
    }

    @Test
    void terminalNamingAnUndeclaredNode_isRejected() {
        Workflow.Builder b = Workflow.of().node("a", in -> "A").terminal("ghost");
        assertThrows(IllegalStateException.class, b::build);
    }

    // ── #1/#2 reachability (also opt-in, via the same terminal() declaration) ──

    @Test
    void aNodeWithNoPathToAnyTerminal_isRejected() {
        Workflow.Builder b = Workflow.of()
                .node("a", in -> "A").node("b", in -> "B").node("orphan", in -> "O")
                .edge("a", "b")
                .terminal("b");
        // "orphan" has no incoming or outgoing edges at all — unreachable from "a" and
        // itself reaches nothing but a (missing) terminal.

        IllegalStateException e = assertThrows(IllegalStateException.class, b::build);
        assertTrue(e.getMessage().contains("orphan"), e.getMessage());
    }

    @Test
    void aCycleWithNoWayOutEver_isRejected() {
        // a -> b -> a, neither ever reaches "done".
        Workflow.Builder b = Workflow.of()
                .node("a", in -> "A").node("b", in -> "B").node("done", in -> "D")
                .edge("a", "b").backEdge("b", "a")
                .terminal("done");

        assertThrows(IllegalStateException.class, b::build);
    }

    @Test
    void aCycleWithAnEscapeToATerminal_builds() {
        Workflow wf = Workflow.of()
                .node("a", in -> "A").node("b", in -> "B").node("done", in -> "D")
                .edge("a", "b").backEdge("b", "a").edge("b", "done")
                .terminal("done")
                .build();
        assertTrue(wf.graph().nodes().size() == 3);
    }

    // ── #10 ambiguous fan-in (always on — not gated by terminal()) ──────────────

    @Test
    void twoForwardPredecessors_withNoDeclaredMergeStrategy_isRejected() {
        Workflow.Builder b = Workflow.of()
                .node("a", in -> "A").node("b", in -> "B").node("join", in -> in)
                .edge("a", "join").edge("b", "join");

        IllegalStateException e = assertThrows(IllegalStateException.class, b::build);
        assertTrue(e.getMessage().contains("join"), e.getMessage());
        assertTrue(e.getMessage().contains("2 forward predecessors"), e.getMessage());
    }

    @Test
    void oneForwardPredecessorPlusABackEdge_isNotAmbiguousFanIn() {
        // The back edge is OR-merge, not AND-join — only one forward predecessor, so no
        // composition question exists.
        Workflow wf = Workflow.of()
                .node("a", in -> "A").node("b", in -> "B")
                .edge("a", "b").backEdge("b", "b")
                .build();
        assertTrue(wf.graph().nodes().size() == 2);
    }

    // ── #9 join-in-cycle (always on) ────────────────────────────────────────────

    @Test
    void aForwardEdgeFromOutsideACycle_intoANodeInsideIt_isRejected() {
        // review <-> revise is a 2-node cycle; "audit" forward-feeds "revise" from
        // outside it — on the second lap "revise" would wait for a token audit never
        // sends again.
        Workflow.Builder b = Workflow.of()
                .node("review", in -> "R").node("revise", in -> "V").node("audit", in -> "A")
                .edge("review", "revise").backEdge("revise", "review")
                .edge("audit", "revise");

        IllegalStateException e = assertThrows(IllegalStateException.class, b::build);
        assertTrue(e.getMessage().contains("revise"), e.getMessage());
        assertTrue(e.getMessage().contains("audit"), e.getMessage());
    }

    @Test
    void aBareSelfLoop_isNotFlaggedByJoinInCycle() {
        Workflow wf = Workflow.of()
                .node("a", in -> "A").node("loop", in -> "L")
                .edge("a", "loop").backEdge("loop", "loop")
                .build();
        assertTrue(wf.graph().nodes().size() == 2);
    }

    @Test
    void aMultiNodeCycleEnteredOnlyOnce_isNotFlagged() {
        Workflow wf = Workflow.of()
                .node("draft", in -> "D").node("review", in -> "R").node("revise", in -> "V")
                .edge("draft", "review").edge("review", "revise").backEdge("revise", "review")
                .build();
        assertTrue(wf.graph().nodes().size() == 3);
    }
}
