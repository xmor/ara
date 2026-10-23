package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.RunContext;
import io.ara.core.auth.ScopeSet;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.bus.ScopeFilteringToolRegistry;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The caller-aware catalog, end to end: with the scope filter in the chain, a task that
 * lacks the scopes for a tool must not see that tool anywhere the model reads — the text
 * catalog, the native {@code LlmCallContext.resolvedTools()} — while the same agent, with
 * a task that carries the scope, sees it again. The wiring itself (which decorator order
 * the runtime assembles) is pinned by the gate-ordering test in {@code hitl}; here the
 * strategies are called directly, the way {@code AgentInstance} does.
 */
class TaskScopedToolCatalogTest {

    private static AraTool tool(String id, String... requiredScopes) {
        return new AraTool() {
            @Override public String toolId() { return id; }
            @Override public String description() { return id + " — does nothing, in tests"; }
            @Override public String argumentSchema() { return "{}"; }
            @Override public ToolResult execute(String argumentJson) { return ToolResult.success(id, "ok"); }
            @Override public List<String> requiredScopes() { return List.of(requiredScopes); }
        };
    }

    private static ToolRegistry registryWith(AraTool... tools) {
        List<AraTool> list = List.of(tools);
        ToolRegistry inner = new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return list; }
            @Override public Optional<AraTool> findById(String id) {
                return list.stream().filter(t -> t.toolId().equals(id)).findFirst();
            }
            @Override public ToolResult execute(String toolId, String argumentJson) {
                return findById(toolId).map(t -> t.execute(argumentJson))
                        .orElseGet(() -> ToolResult.failure(toolId, "unknown tool"));
            }
        };
        return new ScopeFilteringToolRegistry(inner);
    }

    private static MemoryManager memory() {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are helpful.");
        memory.appendToWorkingMemory("user", "do it");
        return memory;
    }

    private static AgentConfig config() {
        return AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .executionTimeout(Duration.ofSeconds(30))
                .enabledTools(List.of("open", "shell_exec"))
                .build();
    }

    private static AgentTask taskWithScopes(String... scopes) {
        return AgentTask.of("do it").withRunContext(
                RunContext.empty().withOpaque(RunContext.SCOPES_KEY, ScopeSet.of(scopes)));
    }

    /** Captures the system prompt and the resolved tools of the last call. */
    private static final class RecordingLlmClient implements LlmClient {
        private List<LlmMessage> lastMessages = List.of();
        private LlmCallContext lastContext;
        private final boolean nativeTools;

        RecordingLlmClient(boolean nativeTools) { this.nativeTools = nativeTools; }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            lastMessages = List.copyOf(messages);
            lastContext = context;
            return new LlmCompletion("Action: FINAL_ANSWER\nAnswer: done", 1, 1, "stop", null);
        }

        String systemPrompt() {
            return lastMessages.stream()
                    .filter(message -> "system".equals(message.role()))
                    .map(LlmMessage::content)
                    .findFirst()
                    .orElseThrow();
        }

        @Override public String providerId() { return "recording-test"; }
        @Override public boolean supportsNativeTools() { return nativeTools; }
    }

    // ── ReAct ──────────────────────────────────────────────────────────────────

    @Test
    void react_aTaskWithoutTheScopeDoesNotSeeTheTool_anywhereTheModelReads() {
        ToolRegistry tools = registryWith(tool("open"), tool("shell_exec", "tools:shell"));

        RecordingLlmClient nonNative = new RecordingLlmClient(false);
        assertTrue(new ReactStrategy().execute(taskWithScopes("misc"), nonNative, memory(), tools, config()).isSuccess());
        assertFalse(nonNative.systemPrompt().contains("shell_exec"),
                "the text catalog must not offer a tool this caller cannot use");
        assertTrue(nonNative.systemPrompt().contains("open"));
    }

    @Test
    void react_aTaskWithTheScopeSeesTheTool_again() {
        ToolRegistry tools = registryWith(tool("open"), tool("shell_exec", "tools:shell"));

        RecordingLlmClient nonNative = new RecordingLlmClient(false);
        assertTrue(new ReactStrategy().execute(taskWithScopes("tools:shell"), nonNative, memory(), tools, config()).isSuccess());
        assertTrue(nonNative.systemPrompt().contains("shell_exec"));
    }

    @Test
    void react_nativeToolSchemasFollowTheSameFilter() {
        ToolRegistry tools = registryWith(tool("open"), tool("shell_exec", "tools:shell"));

        RecordingLlmClient nativeClient = new RecordingLlmClient(true);
        assertTrue(new ReactStrategy().execute(taskWithScopes("misc"), nativeClient, memory(), tools, config()).isSuccess());
        assertEquals(List.of("open"), nativeClient.lastContext.resolvedTools().stream()
                .map(AraTool::toolId).toList());
    }

    // ── plan_execute ───────────────────────────────────────────────────────────

    @Test
    void planExecute_thePlannerIsNotShownAToolThisCallerCannotUse() {
        ToolRegistry tools = registryWith(tool("open"), tool("shell_exec", "tools:shell"));

        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .planReplies("1. Do the thing")
                .stepCompletions(closingStep("done"));
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .executionTimeout(Duration.ofSeconds(30))
                .maxIterations(20)
                .strategyConfig(new io.ara.core.agent.StrategyConfig.PlanExecute("never", 8, 3))
                .build();

        ExecutionResult result = new PlanExecuteStrategy().execute(
                taskWithScopes("misc"), llm, memory(), tools, config);

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        List<LlmMessage> planMessages = llm.callsIn(PlanPhaseLlmClient.Phase.PLAN).get(0).messages();
        String planPrompt = planMessages.stream()
                .filter(message -> "system".equals(message.role()))
                .map(LlmMessage::content)
                .findFirst()
                .orElse("");
        assertFalse(planPrompt.contains("shell_exec"),
                "the planner catalog must not offer a tool this caller cannot use");
        assertTrue(planPrompt.contains("open"));
    }

    private static LlmCompletion closingStep(String text) {
        return new LlmCompletion(text, 5, 5, "tool_calls",
                "{\"tool_id\":\"close_step\",\"arguments\":{\"status\":\"done\"}}");
    }
}
