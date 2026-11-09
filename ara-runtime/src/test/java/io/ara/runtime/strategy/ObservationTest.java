package io.ara.runtime.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ara.core.media.MediaRef;
import io.ara.core.tool.ToolResult;

import java.util.List;

import org.junit.jupiter.api.Test;

class ObservationTest {

    private static final MediaRef CROP = new MediaRef("3f9a1c2e7b4d5a6c8e0f1b2d3c4a5b6c", "image/png", "crop-1.png", 12, null);

    @Test
    void aSuccessWithoutMediaReadsAsItsOutputExactlyAsBeforeMediaExisted() {
        Observation observation = Observation.of("echo", ToolResult.success("echo", "hello"));

        assertEquals("hello", observation.text());
        assertTrue(observation.media().isEmpty());
    }

    @Test
    void aFailureReadsAsTheSentenceThatNamesTheCall() {
        Observation observation = Observation.of("echo", ToolResult.failure("echo", "boom"));

        assertEquals("Tool [echo] failed — boom", observation.text());
        assertTrue(observation.media().isEmpty());
    }

    @Test
    void theFailureSentenceNamesTheToolTheModelCalledNotTheOneTheResultSaysItCameFrom() {
        // A registry that answers under another id must not change what the model is told.
        Observation observation = Observation.of("echo", ToolResult.failure("registry-internal-id", "boom"));

        assertEquals("Tool [echo] failed — boom", observation.text());
    }

    @Test
    void aSuccessWithMediaCarriesTheMediaAndNamesThemInItsText() {
        Observation observation = Observation.of("crop_region",
                ToolResult.success("crop_region", "Crop saved.", List.of(CROP)));

        assertEquals("Crop saved.\nAttached: crop-1.png (id 3f9a1c2e7b4d)", observation.text());
        assertEquals(List.of(CROP), observation.media());
    }

    @Test
    void aTextOnlyObservationHasNoMedia() {
        Observation observation = Observation.ofText("Step closed (done).");

        assertEquals("Step closed (done).", observation.text());
        assertTrue(observation.media().isEmpty());
    }

    @Test
    void theTextProtocolTurnPrefixesTheTextAndLeavesTheMediaToTheCaller() {
        Observation observation = Observation.of("crop_region",
                ToolResult.success("crop_region", "Crop saved.", List.of(CROP)));

        assertEquals("Observation: Crop saved.\nAttached: crop-1.png (id 3f9a1c2e7b4d)", observation.asTextProtocolTurn());
    }

    @Test
    void textIsRequired() {
        assertThrows(NullPointerException.class, () -> new Observation(null, List.of()));
    }
}
