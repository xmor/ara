package io.ara.runtime.strategy;

import io.ara.core.llm.LlmMessage;
import io.ara.core.memory.ToolCallMetadata;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class MessageBufferTest {

    private static final String CATALOG = "CATALOG";
    private static final String SUFFIX  = "SUFFIX";

    private final InMemoryMemoryManager memory = new InMemoryMemoryManager();
    private final ReactExecutionSupport.MessageBuffer buffer = new ReactExecutionSupport.MessageBuffer();

    @Test
    void reusesTheMaterialisedPrefixAndAppendsOnlyNewEntries() {
        memory.appendToWorkingMemory("system", "agent");

        List<LlmMessage> first = buffer.build(memory, CATALOG, SUFFIX);
        assertEquals(1, first.size());
        assertEquals("agent" + CATALOG + SUFFIX, first.get(0).content(),
                "the first system message must carry catalog + suffix");

        memory.appendToWorkingMemory("assistant", "thought one");
        memory.appendToWorkingMemory("tool", "result one");

        List<LlmMessage> second = buffer.build(memory, CATALOG, SUFFIX);
        assertEquals(3, second.size());
        assertSame(first.get(0), second.get(0),
                "the materialised prefix must be reused, not rebuilt, when only the tail grows");
        assertEquals("thought one", second.get(1).content());
        assertEquals("result one", second.get(2).content());
        assertEquals(first.get(0), buffer.build(memory, CATALOG, SUFFIX).get(0),
                "repeated reuse must keep returning the same enhanced first message");
    }

    @Test
    void catalogVariantSwitchToForcedFinalRebuildsTheFirstMessage() {
        memory.appendToWorkingMemory("system", "agent");

        List<LlmMessage> normal = buffer.build(memory, CATALOG, SUFFIX);
        List<LlmMessage> forced = buffer.build(memory, "", SUFFIX);

        assertEquals(1, forced.size());
        assertNotSame(normal.get(0), forced.get(0),
                "switching catalog variant must rebuild, the old first message is now wrong");
        assertEquals("agent", forced.get(0).content(),
                "the forced-final variant drops both catalog and suffix");

        assertSame(forced.get(0), buffer.build(memory, "", SUFFIX).get(0),
                "once on the forced-final variant, reuse resumes");
    }

    @Test
    void clearedOrEvictedMemoryTriggersAFullRebuild() {
        memory.appendToWorkingMemory("system", "agent");
        memory.appendToWorkingMemory("assistant", "old");

        List<LlmMessage> before = buffer.build(memory, CATALOG, SUFFIX);

        memory.clearWorkingMemory();
        memory.appendToWorkingMemory("system", "different agent");
        memory.appendToWorkingMemory("assistant", "new");

        List<LlmMessage> after = buffer.build(memory, CATALOG, SUFFIX);
        assertEquals(2, after.size());
        assertNotSame(before.get(0), after.get(0),
                "a replaced prefix must not be served from the stale cache");
        assertEquals("different agent" + CATALOG + SUFFIX, after.get(0).content());
        assertNotEquals("new", before.get(1).content(), "the cached list must still be the old one");
    }

    @Test
    void tailSystemEntryFromSynthesisIsNotEnhanced() {
        memory.appendToWorkingMemory("system", "agent");

        buffer.build(memory, CATALOG, SUFFIX);
        memory.appendToWorkingMemory("system", "wrap up now");

        List<LlmMessage> messages = buffer.build(memory, CATALOG, SUFFIX);
        assertEquals(2, messages.size());
        assertEquals("wrap up now", messages.get(1).content(),
                "only the very first system message is enhanced — a tail nudge stays plain");
    }

    @Test
    void suffixIsSilentlyDroppedWhenCatalogIsEmpty() {
        memory.appendToWorkingMemory("system", "agent");
        List<LlmMessage> messages = buffer.build(memory, "", SUFFIX);

        assertEquals("agent", messages.get(0).content(),
                "the suffix must not be appended when there is no catalog to go with it");
    }

    @Test
    void enhancementLandsOnTheFirstSystemEntryEvenWhenItIsNotTheFirstInTheWindow() {
        memory.appendToWorkingMemory("user", "recalled episode");
        memory.appendToWorkingMemory("system", "agent");
        memory.appendToWorkingMemory("user", "history");

        List<LlmMessage> messages = buffer.build(memory, CATALOG, SUFFIX);

        assertEquals(3, messages.size());
        assertEquals("recalled episode", messages.get(0).content(),
                "a non-system head must not be enhanced");
        assertEquals("agent" + CATALOG + SUFFIX, messages.get(1).content(),
                "the catalog lands on the first system entry wherever it sits");
        assertEquals("history", messages.get(2).content());
    }

    @Test
    void toolCallMetadataRidesAlongOnTheAppendedTail() {
        memory.appendToWorkingMemory("system", "agent");
        buffer.build(memory, CATALOG, SUFFIX);

        memory.appendToWorkingMemory("assistant_tool_call", "{\"x\":1}",
                new ToolCallMetadata("call-1", "noop"));

        List<LlmMessage> messages = buffer.build(memory, CATALOG, SUFFIX);
        assertEquals(2, messages.size());
        assertEquals("assistant_tool_call", messages.get(1).role());
        assertEquals("call-1", messages.get(1).toolCallId());
        assertEquals("noop", messages.get(1).toolName());
        assertEquals("{\"x\":1}", messages.get(1).content());
    }
}