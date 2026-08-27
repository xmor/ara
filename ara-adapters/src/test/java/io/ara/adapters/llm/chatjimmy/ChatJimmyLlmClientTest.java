package io.ara.adapters.llm.chatjimmy;

import com.fasterxml.jackson.databind.JsonNode;
import io.ara.adapters.llm.StubLlmProvider;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.ToolCallEntry;
import io.ara.core.media.MediaRef;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What {@link ChatJimmyLlmClient} puts on the wire and how it parses chatjimmy.ai's plain-text
 * response stream back into {@link LlmCompletion} — the translation logic this adapter exists
 * for, ported from the reference {@code chatjimmy-reverse-api} project's {@code chatjimmy.js}.
 */
class ChatJimmyLlmClientTest {

    private record ProbeTool(String toolId, String description, String argumentSchema) implements AraTool {
        @Override public ToolResult execute(String argumentJson) {
            throw new UnsupportedOperationException("not part of what these tests exercise");
        }
    }

    private static ChatJimmyLlmClient clientPointedAt(StubLlmProvider provider) {
        return ChatJimmyLlmClient.builder()
                .baseUrl(provider.baseUrl())
                .modelName("llama3.1-8B")
                .timeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void builder_requires_no_credential() {
        // Unlike every other adapter: chatjimmy.ai's backend takes no API key at all.
        assertNotNull(ChatJimmyLlmClient.builder().build());
    }

    @Test
    void complete_flattens_system_messages_and_sends_chat_options() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering("Hello there")) {
            clientPointedAt(provider).complete(
                    List.of(
                            LlmMessage.system("Be concise."),
                            LlmMessage.user("hi")),
                    new LlmCallContext.Builder().agentType("test").temperature(0.5).build());

            JsonNode body = provider.nextRequest();
            assertEquals("Be concise.", body.path("chatOptions").path("systemPrompt").asText());
            assertEquals("llama3.1-8B", body.path("chatOptions").path("selectedModel").asText());
            assertEquals(0.5, body.path("chatOptions").path("temperature").asDouble());
            assertTrue(body.path("attachment").isNull());

            JsonNode messages = body.path("messages");
            assertEquals(1, messages.size(), "the system message must not appear in messages[]");
            assertEquals("user", messages.get(0).path("role").asText());
            assertEquals("hi", messages.get(0).path("content").asText());
        }
    }

    @Test
    void complete_strips_think_and_stats_and_reports_usage() throws Exception {
        String upstream = "<|think|>reasoning about the answer<|/think|>"
                + "The answer is 42."
                + "<|stats|>{\"prefill_tokens\":12,\"decode_tokens\":30,\"total_tokens\":42,"
                + "\"done_reason\":\"stop\"}<|/stats|>";

        try (StubLlmProvider provider = StubLlmProvider.answering(upstream)) {
            LlmCompletion completion = clientPointedAt(provider).complete(
                    List.of(LlmMessage.user("what is the answer?")),
                    new LlmCallContext.Builder().agentType("test").build());

            assertEquals("The answer is 42.", completion.text());
            assertEquals(12, completion.promptTokens());
            assertEquals(30, completion.outputTokens());
            assertEquals("stop", completion.finishReason());
            assertFalse(completion.hasToolCall());
        }
    }

    @Test
    void complete_maps_length_stop_reason() throws Exception {
        String upstream = "truncated output"
                + "<|stats|>{\"done_reason\":\"max_tokens\"}<|/stats|>";

        try (StubLlmProvider provider = StubLlmProvider.answering(upstream)) {
            LlmCompletion completion = clientPointedAt(provider).complete(
                    List.of(LlmMessage.user("go on")),
                    new LlmCallContext.Builder().agentType("test").build());

            assertEquals("length", completion.finishReason());
        }
    }

    @Test
    void complete_parses_tool_calls_marker_and_strips_it_from_text() throws Exception {
        String upstream = "Sure, let me check that.\n"
                + "<tool_calls>[{\"name\":\"get_weather\",\"arguments\":{\"city\":\"Milan\"}}]</tool_calls>"
                + "<|stats|>{\"done_reason\":\"stop\"}<|/stats|>";

        try (StubLlmProvider provider = StubLlmProvider.answering(upstream)) {
            LlmCompletion completion = clientPointedAt(provider).complete(
                    List.of(LlmMessage.user("what's the weather in Milan?")),
                    new LlmCallContext.Builder().agentType("test").build());

            assertTrue(completion.hasToolCall());
            assertEquals("tool_calls", completion.finishReason());
            assertEquals(1, completion.toolCalls().size());

            ToolCallEntry call = completion.toolCalls().get(0);
            assertEquals("get_weather", call.toolId());
            assertTrue(call.argumentJson().contains("Milan"));

            assertFalse(completion.text().contains("<tool_calls>"),
                    "the marker must not leak into the text the agent/user sees");
            assertTrue(completion.text().startsWith("Sure, let me check that."));
        }
    }

    @Test
    void complete_injects_tool_catalogue_into_system_prompt_when_tools_resolved() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering("plain text, no call needed")) {
            LlmCallContext context = new LlmCallContext.Builder()
                    .agentType("test")
                    .resolvedTools(List.<AraTool>of(new ProbeTool("get_weather", "looks up weather",
                            """
                            {"type":"object","properties":{"city":{"type":"string"}},
                             "required":["city"]}""")))
                    .build();

            clientPointedAt(provider).complete(List.of(LlmMessage.user("hi")), context);

            JsonNode body = provider.nextRequest();
            String systemPrompt = body.path("chatOptions").path("systemPrompt").asText();
            assertTrue(systemPrompt.contains("get_weather"));
            assertTrue(systemPrompt.contains("<tool_calls>"));
        }
    }

    @Test
    void complete_flattens_tool_result_into_a_synthetic_user_message() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering("done")) {
            clientPointedAt(provider).complete(
                    List.of(
                            LlmMessage.user("what's the weather?"),
                            LlmMessage.assistantToolCall("call-1", "get_weather", "{\"city\":\"Milan\"}"),
                            LlmMessage.tool("call-1", "get_weather", "Sunny, 22C")),
                    new LlmCallContext.Builder().agentType("test").build());

            JsonNode messages = provider.nextRequest().path("messages");
            assertEquals(3, messages.size());
            assertEquals("assistant", messages.get(1).path("role").asText());
            assertTrue(messages.get(1).path("content").asText().contains("<tool_calls>"));
            assertEquals("user", messages.get(2).path("role").asText());
            assertTrue(messages.get(2).path("content").asText().contains("Sunny, 22C"));
        }
    }

    @Test
    void complete_rejects_media_attachments_before_any_request_is_sent() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering("unused")) {
            MediaRef image = MediaRef.remote(
                    java.net.URI.create("https://example.com/photo.png"), "image/png", "photo.png");

            LlmException ex = assertThrows(LlmException.class, () ->
                    clientPointedAt(provider).complete(
                            List.of(LlmMessage.user("describe this", List.of(image))),
                            new LlmCallContext.Builder().agentType("test").build()));

            assertEquals(LlmException.ErrorType.UNSUPPORTED_OPERATION, ex.errorType());
            assertFalse(ex.isRetryable());
        }
    }

    @Test
    void complete_maps_upstream_500_to_a_retryable_server_error() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.failingWith(500, "internal error")) {
            LlmException ex = assertThrows(LlmException.class, () ->
                    clientPointedAt(provider).complete(
                            List.of(LlmMessage.user("hi")),
                            new LlmCallContext.Builder().agentType("test").build()));

            assertEquals(LlmException.ErrorType.SERVER_ERROR, ex.errorType());
            assertTrue(ex.isRetryable());
        }
    }

    @Test
    void complete_maps_upstream_429_to_a_retryable_rate_limit() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.failingWith(429, "slow down")) {
            LlmException ex = assertThrows(LlmException.class, () ->
                    clientPointedAt(provider).complete(
                            List.of(LlmMessage.user("hi")),
                            new LlmCallContext.Builder().agentType("test").build()));

            assertTrue(ex.isRateLimit());
            assertTrue(ex.isRetryable());
        }
    }

    @Test
    void stream_emits_text_tokens_and_drops_think_and_stats_spans() throws Exception {
        List<String> chunks = List.of(
                "<|think|>pondering",
                "...<|/think|>Hello, ",
                "world!",
                "<|stats|>{\"done_reason\":\"stop\"}",
                "<|/stats|>");

        try (StubLlmProvider provider = StubLlmProvider.streamingText(chunks, Duration.ofMillis(20))) {
            Flow.Publisher<String> publisher = clientPointedAt(provider).stream(
                    List.of(LlmMessage.user("hi")),
                    new LlmCallContext.Builder().agentType("test").build());

            List<String> received = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(1);

            publisher.subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                @Override public void onNext(String item) { received.add(item); }
                @Override public void onError(Throwable throwable) { done.countDown(); }
                @Override public void onComplete() { done.countDown(); }
            });

            assertTrue(done.await(10, TimeUnit.SECONDS), "stream never completed");
            String all = String.join("", received);
            assertEquals("Hello, world!", all);
        }
    }
}
