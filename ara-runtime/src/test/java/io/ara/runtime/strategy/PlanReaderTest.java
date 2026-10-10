package io.ara.runtime.strategy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanReaderTest {

    @Test
    void aJsonPlan_yieldsItsGoalsInOrder() {
        String reply = """
                {"steps":[
                  {"id":"s1","goal":"Create Person","dependsOn":[]},
                  {"id":"s2","goal":"Create Address","dependsOn":[]},
                  {"id":"s3","goal":"Link them","dependsOn":["s1","s2"]}]}""";

        assertEquals(List.of("Create Person", "Create Address", "Link them"), PlanReader.read(reply));
    }

    @Test
    void dependsOnIsIgnored_theOrderIsTheWrittenOrder() {
        String reply = """
                {"steps":[
                  {"id":"s1","goal":"Last in theory","dependsOn":["s2"]},
                  {"id":"s2","goal":"First in theory","dependsOn":[]}]}""";

        assertEquals(List.of("Last in theory", "First in theory"), PlanReader.read(reply));
    }

    @Test
    void aJsonPlanWrappedInAMarkdownFenceAndProse_isStillRead() {
        String reply = "Here is the plan:\n```json\n{\"steps\":[{\"id\":\"s1\",\"goal\":\"Do it\"}]}\n```\nHope it helps.";

        assertEquals(List.of("Do it"), PlanReader.read(reply));
    }

    @Test
    void stepsGivenAsPlainStrings_areAccepted() {
        assertEquals(List.of("one", "two"), PlanReader.read("{\"steps\":[\"one\",\"two\"]}"));
    }

    @Test
    void aStepWithoutAGoal_isSkippedNotInvented() {
        String reply = "{\"steps\":[{\"id\":\"s1\"},{\"id\":\"s2\",\"goal\":\"Real step\"},{\"id\":\"s3\",\"goal\":\"  \"}]}";

        assertEquals(List.of("Real step"), PlanReader.read(reply));
    }

    @Test
    void aNumberedList_isStillAPlan() {
        assertEquals(List.of("First", "Second"), PlanReader.read("1. First\n2) Second"));
    }

    @Test
    void aBulletedList_isStillAPlan() {
        assertEquals(List.of("First", "Second"), PlanReader.read("- First\n* Second"));
    }

    @Test
    void aListItemContainingBraces_isReadAsAListNotAsBrokenJson() {
        assertEquals(List.of("Return {x} to the caller"), PlanReader.read("1. Return {x} to the caller"));
    }

    @Test
    void prose_isUnreadable() {
        assertTrue(PlanReader.read("I would simply answer the question directly.").isEmpty());
    }

    @Test
    void aJsonObjectWithoutSteps_isUnreadable() {
        assertTrue(PlanReader.read("{\"answer\":42}").isEmpty());
    }

    @Test
    void aJsonPlanWithNoSteps_isUnreadable() {
        assertTrue(PlanReader.read("{\"steps\":[]}").isEmpty());
    }

    @Test
    void nullAndBlank_areUnreadable() {
        assertTrue(PlanReader.read(null).isEmpty());
        assertTrue(PlanReader.read("   ").isEmpty());
    }
}
