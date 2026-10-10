package io.ara.examples.basics;

import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmProfile;
import io.ara.examples.support.Live;
import io.ara.examples.support.StreamingLlmStub;
import io.ara.runtime.AraRuntime;

/**
 * The smallest streaming agent: no tools, no interceptors, one LLM turn.
 *
 * <p>Streaming needs exactly two things wired together:
 * <ul>
 *   <li>{@code LlmProfile.builder().streamingEnabled(true)} on the agent's model;</li>
 *   <li>{@code AgentTask.ofStreaming(prompt, token -> ...)} — a sink for the tokens.</li>
 * </ul>
 * The callback runs on the agent's thread <em>while {@code execute()} is still going</em>;
 * the returned {@link AgentResponse} then carries the same text, fully assembled.
 *
 * <p>Runs offline by default with {@link StreamingLlmStub}, which emits one word at a time.
 * Pass {@code live} as the first argument (or {@code -Dara.example.live=true}) to stream
 * from a real OpenAI-compatible endpoint instead — {@code OpenAiLlmClient} streams natively
 * over an SSE socket. The constants below are preset for a local LM-Studio-style server
 * ({@code qwen3-coder-30b-a3b-instruct}, no API key required).
 *
 * @see StreamingWithToolExample — streaming through a ReAct loop that also calls a tool
 * @see io.ara.examples.web.StreamingChatWebExample — the same streaming pattern in a browser
 */
public class SimpleStreamingExample {

    /** The stub's canned answer, streamed one word at a time when running offline. */
    private static final String STUB_ANSWER =
            "Ciao, sono un agente ARA e ti rispondo in streaming, una parola alla volta.";

    private static final String LIVE_BASE_URL = "http://127.0.0.1:1234/v1";
    private static final String LIVE_MODEL    = "qwen3-coder-30b-a3b-instruct";
    /** LM Studio ignores the key but langchain4j wants a non-blank string;
     *  override with {@code -Dara.api.key=...} or {@code ARA_API_KEY} if your gateway checks it. */
    private static final String LIVE_API_KEY  = Live.apiKey("not-required");

    public static void main(String[] args) {

        boolean live = Live.requested(args);

        LlmClient llmClient = live
                ? OpenAiLlmClient.builder()
                        .baseUrl(LIVE_BASE_URL)
                        .apiKey(LIVE_API_KEY)
                        .modelName(LIVE_MODEL)
                        .build()
                : new StreamingLlmStub(msgs -> STUB_ANSWER, 70, "word-stream-stub");

        System.out.println("LLM: " + (live ? "LIVE — " + LIVE_MODEL + " @ " + LIVE_BASE_URL
                                            : "stub — word-by-word (offline). Pass \"live\" for a real model."));

        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("model", llmClient)
                .build()) {

            runtime.start();

            AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                    .agentType("assistant")
                    .systemPrompt("Sei un assistente conciso.")
                    .primaryLlm(LlmProfile.builder()
                            .transportId("model")
                            .streamingEnabled(true)          // ← 1 of 2
                            .build())
                    .plannerStrategy("react")
                    .build());

            AgentTask task = AgentTask.ofStreaming(               // ← 2 of 2
                    "Presentati in un paragrafo.",
                    token -> { System.out.print(token); System.out.flush(); });

            System.out.print("\nAssistant: ");
            AgentResponse response = agent.execute(task);
            System.out.println();

            System.out.println("\n[assembled] success=" + response.isSuccess()
                    + "  answer=\"" + response.content() + "\"");
        }
    }
}
