package io.ara.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ara.core.media.MediaRef;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ToolResultTest {

    private static final MediaRef CROP = new MediaRef("3f9a1c2e7b4d5a6c8e0f1b2d3c4a5b6c", "image/png", "crop-1.png", 12, null);
    private static final MediaRef PAGE = new MediaRef("aa11bb22cc33dd44ee55ff6677889900", "image/png", "page-2.png", 20, null);

    // ── The shape from before media must behave as it did ─────────────────────────────────────

    @Test
    void theFourComponentConstructorProducesAResultWithNoMedia() {
        ToolResult result = new ToolResult("echo", "hello", true, null);

        assertTrue(result.media().isEmpty());
    }

    @Test
    void aResultWithoutMediaHasExactlyItsOutputAsContent() {
        // Everything that reads content() today — execution steps, the next turn's history — must see
        // no difference unless a tool actually returns media.
        assertEquals("hello", ToolResult.success("echo", "hello").content());
        assertEquals("", ToolResult.success("echo", "").content());
    }

    @Test
    void aFailureHasItsErrorAsContent() {
        assertEquals("boom", ToolResult.failure("echo", "boom").content());
    }

    // ── Media ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void aSuccessfulResultCarriesItsMediaInOrder() {
        ToolResult result = ToolResult.success("crop_region", "Crop saved.", List.of(CROP, PAGE));

        assertEquals(List.of(CROP, PAGE), result.media());
        assertEquals("Crop saved.", result.output(), "output stays the tool's own text");
    }

    @Test
    void contentAddsOneAttachedLinePerMediaAfterTheOutput() {
        ToolResult result = ToolResult.success("crop_region", "Crop saved.", List.of(CROP, PAGE));

        assertEquals("Crop saved.\nAttached: crop-1.png (id 3f9a1c2e7b4d)\nAttached: page-2.png (id aa11bb22cc33)",
                result.content());
    }

    @Test
    void aResultWhoseOutputIsEmptyStartsWithTheFirstLineAndNotABlankOne() {
        ToolResult result = ToolResult.success("crop_region", "", List.of(CROP));

        assertEquals("Attached: crop-1.png (id 3f9a1c2e7b4d)", result.content());
    }

    @Test
    void theMediaListIsACopyThatLaterChangesToTheCallersListDoNotReach() {
        List<MediaRef> callers = new ArrayList<>(List.of(CROP));
        ToolResult result = ToolResult.success("crop_region", "ok", callers);

        callers.add(PAGE);

        assertEquals(List.of(CROP), result.media());
        assertThrows(UnsupportedOperationException.class, () -> result.media().add(PAGE));
    }

    @Test
    void nullMediaMeansNoMedia() {
        assertEquals(List.of(), new ToolResult("echo", "ok", true, null, null).media());
    }

    @Test
    void aFailedResultMayNotCarryMedia() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolResult("crop_region", "", false, "no such region", List.of(CROP)));
    }

    @Test
    void theFailureFactoryHasNoMedia() {
        assertTrue(ToolResult.failure("crop_region", "no such region").media().isEmpty());
    }

    @Test
    void theExistingInvariantsStillHold() {
        assertThrows(IllegalArgumentException.class, () -> new ToolResult(" ", "ok", true, null));
        assertThrows(IllegalArgumentException.class, () -> new ToolResult("t", "ok", false, null));
        assertThrows(IllegalArgumentException.class, () -> new ToolResult("t", "ok", true, "error"));
        assertThrows(NullPointerException.class, () -> new ToolResult("t", null, true, null));
    }
}
