package io.ara.core.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the ADR-039 §3 split: {@link LlmProfile} carries only a {@code transportId}
 * reference (asse A) plus parameters (asse B) and governance (asse C); the actual
 * {@code baseUrl}/{@code apiKey}/{@code modelName} live in {@link LlmTransport}, either
 * referenced by id or assembled inline as sugar by the builder.
 */
class LlmProfileTest {

    @Test
    void of_setsTransportIdAndNoInlineTransport() {
        LlmProfile profile = LlmProfile.of("gpt-4o");
        assertEquals("gpt-4o", profile.transportId());
        assertNull(profile.inlineTransport());
    }

    @Test
    void deprecatedModelIdAccessor_returnsSameValueAsTransportId() {
        LlmProfile profile = LlmProfile.of("gpt-4o");
        assertEquals(profile.transportId(), profile.modelId());
    }

    @Test
    void deprecatedModelIdBuilderSetter_setsTransportId() {
        LlmProfile profile = LlmProfile.builder().modelId("legacy-name").build();
        assertEquals("legacy-name", profile.transportId());
    }

    @Test
    void builder_baseUrlAndModelName_assembleAnInlineTransport() {
        LlmProfile profile = LlmProfile.builder()
                .baseUrl("http://localhost:1234")
                .modelName("local-model")
                .apiKey("secret")
                .build();

        assertEquals(new LlmTransport("http://localhost:1234", "secret", "local-model"), profile.inlineTransport());
    }

    @Test
    void builder_withoutBaseUrlOrModelName_hasNoInlineTransport() {
        LlmProfile profile = LlmProfile.builder().modelId("gpt-4o").build();
        assertNull(profile.inlineTransport());
    }

    @Test
    void builder_onlyBaseUrlWithoutModelName_hasNoInlineTransport() {
        LlmProfile profile = LlmProfile.builder().baseUrl("http://h").build();
        assertNull(profile.inlineTransport(), "an inline transport requires both baseUrl and modelName");
    }

    @Test
    void temperatureAndStreamingFlags_remainFlatProfileParameters() {
        LlmProfile profile = LlmProfile.builder()
                .modelId("gpt-4o")
                .temperature(0.7)
                .streamingEnabled(true)
                .nativeJsonSchema(true)
                .build();

        assertEquals(0.7, profile.temperature());
        assertEquals(true, profile.streamingEnabled());
        assertEquals(true, profile.nativeJsonSchema());
    }

    @Test
    void nullTransportId_throws() {
        assertThrows(NullPointerException.class, () -> new LlmProfile(
                null, null, null, null, null, io.ara.core.common.Budget.unlimited(), "EUR",
                false, false, io.ara.core.common.Money.zero("EUR"), io.ara.core.common.Money.zero("EUR"), null));
    }
}
