package io.ara.runtime.workflow.patterns;

import io.ara.runtime.workflow.Workflow;
import io.ara.runtime.workflow.WorkflowPattern;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * <b>Adversarial Verification</b> (ADR-054 D2): a reviewer sits after a worker and either
 * approves what it produced, or sends it back with feedback to try again.
 *
 * <pre>{@code
 * Workflow.of()
 *         .node("draft",   drafter)
 *         .pattern(Critic.of("verify", "draft", verifier).maxRounds(3).approveTo("publish"))
 *         .node("publish", publisher)
 *         .edge("draft", "verify")
 *         .terminal("publish")
 *         .build();
 * }</pre>
 *
 * <p>It compiles to one routing node and two edges — a forward edge to the approval target
 * and a {@code back} edge to the worker — so the review loop is an ordinary cycle in the
 * graph, visible to every structural control, rather than anything the scheduler knows
 * about. The rejection feedback travels as the {@code back} edge's token, which is what
 * makes it the worker's next input.
 *
 * <p>Both {@code maxRounds} and {@code approveTo} are mandatory <em>by construction</em>
 * (you cannot obtain a {@code Critic} without passing through both): a review loop with no
 * cap is the unbounded cycle ADR-052 D5's control #8 exists for, and one with no approval
 * target is a loop with no way out. This is the same move ADR-050 makes with {@code
 * orElse} — the incorrect state is not written, rather than being reported by a validator.
 *
 * <p><b>One consequence to know about.</b> The round counter lives in this spec, not in
 * the scheduler's per-run state, which the node body has no access to. A {@link Workflow}
 * carrying a {@code Critic} is therefore effectively <em>single-run</em>: the count carries
 * across {@link Workflow#run} calls, exactly as an attached {@code RunBudget} already does
 * (build the workflow again for a fresh one). The durable form is control #8's {@code
 * maxVisits} declared on the {@code back} edge itself, which stays deferred — it would
 * mean adding a mandatory field to every edge already built.
 */
public final class Critic implements WorkflowPattern {

    private final String id;
    private final String workerId;
    private final Function<String, Verdict> verifier;
    private final int maxRounds;
    private final String approveTo;

    private Critic(String id, String workerId, Function<String, Verdict> verifier, int maxRounds, String approveTo) {
        this.id = id;
        this.workerId = workerId;
        this.verifier = verifier;
        this.maxRounds = maxRounds;
        this.approveTo = approveTo;
    }

    /**
     * @param id       the reviewer node's id
     * @param workerId the node a rejection sends the work back to — it must already have an
     *                 edge into {@code id}, or there is nothing for the reviewer to review
     * @param verifier the review itself: the worker's output in, a {@link Verdict} out
     */
    public static RoundsStep of(String id, String workerId, Function<String, Verdict> verifier) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(workerId, "workerId must not be null");
        Objects.requireNonNull(verifier, "verifier must not be null");
        if (id.equals(workerId)) {
            throw new IllegalArgumentException("a critic cannot review itself: id and workerId are both '" + id + "'");
        }
        return maxRounds -> {
            if (maxRounds <= 0) {
                throw new IllegalArgumentException("maxRounds must be > 0, got " + maxRounds);
            }
            return approveTo -> {
                Objects.requireNonNull(approveTo, "approveTo must not be null");
                return new Critic(id, workerId, verifier, maxRounds, approveTo);
            };
        };
    }

    /** Step two of three: how many review rounds this loop may take before it fails. */
    @FunctionalInterface
    public interface RoundsStep {
        ApprovalStep maxRounds(int maxRounds);
    }

    /** Step three of three: where an approval goes. */
    @FunctionalInterface
    public interface ApprovalStep {
        Critic approveTo(String nodeId);
    }

    @Override
    public void compileInto(Workflow.Builder builder) {
        // The verdict computed in the body, read by the selector: DataflowScheduler calls
        // the two back to back on one thread inside a single fire(), so a ThreadLocal
        // carries the structured outcome between them without either re-running the
        // verifier (which may be an LLM call) or smuggling a tag into the output string —
        // that string is the token the next node receives, and it must stay the payload.
        ThreadLocal<Verdict> pending = new ThreadLocal<>();
        AtomicInteger rounds = new AtomicInteger();

        builder.routingNode(id,
                input -> {
                    int round = rounds.incrementAndGet();
                    if (round > maxRounds) {
                        throw new IllegalStateException("critic('" + id + "') exceeded maxRounds=" + maxRounds
                                + " — the worker '" + workerId + "' was sent back " + maxRounds + " time(s) "
                                + "without ever being approved");
                    }
                    Verdict verdict = Objects.requireNonNull(
                            verifier.apply(input), () -> "verifier of critic('" + id + "') returned null");
                    pending.set(verdict);
                    return verdict.content();
                },
                output -> {
                    Verdict verdict = pending.get();
                    pending.remove();
                    if (verdict == null) {
                        throw new IllegalStateException("critic('" + id + "') has no verdict for its own output");
                    }
                    return switch (verdict) {
                        case Verdict.Approved a -> Set.of(approveTo);
                        case Verdict.Rejected r -> Set.of(workerId);
                    };
                });
        builder.edge(id, approveTo);
        builder.backEdge(id, workerId);
    }

    public String id() {
        return id;
    }
}
