package io.ara.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ara.core.media.MediaRef;
import io.ara.core.memory.EpisodeLabel;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.ToolCallMetadata;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Pins the one pair of conversions between a {@link MemoryEntry} and an {@link LlmMessage}.
 *
 * <p>The point of this class is the second test, not the first: the round trip proves today's
 * fields survive, and the reflective check makes the next field added to {@code MemoryEntry}
 * fail here until somebody decides how the conversions treat it. Without it, a new component
 * would be dropped by omission, which is how the media of tool-call entries were lost.
 */
class LlmMessageMemoryConversionTest {

    private static final MediaRef CROP = new MediaRef("3f9a1c2e7b4d", "image/png", "crop-1.png", 12L, null);

    /** An entry with every component filled, tool-call shaped because that is the case that broke. */
    private static MemoryEntry fullEntry() {
        return new MemoryEntry("tool", "Crop saved.", Instant.parse("2026-11-09T10:00:00Z"),
                new ToolCallMetadata("call-1", "crop_region"), List.of(CROP));
    }

    @Test
    void anEntryWithEveryFieldSurvivesTheRoundTripExceptTheTimestamp() {
        MemoryEntry original = fullEntry();

        MemoryEntry restored = LlmMessage.of(original).toMemoryEntry();

        assertEquals(original.role(), restored.role());
        assertEquals(original.content(), restored.content());
        assertEquals(original.metadata(), restored.metadata());
        assertEquals(original.media(), restored.media(),
                "the media of a tool-call entry is the field the hand-written copies used to drop");
    }

    /**
     * The components the conversions carry, plus the ones they deliberately do not. A component
     * of {@code MemoryEntry} that is in neither set fails this test: decide what the conversion
     * does with it, make {@code LlmMessage.of} / {@code toMemoryEntry} do that, extend
     * {@link #anEntryWithEveryFieldSurvivesTheRoundTripExceptTheTimestamp}, then add the name here.
     */
    @Test
    void everyComponentOfMemoryEntryIsEitherCarriedOrNamedAsNotCarried() {
        Set<String> carried = Set.of("role", "content", "metadata", "media");
        Set<String> notCarried = Set.of("timestamp");

        Set<String> actual = new TreeSet<>();
        for (RecordComponent component : MemoryEntry.class.getRecordComponents()) {
            actual.add(component.getName());
        }

        Set<String> known = new TreeSet<>(carried);
        known.addAll(notCarried);
        assertEquals(known, actual,
                "MemoryEntry changed shape: " + Arrays.toString(actual.toArray())
                        + " — see this test's javadoc before adding a name to either set");
    }

    @Test
    void aToolCallEntryBecomesAMessageWithItsCallIdAndToolName() {
        LlmMessage message = LlmMessage.of(fullEntry());

        assertEquals("tool", message.role());
        assertEquals("call-1", message.toolCallId());
        assertEquals("crop_region", message.toolName());
        assertEquals(List.of(CROP), message.media());
    }

    @Test
    void anEntryThatIsNotAToolCallKeepsItsMediaAndHasNoCallData() {
        MemoryEntry entry = MemoryEntry.of("user", "look at this", List.of(CROP));

        LlmMessage message = LlmMessage.of(entry);

        assertNull(message.toolCallId());
        assertNull(message.toolName());
        assertEquals(List.of(CROP), message.media());
    }

    @Test
    void toolCallMetadataOnAnEntryOfAnotherRoleIsNotTurnedIntoCallData() {
        // The metadata only means something on the two roles a provider pairs; carrying it on
        // an "assistant" message would send a call id with no call behind it.
        MemoryEntry entry = MemoryEntry.of("assistant", "text", new ToolCallMetadata("call-1", "t"));

        LlmMessage message = LlmMessage.of(entry);

        assertNull(message.toolCallId());
        assertNull(message.toolName());
    }

    @Test
    void anEpisodeLabelIsNotCarriedToAMessage() {
        // Named in the javadoc of LlmMessage.of: a label classifies an entry inside the memory
        // tiers and means nothing to a provider.
        LlmMessage message = LlmMessage.of(MemoryEntry.of("episode", "done", new EpisodeLabel("task_completed")));

        assertEquals("episode", message.role());
        assertNull(message.toolCallId());
    }

    @Test
    void aToolCallMessageMissingItsIdOrNameBecomesAPlainEntry() {
        // The metadata record refuses a null; an entry without correlation data is what the
        // text protocol stores anyway, so this must not throw.
        MemoryEntry withoutId = new LlmMessage("tool", "result", null, "crop_region").toMemoryEntry();
        MemoryEntry withoutName = new LlmMessage("tool", "result", "call-1", null).toMemoryEntry();

        assertNull(withoutId.metadata());
        assertNull(withoutName.metadata());
    }

    @Test
    void aMessageTheEntryCannotRepresentLosesItsReasoningAndSaysSoHere() {
        // ReAct records reasoning as an execution step, not in memory; plan-execute keeps it on
        // the message in a list that never goes through memory. Documented on LlmMessage.of.
        LlmMessage withReasoning = LlmMessage.assistantToolCall("call-1", "crop_region", "{}", "thinking...");

        MemoryEntry entry = withReasoning.toMemoryEntry();

        assertEquals("assistant_tool_call", entry.role());
        assertEquals(new ToolCallMetadata("call-1", "crop_region"), entry.metadata());
        assertTrue(LlmMessage.of(entry).reasoning() == null);
    }

    @Test
    void theEntryMadeFromAMessageIsStampedWhenItIsMade() {
        Instant before = Instant.now();

        MemoryEntry entry = LlmMessage.user("hello").toMemoryEntry();

        assertTrue(!entry.timestamp().isBefore(before), "a message has no clock to restore");
    }

    @Test
    void aToolMessageBuiltWithMediaCarriesItAndKeepsItsCallData() {
        LlmMessage message = LlmMessage.tool("call-1", "crop_region", "Crop saved.", List.of(CROP));

        assertEquals("tool", message.role());
        assertEquals("call-1", message.toolCallId());
        assertEquals("crop_region", message.toolName());
        assertEquals(List.of(CROP), message.media());
        assertTrue(message.hasMedia());
    }

    @Test
    void theToolFactoryWithoutMediaIsUnchanged() {
        assertEquals(List.of(), LlmMessage.tool("call-1", "crop_region", "ok").media());
    }

    @Test
    void anEntryCarryingMetadataAndMediaCanBeBuiltInOneCall() {
        MemoryEntry entry = MemoryEntry.of("tool", "Crop saved.", new ToolCallMetadata("call-1", "crop_region"), List.of(CROP));

        assertEquals(new ToolCallMetadata("call-1", "crop_region"), entry.metadata());
        assertEquals(List.of(CROP), entry.media());
    }
}
