package io.ara.runtime.interceptor;

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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the fine-grained interceptor hooks that fire around individual ReAct
 * iterations — {@code beforeThink}/{@code afterThink} (one LLM call) and {@code
 * beforeToolCall}/{@code afterToolCall} (one tool dispatch) — the typed
 * onBudgetExceeded/onTimeout/onCancelled outcomes, which report expected,
 * policy-driven stops that used to be indistinguishable string-matched reasons
 * inside the generic {@code onError}; and the delegation-specific onDelegate/
 * onDelegateReturn, which nest inside the generic tool-call pair when the tool is
 * {@code delegate_task}.
 */
class AgentInterceptorEventsTest {

    /** Records every hook invocation, in call order, for assertions. */
    private static final class RecordingInterceptor implements AgentInterceptor {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override public void before(AgentExecutionContext ctx, String stepName)                 { events.add("before:" + stepName); }
        @Override public void after(AgentExecutionContext ctx, String stepName, String result)    { events.add("after:" + stepName); }
        @Override public void onError(AgentExecutionContext ctx, String stepName, Throwable t)    { events.add("onError:" + stepName); }
        @Override public void beforeThink(AgentExecutionContext ctx)                              { events.add("beforeThink"); }
        @Override public void afterThink(AgentExecutionContext ctx, LlmCompletion completion)     { events.add("afterThink:" + completion.finishReason()); }
        @Override public void beforeToolCall(AgentExecutionContext ctx, String toolId, String args) { events.add("beforeToolCall:" + toolId); }

        @Override
        public void afterToolCall(AgentExecutionContext ctx, String toolId, String args, ToolResult result) {
            events.add("afterToolCall:" + toolId + ":" + result.success());
        }

        @Override
        public void onBudgetExceeded(AgentExecutionContext ctx, String stepName, String reason) {
            events.add("onBudgetExceeded:" + stepName);
        }

        @Override
        public void onTimeout(AgentExecutionContext ctx, String stepName, Duration timeout) {
            events.add("onTimeout:" + stepName);
        }

        @Override
        public void onCancelled(AgentExecutionContext ctx, String stepName) {
            events.add("onCancelled:" + stepName);
        }

        @Override
        public void onDelegate(AgentExecutionContext ctx, String recipientAgentId, String delegatedTask) {
            events.add("onDelegate:" + recipientAgentId + ":" + delegatedTask);
        }

        @Override
        public void onDelegateReturn(AgentExecutionContext ctx, String recipientAgentId, ToolResult result) {
            events.add("onDelegateReturn:" + recipientAgentId + ":" + result.success());
        }
    }

    private static final AraTool ECHO_TOOL = new AraTool() {
        @Override public String toolId() { return "echo"; }
        @Override public String description() { return "echoes input"; }
        @Override public String argumentSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public ToolResult execute(String argumentJson) { return ToolResult.success(toolId(), "ok"); }
    };

