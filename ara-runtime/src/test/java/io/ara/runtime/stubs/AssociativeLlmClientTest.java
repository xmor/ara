package io.ara.runtime.stubs;

import io.ara.core.agent.AgentConfig;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0-b of ADR-052: the associative stub, the prerequisite that makes the dataflow
 * scheduler's tests writable at all.
 *
 * <p>{@link ScriptedLlmClient} pops from a single queue, so with more than one node in flight
 * which agent gets which response is decided by thread scheduling. These tests pin the property
 * that replaces it: <b>a response belongs to an agent, not to a position</b>.
 */
class AssociativeLlmClientTest {

    private static LlmCallContext ctx(String agentId) {
        return new LlmCallContext.Builder().agentId(agentId).build();
    }

    @Test
    void eachAgentGetsItsOwnScript_regardlessOfCallOrder() {
        AssociativeLlmClient stub = AssociativeLlmClient.script()
                .forAgent("planner").thenFinalAnswer("piano")
                .forAgent("worker-a").thenFinalAnswer("A")
                .forAgent("worker-b").thenFinalAnswer("B")
                .build();

        // deliberately out of declaration order — the property under test
        assertTrue(stub.complete(List.of(), ctx("worker-b")).text().contains("B"));
        assertTrue(stub.complete(List.of(), ctx("planner")).text().contains("piano"));
        assertTrue(stub.complete(List.of(), ctx("worker-a")).text().contains("A"));
    }

    @Test
    void perAgentSequenceIsPreservedWhenInterleaved() {
        AssociativeLlmClient stub = AssociativeLlmClient.script()
                .forAgent("a").thenToolCall("search", "{\"q\":\"x\"}").thenFinalAnswer("A done")
                .forAgent("b").thenFinalAnswer("B done")
                .build();

        assertEquals("tool_calls", stub.complete(List.of(), ctx("a")).finishReason());
        assertTrue(stub.complete(List.of(), ctx("b")).text().contains("B done"));   // interleaved
        assertTrue(stub.complete(List.of(), ctx("a")).text().contains("A done"));
    }

    @Test
    void unknownAgentFailsInsteadOfAnsweringAtRandom() {
        AssociativeLlmClient stub = AssociativeLlmClient.script()
                .forAgent("known").thenFinalAnswer("ok")
                .build();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> stub.complete(List.of(), ctx("unknown")));
        assertTrue(e.getMessage().contains("unknown"), e.getMessage());
        assertTrue(e.getMessage().contains("known"), e.getMessage());
    }

    /**
     * The null case is the one that would silently pass with a lenient stub: a context not built
     * from an {@code AgentConfig} carries no id, and answering it anyway would hand a scripted
     * response to a caller the stub cannot identify.
     */
    @Test
    void nullAgentIdFailsToo() {
        AssociativeLlmClient stub = AssociativeLlmClient.script()
                .forAgent("known").thenFinalAnswer("ok")
                .build();

        assertThrows(IllegalStateException.class,
                () -> stub.complete(List.of(), new LlmCallContext.Builder().build()));
    }

    @Test
    void exhaustedScriptFailsUnlessAFallbackWasDeclared() {
        AssociativeLlmClient strict = AssociativeLlmClient.script()
                .forAgent("a").thenFinalAnswer("only one")
                .build();
        strict.complete(List.of(), ctx("a"));
        assertThrows(IllegalStateException.class, () -> strict.complete(List.of(), ctx("a")));

        AssociativeLlmClient lenient = AssociativeLlmClient.script()
                .forAgent("a").thenFinalAnswer("only one")
                .fallback(new LlmCompletion("Action: FINAL_ANSWER\nAnswer: filler", 1, 1, "stop", null))
                .build();
        lenient.complete(List.of(), ctx("a"));
        assertTrue(lenient.complete(List.of(), ctx("a")).text().contains("filler"));
    }

    /**
     * The reason the class exists: N nodes in flight at once, and no response reaching the wrong
     * agent. This is the assertion {@code ScriptedLlmClient} cannot make.
     */
    @Test
    void concurrentAgentsNeverCrossResponses() throws Exception {
        AssociativeLlmClient stub = AssociativeLlmClient.script()
                .forAgent("n1").thenFinalAnswer("uno").thenFinalAnswer("uno").thenFinalAnswer("uno")
                .forAgent("n2").thenFinalAnswer("due").thenFinalAnswer("due").thenFinalAnswer("due")
                .forAgent("n3").thenFinalAnswer("tre").thenFinalAnswer("tre").thenFinalAnswer("tre")
                .build();
        Map<String, String> expected = Map.of("n1", "uno", "n2", "due", "n3", "tre");

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (String id : expected.keySet()) {
            for (int i = 0; i < 3; i++) {
                tasks.add(() -> stub.complete(List.of(), ctx(id)).text().contains(expected.get(id)));
            }
        }

        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Boolean> f : ex.invokeAll(tasks)) {
                assertTrue(f.get(), "a response reached the wrong agent");
            }
        }
        assertEquals(Map.of("n1", 3, "n2", 3, "n3", 3), stub.callsPerAgent());
    }

    /**
     * The upstream half of Phase 0-b: without {@code agentId} on {@link LlmCallContext} the stub
     * has nothing to key on. {@code agentType} is not a substitute — its default is the shared
     * constant {@code "generic"}.
     */
    @Test
    void llmCallContextCarriesTheAgentIdFromAgentConfig() {
        AgentConfig config = AgentConfig.defaults()
                .name("worker-a")
                .primaryLlm(LlmProfile.of("stub"))
                .build();

        LlmCallContext ctx = LlmCallContext.from(config);

        assertEquals(config.agentId().value(), ctx.agentId());
        assertEquals("generic", ctx.agentType(), "agentType is not a discriminator — that is the point");
    }
}
