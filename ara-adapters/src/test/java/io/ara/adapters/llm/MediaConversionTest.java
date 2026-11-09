package io.ara.adapters.llm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaResolver;
import io.ara.core.media.MediaStore;
import io.ara.core.media.MediaTypes;
import io.ara.core.media.MediaTypes.MediaKind;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the one place media becomes provider content: {@link
 * ToolConversionUtils#toNativeAwareChatMessages}.
 *
 * <p>Assertions are on the produced {@link Content} list, in order — not merely on which
 * types are present. Order is the part a caller cannot see and cannot recover: an adapter
 * that emitted the attachments before the question would still "work", and would quietly
 * change what the model was asked.
 */
class MediaConversionTest {

    private static final byte[] PDF_BYTES = "%PDF-1.4 contract".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G'};

    /** A client declaring exactly the media types the case under test needs. */
    private record FakeClient(String providerId, Set<String> supportedMediaTypes) implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            throw new AssertionError("conversion tests must never reach the provider");
        }
    }

    private static LlmClient fullyCapable() {
        return new FakeClient("fake-full",
                MediaTypes.ofKinds(MediaKind.IMAGE, MediaKind.DOCUMENT, MediaKind.TEXT));
    }

    private static LlmCallContext contextWith(MediaStore store) {
        return new LlmCallContext.Builder().mediaResolver(MediaResolver.backedBy(store)).build();
    }

    // ── The text-only path must not change ────────────────────────────────────

    @Test
    void message_without_media_produces_the_same_single_text_user_message_as_before() {
        List<ChatMessage> converted = ToolConversionUtils.toNativeAwareChatMessages(
                List.of(LlmMessage.user("just words")), contextWith(MediaStore.inMemory()), fullyCapable());

        UserMessage user = assertInstanceOf(UserMessage.class, converted.get(0));
        assertEquals(UserMessage.from("just words"), user);
        assertTrue(user.hasSingleText());
    }

    // ── Flattening order ─────────────────────────────────────────────────────

    @Test
    void text_comes_first_then_the_frame_then_media_in_list_order() {
        MediaStore store = MediaStore.inMemory();
        MediaRef pdf   = store.put("contract.pdf", "application/pdf", PDF_BYTES);
        MediaRef image = store.put("scan.png", "image/png", PNG_BYTES);

        List<Content> contents = userContents(ToolConversionUtils.toNativeAwareChatMessages(
                List.of(LlmMessage.user("check the recess clause", List.of(pdf, image))),
                contextWith(store), fullyCapable()));

        assertEquals(6, contents.size());
        assertEquals("check the recess clause", assertInstanceOf(TextContent.class, contents.get(0)).text());
        assertEquals(ToolConversionUtils.MEDIA_FRAME, assertInstanceOf(TextContent.class, contents.get(1)).text());
        // Each media sits right behind its own label, so the model can name it by id.
        assertEquals("[" + pdf.describe() + "]", assertInstanceOf(TextContent.class, contents.get(2)).text());
        assertInstanceOf(PdfFileContent.class, contents.get(3));
        assertEquals("[" + image.describe() + "]", assertInstanceOf(TextContent.class, contents.get(4)).text());
        assertInstanceOf(ImageContent.class, contents.get(5));
    }

    @Test
    void blank_text_yields_the_frame_and_the_media_only() {
        MediaStore store = MediaStore.inMemory();
        MediaRef pdf = store.put("contract.pdf", "application/pdf", PDF_BYTES);

        List<Content> contents = userContents(ToolConversionUtils.toNativeAwareChatMessages(
                List.of(LlmMessage.user("", List.of(pdf))), contextWith(store), fullyCapable()));

        assertEquals(3, contents.size(), "no user text part when the task had no words");
        assertEquals(ToolConversionUtils.MEDIA_FRAME, assertInstanceOf(TextContent.class, contents.get(0)).text());
        assertEquals("[" + pdf.describe() + "]", assertInstanceOf(TextContent.class, contents.get(1)).text());
        assertInstanceOf(PdfFileContent.class, contents.get(2));
    }

    @Test
    void the_non_authoritative_frame_appears_only_when_there_is_media() {
        UserMessage plain = assertInstanceOf(UserMessage.class, ToolConversionUtils.toNativeAwareChatMessages(
                List.of(LlmMessage.user("just words")), contextWith(MediaStore.inMemory()), fullyCapable()).get(0));

        assertFalse(plain.singleText().contains(ToolConversionUtils.MEDIA_FRAME),
                "a text-only message must carry no framing about attachments it does not have");
    }

    // ── Byte resolution ──────────────────────────────────────────────────────

    @Test
    void stored_media_is_sent_as_base64_of_the_stored_bytes() {
        MediaStore store = MediaStore.inMemory();
        MediaRef pdf = store.put("contract.pdf", "application/pdf", PDF_BYTES);

        List<Content> contents = userContents(ToolConversionUtils.toNativeAwareChatMessages(
                List.of(LlmMessage.user("read it", List.of(pdf))), contextWith(store), fullyCapable()));

        PdfFileContent sent = assertInstanceOf(PdfFileContent.class, contents.get(3));
        assertEquals(Base64.getEncoder().encodeToString(PDF_BYTES), sent.pdfFile().base64Data());
    }

    @Test
    void uri_backed_media_needs_no_store() {
        MediaRef remote = MediaRef.remote(
                URI.create("https://example.test/contract.pdf"), "application/pdf", "contract.pdf");

        List<Content> contents = userContents(ToolConversionUtils.toNativeAwareChatMessages(
                List.of(LlmMessage.user("read it", List.of(remote))),
                contextWith(MediaStore.noop()), fullyCapable()));

        PdfFileContent sent = assertInstanceOf(PdfFileContent.class, contents.get(3));
        assertEquals("https://example.test/contract.pdf", sent.pdfFile().url().toString());
    }

    @Test
    void stored_media_with_no_store_fails_naming_the_mediaId() {
        MediaRef orphan = new MediaRef("deadbeef", "application/pdf", "contract.pdf", 10, null);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ToolConversionUtils.toNativeAwareChatMessages(
                        List.of(LlmMessage.user("read it", List.of(orphan))),
                        contextWith(MediaStore.noop()), fullyCapable()));

        assertTrue(ex.getMessage().contains("deadbeef"), ex.getMessage());
        assertTrue(ex.getMessage().contains("contract.pdf"), ex.getMessage());
    }

    @Test
    void text_media_is_inlined_as_a_named_text_part() {
        MediaStore store = MediaStore.inMemory();
        MediaRef csv = store.put("rows.csv", "text/csv", "a,b\n1,2".getBytes(StandardCharsets.UTF_8));

        List<Content> contents = userContents(ToolConversionUtils.toNativeAwareChatMessages(
                List.of(LlmMessage.user("summarise", List.of(csv))), contextWith(store), fullyCapable()));

        String inlined = assertInstanceOf(TextContent.class, contents.get(2)).text();
        assertTrue(inlined.contains("rows.csv"), inlined);
        assertTrue(inlined.contains("a,b\n1,2"), inlined);
    }

    // ── Capability check, before the provider is touched ─────────────────────

    @Test
    void unsupported_media_fails_non_retryably_naming_type_and_provider() {
        MediaStore store = MediaStore.inMemory();
        MediaRef pdf = store.put("contract.pdf", "application/pdf", PDF_BYTES);
        LlmClient imagesOnly = new FakeClient("fake-images-only", MediaTypes.ofKinds(MediaKind.IMAGE));

        LlmException ex = assertThrows(LlmException.class,
                () -> ToolConversionUtils.toNativeAwareChatMessages(
                        List.of(LlmMessage.user("read it", List.of(pdf))), contextWith(store), imagesOnly));

        assertFalse(ex.isRetryable(), "a downgrade-free failure must not invite a retry or a failover");
        assertTrue(ex.getMessage().contains("application/pdf"), ex.getMessage());
        assertTrue(ex.getMessage().contains("fake-images-only"), ex.getMessage());
    }

    @Test
    void capability_is_checked_before_any_byte_is_resolved() {
        // The resolver fails the test if consulted: the check must precede everything, so a
        // client that cannot take the media never even reads it, let alone sends a request.
        MediaRef pdf = new MediaRef("digest", "application/pdf", "contract.pdf", 10, null);
        LlmCallContext ctx = new LlmCallContext.Builder()
                .mediaResolver(ref -> { throw new AssertionError("must not resolve bytes"); })
                .build();

        assertThrows(LlmException.class, () -> ToolConversionUtils.toNativeAwareChatMessages(
                List.of(LlmMessage.user("read it", List.of(pdf))), ctx,
                new FakeClient("fake-text-only", Set.of())));
    }

    @Test
    void the_single_message_entry_point_refuses_stored_media_instead_of_dropping_it() {
        MediaRef pdf = new MediaRef("digest", "application/pdf", "contract.pdf", 10, null);
        assertThrows(IllegalStateException.class, () -> ToolConversionUtils.toNativeAwareChatMessage(
                LlmMessage.user("read it", List.of(pdf))));
    }

    private static List<Content> userContents(List<ChatMessage> converted) {
        return assertInstanceOf(UserMessage.class, converted.get(0)).contents();
    }
}
