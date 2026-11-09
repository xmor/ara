package io.ara.core.media;

import io.ara.core.media.MediaTypes.MediaKind;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class MediaRefTest {

    @Test
    void unsupported_mime_fails_at_construction() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new MediaRef("abc", "audio/mpeg", "song.mp3", 10, null));
        assertTrue(ex.getMessage().contains("audio/mpeg"), ex.getMessage());
    }

    @Test
    void mime_is_stored_in_canonical_form() {
        assertEquals("application/pdf",
                new MediaRef("abc", "  APPLICATION/PDF ", "contract.pdf", 10, null).mimeType());
    }

    @Test
    void blank_id_and_name_are_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRef(" ", "image/png", "shot.png", 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRef("abc", "image/png", " ", 1, null));
    }

    @Test
    void negative_size_is_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new MediaRef("abc", "image/png", "shot.png", -1, null));
    }

    @Test
    void kind_is_derived_from_the_mime_type() {
        assertEquals(MediaKind.DOCUMENT,
                new MediaRef("abc", "application/pdf", "contract.pdf", 10, null).kind());
    }

    @Test
    void remote_reference_is_external_and_has_unknown_size() {
        MediaRef ref = MediaRef.remote(URI.create("https://example.test/a.pdf"), "application/pdf", "a.pdf");
        assertTrue(ref.isExternal());
        assertEquals(0L, ref.sizeBytes());
        assertEquals("https://example.test/a.pdf", ref.mediaId());
    }

    @Test
    void stored_reference_is_not_external() {
        assertFalse(new MediaRef("abc", "image/png", "shot.png", 3, null).isExternal());
    }

    @Test
    void a_store_id_is_shown_as_its_first_twelve_characters() {
        String digest = "3f9a1c2e7b4d5a6c8e0f1b2d3c4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a";

        MediaRef reference = new MediaRef(digest, "image/png", "crop-1.png", 12, null);

        assertEquals("3f9a1c2e7b4d", reference.shortId());
        assertEquals(MediaRef.SHORT_ID_LENGTH, reference.shortId().length());
    }

    @Test
    void an_id_no_longer_than_the_prefix_is_shown_whole() {
        assertEquals("abc", new MediaRef("abc", "image/png", "shot.png", 1, null).shortId());
    }

    @Test
    void a_uri_backed_reference_is_shown_by_its_whole_id_because_a_uri_prefix_identifies_nothing() {
        MediaRef reference = MediaRef.remote(java.net.URI.create("https://example.org/files/contract.pdf"),
                "application/pdf", "contract.pdf");

        assertEquals("https://example.org/files/contract.pdf", reference.shortId());
    }

    @Test
    void describe_is_the_name_and_the_short_id_in_one_fixed_form() {
        MediaRef reference = new MediaRef("3f9a1c2e7b4d5a6c8e0f1b2d3c4a5b6c", "image/png", "crop-1.png", 12, null);

        assertEquals("crop-1.png (id 3f9a1c2e7b4d)", reference.describe());
    }
}
