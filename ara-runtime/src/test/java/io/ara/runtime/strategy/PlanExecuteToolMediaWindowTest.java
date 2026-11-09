package io.ara.runtime.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.media.MediaRef;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.InMemoryMemoryManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** [V15] The window is applied in plan-execute too, on the step's local history. */
class PlanExecuteToolMediaWindowTest {

    private static final MediaRef FIRST = new MediaRef("aaaaaaaaaaaa1111", "image/png", "first.png", 1L, null);
    private static final MediaRef SECOND = new MediaRef("bbbbbbbbbbbb2222", "image/png", "second.png", 1L, null);

    /** Returns FIRST on its first call and SECOND on its second. */
    private static final class Crop implements AraTool {
        private final AtomicInteger calls = new AtomicInteger();
        @Override public String toolId() { return "crop"; }
        @Override public String description() { return "crops"; }
        @Override public String argumentSchema() { return "{}"; }
        @Override public ToolResult execute(String argumentJson) {
            return ToolResult.success("crop", "cropped", List.of(calls.getAndIncrement() == 0 ? FIRST : SECOND));
        }
    }

    private static final class Llm implements LlmClient {
        final List<List<LlmMessage>> stepCalls = new ArrayList<>();

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            String system = messages.get(0).content();
            if (system.contains("Produce a short numbered execution plan")) {
                return new LlmCompletion("1. Crop twice", 5, 5, "stop", null);
            }
            if (system.contains("You are executing one step of a plan")) {
                stepCalls.add(List.copyOf(messages));
                int round = stepCalls.size();
                return round <= 2
                        ? new LlmCompletion("calling crop", 5, 5, "tool_calls",
                                "{\"tool_id\":\"crop\",\"arguments\":{}}", "call-" + round)
                        : new LlmCompletion("STEP_DONE", 5, 5, "stop", null);
            }
            return new LlmCompletion("Final answer.", 5, 5, "stop", null);
        }

        @Override public String providerId() { return "window-test"; }
        @Override public boolean supportsNativeTools() { return true; }
    }

    @Test
    void theThirdRoundCarriesOnlyTheMediaOfTheSecondBlock() {
        Crop crop = new Crop();
        ToolRegistry registry = new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(crop); }
            @Override public Optional<AraTool> findById(String id) { return Optional.<AraTool>of(crop).filter(t -> t.toolId().equals(id)); }
            @Override public ToolResult execute(String id, String json) { return crop.execute(json); }
        };
        InMemoryMemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are helpful.");
        memory.appendToWorkingMemory("user", "crop twice");
        Llm llm = new Llm();

        var result = new PlanExecuteStrategy().execute(AgentTask.of("crop twice"), llm, memory, registry,
                AgentConfig.defaults().primaryLlm(LlmProfile.of("stub")).plannerStrategy("plan_execute").build());

        assertTrue(result.isSuccess(), result.failureReason());
        List<LlmMessage> third = llm.stepCalls.get(2);
        List<LlmMessage> tools = third.stream().filter(m -> "tool".equals(m.role())).toList();
        assertEquals(2, tools.size());
        assertTrue(tools.get(0).media().isEmpty(), "the first block's image is not sent again");
        assertEquals(List.of(SECOND), tools.get(1).media());
        assertTrue(tools.get(0).content().contains("cropped"), "its text stays");
    }
}
