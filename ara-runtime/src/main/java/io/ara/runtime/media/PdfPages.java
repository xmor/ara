package io.ara.runtime.media;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.filter.MissingImageReaderException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Renders the pages of a PDF as images with Apache PDFBox: PNG, or JPEG where that is smaller.
 *
 * <h2>Why</h2>
 * Models served through vLLM, SGLang and similar endpoints read images and reject a PDF sent as a {@code file} part. One image
 * per page makes the document usable on them. The model then sees pixels, not the PDF's text layer: for a digitally generated PDF
 * extracting the text is cheaper and more accurate, so this is for scans, for documents whose layout, tables, stamps or
 * signatures matter, and for providers that take images only.
 *
 * <h2>Limits are refused, not applied</h2>
 * A PDF with more pages than {@link Options#maxPages()} is <em>refused</em> with the reason. Cutting it would let the model answer
 * about ten pages of forty without anyone knowing: the same rule supportedMediaTypes() follows: never degrade in silence. A page that cannot be
 * rendered also fails the whole document, for the same reason.
 *
 * <h2>What keeps it safe to run on a stranger's file</h2>
 * <ul>
 *   <li><b>A ceiling on pixels.</b> The resolution is a wish, the longest side in pixels is the limit: a PDF page can be metres
 *       wide, and at 150 DPI a poster would need hundreds of MB for one page. The scale is lowered so no side exceeds
 *       {@link Options#maxSidePx()}. Vision models shrink larger images anyway, so nothing they could use is lost.</li>
 *   <li><b>A time limit.</b> A pathological PDF can take very long to draw. After {@link Options#timeout()} the call fails with
 *       the reason; the page being drawn when it expires is abandoned, not forced (PDFBox cannot always be interrupted), and the
 *       work stops at the next page.</li>
 *   <li><b>The smaller format, page by page.</b> A scan is a photograph: as PNG an A4 page at 150 DPI weighs about 2 MB, as
 *       JPEG about 200 KB, and a request of several MB is refused by many endpoints and proxies before it reaches the model
 *       (HTTP 413). A page of sharp text is the opposite case, often smaller as PNG and without JPEG's blur around letters. When
 *       the caller accepts both, each page is encoded both ways and the smaller is kept ({@link #toImages}).</li>
 *   <li><b>Readable failures.</b> A password-protected PDF, an image codec this installation cannot read (JPEG 2000, JBIG2) and a
 *       file that is not a PDF each say what they are, instead of a PDFBox message.</li>
 * </ul>
 */
public final class PdfPages {

    /** 150 DPI keeps invoice and table text legible to a vision model without making the payload (and its token cost) huge. */
    public static final float DEFAULT_DPI = 150f;
    /** The longest side of a rendered page, in pixels: above what a vision model keeps of an image, and 16 MB at most per page. */
    public static final int DEFAULT_MAX_SIDE_PX = 2000;
    /** How long a document may take to render. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    /**
     * @param maxPages  the most pages accepted; a longer PDF is refused
     * @param dpi       the wished resolution
     * @param maxSidePx the longest side of any page in pixels; the resolution is lowered for a page that would exceed it
     * @param timeout   how long the whole document may take
     */
    public record Options(int maxPages, float dpi, int maxSidePx, Duration timeout) {
        public Options {
            if (maxPages <= 0) {
                throw new IllegalArgumentException("maxPages must be positive: " + maxPages);
            }
            if (dpi <= 0) {
                throw new IllegalArgumentException("dpi must be positive: " + dpi);
            }
            if (maxSidePx < 100) {
                throw new IllegalArgumentException("maxSidePx must be at least 100: " + maxSidePx);
            }
            Objects.requireNonNull(timeout, "timeout must not be null");
        }

        /** The defaults, with {@code maxPages} given. */
        public static Options ofPages(int maxPages) {
            return new Options(maxPages, DEFAULT_DPI, DEFAULT_MAX_SIDE_PX, DEFAULT_TIMEOUT);
        }
    }

    static {
        // In an application server the default ImageIO plugin scan runs under the container's class loader, which cannot see the
        // JARs shipped inside a web application (the JAI readers a scanned PDF's embedded images may need). Scanning with this
        // class's own loader registers them. Idempotent, and a no-op where the two loaders are the same.
        Thread current = Thread.currentThread();
        ClassLoader original = current.getContextClassLoader();
        try {
            current.setContextClassLoader(PdfPages.class.getClassLoader());
            ImageIO.scanForPlugins();
        } finally {
            current.setContextClassLoader(original);
        }
    }

    private PdfPages() { }

    public static final String PNG = "image/png";
    public static final String JPEG = "image/jpeg";
    /** Quality of a JPEG page: no visible loss on a scanned document, about a tenth of the PNG's size. */
    static final float JPEG_QUALITY = 0.85f;

    /** One rendered page: its encoded bytes and their type ({@link #PNG} or {@link #JPEG}). */
    public record Page(byte[] bytes, String mimeType) {
        public Page {
            Objects.requireNonNull(bytes, "bytes must not be null");
            Objects.requireNonNull(mimeType, "mimeType must not be null");
        }
    }

    /**
     * Renders every page and encodes it in the smallest of {@code formats} ({@link #PNG}, {@link #JPEG} or both).
     *
     * @return the pages in page order
     * @throws IllegalArgumentException if {@code formats} names neither PNG nor JPEG
     * @throws IOException for the same reasons as {@link #toPng(byte[], Options)}
     */
    public static List<Page> toImages(byte[] pdf, Options options, Set<String> formats) throws IOException {
        Objects.requireNonNull(formats, "formats must not be null");
        boolean png = formats.contains(PNG);
        boolean jpeg = formats.contains(JPEG);
        if (!png && !jpeg) {
            throw new IllegalArgumentException("formats must include " + PNG + " or " + JPEG + ": " + formats);
        }
        if (pdf == null || pdf.length == 0) {
            throw new IOException("the PDF is empty");
        }
        Objects.requireNonNull(options, "options must not be null");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<Page>> job = executor.submit(() -> render(pdf, options, png, jpeg));
            try {
                return job.get(options.timeout().toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                job.cancel(true);
                throw new IOException("drawing the PDF took longer than " + options.timeout().toSeconds()
                        + " seconds: it is too complex to give to the model as images");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                throw cause instanceof IOException io ? io : new IOException(explain(cause), cause);
            } catch (InterruptedException e) {
                job.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("drawing the PDF was interrupted");
            }
        }
    }

    /**
     * @return the PNG bytes of every page, in page order (see {@link #toImages} to let JPEG be chosen where it is smaller)
     * @throws IOException with a reason a person can act on, if the PDF is empty, not a PDF, protected by a password, has more pages
     *                     than {@code options.maxPages()}, uses an image format this installation cannot read, a page cannot be
     *                     rendered, or the whole takes longer than {@code options.timeout()}
     */
    public static List<byte[]> toPng(byte[] pdf, Options options) throws IOException {
        return toImages(pdf, options, Set.of(PNG)).stream().map(Page::bytes).toList();
    }

    /** {@link #toPng(byte[], Options)} with the defaults and {@code maxPages}. */
    public static List<byte[]> toPng(byte[] pdf, int maxPages) throws IOException {
        return toPng(pdf, Options.ofPages(maxPages));
    }

    /** {@link #toPng(byte[], Options)} with the defaults, {@code maxPages} and {@code dpi}. */
    public static List<byte[]> toPng(byte[] pdf, int maxPages, float dpi) throws IOException {
        return toPng(pdf, new Options(maxPages, dpi, DEFAULT_MAX_SIDE_PX, DEFAULT_TIMEOUT));
    }

    private static List<Page> render(byte[] pdf, Options options, boolean png, boolean jpeg) throws IOException {
        PDDocument doc;
        try {
            doc = Loader.loadPDF(pdf);
        } catch (IOException | RuntimeException e) {
            // Whatever PDFBox says here, the file is what it could not open: a password, or not a PDF (or a damaged one) at all.
            throw new IOException(e instanceof InvalidPasswordException ? explain(e)
                    : "the file is not a readable PDF: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
        try (doc) {
            int total = doc.getNumberOfPages();
            if (total == 0) {
                throw new IOException("the PDF has no pages");
            }
            if (total > options.maxPages()) {
                throw new IOException("the PDF has " + total + " pages and at most " + options.maxPages()
                        + " can be given to the model as images: split it or raise the limit");
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            // A scan is often a 600 DPI image drawn on a page we draw at 150: decoding it whole costs seconds and hundreds of MB for
            // pixels that are thrown away. Letting PDFBox decode a JPEG at the reduced size it will be drawn at gives the same page, in
            // a fraction of the time (about 4 to 10 times faster on a 600 DPI A4 scan); it needs no rewrite of the PDF first.
            renderer.setSubsamplingAllowed(true);
            List<Page> pages = new ArrayList<>(total);
            for (int i = 0; i < total; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("drawing the PDF was cancelled");
                }
                BufferedImage image;
                try {
                    image = renderer.renderImage(i, scaleFor(doc.getPage(i), options), ImageType.RGB);
                } catch (IOException | RuntimeException e) {
                    throw new IOException("page " + (i + 1) + ": " + explain(e), e);
                }
                Page asJpeg = jpeg ? new Page(jpeg(image, i), JPEG) : null;
                Page asPng = png ? new Page(png(image, i), PNG) : null;
                pages.add(asPng == null ? asJpeg : asJpeg == null || asPng.bytes().length <= asJpeg.bytes().length ? asPng : asJpeg);
            }
            return pages;
        }
    }

    private static byte[] png(BufferedImage image, int index) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("no PNG writer is available to render page " + (index + 1));
        }
        return out.toByteArray();
    }

    private static byte[] jpeg(BufferedImage image, int index) throws IOException {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("no JPEG writer is available to render page " + (index + 1));
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);   // the page is drawn as RGB: no alpha for JPEG to choke on
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /**
     * The scale at which to draw {@code page}: the one the wished DPI gives, lowered if the longest side would then exceed the
     * ceiling. The visible area (crop box) is what is measured, rotated as the viewer shows it.
     */
    static float scaleFor(PDPage page, Options options) {
        PDRectangle box = page.getCropBox();
        float longest = Math.max(box.getWidth(), box.getHeight());   // rotation only swaps the sides: the longest is the same
        float wished = options.dpi() / 72f;
        if (longest <= 0) {
            return wished;
        }
        return Math.min(wished, options.maxSidePx() / longest);
    }

    /** What went wrong, in words a person can act on. */
    static String explain(Throwable e) {
        if (e instanceof InvalidPasswordException) {
            return "the PDF is protected by a password: remove the protection and try again";
        }
        if (e instanceof MissingImageReaderException) {
            return "the PDF contains images in a format this installation cannot read (JPEG 2000 or JBIG2, usually a scan): "
                    + "it needs the jai-imageio or jbig2-imageio library. " + e.getMessage();
        }
        if (e instanceof OutOfMemoryError) {
            return "the PDF is too large to draw";
        }
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message;
    }
}
