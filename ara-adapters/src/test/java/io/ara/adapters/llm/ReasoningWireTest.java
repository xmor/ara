package io.ara.adapters.llm;

import com.sun.net.httpserver.HttpServer;
import io.ara.adapters.llm.anthropic.AnthropicLlmClient;
import io.ara.adapters.llm.mistral.MistralLlmClient;
import io.ara.adapters.llm.ollama.OllamaLlmClient;
import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.ReasoningEffort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real adapters against a stand-in provider on a local port: what goes on the wire when an agent
 * sets reasoning options, what is read back, and what each provider refuses.
 *
 * <p>This proves the adapter's side of the contract (the request body it writes, the response field
 * it reads) against the wire format as the provider documents it. It does not prove what a real model
 * server does with it; that needs the real servers, and is why the reasoning ADR keeps a separate
 * round of experiments.
 */
class ReasoningWireTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** Serves {@code reply} to any request and remembers the last request's path and body. */
    private String serve(String reply) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The last request body without whitespace, so assertions do not depend on how the provider SDK pretty-prints. */
    private String wire() {
        return body.get().replaceAll("\\s+", "");
    }

    private static LlmCallContext.Builder context() {
        return new LlmCallContext.Builder();
    }

    private static LlmCompletion ask(LlmClient client, LlmCallContext context) {
        return client.complete(List.of(LlmMessage.user("hi")), context);
    }

    // ── OpenAI ─────────────────────────────────────────────────────────────────────────

    private static final String OPENAI_REPLY = """
            {"id":"c","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"the answer",
                         "reasoning_content":"I considered the options."},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""";

    private OpenAiLlmClient openAi(String base) {
        return OpenAiLlmClient.builder().apiKey("k").baseUrl(base + "/v1").modelName("m")
                .timeout(Duration.ofSeconds(10)).forceHttp1(true).build();
    }

    @Test
    void openAi_sendsTheEffort_andKeepsTheRestOfTheRequest() throws Exception {
        OpenAiLlmClient client = openAi(serve(OPENAI_REPLY));

        ask(client, context().temperature(0.4).reasoningEffort(ReasoningEffort.HIGH).build());

        assertTrue(wire().contains("\"reasoning_effort\":\"high\""), body.get());
        assertTrue(wire().contains("\"temperature\":0.4"), "the generic parameters survive: " + body.get());
    }

    @Test
    void openAi_withNoOptions_sendsNoReasoningField() throws Exception {
        OpenAiLlmClient client = openAi(serve(OPENAI_REPLY));

        ask(client, context().build());

        assertFalse(body.get().contains("reasoning"), "a request without options is what it always was: " + body.get());
    }

    @Test
    void openAi_readsTheReasoningTheServerReturns() throws Exception {
        OpenAiLlmClient client = openAi(serve(OPENAI_REPLY));

        LlmCompletion completion = ask(client, context().returnReasoning(true).build());

        assertEquals("the answer", completion.text());
        assertEquals("I considered the options.", completion.reasoning());
    }

    @Test
    void openAi_rejectsABudget_withoutSendingAnything() throws Exception {
        OpenAiLlmClient client = openAi(serve(OPENAI_REPLY));

        LlmException error = assertThrows(LlmException.class,
                () -> ask(client, context().thinkingBudgetTokens(1000).build()));

        assertTrue(error.getMessage().contains("thinkingBudgetTokens"), error.getMessage());
        assertNull(body.get(), "nothing reached the provider");
        assertFalse(error.shouldFailover(), "a configuration error is not a reason to try another endpoint");
    }

    // ── Anthropic ──────────────────────────────────────────────────────────────────────

    private static final String ANTHROPIC_REPLY = """
            {"id":"m","type":"message","role":"assistant","model":"claude",
             "content":[{"type":"thinking","thinking":"Let me think it through.","signature":"sig"},
                        {"type":"text","text":"the answer"}],
             "stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}""";

    private AnthropicLlmClient anthropic(String base) {
        return AnthropicLlmClient.builder().apiKey("k").baseUrl(base + "/v1/").modelName("claude")
                .timeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void anthropic_enablesThinkingWithTheBudget_andReadsTheReasoning() throws Exception {
        AnthropicLlmClient client = anthropic(serve(ANTHROPIC_REPLY));

        LlmCompletion completion = ask(client,
                context().thinkingBudgetTokens(2048).returnReasoning(true).build());

        assertTrue(wire().contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":2048}"), body.get());
        assertEquals("the answer", completion.text());
        assertEquals("Let me think it through.", completion.reasoning());
    }

    @Test
    void anthropic_theClientsDefaultTemperatureIsSentAlongWithThinking_aKnownIncompatibility() throws Exception {
        AnthropicLlmClient client = anthropic(serve(ANTHROPIC_REPLY));

        ask(client, context().thinkingBudgetTokens(2048).build());

        // Observed on the wire: the client was built with its default temperature (0.7) and that
        // default travels with every request, thinking or not. Anthropic, to the best of my knowledge,
        // accepts only an unset or 1.0 temperature while thinking, so this request would be refused by
        // the real API. It cannot be fixed per call: a null in the request does not override the
        // client's default. A client meant for a reasoning model must be built without one. If this
        // assertion starts failing because the default no longer travels, the ADR note is obsolete.
        assertTrue(wire().contains("\"temperature\":0.7"), body.get());
    }

    @Test
    void anthropic_aClientBuiltWithoutTemperature_sendsNoneWithThinking() throws Exception {
        AnthropicLlmClient client = AnthropicLlmClient.builder()
                .apiKey("k").baseUrl(serve(ANTHROPIC_REPLY) + "/v1/").modelName("claude")
                .timeout(Duration.ofSeconds(10)).withoutTemperature().build();

        ask(client, context().thinkingBudgetTokens(2048).build());

        assertFalse(wire().contains("temperature"), body.get());
        assertTrue(wire().contains("\"thinking\""), body.get());
    }

    @Test
    void anthropic_aBudgetAlone_thinksButTheReasoningIsNotReturned() throws Exception {
        AnthropicLlmClient client = anthropic(serve(ANTHROPIC_REPLY));

        LlmCompletion completion = ask(client, context().thinkingBudgetTokens(1024).build());

        assertTrue(wire().contains("\"budget_tokens\":1024"), body.get());
        assertNull(completion.reasoning(), "not asked for, so not returned");
    }

    @Test
    void anthropic_rejectsAnEffort_andRejectsReturningWithoutABudget() throws Exception {
        AnthropicLlmClient client = anthropic(serve(ANTHROPIC_REPLY));

        LlmException effort = assertThrows(LlmException.class,
                () -> ask(client, context().reasoningEffort(ReasoningEffort.LOW).build()));
        LlmException noBudget = assertThrows(LlmException.class,
                () -> ask(client, context().returnReasoning(true).build()));

        assertTrue(effort.getMessage().contains("reasoningEffort"), effort.getMessage());
        assertTrue(noBudget.getMessage().contains("thinkingBudgetTokens"), noBudget.getMessage());
        assertNull(body.get(), "nothing reached the provider");
    }

    // ── Ollama ─────────────────────────────────────────────────────────────────────────

    private static final String OLLAMA_REPLY = """
            {"model":"m","created_at":"2026-01-01T00:00:00Z",
             "message":{"role":"assistant","content":"the answer","thinking":"Working it out."},
             "done":true,"done_reason":"stop","prompt_eval_count":1,"eval_count":1}""";

    private OllamaLlmClient ollama(String base) {
        return OllamaLlmClient.builder().baseUrl(base).modelName("m").timeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void ollama_returningTheReasoningTurnsThinkingOn_andReadsIt() throws Exception {
        OllamaLlmClient client = ollama(serve(OLLAMA_REPLY));

        LlmCompletion completion = ask(client, context().returnReasoning(true).build());

        assertTrue(wire().contains("\"think\":true"), body.get());
        assertEquals("Working it out.", completion.reasoning());
    }

    @Test
    void ollama_rejectsAnEffortAndABudget_ithasOnlyOnAndOff() throws Exception {
        OllamaLlmClient client = ollama(serve(OLLAMA_REPLY));

        assertThrows(LlmException.class, () -> ask(client, context().reasoningEffort(ReasoningEffort.HIGH).build()));
        assertThrows(LlmException.class, () -> ask(client, context().thinkingBudgetTokens(100).build()));
        assertNull(body.get());
    }

    // ── Mistral ────────────────────────────────────────────────────────────────────────

    @Test
    void mistral_rejectsAnEffortAndABudget() throws Exception {
        MistralLlmClient client = MistralLlmClient.builder().apiKey("k").baseUrl(serve("{}") + "/v1")
                .modelName("m").timeout(Duration.ofSeconds(10)).build();

        assertThrows(LlmException.class, () -> ask(client, context().reasoningEffort(ReasoningEffort.LOW).build()));
        assertThrows(LlmException.class, () -> ask(client, context().thinkingBudgetTokens(100).build()));
        assertNull(body.get(), "nothing reached the provider");
    }

    // ── the same rule on the streaming path ────────────────────────────────────────────

    @Test
    void streaming_anUnsupportedOption_failsTheStreamWithTheSameError() throws Exception {
        OpenAiLlmClient client = openAi(serve(OPENAI_REPLY));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);

        client.stream(List.of(LlmMessage.user("hi")), context().thinkingBudgetTokens(100).build())
                .subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                    @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                    @Override public void onNext(String item) { }
                    @Override public void onError(Throwable t) { failure.set(t); done.countDown(); }
                    @Override public void onComplete() { done.countDown(); }
                });

        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(failure.get() instanceof LlmException, "was: " + failure.get());
        assertTrue(failure.get().getMessage().contains("thinkingBudgetTokens"), failure.get().getMessage());
    }
}
