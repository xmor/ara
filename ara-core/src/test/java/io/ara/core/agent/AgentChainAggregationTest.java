package io.ara.core.agent;

import io.ara.core.common.AgentId;
import io.ara.core.common.Money;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers {@link AgentChain#aggregateSuccess} and the {@link AgentChain.FailurePolicy} branches. */
class AgentChainAggregationTest {

    private static final AgentId AGENT = AgentId.of("a1");

    private static AgentResponse ok(String content, int iterations, int in, int out, double cost, long millis) {
        return ok(content, iterations, in, out, cost, "EUR", millis);
    }

    private static AgentResponse ok(String content, int iterations, int in, int out, double cost, String currency, long millis) {
        return AgentResponse.success("t1", AGENT, content, iterations, in, out,
                Money.of(java.math.BigDecimal.valueOf(cost), currency),
                Duration.ofMillis(millis), List.of());
    }

    private static AgentResponse ko(String reason) {
        return AgentResponse.failure("t1", AGENT, reason, Duration.ZERO);
    }

    /**
     * Regression: {@code aggregateSuccess} used to sum {@link AgentResponse#totalTokens()}
     * into the deprecated combined-count factory, which files everything under
     * {@code outputTokens} and leaves {@code inputTokens} at 0. Any downstream per-token
     * cost calculation reading the split was therefore wrong for every fan-out.
     */
    @Test
    void aggregateSuccess_preservesTheInputOutputTokenSplit() {
        AgentResponse merged = AgentChain.aggregateSuccess("merged", List.of(
                ok("a", 1, 100, 10, 0.5, 100),
                ok("b", 2, 200, 20, 0.25, 200)));

        assertEquals(300, merged.inputTokens(),  "input tokens must be summed, not collapsed into output");
        assertEquals(30,  merged.outputTokens(), "output tokens must be summed separately");
        assertEquals(330, merged.totalTokens());
    }

    @Test
    void aggregateSuccess_sumsIterationsCostAndElapsed() {
        AgentResponse merged = AgentChain.aggregateSuccess("merged", List.of(
                ok("a", 1, 100, 10, 0.5,  100),
                ok("b", 2, 200, 20, 0.25, 200),
                ok("c", 3, 300, 30, 0.25, 300)));

        assertEquals(6, merged.iterationsUsed());
        assertEquals(0, merged.estimatedCost().compareTo(Money.of("1.0", "EUR")));
        assertEquals(Duration.ofMillis(600), merged.elapsedTime());
        assertEquals("merged", merged.content());
        assertTrue(merged.isSuccess());
    }

    @Test
    void aggregateSuccess_mixedCurrencies_throws() {
        List<AgentResponse> responses = List.of(
                ok("a", 1, 1, 1, 0.5, "EUR", 1),
                ok("b", 1, 1, 1, 0.5, "USD", 1));

        assertThrows(IllegalArgumentException.class,
                () -> AgentChain.aggregateSuccess("merged", responses));
    }

    @Test
    void aggregateSuccess_rejectsAnEmptyList_ratherThanThrowingIndexOutOfBounds() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentChain.aggregateSuccess("merged", List.of()));
        assertTrue(e.getMessage().contains("at least one response"));
    }

    /**
     * Regression: the aggregate message was built with {@code
     * map(AgentResponse::failureReason).collect(joining("; "))}. {@code failureReason} is
     * nullable, so a response failing without a reason rendered as the literal
     * text {@code "null"} in the message shown to the caller.
     */
    @Test
    void failurePolicies_neverRenderTheLiteralTextNullInTheAggregateMessage() {
        AgentResponse reasonless = new AgentResponse("t1", AGENT, "", AgentState.FAILED,
                0, 0, 0, Money.ZERO_EUR, Duration.ZERO, null, java.time.Instant.now(), List.of(), null);

        AgentResponse partial = AgentChain.FailurePolicy.PARTIAL_OK.apply(
                List.of(reasonless, ko("boom")), AgentChain.MergeStrategy.firstWins());
        assertFalse(partial.failureReason().contains("null"), partial.failureReason());
        assertTrue(partial.failureReason().contains("boom"));

        AgentResponse requireAll = AgentChain.FailurePolicy.REQUIRE_ALL.apply(
                List.of(ok("fine", 1, 1, 1, 0, 1), reasonless), AgentChain.MergeStrategy.firstWins());
        assertFalse(requireAll.failureReason().contains("null"), requireAll.failureReason());
        assertTrue(requireAll.failureReason().contains("1/2"));
    }

    @Test
    void partialOk_withNoSuccessesAndNoReasons_stillProducesAReadableMessage() {
        AgentResponse reasonless = new AgentResponse("t1", AGENT, "", AgentState.FAILED,
                0, 0, 0, Money.ZERO_EUR, Duration.ZERO, null, java.time.Instant.now(), List.of(), null);

        AgentResponse result = AgentChain.FailurePolicy.PARTIAL_OK.apply(
                List.of(reasonless), AgentChain.MergeStrategy.firstWins());

        assertEquals("All parallel agents failed: no reason reported", result.failureReason());
    }

    @Test
    void failFast_returnsTheFirstFailure_withoutMerging() {
        AgentResponse result = AgentChain.FailurePolicy.FAIL_FAST.apply(
                List.of(ok("fine", 1, 1, 1, 0, 1), ko("first failure"), ko("second failure")),
                AgentChain.MergeStrategy.firstWins());

        assertEquals("first failure", result.failureReason());
    }
}
