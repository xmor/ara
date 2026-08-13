package io.ara.core.agent;

import io.ara.core.common.AgentId;
import io.ara.core.common.Money;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the input/output token split added to {@link AgentResponse}: the new
 * fine-grained factories store them directly, the legacy single-total factories keep
 * {@link AgentResponse#totalTokens()} unchanged by attributing the whole count to
 * {@code outputTokens}, and {@code totalTokens()} always stays in sync with the sum
 * (it is derived, never stored) — including across the {@code withContent}/{@code
 * withLlmProvider} copy methods.
 */
class AgentResponseTest {

    private static final AgentId AGENT_ID = AgentId.of("agent");

    @Test
    void success_withInputAndOutputSplit_reportsBothAndTheirSum() {
        AgentResponse response = AgentResponse.success(
                "task-1", AGENT_ID, "done", 2, 30, 70, Money.of("0.01", "EUR"), Duration.ofMillis(5), List.of());

        assertEquals(30, response.inputTokens());
        assertEquals(70, response.outputTokens());
        assertEquals(100, response.totalTokens());
    }

    @Test
    void failure_withInputAndOutputSplit_reportsBothAndTheirSum() {
        AgentResponse response = AgentResponse.failure(
                "task-1", AGENT_ID, "boom", Duration.ofMillis(5), 1, 5, 15, List.of());

        assertEquals(5, response.inputTokens());
        assertEquals(15, response.outputTokens());
        assertEquals(20, response.totalTokens());
    }

    @SuppressWarnings("deprecation")
    @Test
    void legacySingleTotalFactory_attributesTheWholeCountToOutputTokens() {
        AgentResponse response = AgentResponse.success(
                "task-1", AGENT_ID, "done", 1, 42, 0.0, Duration.ofMillis(5), List.of());

        assertEquals(0, response.inputTokens());
        assertEquals(42, response.outputTokens());
        assertEquals(42, response.totalTokens());
    }

    @Test
    void withContentAndWithLlmProvider_preserveTheTokenSplit() {
        AgentResponse response = AgentResponse.success(
                        "task-1", AGENT_ID, "done", 2, 30, 70, Money.of("0.01", "EUR"), Duration.ofMillis(5), List.of())
                .withContent("new content")
                .withLlmProvider("gpt-mini");

        assertEquals("new content", response.content());
        assertEquals("gpt-mini", response.llmProvider());
        assertEquals(30, response.inputTokens());
        assertEquals(70, response.outputTokens());
        assertEquals(100, response.totalTokens());
    }

    @Test
    void withCost_replacesEstimatedCost() {
        AgentResponse response = AgentResponse.success(
                        "task-1", AGENT_ID, "done", 2, 30, 70, Money.of("0.01", "EUR"), Duration.ofMillis(5), List.of())
                .withCost(Money.of("5.00", "USD"));

        assertEquals(0, response.estimatedCost().compareTo(Money.of("5.00", "USD")));
    }
}
