package io.ara.examples.rag;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.EmbeddingClient;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.memory.InMemoryDocumentStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows how to add a Retrieval-Augmented Generation (RAG) agent to a
 * multi-agent architecture using only built-in ARA components.
 *
 * <p>Two agents are wired up:
 * <ul>
 *   <li>{@code kb-agent}     — strategy {@code "rag+react"}: a plain ReAct loop
 *       transparently wrapped by {@code RetrievalAugmentedStrategy}, backed by an
 *       {@link InMemoryDocumentStore} holding a few ARA architecture notes.</li>
 *   <li>{@code orchestrator} — plain {@code "react"} agent that delegates
 *       knowledge questions to {@code kb-agent} via the built-in
 *       {@code delegate_task} tool (see {@code AgentDelegationTool}).</li>
 * </ul>
 *
 * <p>Registering a {@link io.ara.core.retriever.Retriever} on the runtime builder
 * (via {@code .retriever(kb)}) is enough to auto-register the {@code "rag+react"},
 * {@code "rag+respact"}, {@code "rag+plan_execute"} and {@code "rag+reflact"} strategies
 * (see {@code AraRuntime.Builder.build()}) — no extra wiring is required to make RAG
 * available to any agent in the system.
 *
 * <p>Both LLM calls are scripted (no API key / network access needed), so the demo
 * is deterministic and runs offline. To use this for real:
 * <ul>
 *   <li>replace {@link DemoEmbeddingClient} with a real {@link EmbeddingClient}
 *       (e.g. an OpenAI {@code text-embedding-3-small} wrapper);</li>
 *   <li>replace {@code KbAgentScript}/{@code OrchestratorScript} with real
 *       {@code LlmClient}s from {@code ara-adapters} (OpenAI, Anthropic, Ollama);</li>
 *   <li>for a production-scale knowledge base swap {@link InMemoryDocumentStore}
 *       for {@code DocumentStore} (Qdrant-backed) — same {@code Retriever} contract.</li>
 * </ul>
 */
public class RagAgentExample {

