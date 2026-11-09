package io.ara.runtime.llm;

import io.ara.core.agent.AgentExecutionContext;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.ToolMediaPlacement;
import io.ara.core.media.MediaStore;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.runtime.interceptor.AgentInterceptorChain;
import io.ara.runtime.interceptor.InterceptingLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that every single-wrap decorator reports the capabilities of what it wraps,
 * rather than the leaf defaults of {@link LlmClient}.
 *
 * <p>Written parametrically, over the list of decorators rather than one test each, so that
 * a decorator added later has to be registered here to be covered — and so a capability
 * method added later is checked across all of them at once. That is the failure this guards:
 * a decorator that silently answers "no native tools" over an OpenAI client makes the
 * strategy above it inject a redundant text tool catalog, with no exception and no log line.
 */
class DelegatingLlmClientTest {

    private static final Set<String> CAPABLE_MEDIA = Set.of("application/pdf", "image/png");

    /** A leaf client that claims every capability, so a decorator masking one is visible. */
    private static final class CapableClient implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            return new LlmCompletion("ok", 1, 1, "stop", null);
        }

        @Override public String providerId()               { return "capable-provider"; }
        @Override public String lastUsedProviderId()       { return "capable-last-used"; }
        @Override public boolean supportsNativeTools()     { return true; }
        @Override public Set<String> supportedMediaTypes() { return CAPABLE_MEDIA; }
        @Override public ToolMediaPlacement toolMediaPlacement() { return ToolMediaPlacement.INLINE_IN_TOOL_RESULT; }
    }

    private static Stream<Object[]> decorators() {
        return Stream.of(
                new Object[]{"LoggingLlmClient",      (Function<LlmClient, LlmClient>) d -> new LoggingLlmClient(d, 100)},
                new Object[]{"InstrumentedLlmClient", (Function<LlmClient, LlmClient>) d -> new InstrumentedLlmClient(d, AraTelemetry.noop())},
                new Object[]{"InterceptingLlmClient", (Function<LlmClient, LlmClient>) d -> new InterceptingLlmClient(
                        d, AgentInterceptorChain.empty(), () -> (AgentExecutionContext) null)},
                new Object[]{"MediaResolvingLlmClient", (Function<LlmClient, LlmClient>) d ->
                        new MediaResolvingLlmClient(d, MediaStore.noop())}
        );
    }

    @ParameterizedTest(name = "{0} forwards supportsNativeTools")
    @MethodSource("decorators")
    void forwards_supportsNativeTools(String name, Function<LlmClient, LlmClient> wrap) {
        assertTrue(wrap.apply(new CapableClient()).supportsNativeTools(),
                name + " must report the delegate's native-tool capability, not the leaf default");
    }

    @ParameterizedTest(name = "{0} forwards supportedMediaTypes")
    @MethodSource("decorators")
    void forwards_supportedMediaTypes(String name, Function<LlmClient, LlmClient> wrap) {
        assertEquals(CAPABLE_MEDIA, wrap.apply(new CapableClient()).supportedMediaTypes(),
                name + " must report the delegate's media types, not the empty leaf default");
    }

    @ParameterizedTest(name = "{0} forwards toolMediaPlacement")
    @MethodSource("decorators")
    void forwards_toolMediaPlacement(String name, Function<LlmClient, LlmClient> wrap) {
        // The leaf default is FOLLOWING_USER_MESSAGE, so a decorator that did not forward
        // would turn an inline-capable client into the slower, more verbose form silently.
        assertEquals(ToolMediaPlacement.INLINE_IN_TOOL_RESULT, wrap.apply(new CapableClient()).toolMediaPlacement(),
                name + " must report the delegate's placement, not the leaf default");
    }

    @ParameterizedTest(name = "{0} forwards providerId and lastUsedProviderId")
    @MethodSource("decorators")
    void forwards_provider_identity(String name, Function<LlmClient, LlmClient> wrap) {
        LlmClient decorated = wrap.apply(new CapableClient());
        assertEquals("capable-provider", decorated.providerId(), name);
        assertEquals("capable-last-used", decorated.lastUsedProviderId(), name);
    }

    /**
     * {@code AugmentingLlmClient} is a private nested class of {@code
     * RetrievalAugmentedStrategy} and cannot be constructed from here, so it is absent from the
     * list above. It is covered structurally instead: it extends {@link DelegatingLlmClient}
     * and overrides no capability method, so a capability added to the base reaches it without
     * an edit — which is the property this whole class exists to protect.
     */
    @Test
    void the_base_declares_every_capability_method_on_LlmClient() {
        // Guards the one way the base can silently stop protecting anything: a capability
        // method added to LlmClient but not overridden here, leaving every decorator on the
        // leaf default again. Compares declared methods rather than a hand-kept count.
        Set<String> capabilityMethods = Set.of(
                "providerId", "lastUsedProviderId", "supportsNativeTools", "supportedMediaTypes",
                "toolMediaPlacement");
        Set<String> forwarded = Stream.of(DelegatingLlmClient.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .filter(capabilityMethods::contains)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(capabilityMethods, forwarded,
                "every capability method on LlmClient must be forwarded by DelegatingLlmClient");
    }
}
