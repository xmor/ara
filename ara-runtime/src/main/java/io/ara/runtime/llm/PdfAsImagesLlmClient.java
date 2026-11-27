package io.ara.runtime.llm;

import io.ara.runtime.media.PdfPages;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.media.MediaAdaptation;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Flow;

/**
 * Lets a client whose provider reads images but rejects a PDF take a PDF anyway, by giving the model one image per page.
 * It is a <em>declared</em> capability, not a silent fallback:
 *
 * <ul>
 *   <li>{@link #supportedMediaTypes()} includes {@code application/pdf} (only when the wrapped client reads PNG or JPEG images),
 *       and {@link #mediaAdaptations()} says what happens and its limit, so a person can read what the model will be shown;</li>
 *   <li>a PDF over {@code maxPages} is refused with the reason ({@link PdfPages}), never cut;</li>
 *   <li>each page goes as JPEG or PNG, whichever is smaller among the types the wrapped client reads: a scanned page is about ten
 *       times lighter as JPEG, which keeps a document under the request size that endpoints and proxies accept;</li>
 *   <li>a client that already reads PDFs natively should not be wrapped: its own handling is better (layout and text layer).</li>
 * </ul>
 *
 * <p>The decorator sits <em>inside</em> {@code MediaResolvingLlmClient}: it receives the context with the store's resolver,
 * reads the PDF's bytes through it, and hands the wrapped client message references to the pages together with a resolver that
 * serves them. The pages never enter the {@code MediaStore}: they are a detail of one call.
 *
 * <p>A ReAct run sends the history again at every iteration, document included; rasterizing on every call would waste CPU, so
 * the pages of a PDF are rendered once and kept (an LRU keyed by the PDF's content id — a digest of its bytes, so the same
 * document always lands on the same entry). The cache is bounded by the <em>bytes</em> it holds ({@link #DEFAULT_CACHE_BYTES}),
 * not by how many documents: a colour scan is several MB a page. A document that alone does not fit is rendered each time rather
 * than held.
 */
public final class PdfAsImagesLlmClient extends DelegatingLlmClient {

    /** The type this client adapts. */
    public static final String PDF = "application/pdf";
    private static final Set<String> PAGE_TYPES = Set.of(PdfPages.PNG, PdfPages.JPEG);
    /** What the rendered pages of all the kept documents may weigh together: 64 MB. */
    public static final long DEFAULT_CACHE_BYTES = 64L * 1024 * 1024;

    private final PdfPages.Options options;
    private final long cacheBytes;
    /** Access-ordered: the least recently used document is the first to go. Guarded by itself. */
    private final Map<String, List<PdfPages.Page>> rendered = new LinkedHashMap<>(16, 0.75f, true);
    private long renderedBytes;

    /**
     * @param options    pages, resolution, pixel ceiling and time limit of the rendering
     * @param cacheBytes how many bytes of rendered pages are kept between calls
     */
    public PdfAsImagesLlmClient(LlmClient delegate, PdfPages.Options options, long cacheBytes) {
        super(delegate);
        this.options = Objects.requireNonNull(options, "options must not be null");
        if (cacheBytes < 0) {
            throw new IllegalArgumentException("cacheBytes must not be negative: " + cacheBytes);
        }
        this.cacheBytes = cacheBytes;
    }

    /** The page types the wrapped client reads, asked each time (a delegate may change); empty means this client adapts nothing. */
    private Set<String> pageTypes() {
        Set<String> types = new LinkedHashSet<>(delegate.supportedMediaTypes());
        types.retainAll(PAGE_TYPES);
        return types;
    }

    /** What a PDF becomes, or nothing when the wrapped client reads neither page type. */
    private MediaAdaptation adaptation(Set<String> pageTypes) {
        String to = pageTypes.size() == 1 ? pageTypes.iterator().next() : "image/*";
        String format = pageTypes.size() == 1 ? "" : " (JPEG or PNG, whichever is smaller)";
        return new MediaAdaptation(PDF, to, "one image per page" + format + " at up to " + Math.round(options.dpi())
                + " DPI (no side over " + options.maxSidePx() + " px), up to " + options.maxPages() + " pages (a longer PDF is refused)");
    }

    public PdfAsImagesLlmClient(LlmClient delegate, int maxPages, float dpi) {
        this(delegate, new PdfPages.Options(maxPages, dpi, PdfPages.DEFAULT_MAX_SIDE_PX, PdfPages.DEFAULT_TIMEOUT), DEFAULT_CACHE_BYTES);
    }

    public PdfAsImagesLlmClient(LlmClient delegate, int maxPages) {
        this(delegate, PdfPages.Options.ofPages(maxPages), DEFAULT_CACHE_BYTES);
    }

    @Override
    public Set<String> supportedMediaTypes() {
        Set<String> inner = delegate.supportedMediaTypes();
        if (pageTypes().isEmpty()) {
            return inner;   // the pages could not be sent: promise nothing the wrapped client cannot carry out
        }
        Set<String> all = new LinkedHashSet<>(inner);
        all.add(PDF);
        return Set.copyOf(all);
    }

