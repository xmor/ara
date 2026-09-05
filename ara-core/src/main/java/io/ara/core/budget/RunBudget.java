package io.ara.core.budget;

import io.ara.core.agent.RunContext;
import io.ara.core.common.Money;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The single cost governor ADR-054 D6 decides on: one budget per run, sitting on the
 * journal, decremented once per node occurrence (one journal entry = one activation).
 *
 * <p>Before D6, a run had four <em>local</em> caps — {@code maxActivations},
 * {@code maxVisits}, {@code maxHandoffs}, {@code maxSteps} — one per multiplier construct
 * ({@code mapOver}, cycles, swarm, dynamic plans), and nothing summed them. This type is
 * the global limit none of them can express: three axes ({@link #maxTokens},
 * {@link #maxCost}, {@link #maxActivations}) checked against a running tally, so a run
 * that overspends fails naming the axis, and the caller (the scheduler) names the
 * construct that was firing when it happened. The local caps stay as build-time checks
 * (ADR-052 D5 controllo n. 8); this does not replace them.
 *
 * <p><b>Composition (ADR-0069 D2).</b> A {@link RunBudget} can report up to a
 * {@link HierarchicalBudget} parent via {@link Builder#reportingTo}: every {@link #charge}
 * records its spend on the parent (which walks it to the tenant / task-class / cycle
 * ancestors) and then asks the parent whether the tree is still within budget. This is
 * the leaf {@code HierarchicalBudget} was written to expect but did not yet have: ADR-0069
 * wired the parent-side aggregation and left this per-run leaf, without which nothing ever
 * actually charged the hierarchy, as a follow-up (ADR-054).
 *
 * <p><b>Not a pre-flight check.</b> {@link #charge} records first and reports the breach
 * after — the activation has already happened and its cost is already spent by the time
 * the entry hits the journal (the limit ADR-054/ADR-0058 state plainly: the budget does
 * not cover the cost of a node in flight at the moment of the overshoot). The run stops
 * on the <em>next</em> charge, not mid-node.
 *
 * <p>Thread-safe: the tally is an {@link AtomicReference}/{@link AtomicLong} pair updated
 * with pure functions, the same shape as {@link HierarchicalBudget}.
 */
public final class RunBudget {

    /** Opaque-channel key for {@link #attachTo}/{@link #from}, symmetric to {@link HierarchicalBudget}. */
    private static final String RUN_CONTEXT_KEY = "io.ara.core.budget.run";

    private final String            currency;
    private final Optional<Long>    maxTokens;
    private final Optional<Money>   maxCost;
    private final Optional<Long>    maxActivations;
    private final HierarchicalBudget parent;   // null unless reportingTo(...) was set

    private final AtomicReference<Spend> spent;
    private final AtomicLong             activations = new AtomicLong();

    private RunBudget(Builder b) {
        this.currency = b.currency;
        this.maxTokens = Optional.ofNullable(b.maxTokens);
        this.maxCost = Optional.ofNullable(b.maxCost);
        this.maxActivations = Optional.ofNullable(b.maxActivations);
        this.parent = b.parent;
        this.spent = new AtomicReference<>(Spend.zero(currency));
    }

    /** A builder; every cap is optional and an unset cap means "no limit on that axis". */
    public static Builder of() {
        return new Builder();
    }

    /** A governor with no caps and no parent — every {@link #charge} is {@link Charge.Ok}. */
    public static RunBudget unlimited(String currency) {
        return of().currency(currency).build();
    }

    /**
     * Records one activation and its {@code activationSpend}, then reports whether any axis
     * is now over its cap — locally or, if {@link Builder#reportingTo} was set, anywhere up
     * the {@link HierarchicalBudget} tree.
     *
     * @param activationSpend the money / tokens / LLM calls this occurrence drew; must be
     *                        in this budget's currency. {@link Spend#zero} for a node that
     *                        declares no cost.
     */
    public Charge charge(Spend activationSpend) {
        Objects.requireNonNull(activationSpend, "activationSpend must not be null");
        Spend after = spent.updateAndGet(s -> s.plus(activationSpend)); // throws on currency mismatch
        long acts = activations.incrementAndGet();

        if (parent != null) {
            parent.record(activationSpend);
        }

        if (maxTokens.isPresent() && after.tokens() > maxTokens.get()) {
            return new Charge.Exceeded(Charge.Axis.TOKENS,
                    "tokens " + after.tokens() + " > cap " + maxTokens.get(), after, acts);
        }
        if (maxCost.isPresent() && after.money().compareTo(maxCost.get()) > 0) {
            return new Charge.Exceeded(Charge.Axis.COST,
                    "cost " + after.money() + " > cap " + maxCost.get(), after, acts);
        }
        if (maxActivations.isPresent() && acts > maxActivations.get()) {
            return new Charge.Exceeded(Charge.Axis.ACTIVATIONS,
                    "activations " + acts + " > cap " + maxActivations.get(), after, acts);
        }
        if (parent != null && !parent.permits(Spend.zero(currency))) {
            return new Charge.Exceeded(Charge.Axis.HIERARCHY,
                    "hierarchical budget exceeded above this run", after, acts);
        }
        return new Charge.Ok(after, acts);
    }

    // ── propagation, symmetric to HierarchicalBudget (ADR-0069 D3) ────────────

    /** A copy of {@code ctx} carrying this budget on the opaque channel — hand it to an agent-shaped node. */
    public RunContext attachTo(RunContext ctx) {
        return ctx.withOpaque(RUN_CONTEXT_KEY, this);
    }

    /** The run budget carried by {@code ctx}, if any. */
    public static Optional<RunBudget> from(RunContext ctx) {
        return Optional.ofNullable(ctx == null ? null : ctx.opaque(RUN_CONTEXT_KEY, RunBudget.class));
    }

    // ── read-only state ──────────────────────────────────────────────────────

    public String currency()                { return currency; }
    public Spend spent()                    { return spent.get(); }
    public long activations()               { return activations.get(); }
    public Optional<Long> maxTokens()       { return maxTokens; }
    public Optional<Money> maxCost()        { return maxCost; }
    public Optional<Long> maxActivations()  { return maxActivations; }
    public Optional<HierarchicalBudget> parent() { return Optional.ofNullable(parent); }

    /** The outcome of a {@link #charge}: the run continues on {@link Ok}, stops on {@link Exceeded}. */
    public sealed interface Charge permits Charge.Ok, Charge.Exceeded {

        /** Which axis a {@link Charge} breach is on. {@code HIERARCHY} = an ancestor {@link HierarchicalBudget}. */
        enum Axis { TOKENS, COST, ACTIVATIONS, HIERARCHY }

        /** Spend recorded so far, including the activation this charge covered. */
        Spend spentAfter();

        /** Activation count so far, including this one. */
        long activationsAfter();

        default boolean ok() {
            return this instanceof Ok;
        }

        record Ok(Spend spentAfter, long activationsAfter) implements Charge {}

        record Exceeded(Axis axis, String detail, Spend spentAfter, long activationsAfter) implements Charge {
            public Exceeded {
                Objects.requireNonNull(axis, "axis must not be null");
                Objects.requireNonNull(detail, "detail must not be null");
            }
        }
    }

    /** Fluent builder for {@link RunBudget}; matches the {@code RunBudget.of().maxTokens(..).maxCost(..).maxActivations(..)} shape of ADR-054 D6. */
    public static final class Builder {
        private String currency = "EUR";
        private Long maxTokens;
        private Money maxCost;
        private Long maxActivations;
        private HierarchicalBudget parent;

        private Builder() {}

        /** The currency for {@link #maxCost} and every {@link Spend} charged. Defaults to {@code "EUR"}. */
        public Builder currency(String currency) {
            this.currency = Objects.requireNonNull(currency, "currency must not be null");
            return this;
        }

        public Builder maxTokens(long maxTokens) {
            if (maxTokens < 0) throw new IllegalArgumentException("maxTokens must be >= 0, got " + maxTokens);
            this.maxTokens = maxTokens;
            return this;
        }

        /** Cost cap in {@link #currency} — {@code maxCost(2.00)} is 2.00 of whatever currency was set. */
        public Builder maxCost(double maxCost) {
            if (maxCost < 0) throw new IllegalArgumentException("maxCost must be >= 0, got " + maxCost);
            this.maxCost = Money.of(BigDecimal.valueOf(maxCost), currency);
            return this;
        }

        public Builder maxCost(Money maxCost) {
            this.maxCost = Objects.requireNonNull(maxCost, "maxCost must not be null");
            this.currency = maxCost.currency();
            return this;
        }

        public Builder maxActivations(long maxActivations) {
            if (maxActivations < 0) throw new IllegalArgumentException("maxActivations must be >= 0, got " + maxActivations);
            this.maxActivations = maxActivations;
            return this;
        }

        /**
         * Report spend up to {@code parent} (ADR-0069 D2): a {@link #charge} then records on
         * the parent and fails if the tree is over budget anywhere above this run. The
         * parent's currency must match this budget's.
         */
        public Builder reportingTo(HierarchicalBudget parent) {
            this.parent = Objects.requireNonNull(parent, "parent must not be null");
            return this;
        }

        public RunBudget build() {
            if (maxCost != null && !maxCost.currency().equals(currency)) {
                throw new IllegalArgumentException(
                        "maxCost currency (" + maxCost.currency() + ") must match currency (" + currency + ")");
            }
            if (parent != null && !parent.currency().equals(currency)) {
                throw new IllegalArgumentException(
                        "parent currency (" + parent.currency() + ") must match this run budget's (" + currency + ")");
            }
            return new RunBudget(this);
        }
    }
}
