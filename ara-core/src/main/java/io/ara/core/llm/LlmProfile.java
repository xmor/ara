package io.ara.core.llm;

import io.ara.core.common.Budget;
import io.ara.core.common.Money;

import java.util.Objects;

/**
 * Per-agent LLM configuration: which transport to use and how to call it.
 *
 * <p>Used in {@link LlmConfig} as primary or fallback profile. Split by concern
 * (ADR-039 §3, "decisione chiusa"):
 * <ul>
 *   <li><b>Asse A — transport</b> ({@link #transportId()} / {@link #inlineTransport()}):
 *       a reference to shared, ref-counted, versioned infrastructure — the actual
 *       {@code baseUrl}/{@code apiKey}/{@code modelName} live in {@link LlmTransport},
 *       resolved once per session by the runtime's transport registry, never per call.</li>
 *   <li><b>Asse B — parameters</b> ({@link #temperature()}, {@link #topP()}, {@link
 *       #maxTokens()}, {@link #streamingEnabled()}, {@link #nativeJsonSchema()}): plain
 *       config values that flow into {@link LlmCallContext} per call. Different profiles
 *       with different parameters but the same transport share the same {@link
 *       LlmClient} — no duplicated connections just because temperature differs.</li>
 *   <li><b>Asse C — governance</b> ({@link #costBudget()}, {@link #costCurrency()},
 *       {@link #costInputPer1kTokens()}, {@link #costOutputPer1kTokens()}): per-agent
 *       values, unrelated to the transport's lifecycle. Both tariffs and, when the
 *       budget is {@link Budget.Limited}, its cap must share {@link #costCurrency()} —
 *       enforced at construction.</li>
 * </ul>
 *
 * <p>{@link #pinnedTransportVersion()} (ADR-039 §4) is an opt-in escape hatch from the
 * default follow-latest-at-pin-time behavior: {@code null} (the default) means "use
 * whatever transport version is current when a new session pins it"; a non-null value
 * pins to that exact version, for reproducibility, rollback, or controlled A/B testing
 * — even after a {@code publish} makes a newer version current for everyone else.
 *
 * <p>The {@link Builder}'s {@code baseUrl}/{@code apiKey}/{@code modelName} setters are
 * kept as convenience sugar over an inline, content-addressed {@link LlmTransport}
 * (ADR-039 §3: "l'inline-override non sparisce... diventa zucchero") — they do not
 * restore three flat fields on this record; {@link Builder#build()} assembles them into
 * {@link #inlineTransport()} internally.
 */
