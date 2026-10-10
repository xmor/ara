package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code maxStepRoundsPerStep} must bound every round of a step, not only the rounds in which the
 * model answers without calling a tool.
 *
 * <p>The limit used to be compared only in the "no tool call" branch of the step loop, so a step
 * whose every round called a tool never reached it and ran until the whole task's
 * {@code maxIterations} was spent — one chatty step starving all the steps after it.
 */
class PlanExecuteStrategyStepRoundsTest {

    private static final int MAX_STEP_ROUNDS = 2;
    private static final int GENEROUS_MAX_ITERATIONS = 50;

    /** Plans one step, then answers every later call with a request for the {@code echo} tool. */
    private static final class PlanThenAlwaysCallToolClient implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            if (calls.incrementAndGet() == 1) {
                return new LlmCompletion("1. Keep calling the echo tool", 5, 5, "stop", null);
            }
            return new LlmCompletion("{\"tool_id\":\"echo\",\"arguments\":{}}", 5, 5, "tool_calls", null);
        }

        @Override public String providerId() { return "always-calls-tool-stub"; }
    }

    private static final AraTool ECHO = new AraTool() {
        @Override public String toolId() { return "echo"; }
        @Override public String description() { return "echoes"; }
        @Override public String argumentSchema() { return "{}"; }
        @Override public ToolResult execute(String argumentJson) { return ToolResult.success(toolId(), "ok"); }
    };

    private static final ToolRegistry REGISTRY = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(ECHO); }
        @Override public Optional<AraTool> findById(String id) { return Optional.of(ECHO); }
        @Override public ToolResult execute(String toolId, String argumentJson) { return ECHO.execute(argumentJson); }
    };

    private static MemoryManager seededMemory() {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are a helpful AI agent.");
        memory.appendToWorkingMemory("user", "hello");
        return memory;
    }

    @Test
    void aStepThatCallsToolsEveryRound_stopsAtMaxStepRoundsPerStep() {
        PlanThenAlwaysCallToolClient llm = new PlanThenAlwaysCallToolClient();
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .executionTimeout(Duration.ofSeconds(30))
                .maxIterations(GENEROUS_MAX_ITERATIONS)
                .enabledTools(List.of("echo"))
                .strategyConfig(new StrategyConfig.PlanExecute("never", 8, MAX_STEP_ROUNDS))
                .build();

        ExecutionResult result = new PlanExecuteStrategy().execute(
                AgentTask.of("hello"), llm, seededMemory(), REGISTRY, config);

        assertFalse(result.isSuccess(),
                "a step that never finishes within its rounds must not count as completed");
        assertTrue(result.failureReasonOpt().orElse("").contains("maxStepRoundsPerStep"),
                () -> "the failure must say the round limit was the cause, got: " + result.failureReasonOpt());
        assertEquals(1 + MAX_STEP_ROUNDS, llm.calls.get(),
                "one planning call plus exactly maxStepRoundsPerStep rounds — not the whole iteration budget");
        assertTrue(result.iterationsDone() <= 1 + MAX_STEP_ROUNDS,
                () -> "the step consumed " + result.iterationsDone() + " iterations of the task budget");
    }
}
