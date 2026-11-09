package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.StepType;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.LlmRouter;
import io.ara.core.llm.ToolCallEntry;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link ReflActStrategy}'s two reflection triggers (tool failure,
 * unproductive-iteration streak), the "no memory reset" guarantee that distinguishes it
 * from {@link ReflexionStrategy}, the {@code maxReflections} cap, {@code forceFinal}
 * suppression, and the dual-model {@code reflectionProvider} routing. The base decision
 * loop (final-answer/tool-call) is shared with {@link ReactStrategy} via {@link
 * ReactExecutionSupport} and is pinned down by {@code ReactStrategyTest}, not
 * re-verified here.
 */
class ReflActStrategyTest {

    /** Fails {@code clearWorkingMemory()} — proves ReflAct never wipes memory, unlike ReflexionStrategy. */
    private static final class NoResetMemoryManager implements MemoryManager {
        private final InMemoryMemoryManager delegate = new InMemoryMemoryManager();

        @Override public void appendToWorkingMemory(MemoryEntry entry) { delegate.appendToWorkingMemory(entry); }
        @Override public List<MemoryEntry> workingMemory() { return delegate.workingMemory(); }
        @Override public void clearWorkingMemory() {
            throw new AssertionError("ReflAct must never reset working memory mid-episode");
        }
    }

    private static NoResetMemoryManager seeded(String systemPrompt, String userInput) {
        NoResetMemoryManager m = new NoResetMemoryManager();
        m.appendToWorkingMemory("system", systemPrompt);
        m.appendToWorkingMemory("user", userInput);
        return m;
    }

    private static AraTool failingTool() {
        return new AraTool() {
            @Override public String toolId()        { return "flaky"; }
            @Override public String description()   { return "always fails"; }
            @Override public String argumentSchema() { return "{}"; }
            @Override public ToolResult execute(String argsJson) { return ToolResult.failure("flaky", "boom"); }
        };
    }

    private static AraTool noopTool() {
        return new AraTool() {
            @Override public String toolId()        { return "noop"; }
            @Override public String description()   { return "no-op"; }
            @Override public String argumentSchema() { return "{}"; }
            @Override public ToolResult execute(String argsJson) { return ToolResult.success("noop", "ok"); }
        };
    }

    /**
     * Wraps a delegate {@link LlmClient} and throws on its {@code throwOnCall}-th
     * invocation (1-based), delegating on every other call — used to prove {@link
     * ReflActStrategy#reflect} degrades to its fallback critique rather than propagating
     * when the reflection call itself fails.
     */
    private static final class ThrowsOnCallLlmClient implements LlmClient {
        private final LlmClient delegate;
        private final int throwOnCall;
        private int calls = 0;

        ThrowsOnCallLlmClient(LlmClient delegate, int throwOnCall) {
            this.delegate = delegate;
            this.throwOnCall = throwOnCall;
        }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            calls++;
            if (calls == throwOnCall) {
                throw new RuntimeException("simulated reflection-call failure");
            }
            return delegate.complete(messages, context);
        }

