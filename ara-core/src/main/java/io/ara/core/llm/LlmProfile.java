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
        Long         pinnedTransportVersion,
        ReasoningEffort reasoningEffort,
        Integer      thinkingBudgetTokens,
        Boolean      returnReasoning
) {
    public LlmProfile {
        Objects.requireNonNull(transportId, "transportId must not be null");
        if (temperature != null && (temperature < 0.0 || temperature > 2.0))
            throw new IllegalArgumentException("temperature must be in [0.0, 2.0]");
        if (topP != null && (topP < 0.0 || topP > 1.0))
            throw new IllegalArgumentException("topP must be in [0.0, 1.0]");
        if (thinkingBudgetTokens != null && thinkingBudgetTokens < 1)
            throw new IllegalArgumentException("thinkingBudgetTokens must be >= 1, got: " + thinkingBudgetTokens);
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
    /**
     * The shape before the reasoning options, kept so every existing direct {@code new LlmProfile(...)}
     * keeps compiling, with the three options unset ({@code null}: "do not mention reasoning to the
     * provider").
     */
    public LlmProfile(String transportId, LlmTransport inlineTransport, Double temperature, Double topP,
                      Integer maxTokens, Budget costBudget, String costCurrency, boolean streamingEnabled,
                      boolean nativeJsonSchema, Money costInputPer1kTokens, Money costOutputPer1kTokens,
                      Long pinnedTransportVersion) {
        this(transportId, inlineTransport, temperature, topP, maxTokens, costBudget, costCurrency,
                streamingEnabled, nativeJsonSchema, costInputPer1kTokens, costOutputPer1kTokens,
                pinnedTransportVersion, null, null, null);
    }

    /**
     * A builder pre-filled with every field of this profile, so a copy that changes one thing keeps the
     * rest. Copying a profile field by field with the constructor silently drops any field added later
     * (that is how a new option would have been lost on every mutation of the evolution cycle).
     */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.transportId = transportId;
        b.temperature = temperature;
        b.topP = topP;
        b.maxTokens = maxTokens;
        b.costBudget = costBudget;
        b.costCurrency = costCurrency;
        if (inlineTransport != null) {
            b.baseUrl = inlineTransport.baseUrl();
            b.apiKey = inlineTransport.apiKey();
            b.modelName = inlineTransport.modelName();
        }
        b.streamingEnabled = streamingEnabled;
        b.nativeJsonSchema = nativeJsonSchema;
        b.costInputPer1kTokens = costInputPer1kTokens;
        b.costOutputPer1kTokens = costOutputPer1kTokens;
        b.pinnedTransportVersion = pinnedTransportVersion;
        b.reasoningEffort = reasoningEffort;
        b.thinkingBudgetTokens = thinkingBudgetTokens;
        b.returnReasoning = returnReasoning;
        return b;
    }

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
        private ReasoningEffort reasoningEffort     = null;
        private Integer      thinkingBudgetTokens    = null;
        private Boolean      returnReasoning         = null;

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
        /**
         * Declares that the schema of a structured-output contract should travel as a
         * provider-native {@code response_format} rather than be appended to the system prompt.
         *
         * <p>Honoured by adapters that declare {@link LlmClient#supportsNativeStructuredOutput()}
         * — today the OpenAI adapter, per endpoint. The schema then reaches the provider on the
         * request itself, which is more reliable than an instruction a model can ignore; add
         * {@code OpenAiLlmClient.Builder.strictJsonSchema(true)} to have the provider also
         * <em>guarantee</em> conformance by constraining decoding.
         *
         * <p>Because {@code response_format} support is per-<em>endpoint</em> rather than
         * per-provider, a client pointed at an OpenAI-compatible gateway does not claim it
         * unless told to. Asking for the native path on a client that cannot do it fails the
         * call with a non-retryable error naming the capability, rather than silently dropping
         * the schema.
         *
         * <p>Left at its default {@code false}, the schema is appended to the system prompt —
         * which works against every endpoint, including gateways that support no
         * {@code response_format} at all.
         */
        public Builder nativeJsonSchema(boolean v)        { this.nativeJsonSchema = v;         return this; }
        public Builder costInputPer1kTokens(Money v)      { this.costInputPer1kTokens = v;     return this; }
        public Builder costOutputPer1kTokens(Money v)     { this.costOutputPer1kTokens = v;    return this; }
        /**
         * Opts into an explicit transport version pin (ADR-039 §4) instead of the default
         * follow-latest-at-pin-time. {@code null} (the default) means "use whatever is
         * current when a session pins it".
         */
        public Builder pinnedTransportVersion(Long v)     { this.pinnedTransportVersion = v;   return this; }

        /**
         * How hard the model is asked to reason; {@code null} (the default) says nothing to the
         * provider. Applied only by providers that have such a dial (OpenAI); the others reject it.
         */
        public Builder reasoningEffort(ReasoningEffort v) { this.reasoningEffort = v;          return this; }

        /**
         * A cap on the tokens the model may spend thinking; {@code null} says nothing to the
         * provider. Applied only by providers that take a budget (Anthropic); the others reject it.
         */
        public Builder thinkingBudgetTokens(Integer v)    { this.thinkingBudgetTokens = v;     return this; }

        /**
         * Whether the model's reasoning, where the provider returns it, is recorded as
         * {@code REASONING} steps. {@code null} and {@code false} both leave it out; it is
         * {@code Boolean}, not {@code boolean}, so that an agent that never mentions it keeps the
         * content hash it had before this option existed.
         */
        public Builder returnReasoning(Boolean v)         { this.returnReasoning = v;          return this; }

        public LlmProfile build() {
            LlmTransport inline = (baseUrl != null && !baseUrl.isBlank() && modelName != null && !modelName.isBlank())
                    ? new LlmTransport(baseUrl, apiKey, modelName)
                    : null;
            return new LlmProfile(transportId, inline, temperature, topP,
                    maxTokens, costBudget, costCurrency,
                    streamingEnabled, nativeJsonSchema,
                    costInputPer1kTokens, costOutputPer1kTokens, pinnedTransportVersion,
                    reasoningEffort, thinkingBudgetTokens, returnReasoning);
        }
    }
}
