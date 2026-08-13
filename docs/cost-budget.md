# Cost & budget model

How ARA tracks LLM spend and enforces a per-agent budget: the `Money` and
`Budget` value types, where they plug into `LlmProfile`/`AgentConfig`, and how
`AgentResponse.estimatedCost()` gets computed on both the success and the
failure path.

## Table of contents

- [`Money`](#money)
- [`Budget`](#budget)
- [Configuring tariffs and a budget on `LlmProfile`](#configuring-tariffs-and-a-budget-on-llmprofile)
- [Reading the cost off an `AgentResponse`](#reading-the-cost-off-an-agentresponse)
- [Budget enforcement in the ReAct loop](#budget-enforcement-in-the-react-loop)
- [Aggregation across fan-out and pipelines](#aggregation-across-fan-out-and-pipelines)
- [Single-currency constraint](#single-currency-constraint)

---

## `Money`

`io.ara.core.common.Money` is an immutable `(BigDecimal amount, String
currency)` pair — the value type used everywhere a monetary amount flows:
per-1k-token tariffs, the budget cap, and `AgentResponse.estimatedCost()`.

```java
Money.of("0.002", "EUR");        // from a decimal string
Money.of(BigDecimal.ONE, "USD"); // from a BigDecimal
Money.zero("EUR");
Money.ZERO_EUR;                  // Money.zero("EUR")
```

- `amount` must be `>= 0`; `currency` must not be blank. Amounts are
  normalized to a fixed internal scale (6 decimal digits, `HALF_UP`).
- `plus(Money)` and `compareTo(Money)` require both sides to share the same
  `currency` — mixing currencies throws `IllegalArgumentException` rather than
  silently producing a meaningless number.
- `multiply(BigDecimal)` scales an amount by a non-negative factor (used to
  turn a per-1k-token rate into a cost for an actual token count).

Compare amounts with `compareTo(...) == 0`, not `equals(...)` — `equals` is
the record's field-wise equality and is sensitive to the exact `BigDecimal`
representation.

## `Budget`

`io.ara.core.common.Budget` is a sealed type with two variants:

```java
public sealed interface Budget permits Budget.Unlimited, Budget.Limited {
    record Unlimited() implements Budget {}
    record Limited(Money cap) implements Budget {}

    static Budget unlimited();
    static Budget limited(Money cap);

    boolean permits(Money projected);
}
```

`Unlimited` (the default) always `permits(...)`. `Limited` compares the
projected spend against its `cap` via `Money.compareTo` — so `permits(...)`
throws if `projected` isn't in the cap's currency, the same "fail loud on
misconfiguration" behavior as `Money` itself.

## Configuring tariffs and a budget on `LlmProfile`

Asse C ("governance") of `LlmProfile` carries the per-agent cost tariffs, the
budget, and the currency they're all denominated in:

```java
LlmProfile profile = LlmProfile.builder()
        .modelId("gpt-4o")
        .costCurrency("EUR")
        .costInputPer1kTokens(Money.of("0.005", "EUR"))
        .costOutputPer1kTokens(Money.of("0.015", "EUR"))
        .costBudget(Budget.limited(Money.of("2.00", "EUR")))
        .build();
```

Defaults: both tariffs are `Money.zero("EUR")`, `costBudget` is
`Budget.unlimited()`, `costCurrency` is `"EUR"`.

`AgentConfig` delegates to the primary `LlmProfile` for all four:
`costInputPer1kTokens()`, `costOutputPer1kTokens()`, `costBudget()`,
`costCurrency()`.

## Reading the cost off an `AgentResponse`

`AgentResponse.estimatedCost()` is a `Money`, populated on **both** outcomes:

- **Success** — `AgentInstance` computes it from the real prompt/output token
  split: `costInputPer1kTokens * (promptTokens / 1000) + costOutputPer1kTokens
  * (outputTokens / 1000)`, in `config.costCurrency()`.
- **Failure** — the same computation runs over whatever tokens were actually
  consumed before the failure and is attached via `AgentResponse.withCost(...)`.
  Failures with no tokens consumed (e.g. "session busy", cancellation before
  execution started) report `Money.ZERO_EUR`, since no LLM call was made.

```java
AgentResponse response = agent.execute(task);
System.out.printf("cost: %s %s%n",
        response.estimatedCost().amount(), response.estimatedCost().currency());
```

## Budget enforcement in the ReAct loop

Before each LLM call, `ReactExecutionSupport.checkBudget` projects the cost
already spent plus the estimated cost of the next call (`maxTokensPerStep`
worth of output tokens) and checks it against `config.costBudget()` via
`Budget.permits(...)`:

- Both tariffs at zero → budget tracking is off; the check is skipped
  regardless of `costBudget`.
- `Budget.Unlimited` → never blocks.
- `Budget.Limited` → blocks once `spent + nextEstimate` exceeds the cap,
  failing with a reason that starts with the literal prefix `"Cost budget"`
  (matched by `FailureKind.classify` to route the failure to
  `AgentInterceptor.onBudgetExceeded`) and includes the configured currency
  code — never a hardcoded `$`.

## Aggregation across fan-out and pipelines

- `AgentChain.aggregateSuccess` (used by `AgentFuture.allOf` merge
  strategies) sums `estimatedCost()` across all merged responses via
  `Money.plus`, seeded from the first response's currency.
- `PipelineResult.totalCost()` sums `estimatedCost()` across every executed
  step's `AgentResponse`, not just the last one; an empty pipeline reports
  `Money.ZERO_EUR`.

Both throw `IllegalArgumentException` if the responses being merged don't
share a currency — a fan-out or pipeline that mixes currencies is a
configuration bug, not something to average away.

## Single-currency constraint

An `LlmProfile`'s `costCurrency` is authoritative: both `costInputPer1kTokens`
and `costOutputPer1kTokens` must be denominated in it, and so must
`costBudget`'s cap when it's `Budget.Limited`. `LlmProfile`'s compact
constructor validates this at construction time — a profile built with tariffs
or a cap in a different currency than `costCurrency` throws
`IllegalArgumentException` immediately, rather than surfacing as a confusing
`Money.plus`/`compareTo` failure later at budget-check or aggregation time.
