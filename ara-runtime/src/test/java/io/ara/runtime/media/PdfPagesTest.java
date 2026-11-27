package io.ara.runtime.media;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A PDF becomes one image per page (PNG, or JPEG where smaller and accepted); a PDF over the limit is refused with the reason, never cut. */
public class PdfPagesTest {

    /** A PDF of {@code pages} pages, each with a line of text, built here so no binary is checked in. */
    public static byte[] pdfWithPages(int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24f);
                    content.newLineAtOffset(72, 700);
                    content.showText("Page " + i);
                    content.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void rendersOnePngPerPage_inOrder() throws IOException {
        List<byte[]> pages = PdfPages.toPng(pdfWithPages(3), 10);

        assertEquals(3, pages.size());
        for (byte[] png : pages) {
            assertTrue(png.length > 0);
            assertEquals((byte) 0x89, png[0], "a PNG: its magic number");
            assertEquals('P', png[1]);
        }
    }

    @Test
    void aPdfOverTheLimit_isRefusedWithTheReason_notCut() throws IOException {
        byte[] twelve = pdfWithPages(12);

        IOException e = assertThrows(IOException.class, () -> PdfPages.toPng(twelve, 10));

        assertTrue(e.getMessage().contains("12 pages") && e.getMessage().contains("10"), e.getMessage());
    }

    @Test
    void aPdfExactlyAtTheLimit_isAccepted() throws IOException {
        assertEquals(5, PdfPages.toPng(pdfWithPages(5), 5).size());
    }

    @Test
    void whatIsNotAPdf_orIsEmpty_failsWithAReason() {
        assertThrows(IOException.class, () -> PdfPages.toPng(new byte[0], 10));
        assertThrows(IOException.class, () -> PdfPages.toPng("not a pdf".getBytes(), 10));
        assertThrows(IllegalArgumentException.class, () -> PdfPages.toPng(new byte[] {1}, 0));
    }

