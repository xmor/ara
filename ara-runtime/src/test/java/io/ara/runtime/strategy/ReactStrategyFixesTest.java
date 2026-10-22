package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the two ReactStrategy fixes from {@code ReactStrategy_critique.md}:
 * <ul>
 *   <li>3C — streaming responses now estimate token usage instead of reporting 0/0, so the
 *       cost-budget check and {@code totalTokens} stay meaningful in streaming mode.</li>
 *   <li>3A — the synthesis nudge trigger scales with loop length and no longer fires
 *       prematurely on short loops (e.g. iteration 2 of 5).</li>
 * </ul>
 */
class ReactStrategyFixesTest {

    // ── 3C: streaming token estimation ──────────────────────────────────────

    /** Emits two tokens natively so {@code streamAndCollect} produces non-blank text. */
    private static final class StreamingLlmClient implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            return new LlmCompletion("fallback", 0, 0, "stop", null);
        }
        @Override
        public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
            return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {
                    subscriber.onNext("Hello ");
                    subscriber.onNext("world, this is the answer.");
                    subscriber.onComplete();
                }
                @Override public void cancel() {}
            });
        }
        @Override public String providerId() { return "streaming-stub"; }
    }

    @Test
    void streaming_reportsNonZeroEstimatedTokens() {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are a helpful AI agent.");
        memory.appendToWorkingMemory("user", "Say the answer.");

        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().modelId("stub").streamingEnabled(true).build())
                .maxIterations(3)
                .build();

        // tokenCallback must be non-null for the streaming path to activate
        List<String> streamed = new ArrayList<>();
        AgentTask task = AgentTask.ofStreaming("say", streamed::add);

        ExecutionResult result = new ReactStrategy()
                .execute(task, new StreamingLlmClient(), memory, ToolRegistry.empty(), config);

        assertTrue(result.isSuccess());
        assertFalse(streamed.isEmpty(), "tokens should have been streamed to the callback");
        assertTrue(result.tokensUsed() > 0,
                "streaming must estimate token usage (was hard-coded 0 before the 3C fix) — got " + result.tokensUsed());
    }

    // ── 3A: synthesis trigger no longer fires prematurely ───────────────────

    /** Records the messages of every completion call; never finalises so the loop runs on. */
    private static final class RecordingLlmClient implements LlmClient {
        final List<List<LlmMessage>> callMessages = new ArrayList<>();
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            callMessages.add(List.copyOf(messages));
            // finishReason "length" (not "stop") + no tool call => intermediate step, loop continues
            return new LlmCompletion("still thinking...", 1, 1, "length", null);
        }
        @Override public String providerId() { return "recording-test"; }

        /** 1-based index of the first call whose messages contained the synthesis prompt, or -1. */
        int firstCallWithSynthesis() {
            for (int i = 0; i < callMessages.size(); i++) {
                boolean present = callMessages.get(i).stream()
                        .anyMatch(m -> m.content() != null && m.content().contains(ReactExecutionSupport.SYNTHESIS_CORE));
                if (present) return i + 1;
            }
            return -1;
        }
    }

    @Test
    void synthesisTrigger_doesNotFireBeforeTheProportionalTail_onShortLoops() {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are a helpful AI agent.");
        memory.appendToWorkingMemory("user", "Do the work.");

        // maxIterations=5 -> tail=max(2,5/4)=2 -> synthesis fires at iteration >= 3.
        // The old formula (maxIterations - 3) fired at iteration 2 — this asserts the fix.
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .maxIterations(5)
                .build();

        RecordingLlmClient llm = new RecordingLlmClient();
        new ReactStrategy().execute(AgentTask.of("go"), llm, memory, ToolRegistry.empty(), config);

        assertEquals(3, llm.firstCallWithSynthesis(),
                "synthesis nudge must first appear at iteration 3 (proportional tail), not iteration 2");
    }

    @Test
    void synthesisTrigger_neverFires_onVeryShortLoops() {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are a helpful AI agent.");
        memory.appendToWorkingMemory("user", "Do the work.");

        // maxIterations=4 < SYNTHESIS_MIN_ITERATIONS(5) -> synthesis skipped entirely
        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .maxIterations(4)
                .build();

        RecordingLlmClient llm = new RecordingLlmClient();
        new ReactStrategy().execute(AgentTask.of("go"), llm, memory, ToolRegistry.empty(), config);

        assertEquals(-1, llm.firstCallWithSynthesis(),
                "loops shorter than SYNTHESIS_MIN_ITERATIONS must never get a synthesis nudge");
    }

    // ── Synthesis nudge: conditional persist/sentinel clauses ───────────────

    private static final class FakeFileWriteTool implements AraTool {
        @Override public String toolId() { return "file_write"; }
        @Override public String description() { return "test file_write tool"; }
        @Override public String argumentSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
        @Override public ToolResult execute(String argumentJson) { return ToolResult.success("file_write", "ok"); }
    }

    private static ToolRegistry registryWith(AraTool... tools) {
        List<AraTool> list = List.of(tools);
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return list; }
            @Override public Optional<AraTool> findById(String toolId) { return Optional.empty(); }
            @Override public ToolResult execute(String toolId, String argumentJson) {
                return ToolResult.failure(toolId, "not used in this test");
            }
        };
    }

    /** Same as {@link RecordingLlmClient} but reports native function-calling support. */
    private static final class NativeToolsRecordingLlmClient implements LlmClient {
        final List<List<LlmMessage>> callMessages = new ArrayList<>();
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            callMessages.add(List.copyOf(messages));
            return new LlmCompletion("still thinking...", 1, 1, "length", null);
        }
        @Override public String providerId() { return "native-recording-test"; }
        @Override public boolean supportsNativeTools() { return true; }
    }

    private static Optional<LlmMessage> synthesisMessage(List<List<LlmMessage>> callMessages) {
        for (List<LlmMessage> messages : callMessages) {
            for (LlmMessage m : messages) {
                if (m.content() != null && m.content().contains(ReactExecutionSupport.SYNTHESIS_CORE)) {
                    return Optional.of(m);
                }
            }
        }
        return Optional.empty();
    }

    private static AgentConfig fiveIterationConfig() {
        return AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .maxIterations(5)
                .build();
    }

    @Test
    void synthesisNudge_isInjectedAsAutomatedUserMessage() {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are a helpful AI agent.");
        memory.appendToWorkingMemory("user", "Do the work.");

        RecordingLlmClient llm = new RecordingLlmClient();
        new ReactStrategy().execute(AgentTask.of("go"), llm, memory, ToolRegistry.empty(), fiveIterationConfig());

        LlmMessage nudge = synthesisMessage(llm.callMessages).orElseThrow(() -> new AssertionError("nudge never injected"));
        assertEquals("user", nudge.role(),
                "a mid-conversation system turn is rejected by system-first providers (qwen via LiteLLM)");
        assertTrue(nudge.content().startsWith(ReactExecutionSupport.AUTOMATED_PREFIX),
                "framework-generated, so it carries the [automated] marker");
        for (List<LlmMessage> messages : llm.callMessages) {
            assertTrue(messages.stream().skip(1).noneMatch(m -> "system".equals(m.role())),
                    "no system turn after index 0");
        }
    }

    @Test
    void midConversationSystemEntry_isSentAsAutomatedUserTurn() {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are a helpful AI agent.");
        memory.appendToWorkingMemory("user", "Do the work.");
        memory.appendToWorkingMemory("system", "SUMMARY");

        RecordingLlmClient llm = new RecordingLlmClient();
        new ReactStrategy().execute(AgentTask.of("go"), llm, memory, ToolRegistry.empty(), fiveIterationConfig());

        List<LlmMessage> first = llm.callMessages.get(0);
        assertEquals("system", first.get(0).role());
        LlmMessage summary = first.get(2);
        assertEquals("user", summary.role());
        assertEquals(ReactExecutionSupport.AUTOMATED_PREFIX + "SUMMARY", summary.content());
    }

    @Test
    void synthesisNudge_omitsFileWriteClause_whenToolNotEnabled() {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are a helpful AI agent.");
        memory.appendToWorkingMemory("user", "Do the work.");

        RecordingLlmClient llm = new RecordingLlmClient();
        new ReactStrategy().execute(AgentTask.of("go"), llm, memory, ToolRegistry.empty(), fiveIterationConfig());

        LlmMessage nudge = synthesisMessage(llm.callMessages).orElseThrow(() -> new AssertionError("nudge never injected"));
        assertFalse(nudge.content().contains("file_write"),
                "an agent with no file_write tool must not be told to call it");
    }

    @Test
    void synthesisNudge_includesFileWriteClause_whenToolEnabled() {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are a helpful AI agent.");
        memory.appendToWorkingMemory("user", "Do the work.");

        RecordingLlmClient llm = new RecordingLlmClient();
        new ReactStrategy().execute(AgentTask.of("go"), llm, memory,
                registryWith(new FakeFileWriteTool()), fiveIterationConfig());

        LlmMessage nudge = synthesisMessage(llm.callMessages).orElseThrow(() -> new AssertionError("nudge never injected"));
        assertTrue(nudge.content().contains("file_write"),
                "an agent with an enabled file_write tool should still be told to persist unsaved work");
    }

    @Test
    void synthesisNudge_omitsFinalAnswerSentinel_forNativeToolsClient() {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are a helpful AI agent.");
        memory.appendToWorkingMemory("user", "Do the work.");

        NativeToolsRecordingLlmClient llm = new NativeToolsRecordingLlmClient();
        new ReactStrategy().execute(AgentTask.of("go"), llm, memory, ToolRegistry.empty(), fiveIterationConfig());

        LlmMessage nudge = synthesisMessage(llm.callMessages).orElseThrow(() -> new AssertionError("nudge never injected"));
        assertFalse(nudge.content().contains("FINAL_ANSWER"),
                "a native-function-calling client never learned the FINAL_ANSWER text sentinel — the nudge must not teach it one late in the loop");
    }
}