        @Override public String  providerId()          { return delegate.providerId(); }
        @Override public boolean supportsNativeTools() { return delegate.supportsNativeTools(); }
    }

    private static ToolRegistry namedRegistry(AraTool... tools) {
        var map = new HashMap<String, AraTool>();
        for (var t : tools) map.put(t.toolId(), t);
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) {
                return ids.isEmpty() ? List.copyOf(map.values())
                        : ids.stream().map(map::get).filter(Objects::nonNull).toList();
            }
            @Override public Optional<AraTool> findById(String id) { return Optional.ofNullable(map.get(id)); }
            @Override public ToolResult execute(String toolId, String argumentJson) {
                var t = map.get(toolId);
                return t != null ? t.execute(argumentJson) : ToolResult.failure(toolId, "unknown tool");
            }
        };
    }

    private static AgentConfig configWith(StrategyConfig.ReflAct rc) {
        return AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .strategyConfig(rc)
                .build();
    }

    private static LlmCompletion text(String s, String finishReason) {
        return new LlmCompletion(s, 10, 10, finishReason, null);
    }

    // ── Tool-failure trigger ─────────────────────────────────────────────────────

    @Test
    void toolFailure_triggersAnImmediateReflection_recordedInTheTrace_memoryNeverReset() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenToolCall("flaky", "{}")
                .then(text("A different approach: skip the tool.", "stop"))   // reflection call
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: done without the tool", 10, 10, "stop", null))
                .build();

        ExecutionResult result = new ReflActStrategy().execute(
                AgentTask.of("do it"), llm, seeded("You are helpful.", "do it"),
                namedRegistry(failingTool()), configWith(StrategyConfig.ReflAct.defaults()));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertEquals("done without the tool", result.output());

        List<StepType> order = result.steps().stream().map(ExecutionStep::type).toList();
        assertTrue(order.contains(StepType.REFLECTION), () -> "expected a REFLECTION step in " + order);
        int obsIdx = order.indexOf(StepType.OBSERVATION);
        int reflIdx = order.indexOf(StepType.REFLECTION);
        assertTrue(obsIdx >= 0 && obsIdx < reflIdx, "reflection must follow the failed observation: " + order);
    }

    @Test
    void toolFailure_reflectionIsInjectedAsAUserTurn_visibleOnTheNextThinkCall() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenToolCall("flaky", "{}")
                .then(text("Try validating input before calling flaky again.", "stop"))
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: ok", 10, 10, "stop", null))
                .build();

        MemoryManager memory = seeded("You are helpful.", "do it");
        new ReflActStrategy().execute(
                AgentTask.of("do it"), llm, memory, namedRegistry(failingTool()), configWith(StrategyConfig.ReflAct.defaults()));

        boolean hasReflectionTurn = memory.workingMemory().stream()
                .anyMatch(e -> "user".equals(e.role()) && e.content() != null
                        && e.content().startsWith("SELF-REFLECTION:"));
        assertTrue(hasReflectionTurn, "the reflection critique must be visible as a turn in working memory");
    }

    @Test
    void reflectOnToolFailureDisabled_suppressesTheTrigger() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenToolCall("flaky", "{}")
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: ok anyway", 10, 10, "stop", null))
                .build();   // no reflection response scripted — proves it's never called

        ExecutionResult result = new ReflActStrategy().execute(
                AgentTask.of("do it"), llm, seeded("You are helpful.", "do it"),
                namedRegistry(failingTool()), configWith(new StrategyConfig.ReflAct(3, 2, false, null)));

        assertTrue(result.isSuccess());
        assertFalse(result.steps().stream().anyMatch(s -> s.type() == StepType.REFLECTION));
    }

    // ── Parallel dispatch (multiple tool calls in one completion) ──────────────────

    /**
     * {@code ReactExecutionSupport.dispatchParallel} aggregates per-call failure with
     * {@code anyFailed |= recordObservation(...)} — an OR across N results. The
     * single-tool-call tests above never exercise that path at all (they always take the
     * {@code dispatchSingle} branch); this proves the aggregation actually reflects when
     * only *one* of several parallel calls fails, not just when every call does.
     */
    @Test
    void parallelDispatch_reflectsWhenOnlyOneOfTheParallelCallsFails() {
        LlmCompletion twoCalls = new LlmCompletion(
                "calling two tools", 10, 10, "tool_calls", null, null,
                List.of(
                        new ToolCallEntry(null, "flaky", "{}"),
                        new ToolCallEntry(null, "noop", "{}")));

        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(twoCalls)
                .then(text("One of those failed — try something else.", "stop"))   // reflection call
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: done", 10, 10, "stop", null))
                .build();

        ExecutionResult result = new ReflActStrategy().execute(
                AgentTask.of("do both"), llm, seeded("You are helpful.", "do both"),
                namedRegistry(failingTool(), noopTool()), configWith(StrategyConfig.ReflAct.defaults()));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        List<StepType> types = result.steps().stream().map(ExecutionStep::type).toList();
        assertEquals(2, types.stream().filter(t -> t == StepType.TOOL_CALL).count(), () -> "expected both dispatches recorded: " + types);
        assertEquals(2, types.stream().filter(t -> t == StepType.OBSERVATION).count(), () -> "expected both observations recorded: " + types);
        assertTrue(types.contains(StepType.REFLECTION),
                () -> "one failing call among several parallel dispatches must still trigger reflection: " + types);
    }

    /** Companion to the above: proves the OR is not vacuously true — two successes must not trigger anything. */
    @Test
    void parallelDispatch_noReflectionWhenAllParallelCallsSucceed() {
        LlmCompletion twoCalls = new LlmCompletion(
                "calling two tools", 10, 10, "tool_calls", null, null,
                List.of(
                        new ToolCallEntry(null, "noop", "{}"),
                        new ToolCallEntry(null, "noop", "{}")));

        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(twoCalls)
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: done", 10, 10, "stop", null))
                .build();   // no reflection response scripted — proves it's never called

        ExecutionResult result = new ReflActStrategy().execute(
                AgentTask.of("do both"), llm, seeded("You are helpful.", "do both"),
                namedRegistry(noopTool()), configWith(StrategyConfig.ReflAct.defaults()));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertFalse(result.steps().stream().anyMatch(s -> s.type() == StepType.REFLECTION));
    }

    // ── Unproductive-streak trigger ─────────────────────────────────────────────

    @Test
    void unproductiveStreak_triggersReflectionOnceTheThresholdIsReached() {
        // finishReason="length" (not "stop") with no tool call and no FINAL_ANSWER text
        // is neither a final answer nor a tool call — a genuine Continue. unproductiveStreak
        // default is 2: the 2nd such iteration in a row triggers reflection.
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(text("still thinking...", "length"))          // iter 1: Continue, streak=1
                .then(text("still thinking harder...", "length"))    // iter 2: Continue, streak=2 -> reflect
                .then(text("Stop looping — call the noop tool.", "stop"))   // reflection call
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: done", 10, 10, "stop", null))
                .build();

        ExecutionResult result = new ReflActStrategy().execute(
                AgentTask.of("hi"), llm, seeded("You are helpful.", "hi"),
                ToolRegistry.empty(), configWith(StrategyConfig.ReflAct.defaults()));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertTrue(result.steps().stream().anyMatch(s -> s.type() == StepType.REFLECTION));
    }

    @Test
    void unproductiveStreak_resetsWheneverAToolIsDispatched() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(text("thinking...", "length"))          // streak=1
                .thenToolCall("noop", "{}")                    // dispatch -> streak reset to 0
                .then(text("thinking again...", "length"))     // streak=1 (not 2 — no reflection expected here)
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: done", 10, 10, "stop", null))
                .build();

        ExecutionResult result = new ReflActStrategy().execute(
                AgentTask.of("hi"), llm, seeded("You are helpful.", "hi"),
                namedRegistry(noopTool()), configWith(StrategyConfig.ReflAct.defaults()));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertFalse(result.steps().stream().anyMatch(s -> s.type() == StepType.REFLECTION),
                "a tool dispatch must reset the unproductive streak, so 1+1 must never reach the threshold of 2");
    }

    /**
     * All the tests above use the default threshold (2). This — and its sibling below —
     * prove the {@code unproductiveStreak >= rc.unproductiveStreak()} comparison actually
     * reads the configured value rather than a hardcoded {@code 2}: threshold 1 must
     * trigger on the very first unproductive iteration.
     */
    @Test
    void unproductiveStreak_thresholdOfOne_triggersOnTheFirstUnproductiveIteration() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(text("thinking...", "length"))                       // iter 1: streak=1 >= 1 -> reflect
                .then(text("Stop stalling, call a tool.", "stop"))          // reflection call
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: done", 10, 10, "stop", null))
                .build();

        ExecutionResult result = new ReflActStrategy().execute(
                AgentTask.of("hi"), llm, seeded("You are helpful.", "hi"),
                ToolRegistry.empty(), configWith(new StrategyConfig.ReflAct(3, 1, true, null)));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertTrue(result.steps().stream().anyMatch(s -> s.type() == StepType.REFLECTION),
                "threshold=1 must reflect after a single unproductive iteration, not wait for 2");
    }

    /** Sibling of the above in the other direction: threshold 3 must NOT fire at 2, only at 3. */
    @Test
    void unproductiveStreak_thresholdOfThree_doesNotTriggerAtTwo_onlyAtThree() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(text("thinking...", "length"))          // streak=1
                .then(text("thinking still...", "length"))    // streak=2 (must NOT reflect — threshold is 3)
                .then(text("thinking yet again...", "length")) // streak=3 -> reflect
                .then(text("Try a completely different approach.", "stop"))  // reflection call
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: done", 10, 10, "stop", null))
                .build();

        ExecutionResult result = new ReflActStrategy().execute(
                AgentTask.of("hi"), llm, seeded("You are helpful.", "hi"),
                ToolRegistry.empty(), configWith(new StrategyConfig.ReflAct(3, 3, true, null)));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        long reflections = result.steps().stream().filter(s -> s.type() == StepType.REFLECTION).count();
        assertEquals(1, reflections,
                "threshold=3 must reflect exactly once, on the 3rd consecutive unproductive iteration");
    }

    // ── maxReflections cap ───────────────────────────────────────────────────────

    @Test
    void maxReflectionsZero_disablesSelfCorrectionEntirely_equivalentToPlainReact() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenToolCall("flaky", "{}")
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: ok", 10, 10, "stop", null))
                .build();   // no reflection response scripted — proves it's never invoked

        ExecutionResult result = new ReflActStrategy().execute(
                AgentTask.of("do it"), llm, seeded("You are helpful.", "do it"),
                namedRegistry(failingTool()), configWith(new StrategyConfig.ReflAct(0, 2, true, null)));

        assertTrue(result.isSuccess());
        assertFalse(result.steps().stream().anyMatch(s -> s.type() == StepType.REFLECTION));
    }

    /**
     * The only test that actually reaches the cap and then keeps going: {@code
     * maxReflections=1} with two independent tool-failure triggers must reflect once, on
     * the first, and then stay silent on the second even though it independently
     * qualifies — proving {@code reflectionsUsed < rc.maxReflections()} is checked at
     * {@code 1 < 1} (false), not just observed to hold at {@code 0 < 1} once and never
     * re-checked.
     */
    @Test
    void maxReflectionsCap_preventsASecondReflection_onceTheCapIsReached() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenToolCall("flaky", "{}")                                     // iter1: fails, 0<1 -> reflect
                .then(text("Try a different tool next time.", "stop"))            // reflection call (1st, consumes the cap)
                .thenToolCall("flaky", "{}")                                     // iter2: fails again, 1<1 false -> no reflect
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: gave up on the tool", 10, 10, "stop", null))
                .build();   // no 2nd reflection response scripted — proves the cap actually stops it

        ExecutionResult result = new ReflActStrategy().execute(
                AgentTask.of("do it"), llm, seeded("You are helpful.", "do it"),
                namedRegistry(failingTool()), configWith(new StrategyConfig.ReflAct(1, 2, true, null)));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        List<StepType> types = result.steps().stream().map(ExecutionStep::type).toList();
        assertEquals(2, types.stream().filter(t -> t == StepType.TOOL_CALL).count(),
                () -> "both failed dispatches must still be in the trace: " + types);
        assertEquals(1, types.stream().filter(t -> t == StepType.REFLECTION).count(),
                () -> "exactly one reflection — the cap must block the second trigger: " + types);
    }

    // ── Token accounting ─────────────────────────────────────────────────────────

    @Test
    void reflectionCallTokens_areFoldedIntoTheTotals() {
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenToolCall("flaky", "{}")                                          // 10+10
                .then(new LlmCompletion("critique", 7, 13, "stop", null))              // reflection: 7+13
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: ok", 5, 5, "stop", null))  // 5+5
                .build();

        ExecutionResult result = new ReflActStrategy().execute(
                AgentTask.of("do it"), llm, seeded("You are helpful.", "do it"),
                namedRegistry(failingTool()), configWith(StrategyConfig.ReflAct.defaults()));

        assertTrue(result.isSuccess());
        // dispatchSingle's LLM call is the initial tool-call completion (10+10); the
        // synthesis/tool dispatch itself makes no LLM call. Total = 10+10 (toolcall) +
        // 7+13 (reflection) + 5+5 (final) = 50.
        assertEquals(50, result.tokensUsed(),
                "reflection call usage must be folded into the totals, not silently dropped");
    }

    // ── forceFinal suppression ───────────────────────────────────────────────────

    @Test
    void reflectionIsSuppressedOnForcedFinalIterations() {
        // maxIterations=2 -> iteration 2 is forceFinal (iterations >= maxIterations-1).
        // A tool failure on iteration 1 (not forceFinal) DOES trigger a reflection —
        // this test targets the forceFinal iteration specifically, where tools are
        // withheld entirely so no tool-failure trigger can even fire, and an
        // unproductive-streak trigger must also stay silent.
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .then(text("still thinking", "length"))   // iter 1: Continue, streak=1
                .then(text("still thinking", "length"))   // iter 2 (forceFinal): Continue, must NOT reflect
                .build();

        AgentConfig config = AgentConfig.defaults()
                .primaryLlm(LlmProfile.of("stub"))
                .strategyConfig(new StrategyConfig.ReflAct(3, 2, true, null))
                .maxIterations(2)
                .build();

        ExecutionResult result = new ReflActStrategy().execute(
                AgentTask.of("hi"), llm, seeded("You are helpful.", "hi"), ToolRegistry.empty(), config);

        assertFalse(result.isSuccess());   // exhausts maxIterations without a final answer
        assertFalse(result.steps().stream().anyMatch(s -> s.type() == StepType.REFLECTION),
                "no reflection may fire once the loop has entered its forced-final iteration(s)");
    }

    // ── Reflection-call failure degrades gracefully ─────────────────────────────

    /**
     * {@code reflect()} is documented to fall back to a generic critique — never to
     * propagate — when the reflection LLM call itself throws, so a broken critic must
     * not abort a task the main loop could otherwise still complete. Never exercised
     * until now: every other test's reflection call succeeds.
     */
    @Test
    void reflectionCallThrows_degradesToTheFallbackCritique_taskStillSucceeds() {
        ScriptedLlmClient scripted = ScriptedLlmClient.script()
                .thenToolCall("flaky", "{}")                                              // call 1: iter1 Think
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: gave up on the tool", 10, 10, "stop", null))  // call 3: iter2 Think
                .build();
        // Call 2 overall is the reflection call (right after iter1's tool failure) — make it throw.
        ThrowsOnCallLlmClient llm = new ThrowsOnCallLlmClient(scripted, 2);

        ExecutionResult result = new ReflActStrategy().execute(
                AgentTask.of("do it"), llm, seeded("You are helpful.", "do it"),
                namedRegistry(failingTool()), configWith(StrategyConfig.ReflAct.defaults()));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertEquals("gave up on the tool", result.output());

        Optional<ExecutionStep> reflection = result.steps().stream()
                .filter(s -> s.type() == StepType.REFLECTION).findFirst();
        assertTrue(reflection.isPresent(), "a REFLECTION step must still be recorded even though the call failed");
        // Mirrors ReflActStrategy.fallbackCritique()'s exact return value.
        assertEquals("The current approach does not appear to be making progress. Try a different angle.",
                reflection.get().content());
    }

    // ── Dual-model reflectionProvider routing ───────────────────────────────────

    @Test
    void reflectionProvider_routesTheReflectionCall_toTheResolvedClient_notTheMainLlm() {
        ScriptedLlmClient mainLlm = ScriptedLlmClient.script()
                .thenToolCall("flaky", "{}")
                .then(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: ok", 10, 10, "stop", null))
                .build();   // no reflection text scripted here — it must not be asked

        ScriptedLlmClient criticLlm = ScriptedLlmClient.script()
                .thenFinalAnswer("routed critique")   // .text() is read regardless of the FINAL_ANSWER framing
                .build();

        LlmRouter router = (llmConfig, ctx) -> {
            assertEquals("critic-model", llmConfig.primary().modelId());
            return criticLlm;
        };

        ExecutionResult result = new ReflActStrategy(router).execute(
                AgentTask.of("do it"), mainLlm, seeded("You are helpful.", "do it"),
                namedRegistry(failingTool()), configWith(new StrategyConfig.ReflAct(3, 2, true, "critic-model")));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertTrue(result.steps().stream()
                .anyMatch(s -> s.type() == StepType.REFLECTION && s.content().contains("routed critique")));
    }

    // ── Wiring sanity ────────────────────────────────────────────────────────────

    @Test
    void strategyName_isReflact() {
        assertEquals("reflact", new ReflActStrategy().strategyName());
    }
}
