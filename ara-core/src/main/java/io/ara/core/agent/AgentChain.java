package io.ara.core.agent;

import io.ara.core.common.Money;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Aggregation policy types for {@link AgentFuture#allOf}.
 *
 * <p>Kept separate from {@link AgentFuture} so the fan-out execution mechanics
 * (a thin, JDK-only wrapper over {@code CompletableFuture}) stay decoupled from
 * the aggregation policy (how N responses become one).
 */
public final class AgentChain {

    private AgentChain() {}

    /**
     * Builds a successful merged response: {@code content} as given, with
     * iterations/tokens/cost/elapsed summed across all {@code responses}. Shared by
     * the merge strategies below (and by {@code MergeStrategies} in {@code ara-runtime})
     * so every strategy accounts for the real cost of the fan-out it merged, rather
     * than silently reporting zero for strategies that don't happen to reuse this.
     *
     * <p>Accumulated in a single pass. The previous version made four separate stream
     * traversals of {@code responses} — one per summed field — and, more importantly,
     * summed {@link AgentResponse#totalTokens()} into the deprecated combined-count
     * factory, which files the whole amount under {@code outputTokens} and leaves
     * {@code inputTokens} at zero. Any per-token cost computed downstream from that split
     * was therefore wrong for every fan-out. Both counts are now carried through
     * separately.
     *
     * @throws IllegalArgumentException if {@code responses} is empty — there is no task id
     *         or agent id to attribute the merged response to
     */
    public static AgentResponse aggregateSuccess(String content, List<AgentResponse> responses) {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(responses, "responses must not be null");
        if (responses.isEmpty()) {
            throw new IllegalArgumentException("aggregateSuccess requires at least one response");
        }

        int iterations = 0;
        int inputTokens = 0;
        int outputTokens = 0;
        Money cost = Money.zero(responses.get(0).estimatedCost().currency());
        Duration elapsed = Duration.ZERO;
        for (AgentResponse r : responses) {
            iterations   += r.iterationsUsed();
            inputTokens  += r.inputTokens();
            outputTokens += r.outputTokens();
            cost          = cost.plus(r.estimatedCost());
            elapsed       = elapsed.plus(r.elapsedTime());
        }

        AgentResponse first = responses.get(0);
        return AgentResponse.success(first.taskId(), first.agentId(), content,
                iterations, inputTokens, outputTokens, cost, elapsed, List.of());
    }

    /**
     * Joins the failure reasons of {@code responses}, skipping the null/blank ones.
     * {@link AgentResponse#failureReason()} is nullable, so a plain {@code
     * map(AgentResponse::failureReason).collect(joining("; "))} renders missing reasons as
     * the literal text {@code "null"} in the aggregate message shown to the caller.
     */
    private static String joinFailureReasons(List<AgentResponse> responses) {
        String joined = responses.stream()
                .map(AgentResponse::failureReasonOpt)
                .flatMap(Optional::stream)
                .collect(Collectors.joining("; "));
        return joined.isEmpty() ? "no reason reported" : joined;
    }

    /**
     * Aggregation strategy for {@link AgentFuture#allOf}.
     * Receives responses already filtered by {@link FailurePolicy} — at least one guaranteed.
     */
    @FunctionalInterface
    public interface MergeStrategy {
        AgentResponse merge(List<AgentResponse> responses);

        /**
         * Concatenates outputs as a raw JSON array: {@code [out1, out2, out3]}.
         * Assumes each output is already valid JSON.
         */
        static MergeStrategy rawJsonConcat() {
            return responses -> {
                String joined = responses.stream()
                        .map(AgentResponse::content)
                        .collect(Collectors.joining(",", "[", "]"));
                return aggregateSuccess(joined, responses);
            };
        }

        /** Returns the first response (first-wins). */
        static MergeStrategy firstWins() {
            return responses -> responses.get(0);
        }

        /** Concatenates outputs separated by a delimiter. */
        static MergeStrategy joining(String delimiter) {
            return responses -> {
                String joined = responses.stream()
                        .map(AgentResponse::content)
                        .collect(Collectors.joining(delimiter));
                return aggregateSuccess(joined, responses);
            };
        }
    }

    /** Behaviour when one or more agents in an {@link AgentFuture#allOf} fan-out fail. */
    public enum FailurePolicy {

        FAIL_FAST {
            @Override
            public AgentResponse apply(List<AgentResponse> all, MergeStrategy merge) {
                return all.stream().filter(r -> !r.isSuccess()).findFirst()
                        .orElseGet(() -> merge.merge(all));
            }
        },

        PARTIAL_OK {
            @Override
            public AgentResponse apply(List<AgentResponse> all, MergeStrategy merge) {
                List<AgentResponse> successes = all.stream()
                        .filter(AgentResponse::isSuccess).toList();
                if (successes.isEmpty()) {
                    return AgentResponse.failure(all.get(0).taskId(), all.get(0).agentId(),
                            "All parallel agents failed: " + joinFailureReasons(all), Duration.ZERO);
                }
                return merge.merge(successes);
            }
        },

        REQUIRE_ALL {
            @Override
            public AgentResponse apply(List<AgentResponse> all, MergeStrategy merge) {
                List<AgentResponse> failures = all.stream()
                        .filter(r -> !r.isSuccess()).toList();
                if (!failures.isEmpty()) {
                    return AgentResponse.failure(all.get(0).taskId(), all.get(0).agentId(),
                            failures.size() + "/" + all.size() +
                            " parallel agents failed: " + joinFailureReasons(failures), Duration.ZERO);
                }
                return merge.merge(all);
            }
        };

        public abstract AgentResponse apply(List<AgentResponse> responses, MergeStrategy merge);
    }
}
