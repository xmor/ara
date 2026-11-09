package io.ara.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ara.core.media.MediaRef;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ToolExchangeTest {

    private static final MediaRef CROP = new MediaRef("3f9a1c2e7b4d5a6c8e0f1b2d3c4a5b6c", "image/png", "crop-1.png", 12, null);

    @Test
    void theThreeComponentShapeFromBeforeMediaHasNoMedia() {
        // A session stored before tool results could carry media is read back through this shape.
        ToolExchange exchange = new ToolExchange("lights", "{\"on\":false}", "switched off");

        assertTrue(exchange.media().isEmpty());
    }

    @Test
    void anExchangeKeepsTheMediaItsCallReturned() {
        ToolExchange exchange = new ToolExchange("crop_region", "{}", "Crop saved.", List.of(CROP));

        assertEquals(List.of(CROP), exchange.media());
    }

    @Test
    void theMediaListIsACopyThatLaterChangesToTheCallersListDoNotReach() {
        List<MediaRef> callers = new ArrayList<>(List.of(CROP));
        ToolExchange exchange = new ToolExchange("crop_region", "{}", "ok", callers);

        callers.clear();

        assertEquals(List.of(CROP), exchange.media());
        assertThrows(UnsupportedOperationException.class, () -> exchange.media().add(CROP));
    }

    @Test
    void nullMediaMeansNoMedia() {
        assertTrue(new ToolExchange("t", "{}", "ok", null).media().isEmpty());
    }

    @Test
    void theExistingNormalisationsStillApply() {
        ToolExchange exchange = new ToolExchange("t", " ", null, List.of());

        assertEquals("{}", exchange.arguments());
        assertEquals("", exchange.result());
        assertThrows(IllegalArgumentException.class, () -> new ToolExchange(" ", "{}", "ok"));
    }
}
