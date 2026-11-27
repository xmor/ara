package io.ara.runtime.llm;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.media.MediaAdaptation;
import io.ara.runtime.factory.FailoverLlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The conversions a client declares are capabilities like the media types: forwarded by decorators, intersected by pools. */
class MediaAdaptationsTest {

    private static final MediaAdaptation PDF_PAGES = new MediaAdaptation("application/pdf", "image/png", "one image per page");

    private static LlmClient client(List<MediaAdaptation> adaptations) {
        return new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> m, LlmCallContext c) { throw new UnsupportedOperationException(); }
            @Override public Flow.Publisher<String> stream(List<LlmMessage> m, LlmCallContext c) { throw new UnsupportedOperationException(); }
            @Override public String providerId() { return "t"; }
            @Override public Set<String> supportedMediaTypes() { return Set.of("image/png", "application/pdf"); }
            @Override public List<MediaAdaptation> mediaAdaptations() { return adaptations; }
        };
    }

    @Test
    void aClientAdaptsNothingUnlessItSaysSo() {
        LlmClient plain = new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> m, LlmCallContext c) { throw new UnsupportedOperationException(); }
            @Override public Flow.Publisher<String> stream(List<LlmMessage> m, LlmCallContext c) { throw new UnsupportedOperationException(); }
            @Override public String providerId() { return "plain"; }
        };
        assertEquals(List.of(), plain.mediaAdaptations());
    }

    @Test
    void aDecoratorForwardsThem() {
        LlmClient wrapped = new DelegatingLlmClient(client(List.of(PDF_PAGES))) { };
        assertEquals(List.of(PDF_PAGES), wrapped.mediaAdaptations(), "a decorator must not hide what it wraps");
    }

    @Test
    void aPoolPromisesOnlyWhatEveryCandidateDoes() {
        LlmClient adapting = client(List.of(PDF_PAGES));
        LlmClient plain = client(List.of());

        assertEquals(List.of(PDF_PAGES), new FailoverLlmClient(List.of(adapting, client(List.of(PDF_PAGES)))).mediaAdaptations());
        assertTrue(new FailoverLlmClient(List.of(adapting, plain)).mediaAdaptations().isEmpty(),
                "one candidate that cannot convert a PDF makes the pool unable to promise it");
    }
}
