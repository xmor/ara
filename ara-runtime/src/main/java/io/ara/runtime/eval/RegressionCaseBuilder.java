package io.ara.runtime.eval;

import io.ara.core.eval.EvalCase;
import io.ara.core.trace.BlobStore;
import io.ara.core.trace.SpanStatus;
import io.ara.core.trace.TraceSpan;
import io.ara.core.trace.TraceStore;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns a flagged production {@code run_id} into a regression {@link EvalCase}
 * (ADR-0071). The mechanical part is automatic — the original input and context are
 * recovered byte-for-byte from the content-addressed trace (ADR-0068), no manual
 * re-transcription. The judgemental part stays explicit:
 *
 * <ul>
 *   <li><b>"is this a real bug"</b> — never auto-detected. A human decides and passes the
 *       {@code runId} (ADR-0071 D1); everything from there is automatic.</li>
 *   <li><b>the verifier</b> — auto-derived <em>only</em> for a structurally recognisable
 *       failure (a schema / validation / parse error): those produce a blocking
 *       {@code assertion} verifier and the case is {@link EvalCase.Status#READY}
 *       (ADR-0071 D3). A semantically-subtle failure produces no verifier — the case is
 *       {@link EvalCase.Status#DRAFT} and is excluded from {@code verdict} until a human
 *       completes it (ADR-0071 D4). A case that is silently incomplete is worse than none.</li>
 * </ul>
 */
public final class RegressionCaseBuilder {

    private RegressionCaseBuilder() {}

    /** The strategy id a DRAFT case carries until a human supplies a real verifier. */
    public static final String PENDING_VERIFIER = "pending_human_verifier";

    /** A verifier the builder could derive on its own from a structurally-recognisable failure (ADR-0071 D3). */
    public record VerifierDraft(String strategyId, Map<String, String> config) {
        public VerifierDraft {
            Objects.requireNonNull(strategyId, "strategyId must not be null");
            config = Map.copyOf(Objects.requireNonNullElse(config, Map.of()));
        }
    }

    /**
     * @throws IllegalStateException if the run has no root span or its prompt payload is
     *                               not resolvable from {@code blobs} — the corpus entry
     *                               cannot be built without the original input
     */
    public static EvalCase from(String runId, TraceStore traces, BlobStore blobs, String suiteId) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(traces, "traces must not be null");
        Objects.requireNonNull(blobs, "blobs must not be null");
        Objects.requireNonNull(suiteId, "suiteId must not be null");

        List<TraceSpan> spans = traces.findByRunId(runId);
        if (spans.isEmpty()) {
            throw new IllegalStateException("no trace for run " + runId + " — corpus entry not buildable");
        }
        TraceSpan root = rootSpanOf(spans, runId);

        String promptRef = root.promptRef();
        if (promptRef == null) {
            throw new IllegalStateException("root span of run " + runId + " has no promptRef — corpus entry not buildable");
        }
        byte[] payload = blobs.get(promptRef)
                .orElseThrow(() -> new IllegalStateException(
                        "promptRef not resolvable for run " + runId + " — corpus entry not buildable"));
        String input = new String(payload, StandardCharsets.UTF_8);

        Optional<VerifierDraft> autoVerifier = deriveVerifier(spans);

        return new EvalCase(
                UUID.randomUUID().toString(),
                suiteId,
                false,                                    // a regression case is always seen — hold-out does not apply (ADR-0071 D2)
                List.of(),
                "production_failure",                     // already in EvalCase's schema (ADR-0070 D1)
                input,
                contextFrom(runId, root, spans),
                autoVerifier.map(VerifierDraft::strategyId).orElse(PENDING_VERIFIER),
                autoVerifier.map(VerifierDraft::config).orElse(Map.of()),
                0,
                autoVerifier.isPresent() ? EvalCase.Status.READY : EvalCase.Status.DRAFT   // ADR-0071 D4
        );
    }

    private static TraceSpan rootSpanOf(List<TraceSpan> spans, String runId) {
        return spans.stream()
                .filter(s -> s.parentSpanId() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "run " + runId + " has no root span (every span has a parent) — corpus entry not buildable"));
    }

    /**
     * ADR-0071 D3: a verifier is derivable only when the failure is structurally
     * recognisable — a schema / validation / parse error, whose message the real ARA
     * contract processors ({@code JsonSchemaValidator}, {@code RegexValidator}, …) emit in
     * a recognisable shape. A semantically-wrong-but-syntactically-valid output yields
     * nothing, and the case is born DRAFT.
     */
    private static Optional<VerifierDraft> deriveVerifier(List<TraceSpan> spans) {
        String reason = spans.stream()
                .map(TraceSpan::status)
                .filter(SpanStatus.Failed.class::isInstance)
                .map(s -> ((SpanStatus.Failed) s).reason())
                .findFirst()
                .orElse(null);
        if (reason == null) {
            return Optional.empty();
        }
        String r = reason.toLowerCase(Locale.ROOT);
        boolean structural =
                r.contains("schema")
                        || r.contains("not valid json") || r.contains("invalid json") || r.contains("unparseable")
                        || r.contains("required field") || r.contains("missing field") || r.contains("missing required")
                        || r.contains("does not match pattern") || r.contains("regex")
                        || r.contains("out of range") || r.contains("not in enum");
        return structural
                ? Optional.of(new VerifierDraft("assertion", Map.of("assertedFrom", "production_failure_reason")))
                : Optional.empty();
    }

    private static Map<String, String> contextFrom(String runId, TraceSpan root, List<TraceSpan> spans) {
        String outcome = spans.stream()
                .map(TraceSpan::status)
                .filter(SpanStatus.Failed.class::isInstance)
                .map(s -> "Failed: " + ((SpanStatus.Failed) s).reason())
                .findFirst()
                .orElse(root.status().getClass().getSimpleName());
        return Map.of(
                "run_id", runId,
                "root_span", root.spanId(),
                "agent_id", root.agentId(),
                "outcome", outcome);
    }
}
