package io.ara.runtime.workflow.patterns;

import io.ara.runtime.workflow.JournalEntry;
import io.ara.runtime.workflow.NodeOutcome;
import io.ara.runtime.workflow.Workflow;
import io.ara.runtime.workflow.WorkflowResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-054's pattern library, in the shape the ADR decides on (O3): specs that compile onto
 * {@code WorkflowGraph}, each with its own build-time controls. Every pattern here has at
 * least one negative test on its own control, which is what fitness function FF-7 asks for.
 */
class WorkflowPatternsTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    // ── D2: Critic — Adversarial Verification ───────────────────────────────────

    @Test
    void critic_rejectsTwiceThenApproves_andTheFeedbackReachesTheWorker() {
        AtomicInteger attempts = new AtomicInteger();
        List<String> workerInputs = new ArrayList<>();

        Workflow workflow = Workflow.of()
                .node("prepare", in -> in)
                .node("draft", in -> {
                    workerInputs.add(in);
                    return "draft-" + attempts.incrementAndGet();
                })
                .pattern(Critic.of("verify", "draft",
                                out -> out.equals("draft-3") ? new Verdict.Approved(out)
                                        : new Verdict.Rejected("try again after " + out))
                        .maxRounds(5)
                        .approveTo("publish"))
                .node("publish", in -> "published " + in)
                .edge("prepare", "draft")
                .edge("draft", "verify")
                .terminal("publish")
                .build();

        WorkflowResult result = workflow.run("go", pool);

        assertTrue(result.ok(), () -> "run failed: " + result.failureReason());
        assertEquals(3, result.firedTimes("draft"), "two rejections mean three worker attempts");
        assertEquals(3, result.firedTimes("verify"));
        assertEquals(1, result.firedTimes("publish"));
        assertEquals("published draft-3", lastOutputOf(result, "publish"));
        // The rejection feedback is what the worker sees next — not the original input.
        assertEquals(List.of("go", "try again after draft-1", "try again after draft-2"), workerInputs);
    }

    @Test
    void critic_exceedingMaxRounds_failsNamingTheConstruct() {
        Workflow workflow = Workflow.of()
                .node("prepare", in -> in)
                .node("draft", in -> "never good enough")
                .pattern(Critic.of("verify", "draft", out -> new Verdict.Rejected("no"))
                        .maxRounds(2)
                        .approveTo("publish"))
                .node("publish", in -> in)
                .edge("prepare", "draft")
                .edge("draft", "verify")
                .terminal("publish")
                .build();

        WorkflowResult result = workflow.run("go", pool);

        assertFalse(result.ok(), "a loop that never approves must not report success");
        assertTrue(result.failureReason().contains("critic('verify') exceeded maxRounds=2"),
                () -> "the failure should name the construct: " + result.failureReason());
    }

    @Test
    void critic_withoutARoundCapOrAnApprovalTarget_cannotBeBuilt() {
        // Both are mandatory by construction — the staged builder has no path that skips
        // either. What remains checkable is that neither accepts a meaningless value.
        assertThrows(IllegalArgumentException.class,
                () -> Critic.of("verify", "draft", out -> new Verdict.Approved(out)).maxRounds(0),
                "a review loop with no cap is the unbounded cycle control #8 exists for");
        assertThrows(NullPointerException.class,
                () -> Critic.of("verify", "draft", out -> new Verdict.Approved(out)).maxRounds(3).approveTo(null));
        assertThrows(IllegalArgumentException.class,
                () -> Critic.of("verify", "verify", out -> new Verdict.Approved(out)),
                "a critic cannot review itself");
    }

    // ── D2: Filter — Generate-and-Filter ────────────────────────────────────────

    @Test
    void filter_sendsEachOutcomeToItsOwnDeclaredTerminal() {
        assertEquals("published ok-content", runFilterWith("ok-content"));
        assertEquals("discarded: contains policy violation", runFilterWith("bad-content"));
    }

    private String runFilterWith(String candidate) {
        Workflow workflow = Workflow.of()
                .node("generate", in -> candidate)
                .pattern(Filter.of("policy",
                        out -> out.startsWith("bad")
                                ? new Gate.Reject("contains policy violation")
                                : new Gate.Pass(out),
                        "publish", "denied"))
                .node("publish", in -> "published " + in)
                .node("denied", in -> "discarded: " + in)
                .edge("generate", "policy")
                .terminal("publish", "denied")
                .build();

        WorkflowResult result = workflow.run("go", pool);
        assertTrue(result.ok(), () -> "run failed: " + result.failureReason());
        return candidate.startsWith("bad") ? lastOutputOf(result, "denied") : lastOutputOf(result, "publish");
    }

    @Test
    void filter_withoutARejectTarget_cannotBeBuilt() {
        assertThrows(NullPointerException.class,
                () -> Filter.of("policy", out -> new Gate.Pass(out), "publish", null),
                "a discard branch with nowhere to go is the dead end control #3 rejects");
        assertThrows(IllegalArgumentException.class,
                () -> Filter.of("policy", out -> new Gate.Pass(out), "same", "same"),
                "both outcomes going to one place filters nothing");
    }

    // ── D4: Supervisor / Router ─────────────────────────────────────────────────

    @Test
    void supervisor_routesWithinItsAlphabet_andFallsBackWhenTheChoiceIsOutsideIt() {
        assertEquals("research", firedWorkerFor("research"));
        assertEquals("write", firedWorkerFor("write"));
        // A decision naming a node outside the declared alphabet cannot become an edge:
        // it lands on orElse instead, the only other target that exists.
        assertEquals("finish", firedWorkerFor("delete-everything"));
    }

    private String firedWorkerFor(String decision) {
        Workflow workflow = Workflow.of()
                .pattern(Supervisor.of("boss", in -> decision, Set.of("research", "write")).orElse("finish"))
                .node("research", in -> "researched")
                .node("write", in -> "written")
                .node("finish", in -> "finished")
                .terminal("research", "write", "finish")
                .build();

        WorkflowResult result = workflow.run("task", pool);
        assertTrue(result.ok(), () -> "run failed: " + result.failureReason());
        return List.of("research", "write", "finish").stream()
                .filter(id -> result.firedTimes(id) == 1)
                .reduce((a, b) -> {
                    throw new AssertionError("more than one worker fired: " + a + " and " + b);
                })
                .orElseThrow(() -> new AssertionError("no worker fired"));
    }

    @Test
    void supervisor_withoutAFallbackOrARealChoice_cannotBeBuilt() {
        assertThrows(IllegalArgumentException.class,
                () -> Supervisor.of("boss", in -> in, Set.of("only-one")),
                "with one target there is nothing to route");
        assertThrows(NullPointerException.class,
                () -> Supervisor.of("boss", in -> in, Set.of("a", "b")).orElse(null),
                "a router with no fallback can route into a void (control #4)");
        assertThrows(IllegalArgumentException.class,
                () -> Supervisor.of("boss", in -> in, Set.of("boss", "b")),
                "a supervisor cannot route to itself");
    }

    // ── D1: Tournament ──────────────────────────────────────────────────────────

    @Test
    void tournament_runsEveryContenderOnTheSameInput_andTheJudgePicksOne() {
        List<String> judged = new ArrayList<>();

        Workflow workflow = Workflow.of()
                .node("prepare", in -> "problem:" + in)
                // Contender i is its own closure — this is where the variation lives, with
                // nothing encoded into the input string.
                .pattern(Tournament.of("prepare", "solve", 5, i -> input -> input + "/attempt-" + i)
                        .judge("pick", candidates -> {
                            judged.addAll(candidates);
                            return candidates.get(candidates.size() - 1);
                        }))
                .terminal("pick")
                .build();

        WorkflowResult result = workflow.run("go", pool);

        assertTrue(result.ok(), () -> "run failed: " + result.failureReason());
        // Five contender occurrences, one judge — the ADR's own verification for D1.
        for (int i = 0; i < 5; i++) {
            assertEquals(1, result.firedTimes("solve#" + i), "contender " + i);
        }
        assertEquals(1, result.firedTimes("pick"));
        // Every contender saw the identical input (N activations on the same input, not
        // one per list element), and the judge saw them all in contender order.
        assertEquals(List.of("problem:go/attempt-0", "problem:go/attempt-1", "problem:go/attempt-2",
                "problem:go/attempt-3", "problem:go/attempt-4"), judged);
        assertEquals("problem:go/attempt-4", lastOutputOf(result, "pick"));
    }

    @Test
    void tournament_withFewerThanTwoContendersOrACollidingJudge_cannotBeBuilt() {
        assertThrows(IllegalArgumentException.class,
                () -> Tournament.of("prepare", "solve", 1, i -> in -> in),
                "one contender is not a contest");
        assertThrows(IllegalArgumentException.class,
                () -> Tournament.of("prepare", "solve", 3, i -> in -> in).judge("solve#1", cs -> cs.get(0)),
                "the judge id must not collide with a generated contender id");
        assertThrows(IllegalArgumentException.class,
                () -> Tournament.of("prepare", "solve", 3, i -> in -> in).judge("prepare", cs -> cs.get(0)),
                "the judge cannot be the source node");
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
