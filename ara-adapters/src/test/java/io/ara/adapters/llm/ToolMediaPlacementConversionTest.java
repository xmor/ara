package io.ara.adapters.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.ToolMediaPlacement;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaResolver;
import io.ara.core.media.MediaStore;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Where the media a tool returned ends up in the provider request — the one decision of D4,
 * pinned on the messages produced rather than on a provider's reaction to them.
 *
 * <p>The provider is not involved on purpose: the failure this guards is silent. An OpenAI
 * format endpoint answers HTTP 200 to an image inside a tool message and the model never sees
 * it, so no test that "works against the provider" can tell the two forms apart; only the
 * shape of the messages can.
 */
class ToolMediaPlacementConversionTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};
    private static final String TWO_CALLS =
            "[{\"id\":\"c1\",\"name\":\"crop_region\",\"args\":\"{}\"},"
            + "{\"id\":\"c2\",\"name\":\"read_note\",\"args\":\"{}\"}]";

    private record Client(String providerId, Set<String> supportedMediaTypes, ToolMediaPlacement toolMediaPlacement)
            implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            throw new AssertionError("conversion tests must never reach the provider");
        }
    }

    private static Client following() {
        return new Client("openai-like", Set.of("image/png"), ToolMediaPlacement.FOLLOWING_USER_MESSAGE);
    }

    private static Client inline() {
        return new Client("anthropic-like", Set.of("image/png"), ToolMediaPlacement.INLINE_IN_TOOL_RESULT);
    }

    private final MediaStore store = MediaStore.inMemory();
    private final MediaRef crop = store.put("crop-1.png", "image/png", PNG);

    private LlmCallContext context() {
        return new LlmCallContext.Builder().mediaResolver(MediaResolver.backedBy(store)).build();
    }

    private List<ChatMessage> convert(Client client, List<LlmMessage> messages) {
        return ToolConversionUtils.toNativeAwareChatMessages(messages, context(), client);
    }

    private List<LlmMessage> twoParallelToolsOneWithImage(LlmMessage... after) {
        List<LlmMessage> history = new java.util.ArrayList<>(List.of(
                LlmMessage.user("crop the photo and read the note"),
                new LlmMessage("assistant_tool_calls", TWO_CALLS, null, null),
                LlmMessage.tool("c1", "crop_region", "Crop saved.", List.of(crop)),
                LlmMessage.tool("c2", "read_note", "Note: expires 2027")));
        history.addAll(List.of(after));
        return history;
    }

    /** [V10] */
    @Test
    void parallelToolsStayContiguousAndTheImagesFollowTheLastOne() {
        List<ChatMessage> out = convert(following(), twoParallelToolsOneWithImage());

        assertEquals(5, out.size());
        assertInstanceOf(UserMessage.class, out.get(0));
        assertEquals(2, assertInstanceOf(AiMessage.class, out.get(1)).toolExecutionRequests().size());
        assertEquals("c1", assertInstanceOf(ToolExecutionResultMessage.class, out.get(2)).id());
        assertEquals("c2", assertInstanceOf(ToolExecutionResultMessage.class, out.get(3)).id());
        List<Content> media = assertInstanceOf(UserMessage.class, out.get(4)).contents();
        assertEquals(ToolConversionUtils.TOOL_MEDIA_FRAME, ((TextContent) media.get(0)).text());
        assertEquals("[" + crop.describe() + "]", ((TextContent) media.get(1)).text());
        assertInstanceOf(ImageContent.class, media.get(2));
        assertEquals(3, media.size());
    }

    /** [V10] the block ends at the first message that is not a tool result, not at the list's end. */
    @Test
    void theMediaMessageComesBeforeWhateverFollowsTheBlock() {
        List<ChatMessage> out = convert(following(),
                twoParallelToolsOneWithImage(LlmMessage.assistant("The photo is in the top left.")));

        assertEquals(6, out.size());
        assertInstanceOf(UserMessage.class, out.get(4), "images right after the block");
        assertInstanceOf(AiMessage.class, out.get(5));
    }

    @Test
    void twoSuccessiveBlocksEachGetTheirOwnMediaMessage() {
        MediaRef second = store.put("crop-2.png", "image/png", PNG);
        List<ChatMessage> out = convert(following(), List.of(
                LlmMessage.user("go"),
                LlmMessage.assistantToolCall("c1", "crop_region", "{}"),
                LlmMessage.tool("c1", "crop_region", "one", List.of(crop)),
                LlmMessage.assistantToolCall("c2", "crop_region", "{}"),
                LlmMessage.tool("c2", "crop_region", "two", List.of(second))));

        assertEquals(7, out.size());
        assertTrue(assertInstanceOf(UserMessage.class, out.get(3)).contents().stream()
                .anyMatch(c -> c instanceof TextContent t && t.text().contains(crop.shortId())));
        assertTrue(assertInstanceOf(UserMessage.class, out.get(6)).contents().stream()
                .anyMatch(c -> c instanceof TextContent t && t.text().contains(second.shortId())));
    }

    /** [V11] */
    @Test
    void towardsTheFollowingFormNoToolResultEverCarriesNonTextContent() {
        for (ChatMessage m : convert(following(), twoParallelToolsOneWithImage())) {
            if (m instanceof ToolExecutionResultMessage result) {
                assertTrue(result.contents().stream().allMatch(c -> c instanceof TextContent),
                        "an image in a tool message reaches an OpenAI-format endpoint with HTTP 200 and is lost");
            }
        }
    }

    @Test
    void aToolWithoutMediaProducesTheSameMessagesAsBefore() {
        List<ChatMessage> out = convert(following(), List.of(
                LlmMessage.user("go"),
                LlmMessage.assistantToolCall("c1", "read_note", "{}"),
                LlmMessage.tool("c1", "read_note", "Note: expires 2027")));

        assertEquals(3, out.size());
        assertEquals(ToolExecutionResultMessage.from("c1", "read_note", "Note: expires 2027"), out.get(2));
    }

    /** [V12] */
    @Test
    void inlinePlacementPutsTheImageInTheToolResultAndAddsNoUserMessage() {
        List<ChatMessage> out = convert(inline(), twoParallelToolsOneWithImage());

        assertEquals(4, out.size(), "no extra user message");
        ToolExecutionResultMessage withImage = assertInstanceOf(ToolExecutionResultMessage.class, out.get(2));
        List<Content> contents = withImage.contents();
        assertEquals("c1", withImage.id());
        assertTrue(contents.stream().anyMatch(c -> c instanceof ImageContent));
        assertTrue(contents.stream().anyMatch(c -> c instanceof TextContent t
                && t.text().equals("[" + crop.describe() + "]")), "the label travels with the image");
        assertTrue(assertInstanceOf(ToolExecutionResultMessage.class, out.get(3)).hasSingleText(),
                "the sibling without media stays plain text");
    }

    /** [V13] */
    @Test
    void aToolImageTheModelCannotTakeBecomesANoteWhileAUserAttachmentStillFails() {
        Client textOnly = new Client("text-only", Set.of(), ToolMediaPlacement.FOLLOWING_USER_MESSAGE);

        List<ChatMessage> out = convert(textOnly, List.of(
                LlmMessage.user("go"),
                LlmMessage.assistantToolCall("c1", "crop_region", "{}"),
                LlmMessage.tool("c1", "crop_region", "Crop saved.", List.of(crop))));

        assertEquals(3, out.size(), "no media message when nothing can be shown");
        String text = assertInstanceOf(ToolExecutionResultMessage.class, out.get(2)).text();
        assertTrue(text.startsWith("Crop saved."), text);
        assertTrue(text.contains(crop.describe()), text);
        assertTrue(text.contains("not shown to you") && text.contains("image/png") && text.contains("text-only"), text);

        assertThrows(LlmException.class, () -> convert(textOnly, List.of(
                LlmMessage.user("look", List.of(crop)))),
                "the user's own attachment is never downgraded to a note");
    }

    @Test
    void onlyTheUnsupportedMediaOfAToolBecomesANote() {
        MediaRef scan = store.put("scan.pdf", "application/pdf", "%PDF".getBytes());

        List<ChatMessage> out = convert(following(), List.of(
                LlmMessage.assistantToolCall("c1", "crop_region", "{}"),
                LlmMessage.tool("c1", "crop_region", "done", List.of(crop, scan))));

        assertTrue(assertInstanceOf(ToolExecutionResultMessage.class, out.get(1)).text().contains(scan.describe()));
        List<Content> media = assertInstanceOf(UserMessage.class, out.get(2)).contents();
        assertEquals(3, media.size(), "frame, label, the one image");
        assertFalse(media.stream().anyMatch(c -> c instanceof TextContent t && t.text().contains("scan.pdf")));
    }
}
