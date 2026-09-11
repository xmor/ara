package io.ara.core.llm;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

/**
 * Verifies that {@code temperature}/{@code topP} stay {@code null} end-to-end
 * (LlmProfile → AgentConfig → LlmCallContext) when never explicitly set, so an
 * {@code LlmClient} adapter can tell "the caller has no opinion" apart from "the
 * caller explicitly chose this value" — and therefore knows when it is safe to leave
 * its own client-level default alone instead of silently overriding it with some
 * other layer's unrelated built-in default.
 */
class LlmCallContextTest {

    private static AgentConfig configWith(LlmProfile.Builder profile) {
        return AgentConfig.defaults()
                .agentType("t")
                .primaryLlm(profile.build())
                .build();
    }

    @Test
    void temperature_isNull_whenNeverSetAnywhere() {
        AgentConfig config = configWith(LlmProfile.builder().transportId("gpt-4o"));
        assertNull(config.temperature(), "AgentConfig.temperature() must be null, not a fabricated default");

        LlmCallContext ctx = LlmCallContext.of(config, AgentTask.of("hi"));
        assertNull(ctx.temperature(), "an adapter reading this must leave its own default temperature alone");
        assertNull(ctx.baseTemperature());
    }

    @Test
    void topP_isNull_whenNeverSetAnywhere() {
        AgentConfig config = configWith(LlmProfile.builder().transportId("gpt-4o"));
        assertNull(config.topP());

        LlmCallContext ctx = LlmCallContext.of(config, AgentTask.of("hi"));
        assertNull(ctx.topP(), "an adapter reading this must leave its own default topP alone");
    }

    @Test
    void temperature_flowsThrough_whenSetOnLlmProfile() {
        AgentConfig config = configWith(LlmProfile.builder().transportId("gpt-4o").temperature(0.9));

        LlmCallContext ctx = LlmCallContext.of(config, AgentTask.of("hi"));
        assertEquals(0.9, ctx.temperature());
        assertEquals(0.9, ctx.baseTemperature());
    }

    @Test
    void topP_flowsThrough_whenSetOnLlmProfile() {
        AgentConfig config = configWith(LlmProfile.builder().transportId("gpt-4o").topP(0.5));

        LlmCallContext ctx = LlmCallContext.of(config, AgentTask.of("hi"));
        assertEquals(0.5, ctx.topP());
    }

    @Test
    void perCallTemperatureOverride_winsOverAgentConfigValue() {
        AgentConfig config = configWith(LlmProfile.builder().transportId("gpt-4o").temperature(0.9));
        AgentTask task = AgentTask.of("hi")
                .withHints(LlmExecutionHints.forTemperature(0.1));

        LlmCallContext ctx = LlmCallContext.of(config, task);
        assertEquals(0.1, ctx.temperature(), "per-call override must win over the AgentConfig value");
        assertEquals(0.9, ctx.baseTemperature(), "baseTemperature() must still expose the AgentConfig value beneath the override");
    }

    @Test
    void perCallTemperatureOverride_appliesEvenWhenAgentConfigHasNone() {
        AgentConfig config = configWith(LlmProfile.builder().transportId("gpt-4o"));   // no temperature set
        AgentTask task = AgentTask.of("hi")
                .withHints(LlmExecutionHints.forTemperature(0.2));

        LlmCallContext ctx = LlmCallContext.of(config, task);
        assertEquals(0.2, ctx.temperature());
        assertNull(ctx.baseTemperature(), "no AgentConfig value was ever set, so the base must still be null");
    }

    @Test
    void llmProfile_rejectsOutOfRangeTemperature_butAllowsNull() {
        assertDoesNotThrow(() -> LlmProfile.builder().transportId("m").build());   // temperature left null
        assertThrows(IllegalArgumentException.class,
                () -> LlmProfile.builder().transportId("m").temperature(3.0).build());
    }

    @Test
    void withMediaResolver_sharesImmutableListsInsteadOfRecopying() {
        AgentConfig config = configWith(LlmProfile.builder().transportId("gpt-4o").temperature(0.9));
        LlmCallContext ctx = LlmCallContext.of(config, AgentTask.of("hi"))
                .withStopSequences("\n", "\n\n");

        LlmCallContext withResolver = ctx.withMediaResolver(ref -> new byte[0]);

        assertSame(withResolver.stopSequences(), ctx.stopSequences(),
                "the already-immutable stopSequences must be shared, not re-copied per call");
        assertEquals(0.9, withResolver.temperature(), "all other fields must survive the copy");
        assertEquals(ctx.maxOutputTokens(), withResolver.maxOutputTokens());
        assertTrue(withResolver.hasMediaResolver(), "the resolver must be set on the copy");
        assertFalse(ctx.hasMediaResolver(), "the source must stay untouched — with*() returns a new context");
    }

    @Test
    void withMediaResolver_replacesAnExistingResolver() {
        LlmCallContext ctx = LlmCallContext.of(
                        configWith(LlmProfile.builder().transportId("gpt-4o")), AgentTask.of("hi"))
                .withMediaResolver(ref -> new byte[0]);

        LlmCallContext replaced = ctx.withMediaResolver(ref -> new byte[] {1});

        assertTrue(replaced.hasMediaResolver());
        assertNotSame(replaced.mediaResolver(), ctx.mediaResolver());
    }

    @Test
    void withMediaResolver_nullFallsBackToNone() {
        LlmCallContext ctx = LlmCallContext.of(
                configWith(LlmProfile.builder().transportId("gpt-4o")), AgentTask.of("hi"));

        LlmCallContext withNull = ctx.withMediaResolver(null);

        assertFalse(withNull.hasMediaResolver(), "a null resolver must land on MediaResolver.none()");
    }
}
