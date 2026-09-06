package io.ara.runtime.workflow.patterns;

import io.ara.runtime.workflow.Workflow;
import io.ara.runtime.workflow.WorkflowPattern;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * <b>Tournament</b> (ADR-054 D1): the same task attempted several times in parallel, each
 * attempt varied, with a judge that picks one winner rather than combining them.
 *
 * <pre>{@code
 * Workflow.of()
 *         .node("prepare", preparer)
 *         .pattern(Tournament.of("prepare", "solve", 5, i -> input -> solveAt(temperature(i), input))
 *                            .judge("pick", candidates -> bestOf(candidates)))
 *         .terminal("pick")
 *         .build();
 * }</pre>
 *
 * <p><b>Why real nodes and not {@code mapOver}.</b> ADR-052 D4's {@code mapOver} is one
 * activation <em>per element of a list</em>; a tournament is N activations <em>on the same
 * input</em>, which is the distinction ADR-054 D1 opens with. Building it from N declared
 * nodes gets that for free — each contender is fed by its own edge from the same source,
 * so each receives the identical token — and it also makes the variation per contender
 * trivial and type-safe: contender {@code i} is its own closure, built by {@code variant},
 * with nothing encoded into the string it is passed. A {@code mapOver} would have had to
 * smuggle the variation through the element text.
 *
 * <p><b>Why the judge is not a scheduler feature.</b> The fan-in that "selects" is, in this
 * engine, an ordinary node with several forward predecessors and a declared {@code
 * composer} (ADR-052 D5 control #10's opt-in). The composer only shapes the N candidate
 * outputs into one value — it runs on the scheduler's control thread — and the judging
 * itself happens in the node's body, on the pool, where an expensive comparison belongs.
 * So "a fan-in that chooses" needs no new scheduler concept at all: it is a composer plus
 * ordinary application code.
 *
 * <p>ADR-054 D1 requires a mandatory {@code maxActivations} on this pattern for the reason
 * {@code mapOver} has one — an unbounded cost multiplier. Here the degree is {@code
 * replicas}, declared at build time and fixed, so the declaration <em>is</em> the cap:
 * there is no runtime-determined count to bound.
 */
public final class Tournament implements WorkflowPattern {

    /**
     * Separator used to hand the N candidate outputs from the composer to the judge body
     * as one string. NUL is not a character LLM or tool output carries in practice, which
     * is why it is safe as a frame delimiter where a printable one would not be.
     */
    private static final String SEPARATOR = "\0";

    private final String sourceId;
    private final String contenderPrefix;
    private final int replicas;
    private final IntFunction<Function<String, String>> variant;
    private final String judgeId;
    private final Function<List<String>, String> pick;

    private Tournament(String sourceId, String contenderPrefix, int replicas,
                       IntFunction<Function<String, String>> variant,
                       String judgeId, Function<List<String>, String> pick) {
        this.sourceId = sourceId;
        this.contenderPrefix = contenderPrefix;
        this.replicas = replicas;
        this.variant = variant;
        this.judgeId = judgeId;
        this.pick = pick;
    }

    /**
     * @param sourceId        the node whose output every contender receives, unchanged
     * @param contenderPrefix contender node ids are {@code contenderPrefix + "#" + i}
     * @param replicas        how many contenders — at least two, or there is no contest
     * @param variant         builds contender {@code i}'s body; this is where the variation
     *                        (temperature, model, prompt, a different agent entirely) lives
     */
    public static JudgeStep of(String sourceId, String contenderPrefix, int replicas,
                               IntFunction<Function<String, String>> variant) {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(contenderPrefix, "contenderPrefix must not be null");
        Objects.requireNonNull(variant, "variant must not be null");
        if (replicas < 2) {
            throw new IllegalArgumentException("a tournament needs at least 2 contenders, got " + replicas);
        }
        return (judgeId, pick) -> {
            Objects.requireNonNull(judgeId, "judgeId must not be null");
            Objects.requireNonNull(pick, "pick must not be null");
            if (judgeId.equals(sourceId)) {
                throw new IllegalArgumentException("the judge cannot be the source node: both are '" + judgeId + "'");
            }
            if (judgeId.startsWith(contenderPrefix + "#")) {
                throw new IllegalArgumentException("judgeId '" + judgeId + "' collides with the contender ids "
                        + "this tournament generates ('" + contenderPrefix + "#0'...)");
            }
            return new Tournament(sourceId, contenderPrefix, replicas, variant, judgeId, pick);
        };
    }

    /** The mandatory terminal method: a tournament with no judge has no result. */
    @FunctionalInterface
    public interface JudgeStep {
        /**
         * @param judgeId the winner-picking node's id
         * @param pick    the judging itself — the N candidate outputs in contender order,
         *                the chosen one (or anything derived from them) out. Runs on the
         *                pool, so it may be as expensive as an LLM call.
         */
        Tournament judge(String judgeId, Function<List<String>, String> pick);
    }

    @Override
    public void compileInto(Workflow.Builder builder) {
        for (int i = 0; i < replicas; i++) {
            String contenderId = contenderPrefix + "#" + i;
            builder.node(contenderId, Objects.requireNonNull(variant.apply(i),
                    "variant produced a null body for contender " + i));
            builder.edge(sourceId, contenderId);
            builder.edge(contenderId, judgeId);
        }
        builder.node(judgeId, framed -> pick.apply(unframe(framed)));
        // Cheap on the control thread; the comparison itself is in the body above.
        builder.composer(judgeId, candidates -> String.join(SEPARATOR, candidates));
    }

    private static List<String> unframe(String framed) {
        return List.of(framed.split(java.util.regex.Pattern.quote(SEPARATOR), -1));
    }

    /** The generated contender node ids, in contender order. */
    public List<String> contenderIds() {
        List<String> ids = new ArrayList<>(replicas);
        for (int i = 0; i < replicas; i++) {
            ids.add(contenderPrefix + "#" + i);
        }
        return List.copyOf(ids);
    }

    public String judgeId() {
        return judgeId;
    }
}
