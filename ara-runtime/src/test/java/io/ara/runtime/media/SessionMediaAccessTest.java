package io.ara.runtime.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaStore;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

class SessionMediaAccessTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ── What is visible ───────────────────────────────────────────────────────────────────────────────

    @Test
    void aMediaTheSessionHasSeenResolvesByItsWholeId() {
        MediaStore store = MediaStore.inMemory();
        MediaRef seen = store.put("contract.png", "image/png", bytes("contract"));

        SessionMediaAccess access = new SessionMediaAccess(store, List.of(seen));

        assertEquals(Optional.of(seen), access.resolve(seen.mediaId()));
    }

    @Test
    void aMediaResolvesByTheShortIdAModelWasShown() {
        MediaStore store = MediaStore.inMemory();
        MediaRef seen = store.put("contract.png", "image/png", bytes("contract"));
        SessionMediaAccess access = new SessionMediaAccess(store, List.of(seen));

        assertEquals(Optional.of(seen), access.resolve(seen.shortId()));
    }

    @Test
    void theIdIsMatchedWithoutRegardToCaseBecauseAModelCopiesHexInEither() {
        MediaStore store = MediaStore.inMemory();
        MediaRef seen = store.put("contract.png", "image/png", bytes("contract"));
        SessionMediaAccess access = new SessionMediaAccess(store, List.of(seen));

        assertEquals(Optional.of(seen), access.resolve(seen.shortId().toUpperCase()));
    }

    @Test
    void aMediaStoredByAnotherSessionDoesNotResolveEvenThoughTheStoreHoldsIt() {
        MediaStore store = MediaStore.inMemory();
        MediaRef mine = store.put("mine.png", "image/png", bytes("mine"));
        MediaRef theirs = store.put("theirs.png", "image/png", bytes("theirs"));

        SessionMediaAccess access = new SessionMediaAccess(store, List.of(mine));

        assertEquals(Optional.empty(), access.resolve(theirs.mediaId()));
        assertEquals(Optional.empty(), access.resolve(theirs.shortId()));
    }

    @Test
    void blankAndUnknownIdsResolveToNothingRatherThanFailing() {
        SessionMediaAccess access = new SessionMediaAccess(MediaStore.inMemory(), List.of());

        assertEquals(Optional.empty(), access.resolve(null));
        assertEquals(Optional.empty(), access.resolve("  "));
        assertEquals(Optional.empty(), access.resolve("ffffffffffff"));
    }

    @Test
    void theSameMediaSeenTwiceIsOneMedia() {
        MediaStore store = MediaStore.inMemory();
        MediaRef seen = store.put("a.png", "image/png", bytes("a"));

        SessionMediaAccess access = new SessionMediaAccess(store, List.of(seen, seen));

        assertEquals(Optional.of(seen), access.resolve(seen.shortId()), "a duplicate must not read as an ambiguity");
    }

    // ── Prefixes ──────────────────────────────────────────────────────────────────────────────────────

    /** Two media whose ids share a prefix, built directly: the store would give digests, which never collide here. */
    private static MediaRef withId(String id, String name) {
        return new MediaRef(id, "image/png", name, 1, null);
    }

    @Test
    void aPrefixSharedByTwoMediaIsAnErrorThatNamesBothSoTheModelCanAskAgain() {
        SessionMediaAccess access = new SessionMediaAccess(MediaStore.inMemory(),
                List.of(withId("3f9a111111111111", "crop-1.png"), withId("3f9a222222222222", "crop-2.png")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> access.resolve("3f9a"));

        assertTrue(error.getMessage().contains("crop-1.png"), error.getMessage());
        assertTrue(error.getMessage().contains("crop-2.png"), error.getMessage());
        assertTrue(error.getMessage().contains("longer"), error.getMessage());
    }

    @Test
    void aLongerPrefixResolvesWhatAShorterOneCouldNot() {
        MediaRef first = withId("3f9a111111111111", "crop-1.png");
        SessionMediaAccess access = new SessionMediaAccess(MediaStore.inMemory(),
                List.of(first, withId("3f9a222222222222", "crop-2.png")));

        assertEquals(Optional.of(first), access.resolve("3f9a1"));
    }

    @Test
    void anExactIdWinsOverALongerIdItIsThePrefixOf() {
        MediaRef shorter = withId("3f9a11", "short.png");
        SessionMediaAccess access = new SessionMediaAccess(MediaStore.inMemory(),
                List.of(shorter, withId("3f9a1122", "long.png")));

        assertEquals(Optional.of(shorter), access.resolve("3f9a11"),
                "a model that copied a whole id meant that media: calling it ambiguous would be wrong");
    }

    // ── Reading ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aVisibleMediaCanBeRead() {
        MediaStore store = MediaStore.inMemory();
        MediaRef seen = store.put("contract.png", "image/png", bytes("contract"));
        SessionMediaAccess access = new SessionMediaAccess(store, List.of(seen));

        assertArrayEquals(bytes("contract"), access.read(seen));
    }

    @Test
    void aMediaOutsideTheSessionCannotBeReadAndItsExistenceIsNotConfirmed() {
        MediaStore store = MediaStore.inMemory();
        MediaRef theirs = store.put("theirs.png", "image/png", bytes("theirs"));
        MediaRef neverStored = withId("0000000000000000", "theirs.png");
        SessionMediaAccess access = new SessionMediaAccess(store, List.of());

        IllegalArgumentException outside = assertThrows(IllegalArgumentException.class, () -> access.read(theirs));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> access.read(neverStored));

        assertEquals(outside.getMessage(), unknown.getMessage(),
                "the two must read the same, or the message would tell which ids exist");
    }

    @Test
    void aVisibleMediaWhoseBytesAreGoneFailsNamingIt() {
        MediaRef seen = withId("3f9a1c2e7b4d5a6c", "gone.png");
        SessionMediaAccess access = new SessionMediaAccess(MediaStore.inMemory(), List.of(seen));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> access.read(seen));

        assertTrue(error.getMessage().contains("gone.png"), error.getMessage());
    }

    @Test
    void aMediaHeldAtAUriCanBeResolvedButNotRead() {
        MediaRef remote = MediaRef.remote(URI.create("https://example.org/contract.pdf"), "application/pdf", "contract.pdf");
        SessionMediaAccess access = new SessionMediaAccess(MediaStore.inMemory(), List.of(remote));

        assertEquals(Optional.of(remote), access.resolve(remote.mediaId()));
        assertThrows(IllegalArgumentException.class, () -> access.read(remote));
    }

    // ── Writing ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aMediaTheRunStoresIsVisibleAtOnceAndCanBeReadBack() {
        SessionMediaAccess access = new SessionMediaAccess(MediaStore.inMemory(), List.of());

        MediaRef crop = access.put("crop-1.png", "image/png", bytes("crop"));

        assertEquals(Optional.of(crop), access.resolve(crop.shortId()));
        assertArrayEquals(bytes("crop"), access.read(crop));
    }

    @Test
    void aMediaOfATypeTheStoreRefusesIsNotStoredAndNotVisible() {
        SessionMediaAccess access = new SessionMediaAccess(MediaStore.inMemory(), List.of());

        assertThrows(IllegalArgumentException.class, () -> access.put("song.mp3", "audio/mpeg", bytes("la la")));
    }

    @Test
    void theToolsOfOneBlockMayStoreAtTheSameTimeAndEveryMediaIsVisibleAfterwards() throws Exception {
        int tools = 32;
        SessionMediaAccess access = new SessionMediaAccess(MediaStore.inMemory(), List.of());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<MediaRef>> stored = new ArrayList<>();
            for (int i = 0; i < tools; i++) {
                String content = "crop-" + i;
                stored.add(pool.submit(() -> {
                    start.await();
                    return access.put(content + ".png", "image/png", bytes(content));
                }));
            }
            start.countDown();
            for (Future<MediaRef> future : stored) {
                MediaRef reference = future.get();
                assertEquals(Optional.of(reference), access.resolve(reference.mediaId()));
            }
        }
    }
}
