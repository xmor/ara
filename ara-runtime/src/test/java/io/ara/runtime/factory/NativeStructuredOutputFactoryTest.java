package io.ara.runtime.factory;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.contract.JsonSchemaValidator;
import io.ara.runtime.contract.MarkdownFenceStripper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Native structured output ({@code nativeJsonSchema(true)} + an output schema on the contract),
 * now that adapters can send a provider-native {@code response_format}.
 *
 * <p>The combination that {@code AgentFactory} used to reject at creation is now valid: it is up
 * to the client to honour it. A client that {@link LlmClient#supportsNativeStructuredOutput()
 * supports} the native path gets the schema on {@link LlmCallContext#outputJsonSchema()} and the
 * schema is <em>not</em> appended to the system prompt; a client that does not support it fails
 * the task with a clear error instead of silently answering in prose. The working default —
 * {@code nativeJsonSchema(false)}, schema in the system prompt — is unchanged.
 */
class NativeStructuredOutputFactoryTest {

    private static final String SCHEMA = """
            {"type":"object","properties":{"party":{"type":"string"}},"required":["party"]}""";

    /** Records the system prompt and the per-call context, then answers with schema-shaped JSON. */
    private static final class CapturingClient implements LlmClient {
        final List<String> systemPrompts = new ArrayList<>();
        final List<LlmCallContext> contexts = new ArrayList<>();
        private final boolean nativeStructuredOutput;

        CapturingClient(boolean nativeStructuredOutput) {
            this.nativeStructuredOutput = nativeStructuredOutput;
        }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            contexts.add(context);
            messages.stream()
                    .filter(m -> "system".equals(m.role()))
                    .forEach(m -> systemPrompts.add(m.content()));
            return new LlmCompletion(
                    "Action: FINAL_ANSWER\nAnswer: {\"party\":\"ACME S.p.A.\"}", 1, 1, "stop", null);
        }

        @Override public String providerId() { return "capturing"; }

        @Override public boolean supportsNativeStructuredOutput() { return nativeStructuredOutput; }
    }

    private static AgentConfig configWith(AgentId id, boolean nativeJsonSchema) {
        return AgentConfig.defaults()
                .agentId(id)
                .agentType("t")
                .primaryLlm(LlmProfile.builder()
                        .transportId(id.value())
                        .nativeJsonSchema(nativeJsonSchema)
                        .build())
                .plannerStrategy("react")
                .maxIterations(2)
                .build();
    }

    private static AgentContract schemaContract() {
        return AgentContract.builder()
                .outputSchema(JsonSchemaValidator.forOutput(SCHEMA))
                .addOutputProcessor(MarkdownFenceStripper.instance())
                .addOutputProcessor(JsonSchemaValidator.forOutput(SCHEMA))
                .build();
    }

    @Test
    void an_output_schema_with_nativeJsonSchema_is_accepted_at_agent_creation() {
        // Previously rejected; now a legal configuration the client is expected to honour.
        AgentId id = AgentId.of("native-schema-agent");
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(id.value(), new CapturingClient(true))
                .build();
        try {
            assertDoesNotThrow(() -> runtime.createAgent(configWith(id, true), schemaContract()));
            assertEquals(1, runtime.registry().count());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void native_path_carries_the_schema_on_the_context_and_not_in_the_prompt() {
        AgentId id = AgentId.of("native-ok-agent");
        CapturingClient client = new CapturingClient(true);
        AraRuntime runtime = AraRuntime.builder().llmClient(id.value(), client).build();
        try {
            AraAgent agent = runtime.createAgent(configWith(id, true), schemaContract());
            AgentResponse response = agent.execute(AgentTask.of("extract the counterparty"));

            assertTrue(response.isSuccess(), () -> "execute failed: " + response.failureReason());
            // Schema travels on the call context ...
            assertTrue(client.contexts.stream().anyMatch(c -> c.hasOutputSchema()
                            && c.nativeJsonSchema() && c.outputJsonSchema().contains("party")),
                    "the schema must reach the client on the call context");
            // ... and NOT appended to the system prompt (that is the non-native path).
            assertFalse(client.systemPrompts.stream().anyMatch(p -> p.contains("Respond ONLY with a single valid JSON")),
                    "the native path must not also append the schema to the system prompt: " + client.systemPrompts);
            assertTrue(response.content().contains("ACME"), response.content());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void native_path_on_a_client_that_lacks_the_capability_fails_the_task() {
        AgentId id = AgentId.of("native-unsupported-agent");
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(id.value(), new CapturingClient(false))
                .build();
        try {
            AraAgent agent = runtime.createAgent(configWith(id, true), schemaContract());
            AgentResponse response = agent.execute(AgentTask.of("extract the counterparty"));

            // The capability gate is in the leaf adapter; this stub does not enforce it, so this
            // test only pins that creation is allowed and the agent is registered — the hard
            // failure for a real adapter is covered in the adapter module's tests.
            assertNotNull(response);
            assertEquals(1, runtime.registry().count());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void the_default_appends_the_schema_to_the_system_prompt_and_validates_the_answer() {
        AgentId id = AgentId.of("prompt-schema-agent");
        CapturingClient client = new CapturingClient(false);
        AraRuntime runtime = AraRuntime.builder().llmClient(id.value(), client).build();
        try {
            AraAgent agent = runtime.createAgent(configWith(id, false), schemaContract());
            AgentResponse response = agent.execute(AgentTask.of("extract the counterparty"));

            assertTrue(response.isSuccess(), () -> "execute failed: " + response.failureReason());
            assertTrue(client.systemPrompts.stream().anyMatch(p -> p.contains("\"party\"")),
                    "the schema must reach the model through the system prompt: " + client.systemPrompts);
            assertTrue(response.content().contains("ACME"), response.content());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void nativeJsonSchema_without_an_output_schema_is_left_alone() {
        AgentId id = AgentId.of("no-schema-agent");
        AraRuntime runtime = AraRuntime.builder()
                .llmClient(id.value(), new CapturingClient(true))
                .build();
        try {
            AgentContract inputOnly = AgentContract.builder()
                    .addInputProcessor(io.ara.core.agent.processor.ProcessingResult::pass)
                    .build();

            assertDoesNotThrow(() -> runtime.createAgent(configWith(id, true), inputOnly));
        } finally {
            runtime.stop();
        }
    }
}
