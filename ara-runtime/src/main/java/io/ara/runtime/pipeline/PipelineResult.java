package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentResponse;
import io.ara.core.common.Money;
import io.ara.runtime.pipeline.PipelineExecution.StepResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The final output of an {@link AgentPipeline} run.
 *
 * @param success       true if the pipeline completed without contract violations or failures
 * @param finalOutput   the output string produced by the last step
 * @param lastResponse  the full {@link AgentResponse} from the last executed step
 * @param stepHistory   every executed step's result, in execution order, each carrying its
 *                      own {@link AgentResponse} — the basis for {@link #stepsExecuted()}
 *                      and the {@code total*} aggregates below
 * @param failureReason non-null when {@code success == false}
 * @param elapsed       total wall-clock duration of the pipeline run
 */
public record PipelineResult(
        boolean            success,
        String             finalOutput,
        AgentResponse      lastResponse,
        List<StepResult>   stepHistory,
        String             failureReason,
        Instant            completedAt,
        Duration           elapsed
) {

    public PipelineResult {
        Objects.requireNonNull(stepHistory, "stepHistory must not be null");
        stepHistory = List.copyOf(stepHistory);
    }

    /** Names of the executed steps, in execution order (including a step that ultimately failed). */
    public List<String> stepsExecuted() {
        return stepHistory.stream().map(StepResult::stepName).toList();
    }

    /** Prompt/input tokens summed across every executed step's {@link AgentResponse}, not just the last. */
    public int totalInputTokens() {
        return stepHistory.stream().mapToInt(s -> s.response().inputTokens()).sum();
    }

    /** Completion/output tokens summed across every executed step's {@link AgentResponse}, not just the last. */
    public int totalOutputTokens() {
        return stepHistory.stream().mapToInt(s -> s.response().outputTokens()).sum();
    }

    /** {@link #totalInputTokens()} + {@link #totalOutputTokens()}. */
    public int totalTokens() {
        return totalInputTokens() + totalOutputTokens();
    }

    /** Estimated cost summed across every executed step's {@link AgentResponse}, not just the last. */
    public Money totalCost() {
        if (stepHistory.isEmpty()) {
            return Money.ZERO_EUR;
        }
        Money total = Money.zero(stepHistory.get(0).response().estimatedCost().currency());
        for (StepResult step : stepHistory) {
            total = total.plus(step.response().estimatedCost());
        }
        return total;
    }

    public static PipelineResult success(
            String finalOutput,
            AgentResponse lastResponse,
            List<StepResult> stepHistory,
            Duration elapsed
    ) {
        return new PipelineResult(true, finalOutput, lastResponse, stepHistory, null, Instant.now(), elapsed);
    }

    public static PipelineResult failure(
            String reason,
            AgentResponse lastResponse,
            List<StepResult> stepHistory,
            Duration elapsed
    ) {
        return new PipelineResult(false,
                lastResponse != null ? lastResponse.content() : "",
                lastResponse, stepHistory, reason, Instant.now(), elapsed);
    }
}
