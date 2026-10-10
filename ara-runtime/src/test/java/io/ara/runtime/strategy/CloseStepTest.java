package io.ara.runtime.strategy;

import io.ara.runtime.strategy.CloseStep.Closing;
import io.ara.runtime.strategy.CloseStep.Parsed;
import io.ara.runtime.strategy.CloseStep.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloseStepTest {

    private static Closing accepted(String json) {
        return assertInstanceOf(Parsed.Accepted.class, CloseStep.parse(json)).closing();
    }

    private static String refused(String json) {
        return assertInstanceOf(Parsed.Refused.class, CloseStep.parse(json)).correction();
    }

    @Test
    void done_withNotes() {
        Closing closing = accepted("""
                {"status":"done","notes":["Person is in io/github/xmor/ara/Person.java"]}""");

        assertEquals(Status.DONE, closing.status());
        assertEquals(List.of("Person is in io/github/xmor/ara/Person.java"), closing.notes());
        assertNull(closing.reason());
        assertNull(closing.result());
    }

    @Test
    void doneNeedsNothingElse() {
        Closing closing = accepted("{\"status\":\"done\"}");

        assertTrue(closing.notes().isEmpty());
    }

    @Test
    void statusIsReadCaseInsensitively() {
        assertEquals(Status.DONE, accepted("{\"status\":\" Done \"}").status());
    }

    @Test
    void failedAndRevise_carryTheirReason() {
        assertEquals("no network", accepted("{\"status\":\"failed\",\"reason\":\"no network\"}").reason());
        assertEquals(Status.REVISE, accepted("{\"status\":\"revise\",\"reason\":\"needs a db\"}").status());
    }

    @Test
    void failedAndRevise_withoutAReason_areRefused() {
        assertTrue(refused("{\"status\":\"failed\"}").contains("needs a reason"));
        assertTrue(refused("{\"status\":\"revise\",\"reason\":\"  \"}").contains("needs a reason"));
    }

    @Test
    void resultIsKept_whenGiven() {
        assertEquals("the answer", accepted("{\"status\":\"done\",\"result\":\"the answer\"}").result());
    }

    @Test
    void aMissingOrUnknownStatus_isRefused() {
        assertTrue(refused("{}").contains("status must be one of"));
        assertTrue(refused("{\"status\":\"finished\"}").contains("status must be one of"));
    }

    @Test
    void notJsonAndNotAnObject_areRefused() {
        assertTrue(refused("not json").contains("not valid JSON"));
        assertTrue(refused("[1,2]").contains("must be a JSON object"));
        assertTrue(refused(null).contains("must be a JSON object"), "no arguments at all is not an object either");
    }

    @Test
    void blankNotes_areDropped() {
        assertEquals(List.of("keep"), accepted("{\"status\":\"done\",\"notes\":[\" \",\"keep\",\"\"]}").notes());
    }

    @Test
    void fiveNotes_areAllowed_aSixthIsRefused() {
        assertEquals(5, accepted("{\"status\":\"done\",\"notes\":[\"1\",\"2\",\"3\",\"4\",\"5\"]}").notes().size());
        assertTrue(refused("{\"status\":\"done\",\"notes\":[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]}")
                .contains("at most 5"));
    }

    @Test
    void aNoteOfExactlyTheLimit_isAllowed_oneCharacterMoreIsRefused() {
        String atLimit = "x".repeat(CloseStep.MAX_NOTE_CHARS);

        assertEquals(1, accepted("{\"status\":\"done\",\"notes\":[\"" + atLimit + "\"]}").notes().size());
        assertTrue(refused("{\"status\":\"done\",\"notes\":[\"" + atLimit + "x\"]}").contains("shorten"));
    }

    @Test
    void notesThatAreNotAnArray_areRefused() {
        assertTrue(refused("{\"status\":\"done\",\"notes\":\"a string\"}").contains("array of strings"));
    }

    @Test
    void everyRefusal_tellsTheModelTheStepIsStillOpen() {
        assertTrue(refused("{}").contains("still open"));
    }

    @Test
    void formatNotes_isNullWhenThereAreNone_andListsEachNoteOtherwise() {
        assertNull(CloseStep.formatNotes(List.of()));
        String block = CloseStep.formatNotes(List.of("a", "b"));
        assertTrue(block.contains("  - a") && block.contains("  - b"));
    }

    @Test
    void theToolIsNeverExecutable() {
        assertTrue(CloseStep.TOOL.execute("{}").error().contains("must never be executed"));
    }
}
