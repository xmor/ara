package io.ara.core.budget;

import io.ara.core.agent.RunContext;
import io.ara.core.common.Budget;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A budget node with a reference to its parent, so spend recorded anywhere in a delegation
 * tree is subtracted from every ancestor — "sottratto dal parent, mai indipendente"
 * (ADR-0069 D1), the property that today does <em>not</em> hold across a peer-to-peer
 * {@code delegate_task} hop (each hop opens an ephemeral session with its own detached
 * budget — ADR-039).
 *
 * <p>The hierarchy is one class at four granularities — tenant → task class → evolutionary
 * cycle → single run — not four types (D2). {@code RunBudget} (ADR-054 D6) is meant to
 * compose in as the leaf, reporting its spend up to a {@code HierarchicalBudget} parent;
 * this class does not replace it, and does not require it to exist.
 *
 * <p><b>Propagation (ADR-0069 D3).</b> {@link #attachTo(RunContext)} / {@link #from(RunContext)}
 * put and read the node on {@code RunContext}'s leak-safe {@code opaque} channel, so a
 * {@code delegate_task} hop carries the <em>same</em> node to the worker (the delegation
 * tool copies {@code opaque} through unchanged): a worker's {@link #record} then walks up
 * to the caller's node and its ancestors.
 *
 * <p>Thread-safe: spend is an {@link AtomicReference} updated with a pure function, and
 * {@link #record} walks to the root the same way from every thread.
 */
public final class HierarchicalBudget {

    /** Opaque-channel key for {@link #attachTo}/{@link #from} (ADR-0069 D3). */
    private static final String RUN_CONTEXT_KEY = "io.ara.core.budget.node";

    private final Budget             moneyCap;
    private final Optional<Integer>  maxTokens;
    private final Optional<Integer>  maxCalls;
    private final Optional<Duration> maxDuration;
    private final HierarchicalBudget parent;      // null only at the root
    private final String             currency;    // consistent across the whole tree

    private final AtomicReference<Spend> spent;

    private HierarchicalBudget(Budget moneyCap, Integer maxTokens, Integer maxCalls,
                               Duration maxDuration, HierarchicalBudget parent, String currency) {
        this.moneyCap = Objects.requireNonNull(moneyCap, "moneyCap must not be null");
        this.maxTokens = optionalPositive("maxTokens", maxTokens);
        this.maxCalls = optionalPositive("maxCalls", maxCalls);
        this.maxDuration = optionalPositiveDuration(maxDuration);
        this.parent = parent;
        this.currency = currency;
        this.spent = new AtomicReference<>(Spend.zero(currency));
        if (moneyCap instanceof Budget.Limited limited && !limited.cap().currency().equals(currency)) {
            throw new IllegalArgumentException(
                    "moneyCap currency (" + limited.cap().currency() + ") must match the budget tree's currency (" + currency + ")");
        }
    }

    /** A root node (no parent). {@code null} for any of the three optional caps means "no limit on that axis". */
    public static HierarchicalBudget root(Budget moneyCap, Integer maxTokens, Integer maxCalls, Duration maxDuration) {
        return root(moneyCap, maxTokens, maxCalls, maxDuration, currencyOf(moneyCap));
    }

    /** As {@link #root(Budget, Integer, Integer, Duration)} but with an explicit currency (required when {@code moneyCap} is {@link Budget.Unlimited}). */
    public static HierarchicalBudget root(Budget moneyCap, Integer maxTokens, Integer maxCalls,
                                          Duration maxDuration, String currency) {
        return new HierarchicalBudget(moneyCap, maxTokens, maxCalls, maxDuration, null,
                Objects.requireNonNull(currency, "currency must not be null"));
    }

    /** A child of this node, one granularity down. Its money cap, if {@link Budget.Limited}, must be in the tree's currency. */
    public HierarchicalBudget child(Budget moneyCap, Integer maxTokens, Integer maxCalls, Duration maxDuration) {
        return new HierarchicalBudget(moneyCap, maxTokens, maxCalls, maxDuration, this, currency);
    }

    // ── propagation along the delegation chain (ADR-0069 D3) ──────────────────

    /** A copy of {@code ctx} carrying this node on the opaque channel — hand this to a delegated task. */
    public RunContext attachTo(RunContext ctx) {
        return ctx.withOpaque(RUN_CONTEXT_KEY, this);
    }

    /** The budget node carried by {@code ctx}, if any (empty for a context with no budget attached). */
    public static Optional<HierarchicalBudget> from(RunContext ctx) {
        return Optional.ofNullable(ctx == null ? null : ctx.opaque(RUN_CONTEXT_KEY, HierarchicalBudget.class));
    }

    /**
     * Whether {@code projected} spend fits — at this node <em>and</em> at every ancestor,
     * recursively (ADR-0069 D1). Pure: records nothing. A {@code false} here is what makes
     * the requesting node stop its own subtree (D5) without touching what siblings or the
     * parent are doing.
     */
    public boolean permits(Spend projected) {
        Spend current = spent.get();
        boolean localOk =
                moneyCap.permits(current.money().plus(projected.money()))
                        && maxTokens.map(cap -> current.tokens() + projected.tokens() <= cap).orElse(true)
                        && maxCalls.map(cap -> current.calls() + projected.calls() <= cap).orElse(true);
        return localOk && (parent == null || parent.permits(projected));
    }

    /**
     * Whether an elapsed wall-clock time fits the duration cap at this node and every
     * ancestor. Duration is checked, not decremented (ADR-0069 D1) — reuse the caller's
     * own start timestamp, the same way {@code ExecutionConfig.executionTimeout} does.
     */
    public boolean permitsElapsed(Duration elapsed) {
        Objects.requireNonNull(elapsed, "elapsed must not be null");
        boolean localOk = maxDuration.map(cap -> elapsed.compareTo(cap) <= 0).orElse(true);
        return localOk && (parent == null || parent.permitsElapsed(elapsed));
    }

    /** Records {@code actual} spend here and propagates it to the parent — never independent, by construction. */
    public void record(Spend actual) {
        Objects.requireNonNull(actual, "actual must not be null");
        spent.updateAndGet(s -> s.plus(actual));
        if (parent != null) {
            parent.record(actual);
        }
    }

    /** Total spend that has flowed through this node so far. */
    public Spend spent() {
        return spent.get();
    }

    public String currency()             { return currency; }
    public Budget moneyCap()             { return moneyCap; }
    public Optional<Integer> maxTokens() { return maxTokens; }
    public Optional<Integer> maxCalls()  { return maxCalls; }
    public Optional<Duration> maxDuration() { return maxDuration; }

    /** The parent node, or empty at the root. */
    public Optional<HierarchicalBudget> parent() {
        return Optional.ofNullable(parent);
    }

    private static String currencyOf(Budget moneyCap) {
        if (moneyCap instanceof Budget.Limited limited) {
            return limited.cap().currency();
        }
        throw new IllegalArgumentException(
                "an Unlimited money cap needs an explicit currency — use root(budget, ..., currency)");
    }

    private static Optional<Integer> optionalPositive(String name, Integer value) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0, got: " + value);
        }
        return Optional.ofNullable(value);
    }

    private static Optional<Duration> optionalPositiveDuration(Duration value) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException("maxDuration must be positive, got: " + value);
        }
        return Optional.ofNullable(value);
    }
}