    public static void main(String[] args) {
        System.out.println("=== ARA - RAG agent inside a multi-agent architecture ===\n");

        // ── 1. Knowledge base — no external vector DB required for this demo ────
        EmbeddingClient embeddings = new DemoEmbeddingClient();
        InMemoryDocumentStore kb = new InMemoryDocumentStore("ara-docs", embeddings);
        kb.ensureCollection();
        kb.indexDocument("adr-016", "Session isolation (ADR-016)",
                "Ogni AgentInstance gestisce stato e memoria di lavoro per-sessione. "
                + "Sessioni concorrenti sullo stesso agente sono isolate tramite un "
                + "ReentrantLock dedicato; la SessionBusyPolicy (REJECT o ENQUEUE) decide "
                + "cosa succede quando un secondo task arriva sulla stessa sessione mentre "
                + "la precedente e' ancora in esecuzione.");
        kb.indexDocument("adr-030", "LLM routing (ADR-030)",
                "Il LlmRouter risolve il LlmClient da usare in base alla LlmSelectionPolicy "
                + "dell'agente: PRIMARY_ONLY, FAILOVER o ROUND_ROBIN, con fallback su un "
                + "override inline (baseUrl+modelName) o sul client di default registrato.");
        kb.indexDocument("rag", "Retrieval-Augmented Generation in ARA",
                "RetrievalAugmentedStrategy avvolge qualsiasi ExecutionStrategy: recupera i "
                + "passaggi piu' rilevanti una sola volta per task e li inietta nel messaggio "
                + "di sistema tramite un LlmClient decorator, cosi' la strategia sottostante "
                + "(ReAct o Plan-Execute) resta ignara del retrieval.");

        System.out.printf("Knowledge base indicizzata: %d documenti%n%n", kb.listDocuments().size());

        // ── 2. Runtime — .retriever(kb) auto-registra "rag+react"/"rag+plan-execute" ─
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("kb-llm",           new KbAgentScript())
                .llmClient("orchestrator-llm", new OrchestratorScript())
                .retriever(kb)
                .build();
        runtime.start();

        // ── 3. L'agente RAG ──────────────────────────────────────────────────────
        AgentConfig kbAgentConfig = AgentConfig.defaults()
                .agentId(AgentId.of("kb-agent"))
                .agentType("knowledge-base")
                .systemPrompt("Sei l'agente di knowledge-base di ARA. Rispondi solo sulla base "
                        + "del contesto recuperato (## Retrieved Context).")
                .primaryLlm(LlmProfile.of("kb-llm"))
                .plannerStrategy("rag+react")   // ReactStrategy avvolta da RAG
                .enabledTools(List.of())        // niente tool: solo retrieval + LLM
                .maxIterations(3)
                .build();
        runtime.createAgent(kbAgentConfig);
        System.out.println("Agente creato: kb-agent (strategia rag+react)");

        // ── 4. L'orchestratore — delega a kb-agent via delegate_task ─────────────
        AgentConfig orchestratorConfig = AgentConfig.defaults()
                .agentId(AgentId.of("orchestrator"))
                .agentType("orchestrator")
                .systemPrompt("Coordini una squadra di agenti. Per domande sull'architettura "
                        + "di ARA, delega a 'kb-agent' con il tool delegate_task.")
                .primaryLlm(LlmProfile.of("orchestrator-llm"))
                .plannerStrategy("react")
                .enabledTools(List.of("delegate_task"))
                .maxIterations(5)
                .build();
        AraAgent orchestrator = runtime.createAgent(orchestratorConfig);
        System.out.println("Agente creato: orchestrator (strategia react + delegate_task)\n");

        // ── 5. Esecuzione ─────────────────────────────────────────────────────────
        AgentTask task = AgentTask.of(
                "Come gestisce ARA la concorrenza tra sessioni sullo stesso agente?");
        System.out.printf("Task all'orchestrator: %s%n%n", task.input());

        AgentResponse response = orchestrator.execute(task);

        System.out.println("\n=== Risultato ===");
        System.out.printf("Success    : %s%n", response.isSuccess());
        System.out.printf("Answer     : %s%n", response.content());
        System.out.printf("Iterations : %d%n", response.iterationsUsed());
        System.out.printf("Tokens     : %d%n", response.totalTokens());

        runtime.stop();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Demo-only stand-ins — sostituire con implementazioni reali in produzione
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Embedding deterministico, senza dipendenze esterne: hash bag-of-words
     * proiettato su un vettore a dimensione fissa, poi normalizzato L2. Sufficiente
     * a far funzionare la cosine similarity per la demo — NON adatto alla
     * produzione (usare un vero {@link EmbeddingClient}: OpenAI, Cohere, un modello
     * locale di sentence-embedding, ecc., tutti intercambiabili dietro la stessa
     * interfaccia).
     */
    static final class DemoEmbeddingClient implements EmbeddingClient {
        private static final int DIM = 64;

        @Override
        public List<Float> embed(String text) {
            float[] v = new float[DIM];
            for (String token : text.toLowerCase().split("\\W+")) {
                if (token.isBlank()) continue;
                int bucket = Math.floorMod(token.hashCode(), DIM);
                v[bucket] += 1f;
            }
            float norm = 0f;
            for (float f : v) norm += f * f;
            norm = (float) Math.sqrt(norm);
            List<Float> out = new ArrayList<>(DIM);
            for (float f : v) out.add(norm > 0 ? f / norm : 0f);
            return out;
        }

        @Override
        public int dimensions() { return DIM; }
    }

    /**
     * Risposte scriptate per kb-agent: risponde sempre sulla base del blocco
     * "## Retrieved Context" iniettato da RetrievalAugmentedStrategy nel primo
     * messaggio di sistema.
     */
    static final class KbAgentScript implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext ctx) {
            String systemMsg = messages.get(0).content();
            System.out.println("  [kb-agent] contesto recuperato e iniettato nel system prompt:");
            int shown = Math.min(systemMsg.length(), 260);
            System.out.println("  " + systemMsg.substring(0, shown).replace("\n", "\n  ") + "...\n");

            String answer = systemMsg.contains("ADR-016")
                    ? "ARA isola stato e memoria per sessione: ogni sessione ha un proprio "
                      + "ReentrantLock; la policy REJECT rifiuta un secondo task concorrente "
                      + "sulla stessa sessione, ENQUEUE lo mette in coda invece di rifiutarlo."
                    : "Non ho trovato contesto sufficiente nella knowledge base per rispondere.";
            return new LlmCompletion("Action: FINAL_ANSWER\nAnswer: " + answer, 50, 40, "stop", null);
        }

        @Override
        public String providerId() { return "kb-llm"; }
    }

    /**
     * Risposte scriptate per l'orchestrator: alla prima chiamata delega a
     * kb-agent, alla seconda (dopo l'Observation con la risposta delegata)
     * restituisce FINAL_ANSWER.
     */
    static final class OrchestratorScript implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext ctx) {
            boolean hasObservation = messages.stream()
                    .anyMatch(m -> m.content().startsWith("Observation:"));

            if (!hasObservation) {
                String toolCallJson = """
                        {"tool_id":"delegate_task","arguments":{"agent_id":"kb-agent","task":"Come gestisce ARA la concorrenza tra sessioni sullo stesso agente?"}}""";
                return new LlmCompletion(
                        "Delego la domanda tecnica al kb-agent.", 20, 15, "tool_calls", toolCallJson);
            }

            String observation = messages.stream()
                    .filter(m -> m.content().startsWith("Observation:"))
                    .reduce((a, b) -> b)
                    .map(LlmMessage::content)
                    .orElse("(nessuna risposta dal kb-agent)");
            return new LlmCompletion(
                    "Action: FINAL_ANSWER\nAnswer: " + observation, 30, 25, "stop", null);
        }

        @Override
        public String providerId() { return "orchestrator-llm"; }
    }
}
