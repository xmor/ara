package io.ara.runtime.workflow.patterns;

import io.ara.runtime.workflow.Workflow;
import io.ara.runtime.workflow.WorkflowPattern;

import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * <b>Generate-and-Filter</b> (ADR-054 D2): a policy check that either lets a candidate
 * through or discards it, each to its own declared destination.
 *
 * <pre>{@code
 * Workflow.of()
 *         .node("generate", generator)
 *         .pattern(Filter.of("policy", validator, "publish", "denied"))
 *         .node("publish",  publisher)
 *         .node("denied",   in -> "discarded: " + in)
 *         .edge("generate", "policy")
 *         .terminal("publish", "denied")
 *         .build();
 * }</pre>
 *
 * <p>{@code rejectTo} is a required argument, not an option, and that is the whole design
 * of this pattern: a discard branch that goes nowhere is a dead end, which ADR-052 D5's
 * control #3 refuses to build. Requiring the target here means the graph cannot be
 * expressed wrongly in the first place — the same reason ADR-050 makes {@code orElse}
 * mandatory instead of validating for its absence afterwards.
 *
 * <p>Contrast with {@link Critic}: a rejection here moves <em>forward</em>, to a terminal
 * that records the discard, and is never retried. A {@link Verdict.Rejected} moves
 * <em>back</em> to the worker for another attempt. Two shapes, two types.
 */
public final class Filter implements WorkflowPattern {

    private final String id;
    private final Function<String, Gate> validator;
    private final String passTo;
    private final String rejectTo;

    private Filter(String id, Function<String, Gate> validator, String passTo, String rejectTo) {
        this.id = id;
        this.validator = validator;
        this.passTo = passTo;
        this.rejectTo = rejectTo;
    }

    /**
     * @param id        the policy node's id
     * @param validator the check itself: a candidate in, a {@link Gate} out
     * @param passTo    where an accepted candidate goes
     * @param rejectTo  where a discarded one goes — mandatory, see the class Javadoc
     */
    public static Filter of(String id, Function<String, Gate> validator, String passTo, String rejectTo) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(validator, "validator must not be null");
        Objects.requireNonNull(passTo, "passTo must not be null");
        Objects.requireNonNull(rejectTo, "rejectTo must not be null — a filter with nowhere to put what it "
                + "discards is the dead end ADR-052 D5 control #3 rejects; name the discard terminal");
        if (passTo.equals(rejectTo)) {
            throw new IllegalArgumentException("passTo and rejectTo are both '" + passTo
                    + "' — a filter whose two outcomes go to the same place filters nothing");
        }
        return new Filter(id, validator, passTo, rejectTo);
    }

    @Override
    public void compileInto(Workflow.Builder builder) {
        // Same body/selector hand-off as Critic — see its Javadoc for why a ThreadLocal
        // rather than a tag in the output string or a second call to the validator.
        ThreadLocal<Gate> pending = new ThreadLocal<>();

        builder.routingNode(id,
                input -> {
                    Gate gate = Objects.requireNonNull(
                            validator.apply(input), () -> "validator of filter('" + id + "') returned null");
                    pending.set(gate);
                    return gate.content();
                },
                output -> {
                    Gate gate = pending.get();
                    pending.remove();
                    if (gate == null) {
                        throw new IllegalStateException("filter('" + id + "') has no gate decision for its own output");
                    }
                    return switch (gate) {
                        case Gate.Pass p -> Set.of(passTo);
                        case Gate.Reject r -> Set.of(rejectTo);
                    };
                });
        builder.edge(id, passTo);
        builder.edge(id, rejectTo);
    }

    public String id() {
        return id;
    }
}
