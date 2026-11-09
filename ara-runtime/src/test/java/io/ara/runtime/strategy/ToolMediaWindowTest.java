package io.ara.runtime.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ara.core.llm.LlmMessage;
import io.ara.core.media.MediaRef;
import io.ara.runtime.memory.EvictionPolicy;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.ToolCallMetadata;
import io.ara.runtime.memory.SlidingWindowMemoryManager;

import java.util.List;

import org.junit.jupiter.api.Test;

/** [V15] The media of earlier tool blocks stay out of the outgoing messages and in memory. */
class ToolMediaWindowTest {

    private static final MediaRef FIRST = new MediaRef("aaaaaaaaaaaa1111", "image/png", "first.png", 1L, null);
    private static final MediaRef SECOND = new MediaRef("bbbbbbbbbbbb2222", "image/png", "second.png", 1L, null);
    private static final MediaRef UPLOAD = new MediaRef("cccccccccccc3333", "image/png", "upload.png", 1L, null);

    private static LlmMessage tool(String id, MediaRef... media) {
        return LlmMessage.tool(id, "crop", "Crop " + id, List.of(media));
    }

    @Test
    void onlyTheLastBlockKeepsItsMedia() {
        List<LlmMessage> out = ToolMediaWindow.apply(List.of(
                LlmMessage.user("go"),
                LlmMessage.assistantToolCall("c1", "crop", "{}"), tool("c1", FIRST),
                LlmMessage.assistantToolCall("c2", "crop", "{}"), tool("c2", SECOND)));

        assertFalse(out.get(2).hasMedia());
        assertEquals("Crop c1", out.get(2).content(), "the text, with its Attached line, stays");
        assertEquals("c1", out.get(2).toolCallId());
        assertEquals(List.of(SECOND), out.get(4).media());
    }

    @Test
    void everyToolMessageOfTheLastParallelBlockKeepsItsMedia() {
        List<LlmMessage> out = ToolMediaWindow.apply(List.of(
                tool("c1", FIRST),
                LlmMessage.assistantToolCall("c2", "crop", "{}"), tool("c2", SECOND), tool("c3", UPLOAD)));

        assertFalse(out.get(0).hasMedia());
        assertTrue(out.get(2).hasMedia());
        assertTrue(out.get(3).hasMedia(), "a block is the whole run of results, not just its last message");
    }

    @Test
    void userAttachmentsAreNeverWindowed() {
        List<LlmMessage> in = List.of(
                LlmMessage.user("look", List.of(UPLOAD)),
                tool("c1", FIRST),
                LlmMessage.user("and again", List.of(UPLOAD)),
                tool("c2", SECOND));

        List<LlmMessage> out = ToolMediaWindow.apply(in);

        assertEquals(List.of(UPLOAD), out.get(0).media());
        assertEquals(List.of(UPLOAD), out.get(2).media());
        assertFalse(out.get(1).hasMedia());
    }

    @Test
    void nothingToStripReturnsTheSameList() {
        List<LlmMessage> single = List.of(LlmMessage.user("go"), tool("c1", FIRST));
        List<LlmMessage> none = List.of(LlmMessage.user("go", List.of(UPLOAD)));

        assertSame(single, ToolMediaWindow.apply(single));
        assertSame(none, ToolMediaWindow.apply(none));
    }

    @Test
    void theInputIsNotMutated() {
        List<LlmMessage> in = new java.util.ArrayList<>(List.of(tool("c1", FIRST), LlmMessage.assistant("x"), tool("c2", SECOND)));

        ToolMediaWindow.apply(in);

        assertEquals(List.of(FIRST), in.get(0).media());
    }

    private static void block(SlidingWindowMemoryManager memory, String id, MediaRef media) {
        memory.appendToWorkingMemory(MemoryEntry.of("assistant_tool_call", "{}", new ToolCallMetadata(id, "crop")));
        memory.appendToWorkingMemory(MemoryEntry.of("tool", "result " + id, new ToolCallMetadata(id, "crop"), List.of(media)));
    }

    @Test
    void theBufferWindowsTheOutgoingMessagesButMemoryKeepsEveryMedia() {
        SlidingWindowMemoryManager memory = new SlidingWindowMemoryManager(100_000, EvictionPolicy.DROP_OLDEST);
        memory.appendToWorkingMemory(MemoryEntry.of("user", "go"));
        block(memory, "c1", FIRST);
        block(memory, "c2", SECOND);
        ReactExecutionSupport.MessageBuffer buffer = new ReactExecutionSupport.MessageBuffer();

        List<LlmMessage> first = buffer.build(memory, "", "");
        // The second build takes the incremental path over a cached prefix; the window must hold there too,
        // and must move: c2 was the last block a moment ago and no longer is.
        block(memory, "c3", UPLOAD);
        List<LlmMessage> second = buffer.build(memory, "", "");

        assertFalse(first.get(2).hasMedia());
        assertTrue(first.get(4).hasMedia());
        assertFalse(second.get(2).hasMedia());
        assertFalse(second.get(4).hasMedia(), "the window moved to the new block");
        assertTrue(second.get(6).hasMedia());
        List<MediaRef> kept = memory.workingMemory().stream().flatMap(e -> e.media().stream()).toList();
        assertEquals(List.of(FIRST, SECOND, UPLOAD), kept, "memory loses nothing (D1)");
    }
}
