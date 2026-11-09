package io.ara.runtime.memory;

import io.ara.core.media.MediaRef;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.ToolCallMetadata;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code docs/analysis/concurrency-hardening.md} §3 P6, §4 U19 — regression test.
 *
 * <p>{@code workingMemory()} used to return {@code Collections.unmodifiableList(working)}:
 * read-only to the caller, but still backed by the same mutable {@code ArrayList} this
 * class keeps appending to and clearing. A caller that fetched the list and then triggered
 * (directly, or via a nested call) a mutation before finishing with it — building a prompt,
 * an {@code AgentExecutionContext} an interceptor reads later — could see the list change
 * size mid-iteration ({@link java.util.ConcurrentModificationException}) or simply observe
 * a different set of entries than the ones it thought it had captured.
 */
class AbstractMemoryManagerTest {

    @Test
    void workingMemory_isASnapshot_notALiveViewOfLaterAppends() {
        InMemoryMemoryManager m = new InMemoryMemoryManager();
        m.appendToWorkingMemory("user", "first");

        List<MemoryEntry> snapshot = m.workingMemory();
        assertEquals(1, snapshot.size());

        m.appendToWorkingMemory("assistant", "second");

        assertEquals(1, snapshot.size(), "a previously fetched snapshot must not grow when new entries are appended");
        assertEquals(2, m.workingMemory().size(), "a fresh call must see the new entry");
    }

    @Test
    void workingMemory_isASnapshot_notALiveViewOfClear() {
        InMemoryMemoryManager m = new InMemoryMemoryManager();
        m.appendToWorkingMemory("user", "first");
        m.appendToWorkingMemory("assistant", "second");

        List<MemoryEntry> snapshot = m.workingMemory();
        m.clearWorkingMemory();

        assertEquals(2, snapshot.size(), "a previously fetched snapshot must survive clearWorkingMemory()");
    }

    @Test
    void workingMemory_survivesIterationDuringAConcurrentMutation_noConcurrentModificationException() {
        InMemoryMemoryManager m = new InMemoryMemoryManager();
        for (int i = 0; i < 5; i++) {
            m.appendToWorkingMemory("user", "message " + i);
        }

        List<MemoryEntry> snapshot = m.workingMemory();
        Iterator<MemoryEntry> it = snapshot.iterator();
        it.next();
        m.appendToWorkingMemory("assistant", "mutates the live list mid-iteration");
        // Must not throw ConcurrentModificationException: the iterator is over a real copy.
        int remaining = 0;
        while (it.hasNext()) {
            it.next();
            remaining++;
        }
        assertEquals(4, remaining);
    }

    @Test
    void workingMemory_remainsUnmodifiableByTheCaller() {
        InMemoryMemoryManager m = new InMemoryMemoryManager();
        m.appendToWorkingMemory("user", "first");
        List<MemoryEntry> snapshot = m.workingMemory();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(MemoryEntry.of("user", "should not be allowed")));
    }

    private static final MediaRef CROP = new MediaRef("3f9a1c2e7b4d", "image/png", "crop-1.png", 12L, null);

    /** The one case the per-field overloads could not express: tool-call metadata and media together. */
    private static MemoryEntry toolResultWithMedia() {
        return new MemoryEntry("tool", "Crop saved.", Instant.parse("2026-11-09T10:00:00Z"),
                new ToolCallMetadata("call-1", "crop_region"), List.of(CROP));
    }

    @Test
    void anEntryWithMetadataAndMedia_isStoredWhole() {
        InMemoryMemoryManager manager = new InMemoryMemoryManager();

        manager.appendToWorkingMemory(toolResultWithMedia());

        assertEquals(List.of(toolResultWithMedia()), manager.workingMemory(),
                "every field of the entry, timestamp included, reaches working memory");
    }

    @Test
    void theSlidingWindow_storesAnEntryWithMetadataAndMediaWhole() {
        SlidingWindowMemoryManager manager = new SlidingWindowMemoryManager(8_000, EvictionPolicy.DROP_OLDEST);

        manager.appendToWorkingMemory(toolResultWithMedia());

        assertEquals(List.of(toolResultWithMedia()), manager.workingMemory());
    }

    @Test
    void theConvenienceOverloads_buildTheSameEntryAsTheBaseOperation() {
        InMemoryMemoryManager manager = new InMemoryMemoryManager();

        manager.appendToWorkingMemory("user", "plain");
        manager.appendToWorkingMemory("tool", "result", new ToolCallMetadata("call-1", "crop_region"));
        manager.appendToWorkingMemory("user", "with media", List.of(CROP));

        List<MemoryEntry> entries = manager.workingMemory();
        assertEquals(List.of("plain", "result", "with media"), entries.stream().map(MemoryEntry::content).toList());
        assertEquals(new ToolCallMetadata("call-1", "crop_region"), entries.get(1).metadata());
        assertEquals(List.of(CROP), entries.get(2).media());
    }
}
