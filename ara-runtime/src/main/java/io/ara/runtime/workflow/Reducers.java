package io.ara.runtime.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BinaryOperator;

/**
 * Common combinators for {@link Workflow.Builder#reduce(String, BinaryOperator)}
 * (ADR-052 D3) — declaring how two {@link WorkflowNode.Write}s to the same shared-state
 * key combine, instead of a caller reaching for {@code (a, b) -> ...} for every ordinary
 * case.
 *
 * <p>Every combinator here is order-sensitive in the one way that matters for a
 * concurrent fan-in: the first argument is always the value already in the run's shared
 * state, the second is the one just written — not "whichever finished first", which
 * {@link DataflowScheduler}'s own concurrency invariant (state is only ever touched on
 * the thread that called {@code run}) makes deterministic regardless of which node's
 * body actually ran first.
 */
public final class Reducers {

    private Reducers() {}

    /**
     * Concatenates two {@link List}s, existing then new. Both values are cast
     * unchecked — the same runtime-typed contract {@code RunState#get(String, Class)}
     * already uses elsewhere for a heterogeneous key/value store; a key declared with
     * this reducer must only ever be written {@code List}s.
     */
    @SuppressWarnings("unchecked")
    public static BinaryOperator<Object> concatLists() {
        return (existing, added) -> {
            List<Object> merged = new ArrayList<>((List<Object>) existing);
            merged.addAll((List<Object>) added);
            return List.copyOf(merged);
        };
    }

    /** Joins two {@link String} values with {@code delimiter} between them, existing then new. */
    public static BinaryOperator<Object> concatStrings(String delimiter) {
        return (existing, added) -> existing + delimiter + added;
    }

    /**
     * Keeps whichever value was written most recently — an explicit opt-out from this
     * ADR's own default (an undeclared collision fails the run): a caller reaching for
     * this is saying "I know two nodes may write this key, and I don't care which wins."
     */
    public static BinaryOperator<Object> lastWriteWins() {
        return (existing, added) -> added;
    }
}
