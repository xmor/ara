package io.ara.adapters.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.ara.adapters.llm.anthropic.AnthropicLlmClient;
import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.ToolMediaPlacement;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaResolver;
import io.ara.core.media.MediaStore;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The bytes each adapter really sends when a tool returned an image, read off the HTTP body.
 *
 * <p>This is the check the message-level tests cannot make: that the declared placement is the
 * one the provider's serialiser then honours. Both halves matter — the OpenAI format must carry
 * the image in a user message after the tool result (an image inside the tool message is
 * accepted by the endpoint and never reaches the model), and Anthropic must carry it inside
 * the {@code tool_result} block, where its documentation puts it.
 */
class ToolMediaWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String serve(String reply) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
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

    private final MediaStore store = MediaStore.inMemory();
    private final MediaRef crop = store.put("crop-1.png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'});

    private List<LlmMessage> history() {
        return List.of(
                LlmMessage.user("crop the photo"),
                LlmMessage.assistantToolCall("call_1", "crop_region", "{}"),
                LlmMessage.tool("call_1", "crop_region", "Crop saved.", List.of(crop)));
    }

    private LlmCallContext context() {
        return new LlmCallContext.Builder().mediaResolver(MediaResolver.backedBy(store)).build();
    }

    @Test
    void openAiFormatSendsTheToolResultAsTextAndTheImageInAUserMessageAfterIt() throws Exception {
        String reply = """
                {"id":"c","object":"chat.completion","created":1,"model":"m",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""";
        OpenAiLlmClient client = OpenAiLlmClient.builder().apiKey("k").baseUrl(serve(reply) + "/v1").modelName("m")
                .timeout(Duration.ofSeconds(10)).forceHttp1(true).build();
        assertEquals(ToolMediaPlacement.FOLLOWING_USER_MESSAGE, client.toolMediaPlacement());

        client.complete(history(), context());

        JsonNode messages = JSON.readTree(body.get()).get("messages");
        assertEquals(4, messages.size(), body.get());
        JsonNode tool = messages.get(2);
        assertEquals("tool", tool.get("role").asText());
        assertTrue(tool.get("content").isTextual(), "a tool message with parts reaches the endpoint with HTTP 200 and is lost: " + tool);
        assertEquals("Crop saved.", tool.get("content").asText(), "the adapter adds nothing to the tool text");
        JsonNode user = messages.get(3);
        assertEquals("user", user.get("role").asText());
        assertTrue(user.toString().contains("data:image/png;base64,"), user.toString());
        assertTrue(user.toString().contains(crop.shortId()), "the label names the id: " + user);
    }

    @Test
    void anthropicSendsTheImageInsideTheToolResultBlock() throws Exception {
        String reply = """
                {"id":"m","type":"message","role":"assistant","model":"claude",
                 "content":[{"type":"text","text":"ok"}],
                 "stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}""";
        AnthropicLlmClient client = AnthropicLlmClient.builder().apiKey("k").baseUrl(serve(reply) + "/v1/")
                .modelName("claude").timeout(Duration.ofSeconds(10)).withoutTemperature().build();
        assertEquals(ToolMediaPlacement.INLINE_IN_TOOL_RESULT, client.toolMediaPlacement());

        client.complete(history(), context());

        JsonNode messages = JSON.readTree(body.get()).get("messages");
        JsonNode toolResult = messages.get(messages.size() - 1).get("content").get(0);
        assertEquals("tool_result", toolResult.get("type").asText(), body.get());
        assertEquals("call_1", toolResult.get("tool_use_id").asText());
        assertTrue(toolResult.get("content").toString().contains("\"type\":\"image\""), toolResult.toString());
        assertEquals(messages.size(), 3, "no user message added after the tool result: " + body.get());
    }
}