    /** A one-page PDF whose page is {@code width} x {@code height} points (72 points to an inch). */
    private static byte[] pdfOfSize(float width, float height) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage(new PDRectangle(width, height)));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private static java.awt.image.BufferedImage decode(byte[] png) throws IOException {
        return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
    }

    @Test
    void aHugePage_isDrawnSmaller_soNoSideExceedsTheCeiling() throws IOException {
        // 5000 x 5000 points at 150 DPI would be 10 417 px a side, about 430 MB: the ceiling is what keeps it from being drawn.
        List<byte[]> pages = PdfPages.toPng(pdfOfSize(5000, 5000), 3);

        var image = decode(pages.get(0));
        assertTrue(Math.max(image.getWidth(), image.getHeight()) <= PdfPages.DEFAULT_MAX_SIDE_PX,
                image.getWidth() + "x" + image.getHeight());
        assertTrue(Math.max(image.getWidth(), image.getHeight()) >= PdfPages.DEFAULT_MAX_SIDE_PX - 1, "and not smaller than it has to be");
    }

    @Test
    void anOrdinaryPage_keepsTheWishedResolution() throws IOException {
        var image = decode(PdfPages.toPng(pdfOfSize(595, 842), 3).get(0));   // A4

        assertEquals(1240, image.getWidth(), 2);
        assertEquals(1754, image.getHeight(), 2);
    }

    @Test
    void theCeilingIsConfigurable() throws IOException {
        var options = new PdfPages.Options(3, 150f, 800, PdfPages.DEFAULT_TIMEOUT);

        var image = decode(PdfPages.toPng(pdfOfSize(595, 842), options).get(0));

        assertEquals(800, Math.max(image.getWidth(), image.getHeight()), 1);
    }

    @Test
    void aPasswordProtectedPdf_saysSo() throws IOException {
        byte[] protectedPdf;
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            var policy = new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "user",
                    new org.apache.pdfbox.pdmodel.encryption.AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            protectedPdf = out.toByteArray();
        }

        IOException e = assertThrows(IOException.class, () -> PdfPages.toPng(protectedPdf, 5));

        assertTrue(e.getMessage().contains("protected by a password"), e.getMessage());
    }

    @Test
    void aFileThatIsNotAPdf_saysSo() {
        IOException e = assertThrows(IOException.class, () -> PdfPages.toPng("questo non è un pdf".getBytes(), 5));

        assertTrue(e.getMessage().contains("not a readable PDF"), e.getMessage());
    }

    @Test
    void aMissingImageCodec_namesTheLibraryToInstall() {
        String said = PdfPages.explain(new org.apache.pdfbox.filter.MissingImageReaderException("Cannot read JPEG2000 image"));

        assertTrue(said.contains("JPEG 2000") && said.contains("jai-imageio"), said);
    }

    @Test
    void whenItTakesLongerThanTheTimeLimit_itFailsWithTheReason() throws IOException {
        var options = new PdfPages.Options(5, 150f, 2000, java.time.Duration.ofNanos(1));

        IOException e = assertThrows(IOException.class, () -> PdfPages.toPng(pdfWithPages(3), options));

        assertTrue(e.getMessage().contains("took longer than"), e.getMessage());
    }

    @Test
    void invalidOptions_areRefusedWhenBuilt() {
        assertThrows(IllegalArgumentException.class, () -> new PdfPages.Options(0, 150f, 2000, PdfPages.DEFAULT_TIMEOUT));
        assertThrows(IllegalArgumentException.class, () -> new PdfPages.Options(5, 0f, 2000, PdfPages.DEFAULT_TIMEOUT));
        assertThrows(IllegalArgumentException.class, () -> new PdfPages.Options(5, 150f, 10, PdfPages.DEFAULT_TIMEOUT));
    }

    @Test
    void aHighResolutionScan_isDrawnAtTheWishedSize_withItsContentIntact() throws IOException {
        // A page holding a 600 DPI colour image (4960 x 7016 px) of a single red: drawn at 150 DPI the page is red all over, which
        // shows the image was neither lost nor mangled by decoding it at a reduced size.
        java.awt.image.BufferedImage scan = new java.awt.image.BufferedImage(4960, 7016, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var g = scan.createGraphics();
        g.setColor(java.awt.Color.RED);
        g.fillRect(0, 0, 4960, 7016);
        g.dispose();
        byte[] pdf;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var image = org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(doc, scan, 0.8f);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(image, 0, 0, 595, 842);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            pdf = out.toByteArray();
        }

        var drawn = decode(PdfPages.toPng(pdf, 1).get(0));

        assertEquals(1240, drawn.getWidth(), 2);
        int middle = drawn.getRGB(drawn.getWidth() / 2, drawn.getHeight() / 2);
        assertTrue(((middle >> 16) & 0xff) > 230 && ((middle >> 8) & 0xff) < 30 && (middle & 0xff) < 30, "red in the middle: " + Integer.toHexString(middle));
    }

    /** A PDF whose single A4 page is a photograph-like image (noise with a gradient): what a scan looks like to an encoder. */
    public static byte[] scannedPdf() throws IOException {
        java.awt.image.BufferedImage photo = new java.awt.image.BufferedImage(1240, 1754, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(7);
        for (int y = 0; y < photo.getHeight(); y++) {
            for (int x = 0; x < photo.getWidth(); x++) {
                int base = (x + y) * 200 / (photo.getWidth() + photo.getHeight());
                int v = Math.min(255, base + random.nextInt(40));
                photo.setRGB(x, y, (v << 16) | (Math.min(255, v + 20) << 8) | Math.max(0, v - 20));
            }
        }
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var image = org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(doc, photo, 0.9f);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.drawImage(image, 0, 0, 595, 842);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void aScannedPage_goesAsJpeg_whenJpegIsAccepted_andIsMuchLighter() throws IOException {
        byte[] pdf = scannedPdf();
        var both = PdfPages.toImages(pdf, PdfPages.Options.ofPages(1), java.util.Set.of(PdfPages.PNG, PdfPages.JPEG)).get(0);
        byte[] png = PdfPages.toPng(pdf, 1).get(0);

        assertEquals(PdfPages.JPEG, both.mimeType());
        assertTrue(both.bytes().length * 3 < png.length, "JPEG " + both.bytes().length + " vs PNG " + png.length);
        assertEquals(1240, javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(both.bytes())).getWidth(), 2);
    }

    @Test
    void aPageOfText_staysPng_whenPngIsSmaller() throws IOException {
        var page = PdfPages.toImages(pdfWithPages(1), PdfPages.Options.ofPages(1), java.util.Set.of(PdfPages.PNG, PdfPages.JPEG)).get(0);

        assertEquals(PdfPages.PNG, page.mimeType(), "a white page with a line of text is lighter, and sharper, as PNG");
    }

    @Test
    void onlyTheAcceptedFormatsAreUsed() throws IOException {
        var jpegOnly = PdfPages.toImages(pdfWithPages(1), PdfPages.Options.ofPages(1), java.util.Set.of(PdfPages.JPEG)).get(0);
        var pngOnly = PdfPages.toImages(scannedPdf(), PdfPages.Options.ofPages(1), java.util.Set.of(PdfPages.PNG)).get(0);

        assertEquals(PdfPages.JPEG, jpegOnly.mimeType());
        assertEquals(PdfPages.PNG, pngOnly.mimeType());
        assertThrows(IllegalArgumentException.class,
                () -> PdfPages.toImages(pdfWithPages(1), PdfPages.Options.ofPages(1), java.util.Set.of("image/webp")));
    }
}
