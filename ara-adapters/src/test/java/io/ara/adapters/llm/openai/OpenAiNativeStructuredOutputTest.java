package io.ara.adapters.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.ara.adapters.llm.StubLlmProvider;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmException.ErrorType;
import io.ara.core.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native structured output on the OpenAI adapter: the capability is declared per endpoint (as
 * media is), the schema travels as a provider {@code response_format: json_schema} on the wire,
 * and a request for the native path against an endpoint that does not support it fails before it
 * leaves — rather than silently dropping the schema and answering in prose.
 */
class OpenAiNativeStructuredOutputTest {

    private static final String SCHEMA = """
            {"type":"object","properties":{"party":{"type":"string"}},"required":["party"],
             "additionalProperties":false}""";

    private static final String CHAT_REPLY = """
            {"id":"c","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"{\\"party\\":\\"ACME\\"}"},
                         "finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""";

    private static LlmCallContext nativeSchemaContext() {
        return new LlmCallContext.Builder()
                .nativeJsonSchema(true)
                .outputJsonSchema(SCHEMA)
                .outputSchemaName("party_out")
                .build();
    }

    // ── Capability, per endpoint ──────────────────────────────────────────────

    @Test
    void hosted_openai_claims_native_structured_output() {
        var client = OpenAiLlmClient.builder().apiKey("k").modelName("gpt-4o").build();
        assertTrue(client.supportsNativeStructuredOutput());
    }

    @Test
    void a_custom_base_url_does_not_claim_native_structured_output() {
        var client = OpenAiLlmClient.builder()
                .apiKey("k").baseUrl("https://gateway.internal/v1").modelName("m").build();
        assertFalse(client.supportsNativeStructuredOutput(),
                "an endpoint whose response_format support is unknown must be treated as unsupported");
    }

    @Test
    void structuredOutputSupport_opts_a_compatible_endpoint_back_in() {
        var client = OpenAiLlmClient.builder()
                .apiKey("k").baseUrl("https://azure.example/v1").modelName("m")
                .structuredOutputSupport(true).build();
        assertTrue(client.supportsNativeStructuredOutput());
    }

    @Test
    void structuredOutputSupport_can_refuse_even_on_hosted_openai() {
        var client = OpenAiLlmClient.builder()
                .apiKey("k").modelName("gpt-4o").structuredOutputSupport(false).build();
        assertFalse(client.supportsNativeStructuredOutput());
    }

    // ── What that changes at the call ─────────────────────────────────────────

