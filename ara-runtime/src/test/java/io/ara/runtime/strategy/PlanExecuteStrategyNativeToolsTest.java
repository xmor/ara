package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mirrors {@code ReactStrategyTest}'s native-tools coverage for {@link PlanExecuteStrategy}:
 * the step-execution phase must omit the text tool catalog and the inline JSON tool-call
 * instruction — and instead attach {@code resolvedTools} to the {@link LlmCallContext} — when
 * the client speaks native provider function-calling. Planning and synthesis never invoke
 * tools by design, so their calls are asserted to be unaffected either way.
 */
class PlanExecuteStrategyNativeToolsTest {

    private static AraTool noopTool() {
        return new AraTool() {
            @Override public String toolId()         { return "noop"; }
            @Override public String description()    { return "no-op"; }
            @Override public String argumentSchema()  { return "{}"; }
            @Override public ToolResult execute(String argsJson) { return ToolResult.success("noop", "ok"); }
        };
    }

    private static ToolRegistry namedRegistry(AraTool... tools) {
        var map = new HashMap<String, AraTool>();
        for (var t : tools) map.put(t.toolId(), t);
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) {
                return ids.isEmpty() ? List.copyOf(map.values())
                        : ids.stream().map(map::get).filter(Objects::nonNull).toList();
            }
            @Override public Optional<AraTool> findById(String id) {
                return Optional.ofNullable(map.get(id));
            }
            @Override public ToolResult execute(String toolId, String argumentJson) {
                var t = map.get(toolId);
                return t != null ? t.execute(argumentJson) : ToolResult.failure(toolId, "unknown tool");
            }
        };
    }

    private static InMemoryMemoryManager seeded(String systemPrompt, String userInput) {
        InMemoryMemoryManager m = new InMemoryMemoryManager();
        m.appendToWorkingMemory("system", systemPrompt);
        m.appendToWorkingMemory("user", userInput);
        return m;
    }

    @Test
    void nonNativeClient_getsTextCatalogAndJsonInstruction_noResolvedToolsInContext() {
        PhaseAwareLlmClient llm = new PhaseAwareLlmClient(false);

        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .plannerStrategy("plan_execute")
                .build();

        ExecutionResult result = new PlanExecuteStrategy().execute(
                AgentTask.of("do the thing"), llm, seeded("You are helpful.", "do the thing"),
                namedRegistry(noopTool()), config);

        assertTrue(result.isSuccess(), result.failureReason());

        PhaseAwareLlmClient.Call step = llm.callWhereSystemContains("You are executing one step of a plan");
        assertTrue(step.messages().get(0).content().contains("noop"),
                "text tool catalog should be present for a non-native client");
        assertTrue(step.messages().get(0).content().contains("If a tool is needed, output only JSON"),
                "inline JSON tool-call instruction should be present for a non-native client");
        assertFalse(step.context().hasResolvedTools(),
                "resolvedTools should not be attached to the context for a non-native client");
    }

    @Test
    void nativeClient_omitsTextScaffolding_attachesResolvedToolsOnlyDuringStepExecution() {
        PhaseAwareLlmClient llm = new PhaseAwareLlmClient(true);

        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .plannerStrategy("plan_execute")
                .build();

        ExecutionResult result = new PlanExecuteStrategy().execute(
                AgentTask.of("do the thing"), llm, seeded("You are helpful.", "do the thing"),
                namedRegistry(noopTool()), config);

        assertTrue(result.isSuccess(), result.failureReason());

        PhaseAwareLlmClient.Call plan = llm.callWhereSystemContains("Produce a short numbered execution plan");
        PhaseAwareLlmClient.Call step = llm.callWhereSystemContains("You are executing one step of a plan");
        PhaseAwareLlmClient.Call synth = llm.callWhereSystemContains("Produce the complete final answer");

        assertFalse(step.messages().get(0).content().contains("noop"),
                "text tool catalog is redundant with native tool specs and should be omitted");
        assertFalse(step.messages().get(0).content().contains("If a tool is needed, output only JSON"),
                "inline JSON tool-call instruction is redundant with native function-calling and should be omitted");
        assertTrue(step.context().hasResolvedTools(),
                "resolvedTools should be attached to the context for the step-execution call");
        // A step's model is also given the reserved close_step tool (it closes the step); the
        // planner and synthesis never are — asserted just below.
        assertEquals(List.of("noop", "close_step"),
                step.context().resolvedTools().stream().map(AraTool::toolId).toList());

        assertFalse(plan.context().hasResolvedTools(),
                "planning never invokes tools — resolvedTools should not be attached there");
        assertFalse(synth.context().hasResolvedTools(),
                "synthesis never invokes tools — resolvedTools should not be attached there");
    }

    // ── Stub ───────────────────────────────────────────────────────────────────

    /** Records every call and replies based on which phase's suffix marker is present. */
    private static final class PhaseAwareLlmClient implements LlmClient {

        record Call(List<LlmMessage> messages, LlmCallContext context) {}

        private final List<Call> calls = new ArrayList<>();
        private final boolean    nativeTools;

        PhaseAwareLlmClient(boolean nativeTools) { this.nativeTools = nativeTools; }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            calls.add(new Call(List.copyOf(messages), context));
            String system = messages.get(0).content();
            if (system.contains("Produce a short numbered execution plan")) {
                return new LlmCompletion("1. Step one", 5, 5, "stop", null);
            }
            if (system.contains("You are executing one step of a plan")) {
                return new LlmCompletion("STEP_DONE", 5, 5, "stop", null);
            }
            return new LlmCompletion("Final answer.", 5, 5, "stop", null);
        }

        @Override
        public String providerId() { return "phase-aware-test"; }

        @Override
        public boolean supportsNativeTools() { return nativeTools; }

        Call callWhereSystemContains(String marker) {
            return calls.stream()
                    .filter(c -> c.messages().get(0).content().contains(marker))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no call found with system marker: " + marker));
        }
    }
}
