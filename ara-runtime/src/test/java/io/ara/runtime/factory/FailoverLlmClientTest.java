package io.ara.runtime.factory;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers {@link FailoverLlmClient#supportsNativeTools()}'s all-match semantics and the
 * before-first-token failover contract of {@link FailoverLlmClient#stream}.
 */
class FailoverLlmClientTest {

    private static LlmClient client(boolean nativeTools) {
        return new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                return new LlmCompletion("ok", 1, 1, "stop", null);
            }
            @Override public String providerId() { return nativeTools ? "native" : "non-native"; }
            @Override public boolean supportsNativeTools() { return nativeTools; }
        };
    }

    @Test
    void supportsNativeTools_trueOnlyWhenEveryCandidateSupportsIt() {
        assertTrue(new FailoverLlmClient(List.of(client(true), client(true))).supportsNativeTools());
        assertFalse(new FailoverLlmClient(List.of(client(true), client(false))).supportsNativeTools());
        assertFalse(new FailoverLlmClient(List.of(client(false))).supportsNativeTools());
    }

    // ── streaming ─────────────────────────────────────────────────────────────

    @Test
    void stream_forwardsPrimaryTokens_whenPrimarySucceeds() {
        LlmClient primary   = streamingClient("p", stream -> { stream.token("Hel"); stream.token("lo"); stream.complete(); });
        LlmClient secondary = streamingClient("s", stream -> fail("secondary must not be used"));

        Collected c = collect(new FailoverLlmClient(List.of(primary, secondary)));

        assertEquals("Hello", c.text());
        assertTrue(c.completed());
        assertNull(c.error());
    }

    @Test
    void stream_failsOverToNextClient_whenPrimaryErrorsBeforeAnyToken() {
        LlmClient primary   = streamingClient("p", stream -> stream.error(LlmException.rateLimit("p", "429")));
        LlmClient secondary = streamingClient("s", stream -> { stream.token("from-fallback"); stream.complete(); });

        FailoverLlmClient failover = new FailoverLlmClient(List.of(primary, secondary));
        Collected c = collect(failover);

        assertEquals("from-fallback", c.text());
        assertTrue(c.completed());
        assertNull(c.error());
        assertEquals("s", failover.lastUsedProviderId());
    }

    @Test
    void stream_doesNotFailOver_afterFirstTokenDelivered() {
        LlmClient primary = streamingClient("p", stream -> {
            stream.token("partial");
            stream.error(LlmException.serverError("p", "boom", 503));
        });
        LlmClient secondary = streamingClient("s", stream -> fail("must not fail over once a token was delivered"));

        Collected c = collect(new FailoverLlmClient(List.of(primary, secondary)));

        assertEquals("partial", c.text());
        assertFalse(c.completed());
        assertNotNull(c.error());
    }

    @Test
    void stream_abortsImmediately_onNonRetryableError() {
        LlmClient primary   = streamingClient("p", stream -> stream.error(LlmException.authenticationError("p", "401")));
        LlmClient secondary = streamingClient("s", stream -> fail("non-retryable error must abort failover"));

        Collected c = collect(new FailoverLlmClient(List.of(primary, secondary)));

        assertEquals("", c.text());
        assertFalse(c.completed());
        assertInstanceOf(LlmException.class, c.error());
    }

    @Test
    void stream_propagatesError_whenEveryCandidateFailsBeforeTokens() {
        LlmClient primary   = streamingClient("p", stream -> stream.error(LlmException.networkError("p", "down", null)));
        LlmClient secondary = streamingClient("s", stream -> stream.error(LlmException.networkError("s", "down", null)));

        Collected c = collect(new FailoverLlmClient(List.of(primary, secondary)));

        assertFalse(c.completed());
        assertNotNull(c.error());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Drives a scripted, synchronous token stream toward a subscriber. */
    private interface StreamScript { void run(Emitter emitter); }

    private interface Emitter {
        void token(String t);
        void complete();
        void error(Throwable t);
    }

    private static LlmClient streamingClient(String id, StreamScript script) {
        return new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                return new LlmCompletion("blocking-" + id, 1, 1, "stop", null);
            }
            @Override public String providerId() { return id; }
            @Override public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
                return subscriber -> {
                    AtomicBoolean done = new AtomicBoolean(false);
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override public void request(long n) { }
                        @Override public void cancel() { done.set(true); }
                    });
                    Emitter emitter = new Emitter() {
                        @Override public void token(String t) { if (!done.get()) subscriber.onNext(t); }
                        @Override public void complete() { if (done.compareAndSet(false, true)) subscriber.onComplete(); }
                        @Override public void error(Throwable t) { if (done.compareAndSet(false, true)) subscriber.onError(t); }
                    };
                    script.run(emitter);
                };
            }
        };
    }

    private record Collected(String text, boolean completed, Throwable error) { }

    private static Collected collect(LlmClient client) {
        StringBuilder buf = new StringBuilder();
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Throwable> error = new AtomicReference<>();

        client.stream(List.of(LlmMessage.user("hi")), (LlmCallContext) null).subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(String item) { buf.append(item); }
            @Override public void onError(Throwable t) { error.set(t); }
            @Override public void onComplete() { completed.set(true); }
        });

        return new Collected(buf.toString(), completed.get(), error.get());
    }
}
