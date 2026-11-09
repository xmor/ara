package io.ara.runtime.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaStore;

import java.util.List;

import org.junit.jupiter.api.Test;

/** [V18][V19] The citation scan on its own: boundaries, ambiguity, and what comes first. */
class CitedMediaTest {

    private final MediaStore store = MediaStore.inMemory();
    private final MediaRef a = store.put("a.png", "image/png", new byte[] {1});
    private final MediaRef b = store.put("b.png", "image/png", new byte[] {2});
    private final SessionMediaAccess access = new SessionMediaAccess(store, List.of(a, b));

    @Test
    void extractedPartsComeFirstAndAreNotRepeated() {
        List<MediaRef> out = CitedMedia.appendCited(List.of(b), "a: " + a.shortId() + ", b: " + b.shortId(), access);

        assertEquals(List.of(b, a), out);
    }

    @Test
    void nothingCitedReturnsTheSameListUntouched() {
        List<MediaRef> extracted = List.of(b);

        assertSame(extracted, CitedMedia.appendCited(extracted, "no ids here, only 123 and deadbeef", access));
    }

    @Test
    void aShortHexRunOrAHexRunInsideALongerWordIsNotACitation() {
        String insideWord = "x" + a.shortId() + "y";

        assertEquals(List.of(), CitedMedia.appendCited(List.of(), a.shortId().substring(0, 11) + " " + insideWord, access));
    }

    @Test
    void anAmbiguousPrefixIsSkippedWithoutFailing() {
        io.ara.core.media.MediaAccess ambiguous = new io.ara.core.media.MediaAccess() {
            @Override public java.util.Optional<MediaRef> resolve(String id) {
                throw new IllegalArgumentException("'" + id + "' matches more than one media");
            }
            @Override public byte[] read(MediaRef r) { throw new AssertionError(); }
            @Override public MediaRef put(String n, String m, byte[] bytes) { throw new AssertionError(); }
        };

        assertEquals(List.of(), CitedMedia.appendCited(List.of(), "see " + a.shortId(), ambiguous));
    }

    @Test
    void noAccessOrNoTextChangesNothing() {
        List<MediaRef> extracted = List.of(a);

        assertSame(extracted, CitedMedia.appendCited(extracted, a.shortId(), null));
        assertSame(extracted, CitedMedia.appendCited(extracted, "", access));
        assertSame(extracted, CitedMedia.appendCited(extracted, null, access));
    }
}
