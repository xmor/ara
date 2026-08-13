package io.ara.examples.basics;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentExecutionContext;
import io.ara.core.agent.AgentInterceptor;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.common.Budget;
import io.ara.core.common.Money;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Runnable tour of every {@link AgentInterceptor} hook, in one file, with no network access.
 *
 * <p>{@link FullEventInterceptor} overrides all twelve methods; five independent scenarios
 * (each with its own tiny {@code AraRuntime}) each trigger a different subset:
 * <ol>
 *   <li><b>Delegation</b> — a coordinator agent delegates a sub-task to a worker agent via
 *       {@code delegate_task}, and the worker itself calls a plain tool. Exercises
 *       {@code before}/{@code after} (Planning/Executing on both agents),
 *       {@code beforeThink}/{@code afterThink}, {@code beforeToolCall}/{@code afterToolCall},
 *       and {@code onDelegate}/{@code onDelegateReturn}.</li>
 *   <li><b>Budget exceeded</b> — {@code onBudgetExceeded}.</li>
 *   <li><b>Timeout</b> — {@code onTimeout}.</li>
 *   <li><b>Cancelled</b> — {@code onCancelled}.</li>
 *   <li><b>Generic failure</b> — hitting {@code maxIterations} with no {@code FINAL_ANSWER}
 *       is an unclassified stop, so it still reaches {@code onError}.</li>
 * </ol>
 */
public final class InterceptorEventsExample {

    public static void main(String[] args) {
        FullEventInterceptor interceptor = new FullEventInterceptor();

        scenarioDelegation(interceptor);
        scenarioBudgetExceeded(interceptor);
        scenarioTimeout(interceptor);
        scenarioCancelled(interceptor);
        scenarioGenericError(interceptor);
    }

    // ── Scenario 1: delegate_task ────────────────────────────────────────────────

