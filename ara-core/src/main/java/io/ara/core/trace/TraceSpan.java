package io.ara.core.trace;

import io.ara.core.common.Money;

import java.time.Instant;
import java.util.Objects;

/**
 * The common shape both of ARA's existing traces project into (ADR-0068 D1): one unit of
 * work in a run — a workflow node occurrence (ADR-052 D1 journal) or a single-agent ReAct
 * iteration ({@code io.ara.core.agent.ExecutionStep}) — carrying enough to reconstruct the
 * whole execution from a single {@code runId} without touching application logs.
 *
 * <p>Raw prompt/output text does <em>not</em> live here: {@link #promptRef()} /
 * {@link #outputRef()} are content-addressed {@link BlobStore} refs, so the differentiated
 * retention of ADR-0061 can drop the heavy blobs of a successful run while keeping the
 * lightweight span. {@link #specHash()} is nullable until every agent is built through
 * {@code AgentSpec} (ADR-0065) — a span from a plain-{@code AgentConfig} agent has it
 * empty, an honest reflection of adoption, not a defect.
 *
 * <p>{@link #contextProvenanceUntrusted()} is <em>recorded</em> at emission from how the
 * task was built (e.g. an {@code InputProcessor} attached web-retrieved content), never
 * inferred from the span text after the fact (ADR-0068 D3).
 *
 * <p>{@link #failureKind()} is the additive, nullable extension declared by ADR-0074 D6:
 * a bounded label for <em>why the execution stopped</em>, populated only when
 * {@code status} is {@link SpanStatus.Failed}. It holds the {@code name()} of
 * {@code io.ara.runtime.agent.FailureKind} (a {@link String} here, not that enum, so
 * {@code ara-core} keeps no dependency on {@code ara-runtime} — the same string already
 * emitted as the {@code agent.failure_kind} span attribute). It is deliberately
 * <em>not</em> a semantic cause ("ambiguous spec", "wrong tool"): that axis is ADR-0080's
 * {@code FailureCategory}, orthogonal to this one.
 *
 * @param runId                       reuses {@code RunContext.correlationId} (ADR-041/DR-5)
 * @param spanId                      {@code nodeId#occurrence} for a workflow; {@code agentId#iteration} otherwise
 * @param parentSpanId                nullable — nesting across delegation
 * @param agentId                     the agent that ran this unit
 * @param specHash                    from {@code AgentSpec} (ADR-0065); nullable
 * @param promptRef                   content-addressed ref of the prompt sent; nullable
 * @param outputRef                   content-addressed ref of the output produced; nullable (Failed/Suspended)
 * @param tokensIn                    prompt tokens; {@code >= 0}
 * @param tokensOut                   completion tokens; {@code >= 0}
 * @param cost                        cost attributed to this span; never {@code null} (use {@code Money.zero(...)})
 * @param status                      {@link SpanStatus}
 * @param contextProvenanceUntrusted  D3 — recorded, not computed
 * @param startedAt                   emission-time span start
 * @param endedAt                     span end; not before {@code startedAt}
 * @param failureKind                 ADR-0074 D6 — {@code FailureKind.name()}; nullable, only for {@link SpanStatus.Failed}
 */
public record TraceSpan(
        String     runId,
        String     spanId,
        String     parentSpanId,
        String     agentId,
        String     specHash,
        String     promptRef,
        String     outputRef,
        int        tokensIn,
        int        tokensOut,
        Money      cost,
        SpanStatus status,
        boolean    contextProvenanceUntrusted,
        Instant    startedAt,
        Instant    endedAt,
        String     failureKind
) {

    public TraceSpan {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(spanId, "spanId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(cost, "cost must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(endedAt, "endedAt must not be null");
        if (runId.isBlank())  throw new IllegalArgumentException("runId must not be blank");
        if (spanId.isBlank()) throw new IllegalArgumentException("spanId must not be blank");
        if (tokensIn < 0)  throw new IllegalArgumentException("tokensIn must be >= 0, got: " + tokensIn);
        if (tokensOut < 0) throw new IllegalArgumentException("tokensOut must be >= 0, got: " + tokensOut);
        if (endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("endedAt (" + endedAt + ") is before startedAt (" + startedAt + ")");
        }
        if (failureKind != null && !(status instanceof SpanStatus.Failed)) {
            throw new IllegalArgumentException(
                    "failureKind is only valid for a Failed span (ADR-0074 D6), got status: " + status);
        }
    }

    /**
     * Backward-compatible canonical shape before ADR-0074 D6 — {@code failureKind} is left
     * unset. Every pre-existing emission point keeps compiling unchanged.
     */
    public TraceSpan(String runId, String spanId, String parentSpanId, String agentId, String specHash,
                     String promptRef, String outputRef, int tokensIn, int tokensOut, Money cost,
                     SpanStatus status, boolean contextProvenanceUntrusted, Instant startedAt, Instant endedAt) {
        this(runId, spanId, parentSpanId, agentId, specHash, promptRef, outputRef, tokensIn, tokensOut,
                cost, status, contextProvenanceUntrusted, startedAt, endedAt, null);
    }

    public static Builder builder(String runId, String spanId, String agentId) {
        return new Builder(runId, spanId, agentId);
    }

    /** Fills the many optional fields without a 14-argument call at every emission point. */
    public static final class Builder {
        private final String runId;
        private final String spanId;
        private final String agentId;
        private String     parentSpanId;
        private String     specHash;
        private String     promptRef;
        private String     outputRef;
        private int        tokensIn;
        private int        tokensOut;
        private Money      cost = Money.ZERO_EUR;
        private SpanStatus status = new SpanStatus.Completed();
        private boolean    contextProvenanceUntrusted;
        private Instant    startedAt;
        private Instant    endedAt;
        private String     failureKind;

        private Builder(String runId, String spanId, String agentId) {
            this.runId = runId;
            this.spanId = spanId;
            this.agentId = agentId;
        }

        public Builder parentSpanId(String v)  { this.parentSpanId = v; return this; }
        public Builder specHash(String v)      { this.specHash = v;     return this; }
        public Builder promptRef(String v)     { this.promptRef = v;    return this; }
        public Builder outputRef(String v)     { this.outputRef = v;    return this; }
        public Builder tokensIn(int v)         { this.tokensIn = v;     return this; }
        public Builder tokensOut(int v)        { this.tokensOut = v;    return this; }
        public Builder cost(Money v)           { this.cost = v;         return this; }
        public Builder status(SpanStatus v)    { this.status = v;       return this; }
        public Builder contextProvenanceUntrusted(boolean v) { this.contextProvenanceUntrusted = v; return this; }
        public Builder startedAt(Instant v)    { this.startedAt = v;    return this; }
        public Builder endedAt(Instant v)      { this.endedAt = v;      return this; }

        /** ADR-0074 D6 — {@code FailureKind.name()}; only meaningful together with a {@link SpanStatus.Failed} status. */
        public Builder failureKind(String v)   { this.failureKind = v;  return this; }

        public TraceSpan build() {
            return new TraceSpan(runId, spanId, parentSpanId, agentId, specHash, promptRef, outputRef,
                    tokensIn, tokensOut, cost, status, contextProvenanceUntrusted, startedAt, endedAt, failureKind);
        }
    }
}
