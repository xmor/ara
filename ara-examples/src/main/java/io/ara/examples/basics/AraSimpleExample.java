package io.ara.examples.basics;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentExecutionContext;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.AraRuntime;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * End-to-end smoke test for the ARA agent runtime.
 *
 * <p>Wires the full stack (AgentFactory → AgentInstance → ReactStrategy → LLM → Tool)
 * using in-memory stub implementations of every external dependency so the demo
 * runs without network access or a real LLM.
 *
 * <p>Simulated scenario:
 * <ol>
 *   <li>LLM call 1 — the stub decides to invoke the {@code echo} tool.</li>
 *   <li>{@code echo} tool returns a canned response.</li>
 *   <li>LLM call 2 — the stub sees the observation and emits FINAL_ANSWER.</li>
 * </ol>
 */
public class AraSimpleExample {

    public static void main(String[] args) {

        System.out.println("=== ARA Agent Runtime — Demo ===\n");

        // ── 1. Build runtime ──────────────────────────────────────────────────
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("stub", new StubLlmClient())
                .toolRegistry(new StubToolRegistry())
                .interceptors(List.of(new LoggingInterceptor()))
                .build();
        runtime.start();

        // ── 3. Create agent ───────────────────────────────────────────────────
        AgentConfig config = AgentConfig.defaults()
                .agentType("demo-agent")
                .systemPrompt("You are a helpful demo agent.")
                .primaryLlm(LlmProfile.of("stub"))
                .plannerStrategy("react")
                .enabledTools(List.of("echo"))
                .maxIterations(5)
                .build();

        AraAgent agent = runtime.createAgent(config);
        System.out.printf("Agent created  : %s%n", agent.agentId().value());
        System.out.printf("Initial state  : %s%n%n", agent.currentState());

        // ── 4. Execute a task ─────────────────────────────────────────────────
        AgentTask task = AgentTask.of("What does the echo tool say about 'Hello ARA'?");
        System.out.printf("Submitting task: %s%n%n", task.input());

        AgentResponse response = agent.execute(task);

        // ── 5. Print result ───────────────────────────────────────────────────
        System.out.println("\n=== Result ===");
        System.out.printf("Success        : %s%n", response.isSuccess());
        System.out.printf("Final state    : %s%n", response.finalState());
        System.out.printf("Content        : %s%n", response.content());
        System.out.printf("Iterations     : %d%n", response.iterationsUsed());
        System.out.printf("Tokens         : %d%n", response.totalTokens());
        System.out.printf("Estimated cost : %s %s%n",
                response.estimatedCost().amount().toPlainString(), response.estimatedCost().currency());
        System.out.printf("Elapsed        : %dms%n", response.elapsedTime().toMillis());
        System.out.printf("Agent state    : %s (back to IDLE, ready for reuse)%n",
                agent.currentState());

        // ── 6. Reuse the same agent for a second task ─────────────────────────
        System.out.println("\n=== Second task (instance reuse) ===");
        AgentTask task2 = AgentTask.of("Give me a direct answer, no tools needed.");
        AgentResponse response2 = agent.execute(task2);
        System.out.printf("Success        : %s%n", response2.isSuccess());
        System.out.printf("Content        : %s%n", response2.content());

        // ── 7. Cleanup ─────────────────────────────────────────────────────────
        runtime.destroyAgent(agent);
        System.out.printf("%nRegistry count after destroy: %d%n", runtime.registry().count());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Stub implementations
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Stub LLM that alternates between a tool call and a final answer.
     *
     * <ul>
     *   <li>Odd calls  → requests the {@code echo} tool</li>
     *   <li>Even calls → emits FINAL_ANSWER based on last observed content</li>
     * </ul>
     */
    static final class StubLlmClient implements LlmClient {

        private final AtomicInteger callCount = new AtomicInteger(0);

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext ctx) {
            int call = callCount.incrementAndGet();
            System.out.printf("  [StubLLM] call #%d — %d messages in context%n",
                    call, messages.size());

            // Check if the last message contains an Observation (tool result)
            boolean hasObservation = messages.stream()
                    .anyMatch(m -> m.content().startsWith("Observation:"));

            if (!hasObservation) {
                // First time — request the echo tool
                String toolCallJson = """
                        {"tool_id":"echo","arguments":{"text":"Hello ARA"}}""";
                return new LlmCompletion(
                        "I need to call the echo tool to answer this question.",
                        20, 15, "tool_calls", toolCallJson);
            } else {
                // We have an observation — produce the final answer
                String lastObservation = messages.stream()
                        .filter(m -> m.content().startsWith("Observation:"))
                        .reduce((a, b) -> b)
                        .map(LlmMessage::content)
                        .orElse("no observation");

                String answer = "Action: FINAL_ANSWER\nAnswer: The echo tool responded: "
                        + lastObservation.replace("Observation: ", "");
                return new LlmCompletion(answer, 40, 30, "stop", null);
            }
        }

        @Override
        public String providerId() {
            return "stub";
        }
    }