    private static void scenarioDelegation(AgentInterceptor interceptor) {
        header("Scenario 1 — delegate_task: coordinator delegates to worker");

        AraTool echoTool = new AraTool() {
            @Override public String toolId() { return "echo"; }
            @Override public String description() { return "echoes its input"; }
            @Override public String argumentSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            @Override public ToolResult execute(String argumentJson) { return ToolResult.success(toolId(), "echoed"); }
        };
        ToolRegistry workerTools = new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(echoTool); }
            @Override public Optional<AraTool> findById(String id) {
                return echoTool.toolId().equals(id) ? Optional.of(echoTool) : Optional.empty();
            }
            @Override public ToolResult execute(String toolId, String argumentJson) {
                return echoTool.toolId().equals(toolId) ? echoTool.execute(argumentJson) : ToolResult.failure(toolId, "not found");
            }
        };

        // AraRuntime wires delegate_task automatically for every agent it creates (via its
        // internal LocalMessageBus + DelegatingToolRegistry) — no manual registry assembly
        // needed for that part; workerTools above only adds the worker's own "echo" tool.
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("coordinator-model", ScriptedLlmClient.script()
                        .thenToolCall("delegate_task", "{\"agent_id\":\"worker\",\"task\":\"say hello\"}")
                        .thenFinalAnswer("Delegation complete")
                        .build())
                .llmClient("worker-model", ScriptedLlmClient.script()
                        .thenToolCall("echo", "{}")
                        .thenFinalAnswer("worker done")
                        .build())
                .toolRegistryFactory(cfg -> "worker".equals(cfg.agentId().value()) ? workerTools : ToolRegistry.empty())
                .interceptors(List.of(interceptor))
                .build();

        runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("worker")).agentType("t")
                .primaryLlm(LlmProfile.of("worker-model"))
                .plannerStrategy("react")
                .enabledTools(List.of("echo"))
                .maxIterations(3)
                .build());

        AraAgent coordinator = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("coordinator")).agentType("t")
                .primaryLlm(LlmProfile.of("coordinator-model"))
                .plannerStrategy("react")
                .enabledTools(List.of("delegate_task"))
                .maxIterations(3)
                .build());

        AgentResponse response = coordinator.execute(AgentTask.of("please delegate to worker"));
        System.out.printf("  => success=%s output=%s%n", response.isSuccess(), response.content());
    }

    // ── Scenario 2: cost budget ───────────────────────────────────────────────────

    private static void scenarioBudgetExceeded(AgentInterceptor interceptor) {
        header("Scenario 2 — cost budget exceeded");

        // First call's 500+500 tokens fit under the $2 budget (spent 1.5); the second
        // call's projected cost (1.5 + 2.0 est.) does not — failing before it is made.
        LlmCompletion firstResponse = new LlmCompletion("Thinking...", 500, 500, "length", null);
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(firstResponse)
                .thenFinalAnswer("must never be reached")
                .build();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient(llm)
                .interceptors(List.of(interceptor))
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("budget-agent")).agentType("t")
                .primaryLlm(LlmProfile.builder()
                        .modelId("stub")
                        .costInputPer1kTokens(Money.of("1.0", "EUR"))
                        .costOutputPer1kTokens(Money.of("2.0", "EUR"))
                        .costBudget(Budget.limited(Money.of("2.0", "EUR")))
                        .build())
                .plannerStrategy("react")
                .maxTokensPerStep(1000)
                .maxIterations(10)
                .build());

        AgentResponse response = agent.execute(AgentTask.of("q"));
        System.out.printf("  => success=%s reason=%s%n", response.isSuccess(), response.failureReason());
    }

    // ── Scenario 3: execution timeout ─────────────────────────────────────────────

    private static void scenarioTimeout(AgentInterceptor interceptor) {
        header("Scenario 3 — execution timeout");

        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("too slow to matter").build())
                .interceptors(List.of(interceptor))
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("timeout-agent")).agentType("t")
                .primaryLlm(LlmProfile.of("stub"))
                .plannerStrategy("react")
                // Positive but effectively zero — any real work inside the loop (even a
                // stub LLM call) takes longer than one nanosecond, so ReactStrategy's
                // post-call deadline check reliably throws on the first iteration.
                .executionTimeout(Duration.ofNanos(1))
                .maxIterations(5)
                .build());

        AgentResponse response = agent.execute(AgentTask.of("q"));
        System.out.printf("  => success=%s reason=%s%n", response.isSuccess(), response.failureReason());
    }

    // ── Scenario 4: cancellation ──────────────────────────────────────────────────

    private static void scenarioCancelled(AgentInterceptor interceptor) {
        header("Scenario 4 — cancelled before start");

        AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("first task completes normally").build())
                .interceptors(List.of(interceptor))
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("cancel-agent")).agentType("t")
                .primaryLlm(LlmProfile.of("stub"))
                .plannerStrategy("react")
                .build());

        SessionId sessionId = SessionId.of("s1");
        AgentResponse first = agent.execute(AgentTask.of("hi").withSessionId(sessionId));
        System.out.printf("  first task  => success=%s%n", first.isSuccess());

        // Cancel the (still-alive) session before the next task on it ever starts.
        // terminate(SessionId) lives on SessionScoped, not AraAgent itself — session
        // management is a capability only some agents have, so AraRuntime exposes it as
        // a runtime-level convenience that no-ops for agents that don't support it.
        runtime.terminateSession(agent.agentId(), sessionId);
        AgentResponse second = agent.execute(AgentTask.of("hi again").withSessionId(sessionId));
        System.out.printf("  second task => success=%s reason=%s%n", second.isSuccess(), second.failureReason());
    }

    // ── Scenario 5: generic failure (still onError) ───────────────────────────────

    private static void scenarioGenericError(AgentInterceptor interceptor) {
        header("Scenario 5 — max iterations reached (unclassified stop -> onError)");

        AraRuntime runtime = AraRuntime.builder()
                // "length" with no tool call and no FINAL_ANSWER: the loop never
                // terminates on its own and exhausts maxIterations.
                .llmClient(ScriptedLlmClient.script()
                        .then(new LlmCompletion("still thinking...", 10, 10, "length", null))
                        .build())
                .interceptors(List.of(interceptor))
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("error-agent")).agentType("t")
                .primaryLlm(LlmProfile.of("stub"))
                .plannerStrategy("react")
                // With a single scripted "length" step and maxIterations(1), the loop
                // consumes exactly that one response and stops — the client's default
                // FINAL_ANSWER fallback is never reached, so it genuinely exhausts
                // maxIterations instead of accidentally succeeding on a second iteration.
                .maxIterations(1)
                .build());

        AgentResponse response = agent.execute(AgentTask.of("q"));
        System.out.printf("  => success=%s reason=%s%n", response.isSuccess(), response.failureReason());
    }

    private static void header(String title) {
        System.out.println("\n=== " + title + " ===");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The interceptor: every hook, one line of output each
    // ═══════════════════════════════════════════════════════════════════════════

    /** Prints one line per hook invocation, labelled with the hook's name. */
    static final class FullEventInterceptor implements AgentInterceptor {

        @Override
        public void before(AgentExecutionContext ctx, String stepName) {
            System.out.printf("  [before]            step=%-10s state=%s%n", stepName, ctx.currentState());
        }

        @Override
        public void after(AgentExecutionContext ctx, String stepName, String result) {
            System.out.printf("  [after]             step=%-10s result=%.50s%n", stepName, result);
        }

        @Override
        public void onError(AgentExecutionContext ctx, String stepName, Throwable t) {
            System.out.printf("  [onError]           step=%-10s %s%n", stepName, t.getMessage());
        }

        @Override
        public void beforeThink(AgentExecutionContext ctx) {
            System.out.println("  [beforeThink]       about to call the LLM");
        }

        @Override
        public void afterThink(AgentExecutionContext ctx, LlmCompletion completion) {
            System.out.printf("  [afterThink]        finishReason=%-10s tokens=%d%n",
                    completion.finishReason(), completion.totalTokens());
        }

        @Override
        public void beforeToolCall(AgentExecutionContext ctx, String toolId, String argumentJson) {
            System.out.printf("  [beforeToolCall]    tool=%-14s args=%s%n", toolId, argumentJson);
        }

        @Override
        public void afterToolCall(AgentExecutionContext ctx, String toolId, String argumentJson, ToolResult result) {
            System.out.printf("  [afterToolCall]     tool=%-14s success=%s%n", toolId, result.success());
        }

        @Override
        public void onBudgetExceeded(AgentExecutionContext ctx, String stepName, String reason) {
            System.out.printf("  [onBudgetExceeded]  step=%-10s %s%n", stepName, reason);
        }

        @Override
        public void onTimeout(AgentExecutionContext ctx, String stepName, Duration timeout) {
            System.out.printf("  [onTimeout]         step=%-10s configured=%dns%n", stepName, timeout.toNanos());
        }

        @Override
        public void onCancelled(AgentExecutionContext ctx, String stepName) {
            System.out.printf("  [onCancelled]       step=%-10s%n", stepName);
        }

        @Override
        public void onDelegate(AgentExecutionContext ctx, String recipientAgentId, String delegatedTask) {
            System.out.printf("  [onDelegate]        -> %-10s task=%s%n", recipientAgentId, delegatedTask);
        }

        @Override
        public void onDelegateReturn(AgentExecutionContext ctx, String recipientAgentId, ToolResult result) {
            System.out.printf("  [onDelegateReturn]  <- %-10s success=%s%n", recipientAgentId, result.success());
        }
    }
}
