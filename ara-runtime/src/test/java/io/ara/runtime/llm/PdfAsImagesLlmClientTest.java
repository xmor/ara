package io.ara.runtime.llm;

import io.ara.core.agent.AgentConfig;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaResolver;
import io.ara.core.media.MediaStore;
import io.ara.runtime.media.PdfPages;
import io.ara.runtime.media.PdfPagesTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A client that reads images but not PDFs takes a PDF as one image per page, declared in its capabilities,
 * refused when too long, rendered once.
 */
class PdfAsImagesLlmClientTest {

    /** A provider that reads PNG only, and records what it was sent and how it could resolve the bytes. */
    private static final class ImageOnly implements LlmClient {
        final List<List<LlmMessage>> sent = new ArrayList<>();
        final List<LlmCallContext> contexts = new ArrayList<>();
        @Override public LlmCompletion complete(List<LlmMessage> m, LlmCallContext c) {
            sent.add(m);
            contexts.add(c);
            return new LlmCompletion("ok", 1, 1, "stop", null);
        }
        @Override public Flow.Publisher<String> stream(List<LlmMessage> m, LlmCallContext c) { throw new UnsupportedOperationException(); }
        @Override public String providerId() { return "image-only"; }
        @Override public Set<String> supportedMediaTypes() { return Set.of("image/png"); }
    }

    private final MediaStore store = MediaStore.inMemory();

    private MediaRef storedPdf(String name, int pages) throws IOException {
        return store.put(name, "application/pdf", PdfPagesTest.pdfWithPages(pages));
    }

    private LlmCallContext context(AtomicInteger reads) {
        MediaResolver counting = ref -> {
            reads.incrementAndGet();
            return store.get(ref.mediaId()).orElseThrow();
        };
        return LlmCallContext.from(AgentConfig.defaults().agentId(AgentId.of("a")).agentType("t").build())
                .withMediaResolver(counting);
    }

    @Test
    void thePdfIsDeclaredAsSupported_andItsConversionIsDescribed() {
        var client = new PdfAsImagesLlmClient(new ImageOnly(), 10);

        assertTrue(client.supportedMediaTypes().contains("application/pdf"));
        assertTrue(client.supportedMediaTypes().contains("image/png"));
        assertEquals(1, client.mediaAdaptations().size());
        assertTrue(client.mediaAdaptations().get(0).description().contains("10 pages"), client.mediaAdaptations().get(0).description());
    }

