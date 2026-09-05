package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.EmbeddingClient;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.SemanticStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0086: a builder that never calls {@code memoryManagerFactory(...)} still wires a real
 * {@code SlidingWindowMemoryManager} whenever {@code AgentConfig.memory()} carries a positive
 * token budget, instead of always falling back to the unlimited {@code InMemoryMemoryManager}
 * as before. A config that leaves the budget at its default (0) must keep behaving exactly as
 * it did before this ADR — that is the one behaviour change this backlog treats as the highest
 * exposure, so it is asserted here, not just implied by every pre-existing test staying green.
 */
class AraRuntimeMemoryWiringTest {

    private static final String LONG_TAIL = " padding padding padding padding padding";

    /** Records the message list of the most recent {@code complete} call. */
    private static final class RecordingLlmClient implements LlmClient {
        final AtomicReference<List<LlmMessage>> lastMessages = new AtomicReference<>(List.of());
        private int turn = 0;

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            lastMessages.set(List.copyOf(messages));
            turn++;
            return new LlmCompletion(
                    "Action: FINAL_ANSWER\nAnswer: reply " + turn + LONG_TAIL,
                    10, 10, "stop", null);
        }

        @Override
        public String providerId() { return "recording"; }
    }

    /** Similarity-free fake: "search" returns whatever was upserted for that agent, unranked. */
    private static final class RecordingSemanticStore implements SemanticStore {
        final Map<String, List<MemoryEntry>> byAgent = new HashMap<>();

        @Override
        public void upsert(String agentId, String role, String type, String content, List<Float> vector) {
            byAgent.computeIfAbsent(agentId, k -> new ArrayList<>()).add(MemoryEntry.of(role, content));
        }

        @Override
        public List<MemoryEntry> search(String agentId, List<Float> queryVector, int limit) {
            List<MemoryEntry> hits = byAgent.getOrDefault(agentId, List.of());
            return hits.subList(0, Math.min(limit, hits.size()));
        }
    }

    private static final class ConstantEmbeddingClient implements EmbeddingClient {
        @Override public List<Float> embed(String text) { return List.of(0f); }
        @Override public int dimensions() { return 1; }
    }

    private static AgentConfig.Builder baseConfig(AgentId id) {
        return AgentConfig.defaults()
                .agentId(id)
                .agentType("t")
                .primaryLlm(LlmProfile.of(id.value()))
                .plannerStrategy("react")
                .maxIterations(3)
                .maxConversationTurns(20);
    }

    private static void runTurns(AraAgent agent, SessionId session, int count) {
        for (int i = 0; i < count; i++) {
            AgentResponse r = agent.execute(AgentTask.of("message " + i + LONG_TAIL).withSessionId(session));
            assertTrue(r.isSuccess(), () -> "execute failed: " + r.failureReason());
        }
    }

    @Test
    void zeroBudget_keepsTodaysUnlimitedWindow() {
        AgentId id = AgentId.of("unlimited-agent");
        RecordingLlmClient llm = new RecordingLlmClient();
        AraRuntime runtime = AraRuntime.builder().llmClient(id.value(), llm).build();
        // workingMemoryTokenBudget left at its default (0) — must reproduce the old,
        // always-unlimited InMemoryMemoryManager exactly.
        AraAgent agent = runtime.createAgent(baseConfig(id).build());
        SessionId session = SessionId.of("s1");

        runTurns(agent, session, 10);

        // Unlimited: system + 9 replayed turns (2 messages each) + this turn's own input.
        assertEquals(1 + 9 * 2 + 1, llm.lastMessages.get().size());
    }

    @Test
    void positiveBudget_wiresARealSlidingWindowAndEvicts() {
        AgentId id = AgentId.of("bounded-agent");
        RecordingLlmClient llm = new RecordingLlmClient();
        AraRuntime runtime = AraRuntime.builder().llmClient(id.value(), llm).build();
        AraAgent agent = runtime.createAgent(baseConfig(id)
                .workingMemoryTokenBudget(30)
                .workingMemoryEviction("drop_oldest")
                .build());
        SessionId session = SessionId.of("s1");

        runTurns(agent, session, 10);

        // The unlimited case above sends 20 messages after the same 10 turns; a real budget
        // must keep the window far smaller than that, proving eviction actually ran.
        int sent = llm.lastMessages.get().size();
        assertTrue(sent < 10, () -> "expected eviction to bound the window, got " + sent + " messages");
    }

    @Test
    void offloadAndRecall_activateOnlyWhenBothCollaboratorsAreConfigured() {
        AgentId id = AgentId.of("recall-agent");
        RecordingLlmClient llm = new RecordingLlmClient();
        RecordingSemanticStore store = new RecordingSemanticStore();
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(id.value(), llm)
                .embeddingClient(new ConstantEmbeddingClient())
                .semanticStore(store)
                .build();
        AraAgent agent = runtime.createAgent(baseConfig(id)
                .workingMemoryTokenBudget(30)
                .workingMemoryEviction("drop_oldest")
                .build());
        SessionId session = SessionId.of("s1");

        runTurns(agent, session, 10);

        assertTrue(!store.byAgent.getOrDefault(id.value(), List.of()).isEmpty(),
                "eviction with a configured embeddingClient/semanticStore must offload the evicted range");
    }
}
