package io.ara.examples.basics;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.runtime.AraRuntime;

import java.util.List;
import java.util.concurrent.Flow;

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
 * <p>Runs offline by default with {@link WordStreamLlmClient}, a stub that overrides
 * {@link LlmClient#stream} and emits one word at a time. Pass {@code live} as the first
 * argument (or {@code -Dara.example.live=true}) to stream from a real OpenAI-compatible
 * endpoint instead — {@code OpenAiLlmClient} streams natively over an SSE socket. The
 * constants below are preset for a local LM-Studio-style server ({@code openai/gpt-oss-20b},
 * no API key required).
 */
public class SimpleStreamingExample {

    private static final String LIVE_BASE_URL = "http://192.168.1.114:1234/v1";
    private static final String LIVE_MODEL    = "openai/gpt-oss-20b";
    /** LM Studio ignores the key but langchain4j wants a non-blank string;
     *  override with {@code -Dara.api.key=...} or {@code ARA_API_KEY} if your gateway checks it. */
    private static final String LIVE_API_KEY  = firstNonBlank(
            System.getProperty("ara.api.key"), System.getenv("ARA_API_KEY"), "not-required");

    public static void main(String[] args) {

        boolean live = Boolean.getBoolean("ara.example.live")
                || (args.length > 0 && args[0].equalsIgnoreCase("live"));

        LlmClient llmClient = live
                ? OpenAiLlmClient.builder()
                        .baseUrl(LIVE_BASE_URL)
                        .apiKey(LIVE_API_KEY)
                        .modelName(LIVE_MODEL)
                        .build()
                : new WordStreamLlmClient();

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

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    /** Offline stub: streams a fixed sentence, one word (with its trailing space) at a time. */
    static final class WordStreamLlmClient implements LlmClient {

        private static final String ANSWER =
                "Ciao, sono un agente ARA e ti rispondo in streaming, una parola alla volta.";

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            return new LlmCompletion(ANSWER, 12, 18, "stop", null);
        }

        @Override
        public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
            return subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) { }
                    @Override public void cancel() { }
                });
                try {
                    for (String word : ANSWER.split("(?<= )")) {   // split after each space, keep it
                        subscriber.onNext(word);
                        Thread.sleep(70);
                    }
                    subscriber.onComplete();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    subscriber.onError(e);
                }
            };
        }

        @Override
        public String providerId() {
            return "word-stream-stub";
        }
    }
}