    // ──────────────────────────────────────────────────────────────────────────

    /** Tool registry with a single {@code echo} tool. */
    static final class StubToolRegistry implements ToolRegistry {

        private final AraTool echoTool = new AraTool() {
            @Override public String toolId()          { return "echo"; }
            @Override public String description()     { return "Echoes back the provided text."; }
            @Override public String argumentSchema()  {
                return """
                        {"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""";
            }
            @Override
            public ToolResult execute(String argumentJson) {
                // Extract "text" field naively — sufficient for the demo
                String text = argumentJson.contains("\"text\"")
                        ? argumentJson.replaceAll(".*\"text\"\\s*:\\s*\"([^\"]+)\".*", "$1")
                        : argumentJson;
                System.out.printf("  [EchoTool] executing with: %s%n", text);
                return ToolResult.success("echo", "ECHO → " + text);
            }
        };

        @Override
        public List<AraTool> resolveEnabled(List<String> ids) {
            return ids.contains("echo") ? List.of(echoTool) : List.of();
        }

        @Override
        public Optional<AraTool> findById(String toolId) {
            return "echo".equals(toolId) ? Optional.of(echoTool) : Optional.empty();
        }

        @Override
        public ToolResult execute(String toolId, String argumentJson) {
            return findById(toolId)
                    .map(t -> t.execute(argumentJson))
                    .orElseGet(() -> ToolResult.failure(toolId, "Tool not found: " + toolId));
        }
    }


    // ──────────────────────────────────────────────────────────────────────────

    /** Interceptor that prints step boundaries to stdout. */
    static final class LoggingInterceptor
            implements io.ara.core.agent.AgentInterceptor {

        @Override
        public void before(AgentExecutionContext ctx, String stepName) {
            System.out.printf("  [Interceptor] → before(%s) state=%s%n",
                    stepName, ctx.currentState());
        }

        @Override
        public void after(AgentExecutionContext ctx, String stepName, String result) {
            System.out.printf("  [Interceptor] ← after(%s) result=%.60s%n",
                    stepName, result);
        }

        @Override
        public void onError(AgentExecutionContext ctx, String stepName, Throwable t) {
            System.out.printf("  [Interceptor] ✗ onError(%s) %s%n",
                    stepName, t.getMessage());
        }

        @Override
        public void beforeThink(AgentExecutionContext ctx) {
            System.out.println("  [Interceptor]     → beforeThink");
        }

        @Override
        public void afterThink(AgentExecutionContext ctx, LlmCompletion completion) {
            System.out.printf("  [Interceptor]     ← afterThink finishReason=%s tokens=%d%n",
                    completion.finishReason(), completion.totalTokens());
        }

        @Override
        public void beforeToolCall(AgentExecutionContext ctx, String toolId, String argumentJson) {
            System.out.printf("  [Interceptor]     → beforeToolCall(%s) args=%.60s%n", toolId, argumentJson);
        }

        @Override
        public void afterToolCall(AgentExecutionContext ctx, String toolId, String argumentJson, ToolResult result) {
            System.out.printf("  [Interceptor]     ← afterToolCall(%s) success=%s%n", toolId, result.success());
        }

        @Override
        public void onBudgetExceeded(AgentExecutionContext ctx, String stepName, String reason) {
            System.out.printf("  [Interceptor] ✗ onBudgetExceeded(%s) %s%n", stepName, reason);
        }

        @Override
        public void onTimeout(AgentExecutionContext ctx, String stepName, Duration timeout) {
            System.out.printf("  [Interceptor] ✗ onTimeout(%s) after %ds%n", stepName, timeout.toSeconds());
        }

        @Override
        public void onCancelled(AgentExecutionContext ctx, String stepName) {
            System.out.printf("  [Interceptor] ✗ onCancelled(%s)%n", stepName);
        }
    }
}