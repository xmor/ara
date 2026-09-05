package io.ara.runtime.trace;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.StepType;
import io.ara.core.common.Money;
import io.ara.core.trace.BlobStore;
import io.ara.core.trace.SpanStatus;
import io.ara.core.trace.TraceSpan;
import io.ara.runtime.agent.FailureKind;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Projects one completed single-agent execution into a run trace — the first of ADR-0068
 * D1's two emission points ("each accumulated {@code ExecutionStep} produces a
 * {@code TraceSpan}"), the deferred follow-up of that ADR. Pure and side-effect-free apart
 * from the content it puts into the {@link BlobStore}; the caller appends the spans to a
 * {@code TraceStore}.
 *
 * <p><b>Shape.</b> One <em>root</em> span for the whole execution (parent = {@code null},
 * {@code spanId = "<agentId>#run"}) carrying the token/cost totals, the content-addressed
 * prompt/output refs and the outcome — plus one <em>child</em> span per
 * {@link ExecutionStep} ({@code spanId = "<agentId>#<iteration>-<ordinal>"}, parent = the
 * root) for the reasoning structure. {@code ExecutionStep} carries no per-step tokens or
 * cost, so only the root span is billed; the children are structural.
 *
 * <p>The root span's {@link TraceSpan#failureKind()} is set from
 * {@link FailureKind#classify} on a failure — this also wires the ADR-0074 D6 field that
 * the dashboard's failure-mode panel reads.
 *
 * <p><b>Not here yet</b>: the second emission point — a {@code TraceSpan} per workflow
 * journal entry (ADR-052 D1). {@code specHash} is always {@code null} (agents are not
 * {@code AgentSpec}-tracked yet) and {@code contextProvenanceUntrusted} is always
 * {@code false} (ADR-0068 D3's "recorded at emission" signal is not plumbed through).
 */
public final class TraceProjection {

    private TraceProjection() {}

    /** The run id for a single-agent execution: correlation id, else session id, else task id. */
    public static String runIdOf(AgentTask task) {
        Objects.requireNonNull(task, "task must not be null");
        if (task.correlationId() != null && !task.correlationId().isBlank()) {
            return task.correlationId();
        }
        if (task.sessionId() != null && task.sessionId().value() != null && !task.sessionId().value().isBlank()) {
            return task.sessionId().value();
        }
        return task.taskId();
    }

    /** The spans for one completed execution — root first, then one per {@link ExecutionStep}. */
    public static List<TraceSpan> project(AgentTask task, AgentResponse response, BlobStore blobs) {
        Objects.requireNonNull(task, "task must not be null");
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(blobs, "blobs must not be null");

        String runId = runIdOf(task);
        String agentId = response.agentId().value();
        String rootSpanId = agentId + "#run";

        Instant endedAt = response.completedAt();
        Instant startedAt = endedAt.minus(response.elapsedTime());
        if (startedAt.isAfter(endedAt)) {
            startedAt = endedAt;
        }

        String promptRef = ref(blobs, task.input());
        boolean ok = response.isSuccess();
        String outputRef = ok ? ref(blobs, response.content()) : null;
        SpanStatus status = ok
                ? new SpanStatus.Completed()
                : new SpanStatus.Failed(Objects.requireNonNullElse(response.failureReason(), "agent execution failed"));

        TraceSpan.Builder root = TraceSpan.builder(runId, rootSpanId, agentId)
                .promptRef(promptRef)
                .outputRef(outputRef)
                .tokensIn(Math.max(0, response.inputTokens()))
                .tokensOut(Math.max(0, response.outputTokens()))
                .cost(response.estimatedCost() != null ? response.estimatedCost() : Money.ZERO_EUR)
                .status(status)
                .startedAt(startedAt)
                .endedAt(endedAt);
        if (!ok) {
            root.failureKind(FailureKind.classify(response.failureReason()).name());   // ADR-0074 D6
        }

        List<TraceSpan> spans = new ArrayList<>();
        spans.add(root.build());

        List<ExecutionStep> steps = response.steps();
        for (int i = 0; i < steps.size(); i++) {
            ExecutionStep step = steps.get(i);
            String stepPrompt = step.type() == StepType.TOOL_CALL
                    ? ref(blobs, step.toolId() + " " + Objects.requireNonNullElse(step.arguments(), ""))
                    : null;
            spans.add(TraceSpan.builder(runId, agentId + "#" + step.iteration() + "-" + i, agentId)
                    .parentSpanId(rootSpanId)
                    .promptRef(stepPrompt)
                    .outputRef(ref(blobs, step.content()))
                    .status(new SpanStatus.Completed())
                    .startedAt(startedAt)
                    .endedAt(endedAt)
                    .build());
        }
        return spans;
    }

    /** A minimal Failed root span for an execution that threw before producing an {@link AgentResponse}. */
    public static TraceSpan failedByException(AgentTask task, String agentId, Throwable error, BlobStore blobs) {
        Instant now = Instant.now();
        return TraceSpan.builder(runIdOf(task), agentId + "#run", agentId)
                .promptRef(ref(blobs, task.input()))
                .status(new SpanStatus.Failed("Unexpected error: "
                        + Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName())))
                .failureKind(FailureKind.classify("Unexpected error:").name())
                .startedAt(now)
                .endedAt(now)
                .build();
    }

    private static String ref(BlobStore blobs, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        return blobs.put(content.getBytes(StandardCharsets.UTF_8));
    }
}
