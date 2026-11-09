package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.LlmRouter;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ReflexionStrategyTest {

    private AgentConfig config;
    private ToolRegistry emptyTools;
    private AgentTask task;

    @BeforeEach
    void setUp() {
        config = AgentConfig.defaults()
                .agentType("test")
                .primaryLlm(LlmProfile.of("stub"))
                .strategyConfig(new StrategyConfig.Reflexion(2, null, null))
                .build();
        emptyTools = ToolRegistry.empty();
        task = AgentTask.of("Write a Java record Person with name and age");
    }

    // ── Core behaviour ─────────────────────────────────────────────────────────

    @Test
    void succeeds_on_first_attempt_no_reflection_needed() {
        ExecutionStrategy delegate = fixedDelegate(ExecutionResult.success("Person record done", 1, 100));
        ScriptedLlmClient llm = ScriptedLlmClient.script().build(); // never called

        TrackingMemoryManager memory = seeded("You are helpful.", task.input());

        ReflexionStrategy strategy = new ReflexionStrategy(delegate);
        ExecutionResult result = strategy.execute(task, llm, memory, emptyTools, config);

        assertTrue(result.goalAchieved());
        assertEquals("Person record done", result.output());
    }

    @Test
    void succeeds_on_second_attempt_after_one_reflection() {
        CountingDelegate delegate = new CountingDelegate(
                List.of(
                        ExecutionResult.failure("LLM timed out", 3, 200),
                        ExecutionResult.success("Person record done on retry", 2, 150)
                )
        );
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenFinalAnswer("Root cause: timeout. Next attempt: reduce scope.")
                .build();

        TrackingMemoryManager memory = seeded("You are helpful.", task.input());

        ReflexionStrategy strategy = new ReflexionStrategy(delegate);
        ExecutionResult result = strategy.execute(task, llm, memory, emptyTools, config);

        assertTrue(result.goalAchieved());
        assertEquals("Person record done on retry", result.output());
        assertEquals(2, delegate.callCount());
        assertEquals(5, result.iterationsDone()); // 3 + 2
        // 200 (attempt 1) + 150 (attempt 2) + 30 (the reflection call itself: the
        // scripted completion bills 10 prompt + 20 output tokens). The reflection is an
        // LLM call like any other — its usage must not vanish from the totals.
        assertEquals(380, result.tokensUsed());
    }

    @Test
    void reflection_is_injected_into_working_memory_before_retry() {
        CountingDelegate delegate = new CountingDelegate(List.of(
                ExecutionResult.failure("timeout", 1, 100),
                ExecutionResult.success("done", 1, 100)
        ));
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenFinalAnswer("Timeout occurred. Use fewer tool calls next time.")
                .build();

        TrackingMemoryManager memory = seeded("System prompt.", task.input());

        new ReflexionStrategy(delegate).execute(task, llm, memory, emptyTools, config);

        // After the retry, working memory must contain the reflection block
        List<MemoryEntry> wm = memory.workingMemory();
        boolean hasReflexionBlock = wm.stream()
                .anyMatch(e -> "system".equals(e.role())
                        && e.content().contains("REFLEXION"));
        assertTrue(hasReflexionBlock, "working memory must contain the REFLEXION block before retry");
    }

    @Test
    void system_prompt_is_preserved_across_retries() {
        String sysPrompt = "You are an expert Java developer.";
        CountingDelegate delegate = new CountingDelegate(List.of(
                ExecutionResult.failure("fail", 1, 50),
                ExecutionResult.success("ok", 1, 50)
        ));
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenFinalAnswer("Try again differently.")
                .build();

        TrackingMemoryManager memory = seeded(sysPrompt, task.input());

        new ReflexionStrategy(delegate).execute(task, llm, memory, emptyTools, config);

        List<MemoryEntry> wm = memory.workingMemory();
        assertFalse(wm.isEmpty());
        assertEquals("system", wm.get(0).role());
        assertEquals(sysPrompt, wm.get(0).content(),
                "system prompt must be the first entry after reset");
    }

    @Test
    void returns_failure_after_max_reflections_exhausted() {
        ExecutionStrategy delegate = fixedDelegate(ExecutionResult.failure("always fails", 1, 100));
        // LLM called for each reflection (maxReflections=2 → 2 reflection calls)
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenFinalAnswer("Reflection 1: try differently.")
                .thenFinalAnswer("Reflection 2: try even more differently.")
                .build();

        TrackingMemoryManager memory = seeded("sys", task.input());

        ReflexionStrategy strategy = new ReflexionStrategy(delegate);
        ExecutionResult result = strategy.execute(task, llm, memory, emptyTools, config);

        assertFalse(result.goalAchieved());
        assertFalse(result.isSuccess());
        assertTrue(result.failureReason().contains("Max reflections"));
    }

    @Test
    void accumulates_all_prior_reflections_on_multiple_retries() {
        CountingDelegate delegate = new CountingDelegate(List.of(
                ExecutionResult.failure("fail-1", 1, 50),
                ExecutionResult.failure("fail-2", 1, 50),
                ExecutionResult.success("ok on attempt 3", 1, 50)
        ));
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenFinalAnswer("Reflection after attempt 1.")
                .thenFinalAnswer("Reflection after attempt 2.")
                .build();

        AgentConfig cfg = AgentConfig.defaults()
                .agentType("test")
                .primaryLlm(LlmProfile.of("stub"))
                .strategyConfig(new StrategyConfig.Reflexion(3, null, null))
                .build();

        TrackingMemoryManager memory = seeded("sys", task.input());

        ExecutionResult result = new ReflexionStrategy(delegate).execute(task, llm, memory, emptyTools, cfg);

        assertTrue(result.goalAchieved());
        assertEquals(3, delegate.callCount());

        // The working memory before the third attempt must contain both reflections
        List<MemoryEntry> wm = memory.workingMemory();
        boolean hasBothReflections = wm.stream()
                .filter(e -> "system".equals(e.role()))
                .anyMatch(e -> e.content().contains("Attempt 1") && e.content().contains("Attempt 2"));
        assertTrue(hasBothReflections, "both prior reflections must appear in working memory before attempt 3");
    }

    @Test
    void zero_max_reflections_fails_immediately_without_reflection() {
        AgentConfig noRetry = AgentConfig.defaults()
                .agentType("test")
                .primaryLlm(LlmProfile.of("stub"))
                .strategyConfig(new StrategyConfig.Reflexion(0, null, null))
                .build();

        ExecutionStrategy delegate = fixedDelegate(ExecutionResult.failure("always fails", 1, 50));
        ScriptedLlmClient llm = ScriptedLlmClient.script().build();

        TrackingMemoryManager memory = seeded("sys", task.input());

        ExecutionResult result = new ReflexionStrategy(delegate).execute(task, llm, memory, emptyTools, noRetry);

        assertFalse(result.goalAchieved());
        assertFalse(result.isSuccess());
    }

    @Test
    void custom_reflection_prompt_is_used() {
        String customPrompt = "Task: {task}\nError: {failure}\nPrevious: {prior_reflections}\nFix it.";

        AgentConfig cfg = AgentConfig.defaults()
                .agentType("test")
                .primaryLlm(LlmProfile.of("stub"))
                .strategyConfig(new StrategyConfig.Reflexion(1, customPrompt, null))
                .build();

        CountingDelegate delegate = new CountingDelegate(List.of(
                ExecutionResult.failure("timeout", 1, 50),
                ExecutionResult.success("ok", 1, 50)
        ));
        CapturingLlmClient capturingLlm = new CapturingLlmClient("reflection text");

        TrackingMemoryManager memory = seeded("sys", task.input());

        new ReflexionStrategy(delegate).execute(task, capturingLlm, memory, emptyTools, cfg);

        String capturedPrompt = capturingLlm.lastUserMessage();
        assertNotNull(capturedPrompt);
        assertTrue(capturedPrompt.contains("Fix it."), "custom prompt template should be used");
        assertTrue(capturedPrompt.contains(task.input()), "{task} placeholder should be replaced");
    }

    @Test
    void reflection_generation_failure_uses_fallback_text() {
        CountingDelegate delegate = new CountingDelegate(List.of(
                ExecutionResult.failure("parse error", 1, 50),
                ExecutionResult.success("ok", 1, 50)
        ));
        // LLM throws on the reflection call
        LlmClient failingLlm = new LlmClient() {
            @Override
            public io.ara.core.llm.LlmCompletion complete(
                    List<io.ara.core.llm.LlmMessage> messages,
                    io.ara.core.llm.LlmCallContext ctx) {
                throw new RuntimeException("LLM unavailable");
            }
            @Override public String providerId() { return "failing-stub"; }
        };

        TrackingMemoryManager memory = seeded("sys", task.input());

        ExecutionResult result = new ReflexionStrategy(delegate).execute(task, failingLlm, memory, emptyTools, config);

        // Should still succeed on retry with a fallback reflection
        assertTrue(result.goalAchieved());
    }

    // ── reflectionProvider routing ───────────────────────────────────────────────

    @Test
    void reflectionProvider_routesReflectionCallToResolvedClient_notTheMainLlm() {
        CountingDelegate delegate = new CountingDelegate(List.of(
                ExecutionResult.failure("timeout", 1, 50),
                ExecutionResult.success("ok", 1, 50)
        ));
        CapturingLlmClient criticLlm = new CapturingLlmClient("Critic says: try again.");
        AtomicReference<String> requestedProviderId = new AtomicReference<>();
        LlmRouter router = (llmConfig, ctx) -> {
            requestedProviderId.set(llmConfig.primary().modelId());
            return criticLlm;
        };

        AgentConfig cfg = AgentConfig.defaults()
                .agentType("test")
                .primaryLlm(LlmProfile.of("stub"))
                .strategyConfig(new StrategyConfig.Reflexion(1, null, "critic-model"))
                .build();

        TrackingMemoryManager memory = seeded("sys", task.input());

        ExecutionResult result = new ReflexionStrategy(delegate, router)
                .execute(task, new NeverCalledLlmClient(), memory, emptyTools, cfg);

        assertTrue(result.isSuccess());
        assertEquals("critic-model", requestedProviderId.get(),
                "router must be asked to resolve the configured reflectionProvider id");
        assertNotNull(criticLlm.lastUserMessage(),
                "the resolved critic client, not the main llm, must have received the reflection prompt");
    }

    @Test
    void reflectionProvider_withoutRouterWired_fallsBackToMainLlm() {
        // Single-arg constructor — no router — must behave exactly as before this feature
        // existed, even though reflectionProvider is configured.
        CountingDelegate delegate = new CountingDelegate(List.of(
                ExecutionResult.failure("timeout", 1, 50),
                ExecutionResult.success("ok", 1, 50)
        ));
        CapturingLlmClient mainLlm = new CapturingLlmClient("Reflection from the main model.");

        AgentConfig cfg = AgentConfig.defaults()
                .agentType("test")
                .primaryLlm(LlmProfile.of("stub"))
                .strategyConfig(new StrategyConfig.Reflexion(1, null, "critic-model"))
                .build();

        TrackingMemoryManager memory = seeded("sys", task.input());

        ExecutionResult result = new ReflexionStrategy(delegate)
                .execute(task, mainLlm, memory, emptyTools, cfg);

        assertTrue(result.isSuccess());
        assertNotNull(mainLlm.lastUserMessage(), "with no router wired in, the main llm must handle the reflection call");
    }

    @Test
    void blankReflectionProvider_withRouterWired_neverConsultsTheRouter() {
        CountingDelegate delegate = new CountingDelegate(List.of(
                ExecutionResult.failure("timeout", 1, 50),
                ExecutionResult.success("ok", 1, 50)
        ));
        CapturingLlmClient mainLlm = new CapturingLlmClient("Reflection from the main model.");
        LlmRouter routerThatMustNotBeCalled = (llmConfig, ctx) -> {
            throw new AssertionError("router must not be consulted when reflectionProvider is unset");
        };

        // config uses the default StrategyConfig.Reflexion (reflectionProvider == null)
        TrackingMemoryManager memory = seeded("sys", task.input());

        ExecutionResult result = new ReflexionStrategy(delegate, routerThatMustNotBeCalled)
                .execute(task, mainLlm, memory, emptyTools, config);

        assertTrue(result.isSuccess());
        assertNotNull(mainLlm.lastUserMessage());
    }

    @Test
    void reflectionProvider_routerResolutionFailure_degradesGracefullyToMainLlm() {
        CountingDelegate delegate = new CountingDelegate(List.of(
                ExecutionResult.failure("timeout", 1, 50),
                ExecutionResult.success("ok", 1, 50)
        ));
        CapturingLlmClient mainLlm = new CapturingLlmClient("Fallback reflection from the main model.");
        LlmRouter unresolvableRouter = (llmConfig, ctx) -> {
            throw new IllegalStateException("no client registered for provider");
        };

        AgentConfig cfg = AgentConfig.defaults()
                .agentType("test")
                .primaryLlm(LlmProfile.of("stub"))
                .strategyConfig(new StrategyConfig.Reflexion(1, null, "unknown-provider"))
                .build();

        TrackingMemoryManager memory = seeded("sys", task.input());

        ExecutionResult result = new ReflexionStrategy(delegate, unresolvableRouter)
                .execute(task, mainLlm, memory, emptyTools, cfg);

        assertTrue(result.isSuccess(), "a router resolution failure must degrade to the main llm, not fail the task");
        assertNotNull(mainLlm.lastUserMessage());
    }

    @Test
    void strategy_name_is_reflexion() {
        assertEquals("reflexion", new ReflexionStrategy(fixedDelegate(ExecutionResult.success("x", 1, 0))).strategyName());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static TrackingMemoryManager seeded(String systemPrompt, String userInput) {
        TrackingMemoryManager m = new TrackingMemoryManager();
        m.appendToWorkingMemory("system", systemPrompt);
        m.appendToWorkingMemory("user", userInput);
        return m;
    }

    private static ExecutionStrategy fixedDelegate(ExecutionResult fixed) {
        return new ExecutionStrategy() {
            @Override public String strategyName() { return "stub"; }
            @Override public ExecutionResult execute(AgentTask t, LlmClient llm, MemoryManager mem,
                                                     ToolRegistry tools, AgentConfig cfg) {
                return fixed;
            }
        };
    }

    // ── Stubs ──────────────────────────────────────────────────────────────────

    /** Delegate that returns scripted results in order, reusing the last one when exhausted. */
    static final class CountingDelegate implements ExecutionStrategy {
        private final List<ExecutionResult> results;
        private int calls = 0;

        CountingDelegate(List<ExecutionResult> results) {
            this.results = results;
        }

        @Override public String strategyName() { return "stub-counting"; }

        @Override
        public ExecutionResult execute(AgentTask t, LlmClient llm, MemoryManager mem,
                                       ToolRegistry tools, AgentConfig cfg) {
            int idx = Math.min(calls++, results.size() - 1);
            return results.get(idx);
        }

        int callCount() { return calls; }
    }

    /** MemoryManager stub that exposes the current working memory for assertions. */
    static final class TrackingMemoryManager implements MemoryManager {

        private final List<MemoryEntry> wm = new ArrayList<>();

        @Override
        public void appendToWorkingMemory(MemoryEntry entry) {
            wm.add(entry);
        }

        @Override
        public List<MemoryEntry> workingMemory() { return List.copyOf(wm); }

        @Override
        public void clearWorkingMemory() { wm.clear(); }
    }

    /** LLM client that fails the test immediately if ever invoked. */
    static final class NeverCalledLlmClient implements LlmClient {
        @Override
        public io.ara.core.llm.LlmCompletion complete(
                List<io.ara.core.llm.LlmMessage> messages,
                io.ara.core.llm.LlmCallContext ctx) {
            throw new AssertionError("this LlmClient must never be called");
        }
        @Override public String providerId() { return "never-called-stub"; }
    }

    /** LLM client that returns a fixed answer and captures the last user message for assertion. */
    static final class CapturingLlmClient implements LlmClient {
        private final String answer;
        private String lastUser;

        CapturingLlmClient(String answer) { this.answer = answer; }

        @Override
        public io.ara.core.llm.LlmCompletion complete(
                List<io.ara.core.llm.LlmMessage> messages,
                io.ara.core.llm.LlmCallContext ctx) {
            messages.stream()
                    .filter(m -> "user".equals(m.role()))
                    .reduce((a, b) -> b) // last user message
                    .ifPresent(m -> lastUser = m.content());
            return new io.ara.core.llm.LlmCompletion(answer, 0, 10, "stop", null);
        }

        @Override public String providerId() { return "capturing-stub"; }

        String lastUserMessage() { return lastUser; }
    }
}