    @Test
    void a_supported_endpoint_sends_response_format_json_schema_on_the_wire() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(CHAT_REPLY)) {
            var client = OpenAiLlmClient.builder()
                    .apiKey("k").baseUrl(provider.baseUrl()).modelName("m")
                    .structuredOutputSupport(true)
                    .timeout(Duration.ofSeconds(5)).build();

            client.complete(List.of(LlmMessage.user("extract the party")), nativeSchemaContext());

            JsonNode request = provider.nextRequest();
            JsonNode responseFormat = request.get("response_format");
            assertNotNull(responseFormat, "response_format must be on the request: " + request);
            assertEquals("json_schema", responseFormat.get("type").asText());
            JsonNode jsonSchema = responseFormat.get("json_schema");
            assertNotNull(jsonSchema, "json_schema object must be present: " + responseFormat);
            assertEquals("party_out", jsonSchema.get("name").asText());
            // The raw schema travels through: its 'party' property and 'required' survive.
            assertTrue(jsonSchema.toString().contains("party"), jsonSchema.toString());
        }
    }

    @Test
    void the_native_path_on_an_unsupported_endpoint_fails_before_the_request_leaves() throws Exception {
        // The stub fails the test if reached: the point is that nothing goes out.
        try (StubLlmProvider provider = StubLlmProvider.failingWith(500, "{\"error\":\"must not be called\"}")) {
            var client = OpenAiLlmClient.builder()
                    .apiKey("k").baseUrl(provider.baseUrl()).modelName("m")
                    // custom baseUrl + no opt-in ⇒ no native structured output
                    .timeout(Duration.ofSeconds(5)).build();

            LlmException ex = assertThrows(LlmException.class,
                    () -> client.complete(List.of(LlmMessage.user("extract the party")), nativeSchemaContext()));

            assertFalse(ex.isRetryable(), "no fallback endpoint can satisfy a capability this one lacks");
            assertEquals(ErrorType.INVALID_REQUEST, ex.errorType());
            assertTrue(ex.getMessage().contains("nativeJsonSchema") || ex.getMessage().contains("response_format"),
                    ex.getMessage());
        }
    }

    @Test
    void without_native_flag_no_response_format_is_sent_even_with_a_schema() throws Exception {        try (StubLlmProvider provider = StubLlmProvider.answering(CHAT_REPLY)) {
            var client = OpenAiLlmClient.builder()
                    .apiKey("k").baseUrl(provider.baseUrl()).modelName("m")
                    .structuredOutputSupport(true)
                    .timeout(Duration.ofSeconds(5)).build();

            // schema present but nativeJsonSchema false ⇒ non-native path, no response_format
            LlmCallContext ctx = new LlmCallContext.Builder()
                    .nativeJsonSchema(false)
                    .outputJsonSchema(SCHEMA)
                    .build();
            client.complete(List.of(LlmMessage.user("hi")), ctx);

            JsonNode request = provider.nextRequest();
            assertFalse(request.has("response_format") && !request.get("response_format").isNull(),
                    "the non-native path must not send response_format: " + request);
        }
    }

    /**
     * The combination that matters in practice: an agent that also asks for its reasoning back.
     *
     * <p>{@code returnReasoning} makes {@code AbstractLangChain4jLlmClient} take the
     * {@code withReasoningParameters} branch, which <em>rebuilds</em> the {@code ChatRequest}
     * from the generic one's messages and parameters in order to layer the provider's own
     * parameter type underneath. A {@code responseFormat} set on the first builder would be
     * silently lost there if it did not travel inside {@code parameters()} — and since a real
     * deployment (Secpacx sets {@code returnReasoning(TRUE)} on every agent) always takes this
     * branch, losing it would mean the native schema never reaching the provider precisely where
     * it is used.
     */
    @Test
    void response_format_survives_the_reasoning_parameters_rebuild() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(CHAT_REPLY)) {
            var client = OpenAiLlmClient.builder()
                    .apiKey("k").baseUrl(provider.baseUrl()).modelName("m")
                    .structuredOutputSupport(true)
                    .timeout(Duration.ofSeconds(5)).build();

            LlmCallContext ctx = new LlmCallContext.Builder()
                    .nativeJsonSchema(true)
                    .outputJsonSchema(SCHEMA)
                    .outputSchemaName("party_out")
                    .returnReasoning(Boolean.TRUE)      // ⇒ hasReasoningOptions() ⇒ request rebuilt
                    .build();
            assertTrue(ctx.hasReasoningOptions(), "the rebuild branch must actually be taken");

            client.complete(List.of(LlmMessage.user("extract the party")), ctx);

            JsonNode request = provider.nextRequest();
            JsonNode responseFormat = request.get("response_format");
            assertNotNull(responseFormat,
                    "response_format must survive the reasoning rebuild: " + request);
            assertEquals("json_schema", responseFormat.get("type").asText());
            assertEquals("party_out", responseFormat.get("json_schema").get("name").asText());
        }
    }

    // ── strict mode: guidance vs guarantee ────────────────────────────────────

    /**
     * Without the opt-in the schema travels as {@code strict: false} — OpenAI treats it as
     * instruction and does not constrain decoding, so a non-conforming answer is still possible
     * and it is the contract's own validator that catches it.
     */
    @Test
    void by_default_the_native_schema_is_not_strict() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(CHAT_REPLY)) {
            var client = OpenAiLlmClient.builder()
                    .apiKey("k").baseUrl(provider.baseUrl()).modelName("m")
                    .structuredOutputSupport(true)
                    .timeout(Duration.ofSeconds(5)).build();

            client.complete(List.of(LlmMessage.user("x")), nativeSchemaContext());

            JsonNode jsonSchema = provider.nextRequest().get("response_format").get("json_schema");
            assertFalse(jsonSchema.get("strict").asBoolean(),
                    "strict must stay off unless asked for: " + jsonSchema);
        }
    }

    /**
     * With {@code strictJsonSchema(true)} the schema is sent as {@code strict: true}, which is
     * what makes the provider guarantee conformance instead of merely aiming at it.
     */
    @Test
    void strictJsonSchema_sends_strict_true() throws Exception {
        try (StubLlmProvider provider = StubLlmProvider.answering(CHAT_REPLY)) {
            var client = OpenAiLlmClient.builder()
                    .apiKey("k").baseUrl(provider.baseUrl()).modelName("m")
                    .structuredOutputSupport(true)
                    .strictJsonSchema(true)
                    .timeout(Duration.ofSeconds(5)).build();

            client.complete(List.of(LlmMessage.user("x")), nativeSchemaContext());

            JsonNode jsonSchema = provider.nextRequest().get("response_format").get("json_schema");
            assertTrue(jsonSchema.get("strict").asBoolean(), jsonSchema.toString());
            // The schema itself still travels intact alongside the flag.
            assertTrue(jsonSchema.get("schema").toString().contains("additionalProperties"),
                    jsonSchema.toString());
        }
    }
}
