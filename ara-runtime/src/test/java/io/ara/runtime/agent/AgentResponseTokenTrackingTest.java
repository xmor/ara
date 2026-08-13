package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.common.Money;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that {@code ReactStrategy}'s per-call prompt/output token tracking
 * reaches {@link AgentResponse#inputTokens()}/{@link AgentResponse#outputTokens()}
 * through {@code ExecutionResult}, and that the estimated cost uses the real split
 * (input rate × input tokens + output rate × output tokens) rather than the blended
 * input/output rate average this used to fall back on before the split was tracked.
 */
class AgentResponseTokenTrackingTest {

    @Test
    void success_reportsThePromptAndOutputTokensFromEachLlmCall_notJustTheTotal() {
        // Iteration 1 (tool call): prompt=10, output=10 (ScriptedLlmClient.thenToolCall).
        // Iteration 2 (final answer): prompt=10, output=20 (ScriptedLlmClient.thenFinalAnswer).
        // => inputTokens=20, outputTokens=30, totalTokens=50.
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        .thenToolCall("noop", "{}")
                        .thenFinalAnswer("done")
                        .build())
                .toolRegistry(io.ara.core.tool.ToolRegistry.empty())
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("token-agent")).agentType("t")
                .primaryLlm(LlmProfile.of("stub"))
                .plannerStrategy("react")
                .maxIterations(5)
                .build());

        // No "noop" tool registered above — the scripted tool call fails, but the strategy
        // still records the completion's tokens either way; only the final assertions
        // (inputTokens/outputTokens) matter here, not whether the tool dispatch succeeded.
        AgentResponse response = agent.execute(AgentTask.of("hi"));

        assertEquals(20, response.inputTokens());
        assertEquals(30, response.outputTokens());
        assertEquals(50, response.totalTokens());
    }

    @Test
    void estimatedCost_usesTheRealInputOutputSplit_notABlendedAverage() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        .thenToolCall("noop", "{}")
                        .thenFinalAnswer("done")
                        .build())
                .toolRegistry(io.ara.core.tool.ToolRegistry.empty())
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("cost-agent")).agentType("t")
                .primaryLlm(LlmProfile.builder()
                        .modelId("stub")
                        .costInputPer1kTokens(Money.of("1.0", "EUR"))
                        .costOutputPer1kTokens(Money.of("3.0", "EUR"))
                        .build())
                .plannerStrategy("react")
                .maxIterations(5)
                .build());

        AgentResponse response = agent.execute(AgentTask.of("hi"));

        // inputTokens=20, outputTokens=30 (see the test above).
        // Real split: (20/1000)*1.0 + (30/1000)*3.0 = 0.02 + 0.09 = 0.11.
        // The old blended-average approximation would have given
        // (50/1000)*((1.0+3.0)/2) = 0.10 instead — a different, less accurate number.
        assertTrue(response.isSuccess(), response.failureReason());
        assertEquals(0, response.estimatedCost().compareTo(Money.of("0.11", "EUR")));
    }
}
