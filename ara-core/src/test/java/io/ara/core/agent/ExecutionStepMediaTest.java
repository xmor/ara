package io.ara.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ara.core.media.MediaRef;
import io.ara.core.tool.ToolResult;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * An observation step is where the session history finds out which media each tool call produced:
 * the text of the step names them, but a name cannot be turned back into a reference.
 */
class ExecutionStepMediaTest {

    private static final MediaRef CROP = new MediaRef("3f9a1c2e7b4d5a6c8e0f1b2d3c4a5b6c", "image/png", "crop-1.png", 12, null);

    @Test
    void anObservationBuiltFromAResultCarriesTheResultsMedia() {
        ExecutionStep step = ExecutionStep.observation(ToolResult.success("crop_region", "Crop saved.", List.of(CROP)), 2);

        assertEquals(List.of(CROP), step.media());
        assertEquals("Crop saved.\nAttached: crop-1.png (id 3f9a1c2e7b4d)", step.content(),
                "the text still names the media, for a turn that no longer has the image");
    }

    @Test
    void everyOtherKindOfStepHasNoMedia() {
        assertTrue(ExecutionStep.thought("thinking", 1).media().isEmpty());
        assertTrue(ExecutionStep.toolCall("crop_region", "{}", 1).media().isEmpty());
        assertTrue(ExecutionStep.finalAnswer("done", 3).media().isEmpty());
        assertTrue(ExecutionStep.observation("text only", 1).media().isEmpty());
        assertTrue(ExecutionStep.observation(ToolResult.success("echo", "hello"), 1).media().isEmpty());
    }

    @Test
    void theShapesFromBeforeMediaStillBuildAStepWithNone() {
        ExecutionStep sixComponents = new ExecutionStep(StepType.OBSERVATION, "echo", null, "hello", true, 1);
        ExecutionStep fiveComponents = new ExecutionStep(StepType.OBSERVATION, "echo", null, "hello", 1);

        assertTrue(sixComponents.media().isEmpty());
        assertEquals(Boolean.TRUE, sixComponents.success());
        assertTrue(fiveComponents.media().isEmpty());
        assertEquals(Boolean.FALSE, fiveComponents.success(), "the legacy shape's own default is unchanged");
    }
}