    @Test
    void aClientThatCannotTakeImages_promisesNothing() {
        LlmClient textOnly = new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> m, LlmCallContext c) { return new LlmCompletion("x", 1, 1, "stop", null); }
            @Override public Flow.Publisher<String> stream(List<LlmMessage> m, LlmCallContext c) { throw new UnsupportedOperationException(); }
            @Override public String providerId() { return "text"; }
        };
        var client = new PdfAsImagesLlmClient(textOnly, 10);

        assertFalse(client.supportedMediaTypes().contains("application/pdf"), "the pages could not be sent");
        assertTrue(client.mediaAdaptations().isEmpty());
    }

    @Test
    void thePdfReachesTheProviderAsOneImagePerPage_withTheirBytesResolvable() throws Exception {
        ImageOnly provider = new ImageOnly();
        var client = new PdfAsImagesLlmClient(provider, 10);
        MediaRef pdf = storedPdf("contratto.pdf", 3);

        client.complete(List.of(LlmMessage.user("leggi", List.of(pdf))), context(new AtomicInteger()));

        List<MediaRef> media = provider.sent.get(0).get(0).media();
        assertEquals(3, media.size());
        assertEquals("contratto - page 1.png", media.get(0).name(), "the page number is in the name, so the model can cite it");
        assertEquals("image/png", media.get(2).mimeType());
        byte[] page = provider.contexts.get(0).mediaResolver().bytesOf(media.get(1));
        assertEquals((byte) 0x89, page[0], "the provider's resolver serves the rendered page");
    }

    @Test
    void anythingElseIsLeftExactlyAsItCame() throws Exception {
        ImageOnly provider = new ImageOnly();
        var client = new PdfAsImagesLlmClient(provider, 10);
        MediaRef png = store.put("foto.png", "image/png", new byte[] {1, 2, 3});
        List<LlmMessage> messages = List.of(LlmMessage.system("s"), LlmMessage.user("guarda", List.of(png)));
        LlmCallContext ctx = context(new AtomicInteger());

        client.complete(messages, ctx);

        assertSame(messages, provider.sent.get(0), "no PDF: the very same messages");
        assertSame(ctx, provider.contexts.get(0), "and the very same context");
    }

    @Test
    void aPdfOverTheLimit_isRefusedNotCut_withTheDocumentsName() throws Exception {
        var client = new PdfAsImagesLlmClient(new ImageOnly(), 4);
        MediaRef pdf = storedPdf("lungo.pdf", 6);

        LlmException e = assertThrows(LlmException.class,
                () -> client.complete(List.of(LlmMessage.user("x", List.of(pdf))), context(new AtomicInteger())));

        assertTrue(e.getMessage().contains("lungo.pdf") && e.getMessage().contains("6 pages"), e.getMessage());
        assertFalse(e.isRetryable(), "the same PDF fails the same way");
    }

    @Test
    void theSameDocumentIsRenderedOnce_evenWhenTheHistoryIsSentAgain() throws Exception {
        ImageOnly provider = new ImageOnly();
        var client = new PdfAsImagesLlmClient(provider, 10);
        MediaRef pdf = storedPdf("doc.pdf", 2);
        AtomicInteger reads = new AtomicInteger();
        List<LlmMessage> history = List.of(LlmMessage.user("leggi", List.of(pdf)));

        client.complete(history, context(reads));
        client.complete(history, context(reads));
        client.complete(history, context(reads));

        assertEquals(1, reads.get(), "the PDF's bytes are read, and its pages rendered, once");
        assertEquals(provider.sent.get(0).get(0).media(), provider.sent.get(2).get(0).media(), "stable page ids");
    }

    @Test
    void theCacheIsBoundedByBytes_notByHowManyDocuments() throws Exception {
        MediaRef a = storedPdf("a.pdf", 2);
        MediaRef b = store.put("b.pdf", "application/pdf", PdfPagesTest.pdfWithPages(3));
        // How much the larger document weighs once drawn: the budget below holds it, but not the smaller one beside it.
        var probe = new PdfAsImagesLlmClient(new ImageOnly(), PdfPages.Options.ofPages(10), Long.MAX_VALUE);
        probe.complete(List.of(LlmMessage.user("x", List.of(b))), context(new AtomicInteger()));
        long sizeOfB = probe.cachedBytes();
        assertTrue(sizeOfB > 0);

        var client = new PdfAsImagesLlmClient(new ImageOnly(), PdfPages.Options.ofPages(10), sizeOfB + 1);
        AtomicInteger reads = new AtomicInteger();
        client.complete(List.of(LlmMessage.user("x", List.of(a))), context(reads));
        assertTrue(client.cachedBytes() > 0 && client.cachedBytes() < sizeOfB, "a is held, within the budget");
        client.complete(List.of(LlmMessage.user("x", List.of(b))), context(reads));
        assertEquals(sizeOfB, client.cachedBytes(), "b came in and a made room: only b is held");
        client.complete(List.of(LlmMessage.user("x", List.of(a))), context(reads));

        assertEquals(3, reads.get(), "a was pushed out by b, so it was read and drawn again");
    }

    @Test
    void aDocumentThatAloneDoesNotFit_isDrawnEachTime_andNeverHeld() throws Exception {
        var client = new PdfAsImagesLlmClient(new ImageOnly(), PdfPages.Options.ofPages(10), 10);   // 10 bytes: nothing fits
        MediaRef pdf = storedPdf("doc.pdf", 2);
        AtomicInteger reads = new AtomicInteger();
        List<LlmMessage> history = List.of(LlmMessage.user("x", List.of(pdf)));

        client.complete(history, context(reads));
        client.complete(history, context(reads));

        assertEquals(2, reads.get());
        assertEquals(0, client.cachedBytes());
    }

    /** A provider that reads PNG and JPEG, as the vision models served by vLLM and similar endpoints do. */
    private static final class PhotoReader implements LlmClient {
        final List<List<LlmMessage>> sent = new ArrayList<>();
        @Override public LlmCompletion complete(List<LlmMessage> m, LlmCallContext c) {
            sent.add(m);
            return new LlmCompletion("ok", 1, 1, "stop", null);
        }
        @Override public Flow.Publisher<String> stream(List<LlmMessage> m, LlmCallContext c) { throw new UnsupportedOperationException(); }
        @Override public String providerId() { return "photo"; }
        @Override public Set<String> supportedMediaTypes() { return Set.of("image/png", "image/jpeg"); }
    }

    @Test
    void aScannedPdf_reachesAModelThatReadsJpeg_asJpegPages() throws Exception {
        var inner = new PhotoReader();
        var client = new PdfAsImagesLlmClient(inner, 10);
        MediaRef scan = store.put("carta.pdf", "application/pdf", PdfPagesTest.scannedPdf());

        client.complete(List.of(LlmMessage.user("x", List.of(scan))), context(new AtomicInteger()));

        MediaRef page = inner.sent.get(0).get(0).media().get(0);
        assertEquals("image/jpeg", page.mimeType());
        assertEquals("carta - page 1.jpg", page.name());
        assertTrue(client.mediaAdaptations().get(0).description().contains("JPEG or PNG"), client.mediaAdaptations().get(0).description());
        assertEquals("image/*", client.mediaAdaptations().get(0).to());
    }

    @Test
    void aModelThatReadsPngOnly_getsPngPages_evenForAScan() throws Exception {
        var inner = new ImageOnly();
        var client = new PdfAsImagesLlmClient(inner, 10);
        MediaRef scan = store.put("carta.pdf", "application/pdf", PdfPagesTest.scannedPdf());

        client.complete(List.of(LlmMessage.user("x", List.of(scan))), context(new AtomicInteger()));

        assertEquals("image/png", inner.sent.get(0).get(0).media().get(0).mimeType());
        assertEquals("image/png", client.mediaAdaptations().get(0).to());
    }
}
