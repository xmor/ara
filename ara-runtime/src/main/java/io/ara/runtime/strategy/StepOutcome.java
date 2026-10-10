package io.ara.runtime.strategy;

/**
 * How one step of a {@link PlanExecuteStrategy} plan ended.
 *
 * <p>A step used to hand back a bare {@code String}, with {@code null}/blank as the only way to
 * say "that did not work" — so the caller could learn <i>that</i> a step failed but never
 * <i>why</i>, and there was no room to say anything else about how it went. A type with one case
 * per way of ending is the smallest thing that carries both.
 *
 * <p>Immutable; safe to hand between threads.
 */
sealed interface StepOutcome {

    /** The step produced a usable result. */
    record Done(String result) implements StepOutcome { }

    /**
     * The step finished, but its model says the steps still ahead need changing.
     *
     * @param result what the step produced; kept like any finished step's
     * @param reason what must change, passed on to whoever replans
     */
    record Revise(String result, String reason) implements StepOutcome { }

    /**
     * The step ended with nothing usable.
     *
     * @param reason a phrase that completes "Step 2 of 4 [goal] …", e.g. "produced no result"
     */
    record Failed(String reason) implements StepOutcome { }

    /** {@link Done} when {@code result} has content, otherwise {@link Failed} with {@code reasonIfEmpty}. */
    static StepOutcome of(String result, String reasonIfEmpty) {
        return result != null && !result.isBlank() ? new Done(result) : new Failed(reasonIfEmpty);
    }
}
