package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.RunContext;
import io.ara.core.budget.RunBudget;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.ToolCallEntry;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReactStrategyTest {

    private static final ToolRegistry NO_TOOLS = ToolRegistry.empty();

    // ── ReAct suffix ───────────────────────────────────────────────────────────

    @Test
    void does_not_append_react_suffix_when_no_tools_are_available() {
        RecordingLlmClient llm = new RecordingLlmClient();
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are a helpful AI agent.");
        memory.appendToWorkingMemory("user", "Translate hello to English.");

        AgentConfig config = AgentConfig.defaults()
                .agentType("translator")
                .primaryLlm(LlmProfile.of("stub"))
                .build();

        ExecutionResult result = new ReactStrategy().execute(
                AgentTask.of("translate"),
                llm,
                memory,
                ToolRegistry.empty(),
                config
        );

        assertTrue(result.isSuccess());
        assertFalse(llm.systemPrompt().contains("Never mix tool calls and final answers in the same response."),
                "the ReAct suffix should not be appended when the tool list is empty");
    }

    @Test
    void appends_tool_catalog_and_react_suffix_when_client_lacks_native_tools() {
        RecordingLlmClient llm = new RecordingLlmClient(false);
        TrackingMemory memory = seeded("You are helpful.", "What is 6*7?");

        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .build();

        ExecutionResult result = new ReactStrategy().execute(
                AgentTask.of("What is 6*7?"), llm, memory, namedRegistry(noopTool()), config);

        assertTrue(result.isSuccess());
        assertTrue(llm.systemPrompt().contains("noop"),
                "the text tool catalog should list available tools for a non-native client");
        assertTrue(llm.systemPrompt().contains("Never mix tool calls and final answers in the same response."),
                "the ReAct suffix should be appended for a non-native client");
    }

    @Test
    void omits_tool_catalog_and_react_suffix_when_client_supports_native_tools() {
        RecordingLlmClient llm = new RecordingLlmClient(true);
        TrackingMemory memory = seeded("You are helpful.", "What is 6*7?");

        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .build();

        ExecutionResult result = new ReactStrategy().execute(
                AgentTask.of("What is 6*7?"), llm, memory, namedRegistry(noopTool()), config);

        assertTrue(result.isSuccess());
        assertFalse(llm.systemPrompt().contains("noop"),
                "the text tool catalog is redundant with native tool specs and should be omitted");
        assertFalse(llm.systemPrompt().contains("Never mix tool calls and final answers in the same response."),
                "the ReAct suffix is redundant with native tool-calling and should be omitted");
    }

    // ── Budget enforcement (D-03) ──────────────────────────────────────────────

    @Test
    void budget_exceeded_stops_loop_before_next_llm_call() {
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder()
                        .modelId("stub")
                        .costInputPer1kTokens(io.ara.core.common.Money.of("1.0", "EUR"))
                        .costOutputPer1kTokens(io.ara.core.common.Money.of("2.0", "EUR"))
                        .costBudget(io.ara.core.common.Budget.limited(io.ara.core.common.Money.of("2.0", "EUR")))
                        .build())                .maxTokensPerStep(1000)
                .maxIterations(10)
                .build();

        LlmCompletion firstResponse = new LlmCompletion(
                "Thinking about the answer...", 500, 500, "length", null);

        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(firstResponse)
                .thenFinalAnswer("This answer should never be reached")
                .build();

        TrackingMemory memory = seeded("sys", "What is 2+2?");
        ExecutionResult result = new ReactStrategy().execute(AgentTask.of("What is 2+2?"), llm, memory, NO_TOOLS, config);

        assertFalse(result.isSuccess(), "should fail when budget is exceeded");
        assertTrue(result.failureReason().contains("budget") || result.failureReason().contains("Cost"),
                "failure reason must mention budget, got: " + result.failureReason());
        assertTrue(result.failureReason().contains("EUR"),
                "failure reason must show the configured currency, got: " + result.failureReason());
        assertFalse(result.failureReason().contains("$"),
                "failure reason must not hardcode a dollar sign, got: " + result.failureReason());
    }

    @Test
    void unlimited_budget_never_blocks_even_with_high_cost_rates() {
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder()
                        .modelId("stub")
                        .costInputPer1kTokens(io.ara.core.common.Money.of("1000.0", "EUR"))
                        .costOutputPer1kTokens(io.ara.core.common.Money.of("1000.0", "EUR"))
                        .costBudget(io.ara.core.common.Budget.unlimited())
                        .build())
                .maxTokensPerStep(1000)
                .maxIterations(5)
                .build();

        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenFinalAnswer("answer under unlimited budget")
                .build();

        TrackingMemory memory = seeded("sys", "test");
        ExecutionResult result = new ReactStrategy().execute(
                AgentTask.of("test"), llm, memory, NO_TOOLS, config);

        assertTrue(result.isSuccess(), "an unlimited budget must never block, regardless of cost rates");
    }

    @Test
    void zero_budget_means_no_limit() {
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder()
                        .modelId("stub")
                        .costInputPer1kTokens(io.ara.core.common.Money.of("10.0", "EUR"))
                        .costOutputPer1kTokens(io.ara.core.common.Money.of("10.0", "EUR"))
                        .build())                .maxIterations(5)
                .build();

        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenFinalAnswer("answer without budget limit")
                .build();

        TrackingMemory memory = seeded("sys", "test");
        ExecutionResult result = new ReactStrategy().execute(
                AgentTask.of("test"), llm, memory, NO_TOOLS, config);

        assertTrue(result.isSuccess());
        assertEquals("answer without budget limit", result.output());
    }

    @Test
    void budget_not_enforced_when_cost_rate_is_zero() {
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder()
                        .modelId("stub")
                        .costInputPer1kTokens(io.ara.core.common.Money.zero("EUR"))
                        .costOutputPer1kTokens(io.ara.core.common.Money.zero("EUR"))
                        .costBudget(io.ara.core.common.Budget.limited(io.ara.core.common.Money.of("0.001", "EUR")))
                        .build())                .maxIterations(5)
                .build();

        LlmCompletion expensiveResponse = new LlmCompletion(
                "Action: FINAL_ANSWER\nAnswer: done", 10000, 10000, "stop", null);
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(expensiveResponse)
                .build();

        TrackingMemory memory = seeded("sys", "test");
        ExecutionResult result = new ReactStrategy().execute(
                AgentTask.of("test"), llm, memory, NO_TOOLS, config);

        assertTrue(result.isSuccess(), "budget must not be enforced when cost rate is 0");
    }

    // ── ADR-0069 D2/D3 — the run's RunBudget, when the task's RunContext carries one ──

    @Test
    void run_budget_from_run_context_stops_loop_when_a_call_exceeds_the_cap() {
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .maxIterations(10)
                .build();

        LlmCompletion firstResponse = new LlmCompletion("Thinking...", 100, 100, "length", null);
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(firstResponse)
                .thenFinalAnswer("must never be reached")
                .build();

        RunBudget runBudget = RunBudget.of().maxTokens(150).build();
        AgentTask task = AgentTask.of("test").withRunContext(runBudget.attachTo(RunContext.empty()));

        TrackingMemory memory = seeded("sys", "test");
        ExecutionResult result = new ReactStrategy().execute(task, llm, memory, NO_TOOLS, config);

        assertFalse(result.isSuccess(), "the run budget's token cap (150) is exceeded by the first call (200)");
        assertTrue(result.failureReason().contains("Run budget exceeded"),
                "failure reason must name the run budget breach, got: " + result.failureReason());
        assertTrue(result.failureReason().contains("TOKENS"),
                "failure reason must name the exceeded axis, got: " + result.failureReason());
    }

    @Test
    void no_run_budget_on_the_task_leaves_execution_unaffected() {
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .maxIterations(5)
                .build();

        ScriptedLlmClient llm = ScriptedLlmClient.script().thenFinalAnswer("ok").build();

        TrackingMemory memory = seeded("sys", "test");
        // AgentTask.of(...) carries RunContext.empty() — no RunBudget attached.
        ExecutionResult result = new ReactStrategy().execute(AgentTask.of("test"), llm, memory, NO_TOOLS, config);

        assertTrue(result.isSuccess());
        assertEquals("ok", result.output());
    }

    @Test
    void budget_enforced_when_only_output_rate_set() {
        // Regression: the budget guard used to require costInputPer1kTokens > 0, so a
        // config with only an output rate silently skipped budget enforcement.
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder()
                        .modelId("stub")
                        .costInputPer1kTokens(io.ara.core.common.Money.zero("EUR"))
                        .costOutputPer1kTokens(io.ara.core.common.Money.of("2.0", "EUR"))
                        .costBudget(io.ara.core.common.Budget.limited(io.ara.core.common.Money.of("2.0", "EUR")))
                        .build())
                .maxTokensPerStep(1000)
                .maxIterations(10)
                .build();

        LlmCompletion firstResponse = new LlmCompletion(
                "Thinking...", 500, 500, "length", null);   // spent=1.0, nextEst=2.0 → 3.0 > 2.0
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(firstResponse)
                .thenFinalAnswer("must never be reached")
                .build();

        TrackingMemory memory = seeded("sys", "q");
        ExecutionResult result = new ReactStrategy().execute(
                AgentTask.of("q"), llm, memory, NO_TOOLS, config);

        assertFalse(result.isSuccess(), "budget must be enforced with only an output rate");
        assertTrue(result.failureReason().toLowerCase().contains("budget")
                        || result.failureReason().contains("Cost"),
                "failure must cite budget, got: " + result.failureReason());
    }

    @Test
    void token_accumulation_is_correct_across_iterations() {
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .maxIterations(10)
                .build();

        LlmCompletion toolCall = new LlmCompletion(
                """
                {"tool_id":"noop","arguments":{}}""", 100, 50, "tool_calls",
                """
                {"tool_id":"noop","arguments":{}}""");
        LlmCompletion finalAns = new LlmCompletion(
                "Action: FINAL_ANSWER\nAnswer: 42", 80, 30, "stop", null);

        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(toolCall)
                .then(finalAns)
                .build();

        TrackingMemory memory = seeded("sys", "What is 6*7?");
        ToolRegistry tools = namedRegistry(noopTool());

        ExecutionResult result = new ReactStrategy().execute(
                AgentTask.of("What is 6*7?"), llm, memory, tools, config);

        assertTrue(result.isSuccess());
        assertEquals(100 + 50 + 80 + 30, result.tokensUsed(),
                "tokensUsed must be the sum of all prompt+output tokens across iterations");
    }

    @Test
    void normal_flow_unaffected_by_budget_feature() {
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder()
                        .modelId("stub")
                        .costInputPer1kTokens(io.ara.core.common.Money.of("0.001", "EUR"))
                        .costOutputPer1kTokens(io.ara.core.common.Money.of("0.002", "EUR"))
                        .costBudget(io.ara.core.common.Budget.limited(io.ara.core.common.Money.of("100.0", "EUR")))
                        .build())                .maxIterations(5)
                .build();

        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenFinalAnswer("normal answer")
                .build();

        TrackingMemory memory = seeded("You are helpful.", "Summarize this.");
        ExecutionResult result = new ReactStrategy().execute(
                AgentTask.of("Summarize this."), llm, memory, NO_TOOLS, config);

        assertTrue(result.isSuccess());
        assertEquals("normal answer", result.output());
        assertEquals(1, result.iterationsDone());
    }

    @Test
    void budget_check_uses_prompt_and_output_tokens_separately() {
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder()
                        .modelId("stub")
                        .costInputPer1kTokens(io.ara.core.common.Money.of("5.0", "EUR"))
                        .costOutputPer1kTokens(io.ara.core.common.Money.of("1.0", "EUR"))
                        .costBudget(io.ara.core.common.Budget.limited(io.ara.core.common.Money.of("5.0", "EUR")))
                        .build())                .maxTokensPerStep(100)
                .maxIterations(10)
                .build();

        LlmCompletion heavyPrompt = new LlmCompletion(
                "Thinking...", 1000, 10, "length", null);

        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(heavyPrompt)
                .thenFinalAnswer("should not reach here")
                .build();

        TrackingMemory memory = seeded("sys", "test");
        ExecutionResult result = new ReactStrategy().execute(
                AgentTask.of("test"), llm, memory, NO_TOOLS, config);

        assertFalse(result.isSuccess());
        assertTrue(result.failureReason().toLowerCase().contains("budget")
                || result.failureReason().toLowerCase().contains("cost"),
                "failure must cite cost/budget, got: " + result.failureReason());
    }

    // ── Parallel tool calls (D-01) ─────────────────────────────────────────────

    @Test
    void parallel_tool_calls_all_dispatched_and_observed() {
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .maxIterations(5)
                .build();

        LlmCompletion multiCall = new LlmCompletion(
                "I need two tools", 50, 30, "tool_calls", null, null,
                List.of(
                        new ToolCallEntry("call-1", "tool_a", "{}"),
                        new ToolCallEntry("call-2", "tool_b", "{}")
                ));

        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(multiCall)
                .thenFinalAnswer("both tools done")
                .build();

        List<String> calledTools = new java.util.concurrent.CopyOnWriteArrayList<>();
        ToolRegistry tools = new ToolRegistry() {
            @Override public List<io.ara.core.tool.AraTool> resolveEnabled(List<String> ids) { return List.of(); }
            @Override public java.util.Optional<io.ara.core.tool.AraTool> findById(String id) { return java.util.Optional.empty(); }
            @Override public io.ara.core.tool.ToolResult execute(String toolId, String argumentJson) {
                calledTools.add(toolId);
                return io.ara.core.tool.ToolResult.success(toolId, "result-" + toolId);
            }
        };

        TrackingMemory memory = seeded("sys", "run two tools");
        ExecutionResult result = new ReactStrategy().execute(
                AgentTask.of("run two tools"), llm, memory, tools, config);

        assertTrue(result.isSuccess());
        assertTrue(calledTools.contains("tool_a"), "tool_a must have been called");
        assertTrue(calledTools.contains("tool_b"), "tool_b must have been called");
        assertEquals(2, calledTools.size());
    }

    @Test
    void parallel_tool_observations_appended_in_order() {
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .maxIterations(5)
                .build();

        LlmCompletion multiCall = new LlmCompletion(
                "calling tools", 20, 10, "tool_calls", null, null,
                List.of(
                        new ToolCallEntry(null, "first",  "{}"),
                        new ToolCallEntry(null, "second", "{}"),
                        new ToolCallEntry(null, "third",  "{}")
                ));

        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(multiCall)
                .thenFinalAnswer("done")
                .build();

        ToolRegistry tools = new ToolRegistry() {
            @Override public List<io.ara.core.tool.AraTool> resolveEnabled(List<String> ids) { return List.of(); }
            @Override public java.util.Optional<io.ara.core.tool.AraTool> findById(String id) { return java.util.Optional.empty(); }
            @Override public io.ara.core.tool.ToolResult execute(String toolId, String argumentJson) {
                return io.ara.core.tool.ToolResult.success(toolId, "out-" + toolId);
            }
        };

        TrackingMemory memory = seeded("sys", "test");
        new ReactStrategy().execute(AgentTask.of("test"), llm, memory, tools, config);

        List<String> observations = memory.workingMemory().stream()
                .filter(e -> "user".equals(e.role()))
                .map(MemoryEntry::content)
                .filter(c -> c.startsWith("Observation: "))
                .toList();

        assertEquals(3, observations.size(), "one observation per tool call");
        assertTrue(observations.get(0).contains("first"),  "first observation must be for 'first'");
        assertTrue(observations.get(1).contains("second"), "second observation must be for 'second'");
        assertTrue(observations.get(2).contains("third"),  "third observation must be for 'third'");
    }

    @Test
    void parallel_single_call_does_not_use_threading() {
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .maxIterations(5)
                .build();

        LlmCompletion singleInList = new LlmCompletion(
                "one tool", 10, 10, "tool_calls", null, null,
                List.of(new ToolCallEntry("cid-1", "solo_tool", "{\"x\":1}")));

        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(singleInList)
                .thenFinalAnswer("done")
                .build();

        List<String> dispatched = new ArrayList<>();
        ToolRegistry tools = new ToolRegistry() {
            @Override public List<io.ara.core.tool.AraTool> resolveEnabled(List<String> ids) { return List.of(); }
            @Override public java.util.Optional<io.ara.core.tool.AraTool> findById(String id) { return java.util.Optional.empty(); }
            @Override public io.ara.core.tool.ToolResult execute(String toolId, String argumentJson) {
                dispatched.add(toolId);
                return io.ara.core.tool.ToolResult.success(toolId, "ok");
            }
        };

        TrackingMemory memory = seeded("sys", "test");
        ExecutionResult result = new ReactStrategy().execute(
                AgentTask.of("test"), llm, memory, tools, config);

        assertTrue(result.isSuccess());
        assertEquals(List.of("solo_tool"), dispatched);
    }

    @Test
    void extractAll_returns_legacy_single_call_when_toolCalls_empty() {
        LlmCompletion legacy = new LlmCompletion(
                "think", 10, 10, "tool_calls",
                """
                {"tool_id":"legacy_tool","arguments":{"q":"test"}}""");

        List<ToolCallParser.ToolCallRequest> calls = ToolCallParser.extractAll(legacy);

        assertEquals(1, calls.size());
        assertEquals("legacy_tool", calls.get(0).toolId());
    }

    @Test
    void extractAll_returns_all_entries_when_toolCalls_populated() {
        LlmCompletion multi = new LlmCompletion(
                "parallel", 10, 10, "tool_calls", null, null,
                List.of(
                        new ToolCallEntry("id-A", "tool_x", "{\"a\":1}"),
                        new ToolCallEntry("id-B", "tool_y", "{\"b\":2}")
                ));

        List<ToolCallParser.ToolCallRequest> calls = ToolCallParser.extractAll(multi);

        assertEquals(2, calls.size());
        assertEquals("tool_x",  calls.get(0).toolId());
        assertEquals("id-A",    calls.get(0).toolCallId());
        assertEquals("tool_y",  calls.get(1).toolId());
        assertEquals("id-B",    calls.get(1).toolCallId());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static TrackingMemory seeded(String systemPrompt, String userInput) {
        TrackingMemory m = new TrackingMemory();
        m.appendToWorkingMemory("system", systemPrompt);
        m.appendToWorkingMemory("user", userInput);
        return m;
    }

    private static io.ara.core.tool.AraTool noopTool() {
        return new io.ara.core.tool.AraTool() {
            @Override public String toolId()         { return "noop"; }
            @Override public String description()    { return "no-op"; }
            @Override public String argumentSchema() { return "{}"; }
            @Override public io.ara.core.tool.ToolResult execute(String argsJson) {
                return io.ara.core.tool.ToolResult.success("noop", "ok");
            }
        };
    }

    private static ToolRegistry namedRegistry(io.ara.core.tool.AraTool... tools) {
        var map = new java.util.HashMap<String, io.ara.core.tool.AraTool>();
        for (var t : tools) map.put(t.toolId(), t);
        return new ToolRegistry() {
            @Override public List<io.ara.core.tool.AraTool> resolveEnabled(List<String> ids) {
                return ids.isEmpty() ? List.copyOf(map.values())
                        : ids.stream().map(map::get).filter(java.util.Objects::nonNull).toList();
            }
            @Override public java.util.Optional<io.ara.core.tool.AraTool> findById(String id) {
                return java.util.Optional.ofNullable(map.get(id));
            }
            @Override public io.ara.core.tool.ToolResult execute(String toolId, String argumentJson) {
                var t = map.get(toolId);
                return t != null ? t.execute(argumentJson)
                        : io.ara.core.tool.ToolResult.failure(toolId, "unknown tool");
            }
        };
    }

    // ── Stubs ──────────────────────────────────────────────────────────────────

    static final class TrackingMemory implements MemoryManager {
        private final List<MemoryEntry> wm = new ArrayList<>();

        @Override
        public void appendToWorkingMemory(String role, String content) {
            wm.add(MemoryEntry.of(role, content));
        }

        @Override
        public List<MemoryEntry> workingMemory() { return List.copyOf(wm); }

        @Override
        public void clearWorkingMemory() { wm.clear(); }
    }

    private static final class RecordingLlmClient implements LlmClient {
        private List<LlmMessage> lastMessages = List.of();
        private final boolean    nativeTools;

        RecordingLlmClient() { this(false); }

        RecordingLlmClient(boolean nativeTools) { this.nativeTools = nativeTools; }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            lastMessages = List.copyOf(messages);
            return new LlmCompletion("Action: FINAL_ANSWER\nAnswer: done", 1, 1, "stop", null);
        }

        String systemPrompt() {
            return lastMessages.stream()
                    .filter(message -> "system".equals(message.role()))
                    .map(LlmMessage::content)
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public String providerId() {
            return "recording-test";
        }

        @Override
        public boolean supportsNativeTools() {
            return nativeTools;
        }
    }
}