    private static ToolRegistry singleTool(AraTool tool) {
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(tool); }
            @Override public Optional<AraTool> findById(String id) {
                return tool.toolId().equals(id) ? Optional.of(tool) : Optional.empty();
            }
            @Override public ToolResult execute(String toolId, String argumentJson) {
                return tool.toolId().equals(toolId) ? tool.execute(argumentJson) : ToolResult.failure(toolId, "not found");
            }
        };
    }

    @Test
    void thinkAndToolCallHooksFireOnceProReActIteration() {
        RecordingInterceptor interceptor = new RecordingInterceptor();
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenToolCall("echo", "{}")
                .thenFinalAnswer("done")
                .build();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient(llm)
                .toolRegistry(singleTool(ECHO_TOOL))
                .interceptors(List.of(interceptor))
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("think-tool-agent"))
                .agentType("t")
                .primaryLlm(LlmProfile.of("stub"))
                .plannerStrategy("react")
                .enabledTools(List.of("echo"))
                .maxIterations(5)
                .build());

        AgentResponse response = agent.execute(AgentTask.of("hi"));

        assertTrue(response.isSuccess(), response.failureReason());
        assertEquals(
                List.of("before:Planning", "after:Planning", "before:Executing",
                        "beforeThink", "afterThink:tool_calls",
                        "beforeToolCall:echo", "afterToolCall:echo:true",
                        "beforeThink", "afterThink:stop",
                        "after:Executing"),
                interceptor.events);
    }

    @Test
    void budgetExceeded_firesTypedHook_notGenericOnError() {
        RecordingInterceptor interceptor = new RecordingInterceptor();
        // Mirrors ReactStrategyTest.budget_exceeded_stops_loop_before_next_llm_call:
        // the first call's 500+500 tokens fit under budget (spent 1.5 <= 2.0), the
        // second call's projected cost (1.5 + 2.0 est.) does not — failing before
        // a second LLM call is made.
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
                .agentId(AgentId.of("budget-agent"))
                .agentType("t")
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

        assertFalse(response.isSuccess());
        assertTrue(interceptor.events.stream().anyMatch(e -> e.equals("onBudgetExceeded:Executing")),
                "expected onBudgetExceeded, got: " + interceptor.events);
        assertFalse(interceptor.events.stream().anyMatch(e -> e.startsWith("onError")),
                "budget overrun is an expected, policy-driven stop — must not also fire onError: " + interceptor.events);
    }

    @Test
    void timeout_firesTypedHook_notGenericOnError() {
        RecordingInterceptor interceptor = new RecordingInterceptor();
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenFinalAnswer("too slow to matter")
                .build();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient(llm)
                .interceptors(List.of(interceptor))
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("timeout-agent"))
                .agentType("t")
                .primaryLlm(LlmProfile.of("stub"))
                .plannerStrategy("react")
                // Positive but effectively zero: ExecutionConfig rejects Duration.ZERO, and
                // any real work done inside the loop (even a stub LLM call) takes longer
                // than one nanosecond, so the post-call deadline check in ReactStrategy
                // reliably throws ExecutionTimeoutException on the first iteration.
                .executionTimeout(Duration.ofNanos(1))
                .maxIterations(5)
                .build());

        AgentResponse response = agent.execute(AgentTask.of("q"));

        assertFalse(response.isSuccess());
        assertTrue(interceptor.events.stream().anyMatch(e -> e.equals("onTimeout:Executing")),
                "expected onTimeout, got: " + interceptor.events);
        assertFalse(interceptor.events.stream().anyMatch(e -> e.startsWith("onError")),
                "timeout is an expected, policy-driven stop — must not also fire onError: " + interceptor.events);
    }

    @Test
    void cancelledBeforeStart_firesTypedHook_notGenericOnError() {
        RecordingInterceptor interceptor = new RecordingInterceptor();
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenFinalAnswer("first task completes normally")
                .fallback(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: unused", 10, 20, "stop", null))
                .build();

        AraRuntime runtime = AraRuntime.builder()
                .llmClient(llm)
                .interceptors(List.of(interceptor))
                .build();

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("cancel-agent"))
                .agentType("t")
                .primaryLlm(LlmProfile.of("stub"))
                .plannerStrategy("react")
                .build());

        SessionId sessionId = SessionId.of("s1");
        AgentResponse first = agent.execute(AgentTask.of("hi").withSessionId(sessionId));
        assertTrue(first.isSuccess(), first.failureReason());

        // Cancel the (still-alive) session before the next task on it ever starts.
        runtime.terminateSession(agent.agentId(), sessionId);
        interceptor.events.clear();

        AgentResponse second = agent.execute(AgentTask.of("hi again").withSessionId(sessionId));

        assertFalse(second.isSuccess());
        assertEquals(List.of("onCancelled:Executing"), interceptor.events);
    }

    @Test
    void delegateTask_firesOnDelegateAndOnDelegateReturn_nestedInsideGenericToolCallHooks() {
        RecordingInterceptor interceptor = new RecordingInterceptor();

        // Same two-agent wiring as DelegationSessionPersistenceTest: AraRuntime wires
        // delegate_task automatically (via LocalMessageBus + DelegatingToolRegistry) for
        // every agent it creates, so no manual ToolRegistry assembly is needed here.
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("coordinator-model", ScriptedLlmClient.script()
                        .thenToolCall("delegate_task", "{\"agent_id\":\"worker\",\"task\":\"do the sub-task\"}")
                        .thenFinalAnswer("delegation done")
                        .build())
                .llmClient("worker-model", ScriptedLlmClient.script()
                        .thenFinalAnswer("worked on it")
                        .build())
                .interceptors(List.of(interceptor))
                .build();

        runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("worker")).agentType("t")
                .primaryLlm(LlmProfile.of("worker-model"))
                .plannerStrategy("react")
                .maxIterations(3)
                .build());

        AraAgent coordinator = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("coordinator")).agentType("t")
                .primaryLlm(LlmProfile.of("coordinator-model"))
                .plannerStrategy("react")
                .enabledTools(List.of("delegate_task"))
                .maxIterations(3)
                .build());

        AgentResponse response = coordinator.execute(AgentTask.of("please delegate"));

        assertTrue(response.isSuccess(), response.failureReason());
        // The runtime shares one interceptor list across both agents, so the worker's own
        // before/beforeThink/after events are interleaved in here too — only the
        // delegation-specific and tool-call events for delegate_task are asserted.
        assertEquals(List.of("beforeToolCall:delegate_task", "onDelegate:worker:do the sub-task"),
                interceptor.events.stream()
                        .filter(e -> e.startsWith("beforeToolCall:delegate_task") || e.startsWith("onDelegate:"))
                        .toList());
        assertEquals(List.of("onDelegateReturn:worker:true", "afterToolCall:delegate_task:true"),
                interceptor.events.stream()
                        .filter(e -> e.startsWith("onDelegateReturn:") || e.startsWith("afterToolCall:delegate_task"))
                        .toList());
    }
}
