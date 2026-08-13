package io.ara.core.agent;

import io.ara.core.common.AgentId;
import io.ara.core.common.Money;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The final output produced by an {@link AraAgent} after completing a task.
 *
 * <p>Carries the textual answer, execution metadata (iterations used, token
 * usage, elapsed time, final cost) and an optional failure reason when the
 * agent ended in {@link AgentState#FAILED}.
 *
 * @param taskId          identifier of the originating {@link AgentTask}
 * @param agentId         the agent that produced this response
 * @param content         the natural-language response text
 * @param finalState      the state the agent was in when this response was emitted
 * @param iterationsUsed  number of ReAct loop iterations consumed
 * @param inputTokens     prompt/input tokens consumed across all LLM calls in this execution
 * @param outputTokens    completion/output tokens consumed across all LLM calls in this execution
 * @param estimatedCost   approximate cost of all LLM calls
 * @param elapsedTime     wall-clock duration from task submission to response
 * @param failureReason   human-readable reason for failure; empty when successful
 * @param completedAt     wall-clock timestamp of response emission
 * @param steps           ordered execution trace (thoughts, tool calls, observations)
 * @param llmProvider     LLM provider id used for this execution (e.g. {@code "gpt-mini"},
 *                        {@code "langchain4j-gpt-4o"}); {@code null} when not tracked
 */
public record AgentResponse(
        String taskId,
        AgentId agentId,
        String content,
        AgentState finalState,
        int iterationsUsed,
        int inputTokens,
        int outputTokens,
        Money estimatedCost,
        Duration elapsedTime,
        String failureReason,
        Instant completedAt,
        List<ExecutionStep> steps,
        String llmProvider
) {

    public AgentResponse {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(finalState, "finalState must not be null");
        Objects.requireNonNull(estimatedCost, "estimatedCost must not be null");
        Objects.requireNonNull(elapsedTime, "elapsedTime must not be null");
        Objects.requireNonNull(completedAt, "completedAt must not be null");
        steps = steps != null ? List.copyOf(steps) : List.of();
    }

    /**
     * Total tokens consumed across all LLM calls in this execution ({@link #inputTokens} +
     * {@link #outputTokens}) — kept as a derived method, rather than a stored field, so
     * the two counts can never drift out of sync with their sum.
     */
    public int totalTokens() {
        return inputTokens + outputTokens;
    }

    /**
     * Returns {@code true} if the agent completed the task successfully.
     *
     * @return {@code true} when {@code finalState == DONE}
     */
    public boolean isSuccess() {
        return finalState == AgentState.DONE;
    }

    /**
     * Returns the failure reason wrapped in an {@link Optional}, empty on success.
     *
     * @return an optional failure reason
     */
    public Optional<String> failureReasonOpt() {
        return (failureReason == null || failureReason.isBlank())
                ? Optional.empty()
                : Optional.of(failureReason);
    }

    /**
     * Factory method for a successful response with the input/output token split.
     *
     * @param taskId         originating task id
     * @param agentId        the producing agent
     * @param content        the answer text
     * @param iterationsUsed ReAct loop iterations consumed
     * @param inputTokens    prompt/input tokens across all LLM calls
     * @param outputTokens   completion/output tokens across all LLM calls
     * @param estimatedCost  estimated cost
     * @param elapsed        wall-clock execution duration
     * @return a successful {@code AgentResponse}
     */
    public static AgentResponse success(
            String taskId,
            AgentId agentId,
            String content,
            int iterationsUsed,
            int inputTokens,
            int outputTokens,
            Money estimatedCost,
            Duration elapsed,
            List<ExecutionStep> steps
    ) {
        return new AgentResponse(
                taskId, agentId, content, AgentState.DONE,
                iterationsUsed, inputTokens, outputTokens, estimatedCost,
                elapsed, null, Instant.now(), steps, null
        );
    }

    /**
     * Factory method for a successful response from a single combined token count.
     *
     * @deprecated use {@link #success(String, AgentId, String, int, int, int, Money, Duration, List)}
     *             when the strategy tracks prompt/output tokens separately. Kept for callers
     *             that only have a combined total: the full count is attributed to {@link
     *             #outputTokens}, {@link #inputTokens} is {@code 0} — {@link #totalTokens()}
     *             is unaffected either way. {@code estimatedCost} is interpreted as an EUR amount.
     */
    @Deprecated(forRemoval = false)
    public static AgentResponse success(
            String taskId,
            AgentId agentId,
            String content,
            int iterationsUsed,
            int totalTokens,
            double estimatedCost,
            Duration elapsed,
            List<ExecutionStep> steps
    ) {
        return new AgentResponse(
                taskId, agentId, content, AgentState.DONE,
                iterationsUsed, 0, totalTokens, Money.of(java.math.BigDecimal.valueOf(estimatedCost), "EUR"),
                elapsed, null, Instant.now(), steps, null
        );
    }

    /**
     * Factory method for a failed response.
     *
     * @param taskId        originating task id
     * @param agentId       the producing agent
     * @param failureReason human-readable explanation of the failure
     * @param elapsed       wall-clock execution duration up to the failure
     * @return a failed {@code AgentResponse}
     */
    public static AgentResponse failure(
            String taskId,
            AgentId agentId,
            String failureReason,
            Duration elapsed
    ) {
        return new AgentResponse(
                taskId, agentId, "", AgentState.FAILED,
                0, 0, 0, Money.ZERO_EUR, elapsed, failureReason, Instant.now(), List.of(), null
        );
    }

    public static AgentResponse failure(
            String taskId,
            AgentId agentId,
            String failureReason,
            Duration elapsed,
            List<ExecutionStep> steps
    ) {
        return new AgentResponse(
                taskId, agentId, "", AgentState.FAILED,
                0, 0, 0, Money.ZERO_EUR, elapsed, failureReason, Instant.now(), steps, null
        );
    }

    /** Factory method for a failed response with the input/output token split. */
    public static AgentResponse failure(
            String taskId,
            AgentId agentId,
            String failureReason,
            Duration elapsed,
            int iterationsUsed,
            int inputTokens,
            int outputTokens,
            List<ExecutionStep> steps
    ) {
        return new AgentResponse(
                taskId, agentId, "", AgentState.FAILED,
                iterationsUsed, inputTokens, outputTokens, Money.ZERO_EUR, elapsed, failureReason, Instant.now(), steps, null
        );
    }

    /**
     * Factory method for a failed response from a single combined token count.
     *
     * @deprecated see {@link #success(String, AgentId, String, int, int, int, Money, Duration, List)}.
     */
    @Deprecated(forRemoval = false)
    public static AgentResponse failure(
            String taskId,
            AgentId agentId,
            String failureReason,
            Duration elapsed,
            int iterationsUsed,
            int totalTokens,
            List<ExecutionStep> steps
    ) {
        return new AgentResponse(
                taskId, agentId, "", AgentState.FAILED,
                iterationsUsed, 0, totalTokens, Money.ZERO_EUR, elapsed, failureReason, Instant.now(), steps, null
        );
    }

    /** Returns a copy of this response with {@code content} replaced by {@code newContent}. */
    public AgentResponse withContent(String newContent) {
        Objects.requireNonNull(newContent, "newContent must not be null");
        return new AgentResponse(taskId, agentId, newContent, finalState, iterationsUsed,
                inputTokens, outputTokens, estimatedCost, elapsedTime, failureReason, completedAt, steps, llmProvider);
    }

    /** Returns a copy of this response with {@code llmProvider} set. */
    public AgentResponse withLlmProvider(String llmProvider) {
        return new AgentResponse(taskId, agentId, content, finalState, iterationsUsed,
                inputTokens, outputTokens, estimatedCost, elapsedTime, failureReason, completedAt, steps, llmProvider);
    }

    /** Returns a copy of this response with {@code estimatedCost} replaced. */
    public AgentResponse withCost(Money estimatedCost) {
        Objects.requireNonNull(estimatedCost, "estimatedCost must not be null");
        return new AgentResponse(taskId, agentId, content, finalState, iterationsUsed,
                inputTokens, outputTokens, estimatedCost, elapsedTime, failureReason, completedAt, steps, llmProvider);
    }
}