    @Override
    public List<MediaAdaptation> mediaAdaptations() {
        Set<String> pageTypes = pageTypes();
        if (pageTypes.isEmpty()) {
            return delegate.mediaAdaptations();
        }
        List<MediaAdaptation> all = new ArrayList<>(delegate.mediaAdaptations());
        all.add(adaptation(pageTypes));
        return List.copyOf(all);
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        Adapted adapted = adapt(messages, context);
        return delegate.complete(adapted.messages, adapted.context);
    }

    @Override
    public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
        Adapted adapted = adapt(messages, context);
        return delegate.stream(adapted.messages, adapted.context);
    }

    private record Adapted(List<LlmMessage> messages, LlmCallContext context) { }

    /** Replaces each PDF reference by references to its pages; everything else is left exactly as it came. */
    private Adapted adapt(List<LlmMessage> messages, LlmCallContext context) {
        // A null context is a deprecated call path that carries no media either.
        if (context == null || messages.stream().noneMatch(m -> m.media().stream().anyMatch(PdfAsImagesLlmClient::isPdf))) {
            return new Adapted(messages, context);
        }
        MediaResolver base = context.mediaResolver();
        Map<String, byte[]> pageBytes = new HashMap<>();
        List<LlmMessage> out = new ArrayList<>(messages.size());
        for (LlmMessage m : messages) {
            if (m.media().stream().noneMatch(PdfAsImagesLlmClient::isPdf)) {
                out.add(m);
                continue;
            }
            List<MediaRef> media = new ArrayList<>();
            for (MediaRef ref : m.media()) {
                if (!isPdf(ref)) {
                    media.add(ref);
                    continue;
                }
                List<PdfPages.Page> pages = pagesOf(ref, base);
                String baseName = ref.name().toLowerCase().endsWith(".pdf") ? ref.name().substring(0, ref.name().length() - 4) : ref.name();
                for (int i = 0; i < pages.size(); i++) {
                    // The page number is in the name: it is shown to the model, which can cite the page an answer came from.
                    PdfPages.Page drawn = pages.get(i);
                    String extension = PdfPages.JPEG.equals(drawn.mimeType()) ? ".jpg" : ".png";
                    MediaRef page = new MediaRef(ref.mediaId() + "#p" + (i + 1), drawn.mimeType(),
                            baseName + " - page " + (i + 1) + extension, drawn.bytes().length, null);
                    pageBytes.put(page.mediaId(), drawn.bytes());
                    media.add(page);
                }
            }
            out.add(new LlmMessage(m.role(), m.content(), m.toolCallId(), m.toolName(), media, m.reasoning()));
        }
        MediaResolver chained = ref -> {
            byte[] page = pageBytes.get(ref.mediaId());
            return page != null ? page : base.bytesOf(ref);
        };
        return new Adapted(out, context.withMediaResolver(chained));
    }

    private static boolean isPdf(MediaRef ref) {
        return PDF.equals(ref.mimeType()) && !ref.isExternal();
    }

    private List<PdfPages.Page> pagesOf(MediaRef pdf, MediaResolver resolver) {
        Set<String> types = pageTypes();
        // The key carries the page types too: pages drawn as JPEG must not reach a client that now reads PNG only.
        String key = pdf.mediaId() + "|" + new java.util.TreeSet<>(types);
        synchronized (rendered) {
            List<PdfPages.Page> cached = rendered.get(key);
            if (cached != null) {
                return cached;
            }
        }
        List<PdfPages.Page> pages;
        try {
            pages = PdfPages.toImages(resolver.bytesOf(pdf), options, types);
        } catch (IOException e) {
            // Not retryable: the same PDF fails the same way. Said plainly, with the document's name.
            throw LlmException.invalidRequest(providerId(), "'" + pdf.name() + "' cannot be given to the model as images: " + e.getMessage());
        }
        remember(key, pages);
        return pages;
    }

    /** Keeps the pages while the cache stays within its byte budget, dropping the least recently used documents first. */
    private void remember(String id, List<PdfPages.Page> pages) {
        long size = weight(pages);
        if (size > cacheBytes) {
            return;   // it alone does not fit: render it each time rather than hold more than was allowed
        }
        synchronized (rendered) {
            List<PdfPages.Page> previous = rendered.put(id, pages);
            if (previous != null) {
                renderedBytes -= weight(previous);
            }
            renderedBytes += size;
            var it = rendered.entrySet().iterator();
            while (renderedBytes > cacheBytes && it.hasNext()) {
                var eldest = it.next();
                if (eldest.getKey().equals(id)) {
                    continue;   // never drop what was just added
                }
                renderedBytes -= weight(eldest.getValue());
                it.remove();
            }
        }
    }

    private static long weight(List<PdfPages.Page> pages) {
        return pages.stream().mapToLong(p -> p.bytes().length).sum();
    }

    /** How many bytes of rendered pages are held right now (for tests and diagnostics). */
    long cachedBytes() {
        synchronized (rendered) {
            return renderedBytes;
        }
    }
}
