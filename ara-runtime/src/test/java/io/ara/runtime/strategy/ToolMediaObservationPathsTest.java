package io.ara.runtime.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
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

/**
 * A tool that returns media must produce the same observation — the same text and the same media —
 * whichever strategy ran it and whichever way the client speaks tool calls. Two places used to turn
 * a result into an observation by hand; each of the four paths below is held to the same expected
 * text and media, so one that drifts from the others fails on its own.
 *
 * <p>The four paths are ReAct and plan-execute, each with a client that sends native tool calls
 * (the result comes back as a {@code tool} message) and one that does not (the result comes back
 * as an {@code Observation:} user turn).
 */
class ToolMediaObservationPathsTest {

    private static final MediaRef CROP = new MediaRef("3f9a1c2e7b4d5a6c8e0f1b2d3c4a5b6c", "image/png", "crop-1.png", 12, null);
    private static final String EXPECTED_TEXT = "Crop saved.\nAttached: crop-1.png (id 3f9a1c2e7b4d)";
    private static final String CALL_JSON = "{\"tool_id\":\"crop_region\",\"arguments\":{}}";

    private static final AraTool CROP_REGION = new AraTool() {
        @Override public String toolId() { return "crop_region"; }
        @Override public String description() { return "crops a region and returns it"; }
        @Override public String argumentSchema() { return "{}"; }
        @Override public ToolResult execute(String argumentJson) {
            return ToolResult.success("crop_region", "Crop saved.", List.of(CROP));
        }
    };

    private static final ToolRegistry REGISTRY = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(CROP_REGION); }
        @Override public Optional<AraTool> findById(String id) {
            return CROP_REGION.toolId().equals(id) ? Optional.of(CROP_REGION) : Optional.empty();
        }
        @Override public ToolResult execute(String toolId, String argumentJson) { return CROP_REGION.execute(argumentJson); }
    };

    /** What the model is shown for the call: the message that carries the result, whatever its role. */
    private record Seen(LlmMessage message) {
        /** The result text, without the text protocol's prefix, so the paths can be compared. */
        String text() {
            String content = message.content();
            return content.startsWith("Observation: ") ? content.substring("Observation: ".length()) : content;
        }
    }

    // ── ReAct ───────────────────────────────────────────────────────────────────────────────────

    /** Calls the tool on its first request, answers on its second, and keeps every request. */
    private static final class ReactClient implements LlmClient {
        final boolean nativeTools;
        final List<List<LlmMessage>> requests = new ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();

        ReactClient(boolean nativeTools) { this.nativeTools = nativeTools; }

        @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            requests.add(List.copyOf(messages));
            if (calls.getAndIncrement() == 0) {
                return nativeTools
                        ? new LlmCompletion("calling", 5, 5, "tool_calls", CALL_JSON, "call-7")
                        : new LlmCompletion("calling", 5, 5, "tool_calls", CALL_JSON);
            }
            return new LlmCompletion("done", 5, 5, "stop", null);
        }
        @Override public String providerId() { return "react-media-test"; }
        @Override public boolean supportsNativeTools() { return nativeTools; }
    }

    private static Seen reactPath(boolean nativeTools) {
        ReactClient llm = new ReactClient(nativeTools);
        InMemoryMemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are helpful.");
        memory.appendToWorkingMemory("user", "crop the photo");

        ExecutionResult result = new ReactStrategy().execute(AgentTask.of("crop the photo"), llm, memory, REGISTRY, config("react"));

        assertTrue(result.isSuccess(), result.failureReason());
        return resultMessageIn(llm.requests.get(1), nativeTools);
    }

    // ── plan-execute ────────────────────────────────────────────────────────────────────────────

    /** One plan step whose first round calls the tool, second round closes the step. */
    private static final class PlanClient implements LlmClient {
        final boolean nativeTools;
        final List<List<LlmMessage>> stepRounds = new ArrayList<>();

        PlanClient(boolean nativeTools) { this.nativeTools = nativeTools; }

        @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            String system = messages.get(0).content();
            if (system.contains("Produce a short numbered execution plan")) {
                return new LlmCompletion("1. Crop the photo", 5, 5, "stop", null);
            }
            if (system.contains("You are executing one step of a plan")) {
                stepRounds.add(List.copyOf(messages));
                if (stepRounds.size() == 1) {
                    return nativeTools
                            ? new LlmCompletion("calling", 5, 5, "tool_calls", CALL_JSON, "call-7")
                            : new LlmCompletion(CALL_JSON, 5, 5, "stop", null);
                }
                return new LlmCompletion("STEP_DONE", 5, 5, "stop", null);
            }
            return new LlmCompletion("Final answer.", 5, 5, "stop", null);
        }
        @Override public String providerId() { return "plan-media-test"; }
        @Override public boolean supportsNativeTools() { return nativeTools; }
    }

    private static Seen planPath(boolean nativeTools) {
        PlanClient llm = new PlanClient(nativeTools);
        InMemoryMemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are helpful.");
        memory.appendToWorkingMemory("user", "crop the photo");

        ExecutionResult result = new PlanExecuteStrategy().execute(AgentTask.of("crop the photo"), llm, memory, REGISTRY, config("plan_execute"));

        assertTrue(result.isSuccess(), result.failureReason());
        return resultMessageIn(llm.stepRounds.get(1), nativeTools);
    }

    // ── Shared ──────────────────────────────────────────────────────────────────────────────────

    private static AgentConfig config(String strategy) {
        return AgentConfig.defaults().primaryLlm(LlmProfile.of("stub")).plannerStrategy(strategy)
                .enabledTools(List.of("crop_region")).build();
    }

    private static Seen resultMessageIn(List<LlmMessage> request, boolean nativeTools) {
        return new Seen(request.stream()
                .filter(m -> nativeTools ? "tool".equals(m.role())
                        : "user".equals(m.role()) && m.content().startsWith("Observation:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no tool result in " + request)));
    }

    @Test
    void reactWithANativeClientSendsTheResultAsAToolMessageCarryingTheMedia() {
        Seen seen = reactPath(true);

        assertEquals("tool", seen.message().role());
        assertEquals("call-7", seen.message().toolCallId());
        assertEquals(EXPECTED_TEXT, seen.text());
        assertEquals(List.of(CROP), seen.message().media());
    }

    @Test
    void reactWithATextClientSendsTheResultAsAnObservationTurnCarryingTheMedia() {
        Seen seen = reactPath(false);

        assertEquals("user", seen.message().role());
        assertNull(seen.message().toolCallId(), "the text protocol has no call id to pair with");
        assertEquals(EXPECTED_TEXT, seen.text());
        assertEquals(List.of(CROP), seen.message().media());
    }

    @Test
    void planExecuteWithANativeClientSendsTheResultAsAToolMessageCarryingTheMedia() {
        Seen seen = planPath(true);

        assertEquals("tool", seen.message().role());
        assertEquals("call-7", seen.message().toolCallId());
        assertEquals(EXPECTED_TEXT, seen.text());
        assertEquals(List.of(CROP), seen.message().media());
    }

    @Test
    void planExecuteWithATextClientSendsTheResultAsAnObservationTurnCarryingTheMedia() {
        Seen seen = planPath(false);

        assertEquals("user", seen.message().role());
        assertEquals(EXPECTED_TEXT, seen.text());
        assertEquals(List.of(CROP), seen.message().media());
    }
}
