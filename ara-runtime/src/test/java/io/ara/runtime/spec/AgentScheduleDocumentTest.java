package io.ara.runtime.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.AgentSchedule;
import io.ara.core.common.AgentId;
import io.ara.runtime.spec.AgentScheduleDocument.Entry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schedule document: every field survives a round trip, version 2 is strict, version 1 files
 * (the first export, which exist) are still read as they were written, and one bad entry never
 * hides the good ones.
 */
class AgentScheduleDocumentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-21T08:15:30Z"), ZoneOffset.UTC);

    private static AgentSchedule interval(String id, String agent, long seconds, boolean active) {
        return AgentSchedule.builder()
                .scheduleId(id).agentId(AgentId.of(agent))
                .every(Duration.ofSeconds(seconds))
                .withInput("input for " + id)
                .active(active)
                .build();
    }

    private static AgentSchedule cron(String id, String agent, String expression) {
        return AgentSchedule.builder()
                .scheduleId(id).agentId(AgentId.of(agent))
                .cron(expression)
                .withInput("input for " + id)
                .build();
    }

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static List<Entry> decode(String text) throws Exception {
        return AgentScheduleDocument.decode(json(text));
    }

    private static Entry.Invalid invalid(Entry entry) {
        return assertInstanceOf(Entry.Invalid.class, entry);
    }

    private static AgentSchedule valid(Entry entry) {
        return assertInstanceOf(Entry.Valid.class, entry).schedule();
    }

    // ── round trip ─────────────────────────────────────────────────────────────────────

    @Test
    void roundTripPreservesEveryField() {
        List<AgentSchedule> original = List.of(
                interval("daily", "Reporter", 3600, true),
                cron("nightly", "Watcher", "0 6 * * MON-FRI"),
                interval("paused", "Reporter", 60, false));

        JsonNode tree = AgentScheduleDocument.encode(original, FIXED);

        assertEquals(2, tree.get("schemaVersion").asInt());
        assertEquals("2026-10-21T08:15:30Z", tree.get("exportedAt").asText());
        List<Entry> entries = AgentScheduleDocument.decode(tree);
        assertEquals(3, entries.size());
        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.get(i), valid(entries.get(i)));
            assertEquals(i, entries.get(i).index());
        }
    }

    @Test
    void aScheduleWithAnEmptyInputRoundTrips() {
        // AgentSchedule.Builder's own default input is "", so a schedule built without one must come back.
        AgentSchedule bare = AgentSchedule.builder()
                .scheduleId("bare").agentId(AgentId.of("A")).every(Duration.ofSeconds(5)).build();

        assertEquals(bare, valid(AgentScheduleDocument.decode(AgentScheduleDocument.encode(List.of(bare), FIXED)).get(0)));
    }

    /** The optional run-timeout and its absence ride the same document: absent stays absent. */
    @Test
    void theRunTimeoutRoundTrips_andItsAbsenceStaysAbsence() {
        AgentSchedule timed = AgentSchedule.builder()
                .scheduleId("timed").agentId(AgentId.of("A")).every(Duration.ofSeconds(5))
                .maxDuration(Duration.ofMinutes(30)).build();

        JsonNode tree = AgentScheduleDocument.encode(List.of(timed), FIXED);
        assertEquals(1_800_000, tree.get("schedules").get(0).get("maxDurationMs").asLong());
        assertEquals(timed, valid(AgentScheduleDocument.decode(tree).get(0)));

        AgentSchedule untimed = AgentSchedule.builder()
                .scheduleId("untimed").agentId(AgentId.of("A")).cron("0 6 * * *").build();
        JsonNode plain = AgentScheduleDocument.encode(List.of(untimed), FIXED);
        assertNull(plain.get("schedules").get(0).get("maxDurationMs"), "no field where none applies");
        assertEquals(untimed, valid(AgentScheduleDocument.decode(plain).get(0)));
    }

    @Test
    void aNonPositiveRunTimeoutIsRefused_atDecodeAndAtBuild() throws Exception {
        JsonNode broken = json("""
                {"schemaVersion": 2, "exportedAt": "2026-10-21T08:15:30Z", "schedules": [
                  {"scheduleId": "t", "agentName": "A", "inputTemplate": "x", "triggerType": "INTERVAL",
                   "intervalSeconds": 5, "maxDurationMs": 0, "active": true}]}
                """);
        Entry entry = AgentScheduleDocument.decode(broken).get(0);
        assertInstanceOf(Entry.Invalid.class, entry);
        assertThrows(IllegalArgumentException.class, () -> AgentSchedule.builder()
                .scheduleId("t").agentId(AgentId.of("A")).every(Duration.ofSeconds(5))
                .maxDurationMs(-1L).build());
    }

    @Test
    void exportIsDeterministicForAFixedClock() throws Exception {
        List<AgentSchedule> schedules = List.of(interval("a", "A", 30, true), cron("b", "A", "*/5 * * * *"));
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        var format = DocumentFormats.defaults().byId("json");

        format.write(AgentScheduleDocument.encode(schedules, FIXED), first);
        format.write(AgentScheduleDocument.encode(schedules, FIXED), second);

        assertEquals(first.toString(StandardCharsets.UTF_8), second.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anIntervalThatIsNotWholeSecondsCannotBeExported() {
        AgentSchedule fast = AgentSchedule.builder()
                .scheduleId("fast").agentId(AgentId.of("A")).every(Duration.ofMillis(1500)).build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AgentScheduleDocument.encode(List.of(fast), FIXED));
        assertTrue(e.getMessage().contains("fast"), e.getMessage());
    }

    @Test
    void readsAndWritesThroughAFormat() throws Exception {
        var format = DocumentFormats.defaults().byId("json");
        List<AgentSchedule> schedules = List.of(interval("daily", "Reporter", 3600, true));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        AgentScheduleDocument.write(format, schedules, out);
        List<Entry> back = AgentScheduleDocument.read(format, new ByteArrayInputStream(out.toByteArray()));

        assertEquals(schedules.get(0), valid(back.get(0)));
    }

    // ── version 2 is strict ────────────────────────────────────────────────────────────

    @Test
    void version2RejectsAnUnknownFieldWithItsPath() throws Exception {
        List<Entry> entries = decode("""
            {"schemaVersion": 2, "schedules": [
              {"scheduleId": "ok", "agentName": "A", "triggerType": "INTERVAL", "intervalSeconds": 10},
              {"scheduleId": "typo", "agentName": "A", "triggerType": "INTERVAL", "intervalSecs": 10, "intervalSeconds": 10}
            ]}""");

        valid(entries.get(0));
        String error = invalid(entries.get(1)).error();
        assertTrue(error.startsWith("schedules[1].intervalSecs: unknown field"), error);
    }

    @Test
    void version2DoesNotCoerceActive() throws Exception {
        Entry.Invalid text = invalid(decode("""
            {"schemaVersion": 2, "schedules": [
              {"scheduleId": "s", "agentName": "A", "triggerType": "INTERVAL", "intervalSeconds": 10, "active": "false"}
            ]}""").get(0));

        assertTrue(text.error().startsWith("schedules[0].active: expected true or false"), text.error());
    }

    @Test
    void version2RejectsFieldsThatContradictTheTrigger() throws Exception {
        List<Entry> entries = decode("""
            {"schemaVersion": 2, "schedules": [
              {"scheduleId": "a", "agentName": "A", "triggerType": "INTERVAL", "intervalSeconds": 10, "cronExpression": "* * * * *"},
              {"scheduleId": "b", "agentName": "A", "triggerType": "CRON", "cronExpression": "* * * * *", "intervalSeconds": 10}
            ]}""");

        assertTrue(invalid(entries.get(0)).error().contains("schedules[0].cronExpression: is not allowed"));
        assertTrue(invalid(entries.get(1)).error().contains("schedules[1].intervalSeconds: is not allowed"));
    }

    @Test
    void version2RejectsAnUnknownRootField() {
        AgentSpecDocumentException e = assertThrows(AgentSpecDocumentException.class,
                () -> decode("{\"schemaVersion\": 2, \"schedules\": [], \"extra\": 1}"));
        assertEquals("extra", e.path());
    }

    @Test
    void activeDefaultsToTrueWhenAbsent() throws Exception {
        AgentSchedule schedule = valid(decode("""
            {"schemaVersion": 2, "schedules": [
              {"scheduleId": "s", "agentName": "A", "triggerType": "CRON", "cronExpression": "0 6 * * *"}]}""").get(0));

        assertTrue(schedule.active());
        assertEquals("", schedule.inputTemplate());
    }

    // ── version 1 (the first export) keeps its rules ───────────────────────────────────

    @Test
    void version1FilesAreReadWithTheRulesTheyWereWrittenUnder() throws Exception {
        List<Entry> entries = decode("""
            {"version": 1, "exportedAt": 20261021, "extra": true, "schedules": [
              {"scheduleId": "a", "agentName": "A", "inputTemplate": "x", "triggerType": "INTERVAL",
               "intervalSeconds": 10, "futureField": {"nested": true}},
              {"scheduleId": "b", "agentName": "A", "inputTemplate": "x", "triggerType": "CRON",
               "cronExpression": "0 6 * * *", "active": "false"},
              {"scheduleId": "c", "agentName": "A", "inputTemplate": "x", "triggerType": "CRON",
               "cronExpression": "0 6 * * *", "active": 7}
            ]}""");

        assertTrue(valid(entries.get(0)).active());
        assertFalse(valid(entries.get(1)).active(), "the string \"false\" was read as false by the first reader");
        assertTrue(valid(entries.get(2)).active(), "anything not recognisably false was true");
    }

    @Test
    void aFileWithNoVersionAtAllIsReadAsVersion1() throws Exception {
        assertTrue(decode("""
            {"schedules": [{"scheduleId": "s", "agentName": "A", "inputTemplate": "x",
             "triggerType": "INTERVAL", "intervalSeconds": 10, "futureField": 1}]}""").get(0)
                instanceof Entry.Valid);
    }

    // ── versions ───────────────────────────────────────────────────────────────────────

    @Test
    void aFutureVersionIsRejectedNamingBothVersions() {
        AgentSpecDocumentException e = assertThrows(AgentSpecDocumentException.class,
                () -> decode("{\"schemaVersion\": 3, \"schedules\": []}"));

        assertEquals("schemaVersion", e.path());
        assertTrue(e.getMessage().contains("3") && e.getMessage().contains("2"), e.getMessage());
    }

    @Test
    void aFutureLegacyVersionKeyIsRejectedToo() {
        assertThrows(AgentSpecDocumentException.class, () -> decode("{\"version\": 9, \"schedules\": []}"));
    }

    @Test
    void aVersionBelowOneIsRejected() {
        assertThrows(AgentSpecDocumentException.class, () -> decode("{\"schemaVersion\": 0, \"schedules\": []}"));
    }

    // ── per-entry errors ───────────────────────────────────────────────────────────────

    @Test
    void oneBadEntryDoesNotHideTheOthersAndSaysWhichItIs() throws Exception {
        List<Entry> entries = decode("""
            {"schemaVersion": 2, "schedules": [
              {"scheduleId": "ok", "agentName": "A", "triggerType": "INTERVAL", "intervalSeconds": 30},
              {"scheduleId": "", "agentName": "A", "triggerType": "INTERVAL", "intervalSeconds": 30},
              {"scheduleId": "no-agent", "triggerType": "INTERVAL", "intervalSeconds": 30},
              {"scheduleId": "no-cron", "agentName": "A", "triggerType": "CRON"},
              {"scheduleId": "bad-type", "agentName": "A", "triggerType": "HOURLY"},
              {"scheduleId": "no-type", "agentName": "A"},
              {"scheduleId": "no-seconds", "agentName": "A", "triggerType": "INTERVAL"},
              {"scheduleId": "zero", "agentName": "A", "triggerType": "INTERVAL", "intervalSeconds": 0},
              {"scheduleId": "negative", "agentName": "A", "triggerType": "INTERVAL", "intervalSeconds": -5},
              {"scheduleId": "fractional", "agentName": "A", "triggerType": "INTERVAL", "intervalSeconds": 1.5},
              "not an object"
            ]}""");

        assertEquals(11, entries.size());
        assertEquals("ok", valid(entries.get(0)).scheduleId());
        for (int i = 1; i < entries.size(); i++) {
            Entry.Invalid bad = invalid(entries.get(i));
            assertEquals(i, bad.index());
            assertTrue(bad.error().startsWith("schedules[" + i + "]"), bad.error());
        }
        assertEquals("no-agent", invalid(entries.get(2)).scheduleId(), "an invalid entry keeps its id when it has one");
        assertEquals(null, invalid(entries.get(1)).scheduleId());
        assertEquals(null, invalid(entries.get(10)).scheduleId());
        assertTrue(invalid(entries.get(4)).error().contains("HOURLY"));
        assertTrue(invalid(entries.get(7)).error().contains("greater than 0"));
    }

    @Test
    void aRepeatedScheduleIdMakesTheLaterEntryInvalid() throws Exception {
        List<Entry> entries = decode("""
            {"schemaVersion": 2, "schedules": [
              {"scheduleId": "dup", "agentName": "A", "triggerType": "INTERVAL", "intervalSeconds": 10},
              {"scheduleId": "dup", "agentName": "B", "triggerType": "INTERVAL", "intervalSeconds": 20}
            ]}""");

        assertEquals("A", valid(entries.get(0)).agentId().value());
        Entry.Invalid second = invalid(entries.get(1));
        assertEquals("dup", second.scheduleId());
        assertTrue(second.error().contains("already used by schedules[0]"), second.error());
    }

    @Test
    void aBadCronExpressionIsReportedOnImportNotAtRegistration() throws Exception {
        List<Entry> entries = decode("""
            {"schemaVersion": 2, "schedules": [
              {"scheduleId": "ok", "agentName": "A", "triggerType": "CRON", "cronExpression": "0 6 * * MON-FRI"},
              {"scheduleId": "short", "agentName": "A", "triggerType": "CRON", "cronExpression": "0 6 * *"},
              {"scheduleId": "range", "agentName": "A", "triggerType": "CRON", "cronExpression": "61 * * * *"}
            ]}""");

        valid(entries.get(0));
        assertTrue(invalid(entries.get(1)).error().startsWith("schedules[1].cronExpression: invalid cron expression"));
        assertTrue(invalid(entries.get(2)).error().startsWith("schedules[2].cronExpression: invalid cron expression"));
    }

    // ── the document as a whole ────────────────────────────────────────────────────────

    @Test
    void payloadsThatAreNotAScheduleDocumentAreRejected() {
        assertThrows(AgentSpecDocumentException.class, () -> decode("[1,2,3]"));
        assertThrows(AgentSpecDocumentException.class, () -> decode("{\"other\": []}"));
        assertThrows(AgentSpecDocumentException.class, () -> decode("{\"schedules\": {}}"));
        assertThrows(AgentSpecDocumentException.class, () -> AgentScheduleDocument.decode(null));
    }

    @Test
    void anEmptyScheduleListIsADocument() throws Exception {
        assertTrue(decode("{\"schemaVersion\": 2, \"schedules\": []}").isEmpty());
    }
}