public record LlmProfile(
        String       transportId,
        LlmTransport inlineTransport,
        Double       temperature,
        Double       topP,
        Integer      maxTokens,
        Budget       costBudget,
        String       costCurrency,
        boolean      streamingEnabled,
        boolean      nativeJsonSchema,
        Money        costInputPer1kTokens,
        Money        costOutputPer1kTokens,
        Long         pinnedTransportVersion
) {
    public LlmProfile {
        Objects.requireNonNull(transportId, "transportId must not be null");
        if (temperature != null && (temperature < 0.0 || temperature > 2.0))
            throw new IllegalArgumentException("temperature must be in [0.0, 2.0]");
        if (topP != null && (topP < 0.0 || topP > 1.0))
            throw new IllegalArgumentException("topP must be in [0.0, 1.0]");
        Objects.requireNonNull(costInputPer1kTokens, "costInputPer1kTokens must not be null");
        Objects.requireNonNull(costOutputPer1kTokens, "costOutputPer1kTokens must not be null");
        Objects.requireNonNull(costBudget, "costBudget must not be null");
        Objects.requireNonNull(costCurrency, "costCurrency must not be null");
        if (!costCurrency.equals(costInputPer1kTokens.currency()))
            throw new IllegalArgumentException(
                    "costInputPer1kTokens currency (" + costInputPer1kTokens.currency()
                            + ") must match costCurrency (" + costCurrency + ")");
        if (!costCurrency.equals(costOutputPer1kTokens.currency()))
            throw new IllegalArgumentException(
                    "costOutputPer1kTokens currency (" + costOutputPer1kTokens.currency()
                            + ") must match costCurrency (" + costCurrency + ")");
        if (costBudget instanceof Budget.Limited limited && !costCurrency.equals(limited.cap().currency()))
            throw new IllegalArgumentException(
                    "costBudget cap currency (" + limited.cap().currency()
                            + ") must match costCurrency (" + costCurrency + ")");
    }

    /**
     * @deprecated kept only as the pre-ADR-039 name for {@link #transportId()}; both
     *             accessors return the same value.
     */
    @Deprecated(forRemoval = false)
    public String modelId() { return transportId; }

    public static LlmProfile of(String transportId) {
        return builder().modelId(transportId).build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String       transportId        = "";
        private Double       temperature        = null;
        private Double       topP               = null;
        private Integer      maxTokens          = null;
        private Budget       costBudget         = Budget.unlimited();
        private String       costCurrency       = "EUR";
        private String       baseUrl            = null;
        private String       apiKey             = null;
        private String       modelName          = null;
        private boolean      streamingEnabled        = false;
        private boolean      nativeJsonSchema        = false;
        private Money        costInputPer1kTokens    = Money.zero("EUR");
        private Money        costOutputPer1kTokens   = Money.zero("EUR");
        private Long         pinnedTransportVersion  = null;

        private Builder() {}

        /** @deprecated use {@link #transportId(String)}; kept as the pre-ADR-039 name. */
        @Deprecated(forRemoval = false)
        public Builder modelId(String v)                  { this.transportId = v;              return this; }
        public Builder transportId(String v)               { this.transportId = v;              return this; }
        public Builder temperature(Double v)              { this.temperature = v;              return this; }
        public Builder topP(Double v)                     { this.topP = v;                     return this; }
        public Builder maxTokens(Integer v)               { this.maxTokens = v;                return this; }
        public Builder costBudget(Budget v)               { this.costBudget = v;               return this; }
        public Builder costCurrency(String v)             { this.costCurrency = v;             return this; }
        /** Sugar for an inline {@link LlmTransport} — see the class javadoc. */
        public Builder baseUrl(String v)                  { this.baseUrl = v;                  return this; }
        /** Sugar for an inline {@link LlmTransport} — see the class javadoc. */
        public Builder apiKey(String v)                   { this.apiKey = v;                   return this; }
        /** Sugar for an inline {@link LlmTransport} — see the class javadoc. */
        public Builder modelName(String v)                { this.modelName = v;                return this; }
        public Builder streamingEnabled(boolean v)        { this.streamingEnabled = v;         return this; }
        public Builder nativeJsonSchema(boolean v)        { this.nativeJsonSchema = v;         return this; }
        public Builder costInputPer1kTokens(Money v)      { this.costInputPer1kTokens = v;     return this; }
        public Builder costOutputPer1kTokens(Money v)     { this.costOutputPer1kTokens = v;    return this; }
        /**
         * Opts into an explicit transport version pin (ADR-039 §4) instead of the default
         * follow-latest-at-pin-time. {@code null} (the default) means "use whatever is
         * current when a session pins it".
         */
        public Builder pinnedTransportVersion(Long v)     { this.pinnedTransportVersion = v;   return this; }

        public LlmProfile build() {
            LlmTransport inline = (baseUrl != null && !baseUrl.isBlank() && modelName != null && !modelName.isBlank())
                    ? new LlmTransport(baseUrl, apiKey, modelName)
                    : null;
            return new LlmProfile(transportId, inline, temperature, topP,
                    maxTokens, costBudget, costCurrency,
                    streamingEnabled, nativeJsonSchema,
                    costInputPer1kTokens, costOutputPer1kTokens, pinnedTransportVersion);
        }
    }
}
