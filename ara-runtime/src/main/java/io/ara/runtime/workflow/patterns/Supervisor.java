package io.ara.runtime.workflow.patterns;

import io.ara.runtime.workflow.Workflow;
import io.ara.runtime.workflow.WorkflowPattern;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * <b>Supervisor / Router</b> (ADR-054 D4): a node that decides which worker handles the
 * task next, choosing from an alphabet declared at build time rather than from whatever
 * string it happens to produce.
 *
 * <pre>{@code
 * Workflow.of()
 *         .pattern(Supervisor.of("boss", decide, Set.of("research", "write", "review")).orElse("finish"))
 *         .node("research", researcher)
 *         .node("write",    writer)
 *         .node("review",   reviewer)
 *         .node("finish",   in -> in)
 *         .terminal("finish")
 *         .build();
 * }</pre>
 *
 * <p>The alphabet is what makes this a router rather than a jump: every target is an edge
 * in the graph before the run starts, so reachability, livelock and termination controls
 * all apply to it. {@code orElse} is the mandatory terminal method — the same shape ADR-050
 * gave {@code IntentRouter}, and the reason a supervisor can never route into a void.
 *
 * <p><b>A declared weakening.</b> ADR-054 D4 wants the choice constrained by the provider's
 * structured output, so a name outside the alphabet is unrepresentable. Nodes here are
 * still plain functions (ADR-052 D1/D2 keep them opaque until they are agent-shaped), so
 * what this can enforce is a <em>runtime</em> check: a decision outside the alphabet routes
 * to {@code orElse} rather than failing the run, and a decision the alphabet does contain
 * is used as-is. That is weaker than the ADR's design and is written here so the gap is
 * visible at the point where someone would otherwise assume the stronger guarantee.
 */
public final class Supervisor implements WorkflowPattern {

    private final String id;
    private final Function<String, String> decide;
    private final Set<String> alphabet;
    private final String orElse;

    private Supervisor(String id, Function<String, String> decide, Set<String> alphabet, String orElse) {
        this.id = id;
        this.decide = decide;
        this.alphabet = alphabet;
        this.orElse = orElse;
    }

    /**
     * @param id       the supervisor node's id
     * @param decide   the routing decision: the incoming task in, the id of the chosen node out
     * @param alphabet every node {@code decide} is allowed to name — at least two, or the
     *                 decision is not a decision
     */
    public static ElseStep of(String id, Function<String, String> decide, Set<String> alphabet) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(decide, "decide must not be null");
        Objects.requireNonNull(alphabet, "alphabet must not be null");
        if (alphabet.size() < 2) {
            throw new IllegalArgumentException("a supervisor's alphabet needs at least 2 targets, got "
                    + alphabet.size() + " — with one there is nothing to route");
        }
        if (alphabet.contains(id)) {
            throw new IllegalArgumentException("a supervisor cannot route to itself: '" + id + "' is in its own alphabet");
        }
        Set<String> copy = new LinkedHashSet<>(alphabet);
        return orElse -> {
            Objects.requireNonNull(orElse, "orElse must not be null — a router with no fallback can route "
                    + "into a void (ADR-050, ADR-052 D5 control #4); name the node an unrecognized decision goes to");
            return new Supervisor(id, decide, Set.copyOf(copy), orElse);
        };
    }

    /** The mandatory terminal method: where a decision outside the alphabet goes. */
    @FunctionalInterface
    public interface ElseStep {
        Supervisor orElse(String nodeId);
    }

    @Override
    public void compileInto(Workflow.Builder builder) {
        // No ThreadLocal here, unlike Critic/Filter: the decision is about which edge to
        // take, not about what content flows on it, so the selector can make it directly
        // from the node's (pass-through) output — one call to decide, on the pool thread.
        builder.routingNode(id,
                input -> input,
                output -> {
                    String chosen = decide.apply(output);
                    return Set.of(chosen != null && alphabet.contains(chosen) ? chosen : orElse);
                });
        for (String target : alphabet) {
            builder.edge(id, target);
        }
        if (!alphabet.contains(orElse)) {
            builder.edge(id, orElse);
        }
    }

    public String id() {
        return id;
    }

    /** The declared set of nodes this supervisor may route to, excluding {@link #orElse()}. */
    public Set<String> alphabet() {
        return alphabet;
    }

    public String orElse() {
        return orElse;
    }
}
