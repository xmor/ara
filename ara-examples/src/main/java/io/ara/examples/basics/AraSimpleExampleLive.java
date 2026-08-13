package io.ara.examples.basics;

import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.agent.*;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.AraRuntime;

import java.util.List;

/**
 * Live variant of {@link AraSimpleExample}: same structure, same {@code echo} tool,
 * same interceptor — but with a real LLM model via {@link OpenAiLlmClient}.
 *
 * <p>Configure {@link #BASE_URL}, {@link #API_KEY} and {@link #MODEL} before running.
 * Omit {@code BASE_URL} (set it to {@code null}) to target hosted OpenAI.
 */
public class AraSimpleExampleLive {

    private static final String BASE_URL = "http://192.168.1.114:1234/v1";  // local server for example LM Studio
    private static final String API_KEY  = resolveApiKey();
    private static final String MODEL    = "openai/gpt-oss-20b"; // local model

    private static String resolveApiKey() {
        String property = System.getProperty("ara.api.key");
        if (property != null && !property.isBlank()) {
            return property;
        }
        String env = System.getenv("ARA_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        throw new IllegalStateException(
                "Missing API key: set -Dara.api.key=... or the ARA_API_KEY environment variable");
    }

    public static void main(String[] args) {

        System.out.println("=== ARA Agent Runtime — Live Demo ===\n");

        // Create local model
        LlmClient local = OpenAiLlmClient.builder()
                .baseUrl(BASE_URL)
                .apiKey(API_KEY)
                .modelName(MODEL)
                .build();

        // Create runtime
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("local", local)
                .toolRegistry(new AraSimpleExample.StubToolRegistry())
                .interceptors(List.of(new AraSimpleExample.LoggingInterceptor()))
                .build()) {

            runtime.start();

            // Create agent config
            AgentConfig config = AgentConfig.defaults()
                    .agentType("demo-agent")
                    .systemPrompt("You are a helpful demo agent.")
                    .primaryLlm(LlmProfile.of("local"))
                    .plannerStrategy("react")
                    .enabledTools(List.of("echo"))
                    .maxIterations(5)
                    .build();

            // Create agent
            AraAgent agent = runtime.createAgent(config);
            System.out.printf("Agent created  : %s%n", agent.agentId().value());
            System.out.printf("Initial state  : %s%n%n", agent.currentState());

            // Create task
            AgentTask task = AgentTask.of("What does the echo tool say about 'Hello ARA'?");
            System.out.printf("Submitting task: %s%n%n", task.input());

            // Execute task
            AgentResponse response = agent.execute(task);

            // Print results
            System.out.println("\n=== Result ===");
            System.out.printf("Success        : %s%n", response.isSuccess());
            System.out.printf("Final state    : %s%n", response.finalState());
            System.out.printf("Content        : %s%n", response.content());
            System.out.printf("Iterations     : %d%n", response.iterationsUsed());
            System.out.printf("Tokens         : %d%n", response.totalTokens());
            System.out.printf("Estimated cost : %s %s%n",
                    response.estimatedCost().amount().toPlainString(), response.estimatedCost().currency());
            System.out.printf("Elapsed        : %dms%n", response.elapsedTime().toMillis());
            System.out.printf("Agent state    : %s (back to IDLE, ready for reuse)%n", agent.currentState());

            // Cleanup
            runtime.destroyAgent(agent);
            System.out.printf("%nRegistry count after destroy: %d%n", runtime.registry().count());
        }
    }
}
