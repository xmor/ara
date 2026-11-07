package io.ara.runtime.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ara.core.agent.AgentSchedule;
import io.ara.core.common.AgentId;
import io.ara.core.spec.DocumentFormat;
import io.ara.runtime.scheduler.LocalAgentScheduler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.ara.runtime.spec.DocumentFields.guard;

/**
 * The schedule document: a format-independent {@link JsonNode} tree that carries a snapshot of
 * the {@link AgentSchedule}s registered on a scheduler, and the only place that maps it to and
 * from them. Use it to back up and restore schedules, or to move them between environments.
 *
 * <pre>{@code
 * { "schemaVersion": 2,
 *   "exportedAt": "2026-10-21T10:15:30+02:00",
 *   "schedules": [
 *     { "scheduleId": "daily-report", "agentName": "Reporter",
 *       "inputTemplate": "Genera il report", "triggerType": "INTERVAL",
 *       "intervalSeconds": 3600, "active": true },
 *     { "scheduleId": "nightly", "agentName": "Reporter",
 *       "inputTemplate": "...", "triggerType": "CRON",
 *       "cronExpression": "0 6 * * *", "active": false } ] }
 * }</pre>
 *
 * <p>It follows the same model as {@link AgentSpecDocument}: the tree is independent of the
 * bytes ({@link DocumentFormat} carries it), the mapping is hand-written with no reflection,
 * and a mistake is reported with the path of the field ({@code schedules[3].intervalSeconds}).
 * {@code agentName} is {@link AgentSchedule#agentId()}'s value: the scheduler addresses agents
 * by name.
 *
 * <p><b>Versions.</b> The writer emits {@link #SCHEMA_VERSION} 2. Version 2 is strict, as
 * {@link AgentSpecDocument}'s version 1 is: an unknown field is an error, types are not
 * coerced ({@code "active": "false"} is an error, not {@code true}), and fields that contradict
 * the trigger ({@code cronExpression} on an {@code INTERVAL}) are an error. Version 1 is what the
 * first export wrote, under the key {@code version} and with no {@code schemaVersion}; it is
 * still read with the rules it was written under — unknown fields ignored, {@code active}
 * read leniently — because those files exist and were valid. A file with neither key is read as
 * version 1. A version above {@link #SCHEMA_VERSION} is rejected rather than guessed at.
 *
 * <p><b>One bad entry does not sink the others.</b> A backup is restored to get back as much as
 * possible, so {@link #decode(JsonNode)} returns one {@link Entry} per element of
 * {@code schedules}: a {@link Entry.Valid} or an {@link Entry.Invalid} carrying the reason, the
 * index and the {@code scheduleId} when it could be read. Only a problem with the document as a
 * whole (not an object, no {@code schedules} array, a future version, an unknown root field in
 * version 2) throws. A repeated {@code scheduleId} makes the later entry invalid, since
 * registering both would silently replace the first. A cron expression is validated here with
 * {@link LocalAgentScheduler.CronEvaluator}, so a bad one is reported on import instead of at
 * registration.
 *
 * <p>Stateless; thread-safe.
 */
public final class AgentScheduleDocument {

    /** The newest document version this class reads and the one it writes. */
    public static final int SCHEMA_VERSION = 2;

    /** The version whose files are read with the rules they were written under. */
    private static final int LENIENT_VERSION = 1;

    private enum TriggerType { INTERVAL, CRON }

    /** One element of the {@code schedules} array, decoded on its own. */
    public sealed interface Entry {

        /** Position in the {@code schedules} array, from 0. */
        int index();

        /** A schedule that decoded cleanly. */
        record Valid(int index, AgentSchedule schedule) implements Entry {}

        /**
         * An element that cannot be turned into a schedule.
         *
         * @param scheduleId the element's {@code scheduleId} when it had a readable one, else {@code null}
         * @param error      what is wrong, starting with the field path ({@code schedules[2].triggerType: ...})
         */
        record Invalid(int index, String scheduleId, String error) implements Entry {}
    }

    private AgentScheduleDocument() {}

    // ── Encode ─────────────────────────────────────────────────────────────────────────

    /** Same as {@link #encode(List, Clock)}, stamped with the system clock. */
    public static ObjectNode encode(List<AgentSchedule> schedules) {
        return encode(schedules, Clock.systemDefaultZone());
    }

    /**
     * The document for {@code schedules}, in list order. {@code exportedAt} is the only value
     * that depends on the moment, and comes from {@code clock}, so two exports of the same list
     * at the same instant are identical.
     *
     * @throws IllegalArgumentException if an interval is not a whole number of seconds: the
     *         document has no way to say so, and rounding would change when the schedule fires
     */
    public static ObjectNode encode(List<AgentSchedule> schedules, Clock clock) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("exportedAt", OffsetDateTime.now(clock).toString());
        ArrayNode array = root.putArray("schedules");
        for (AgentSchedule schedule : schedules) {
            ObjectNode node = array.addObject();
            node.put("scheduleId", schedule.scheduleId());
            node.put("agentName", schedule.agentId().value());
            node.put("inputTemplate", schedule.inputTemplate());
            switch (schedule.trigger()) {
                case AgentSchedule.Trigger.Interval interval -> {
                    if (interval.every().getNano() != 0) {
                        throw new IllegalArgumentException("schedule '" + schedule.scheduleId() + "': interval "
                                + interval.every() + " is not a whole number of seconds");
                    }
                    node.put("triggerType", TriggerType.INTERVAL.name());
                    node.put("intervalSeconds", interval.every().toSeconds());
                }
                case AgentSchedule.Trigger.Cron cron -> {
                    node.put("triggerType", TriggerType.CRON.name());
                    node.put("cronExpression", cron.expression());
                }
            }
            if (schedule.maxDuration() != null) {
                // Optional and additive: an element without it reads as it always did at version 2.
                node.put("maxDurationMs", schedule.maxDuration().toMillis());
            }
            node.put("active", schedule.active());
        }
        return root;
    }

    /** Encodes {@code schedules} and writes them to {@code out} (left open) in {@code format}. */
    public static void write(DocumentFormat format, List<AgentSchedule> schedules, OutputStream out)
            throws IOException {
        format.write(encode(schedules), out);
    }

    // ── Decode ─────────────────────────────────────────────────────────────────────────

    /** Reads {@code in} (left open) in {@code format} and decodes it. */
    public static List<Entry> read(DocumentFormat format, InputStream in) throws IOException {
        return decode(format.read(in));
    }

    /**
     * Decodes every element of the document's {@code schedules} array on its own, in order.
     *
     * @throws AgentSpecDocumentException if the document as a whole is unusable (see the class
     *         comment); a problem confined to one schedule is an {@link Entry.Invalid} instead
     */
    public static List<Entry> decode(JsonNode root) {
        DocumentFields document = new DocumentFields(root, "");
        int version = version(document);
        boolean strict = version > LENIENT_VERSION;

        JsonNode schedules = document.raw("schedules");
        if (schedules == null || !schedules.isArray()) {
            throw new AgentSpecDocumentException("schedules", "is required and must be an array");
        }
        if (strict) {
            document.string("exportedAt");
            document.finish();
        }

        List<Entry> entries = new ArrayList<>(schedules.size());
        Map<String, Integer> firstIndexOf = new HashMap<>();
        for (int index = 0; index < schedules.size(); index++) {
            entries.add(decodeEntry(schedules.get(index), index, strict, firstIndexOf));
        }
        return entries;
    }

    private static int version(DocumentFields document) {
        Integer declared = document.integer("schemaVersion");
        if (declared == null) {
            // The first export called it "version"; it is only read when the new key is absent.
            declared = document.integer("version");
        }
        int version = declared == null ? LENIENT_VERSION : declared;
        if (version < 1) {
            throw new AgentSpecDocumentException("schemaVersion", "must be at least 1, got " + version);
        }
        if (version > SCHEMA_VERSION) {
            throw new AgentSpecDocumentException("schemaVersion", "is " + version
                    + ", newer than the newest this reader understands (" + SCHEMA_VERSION + ")");
        }
        return version;
    }

    private static Entry decodeEntry(JsonNode element, int index, boolean strict,
                                     Map<String, Integer> firstIndexOf) {
        String scheduleId = readableScheduleId(element);
        try {
            AgentSchedule schedule = decodeSchedule(
                    new DocumentFields(element, "schedules[" + index + "]"), strict);
            Integer first = firstIndexOf.putIfAbsent(schedule.scheduleId(), index);
            if (first != null) {
                return new Entry.Invalid(index, scheduleId, "schedules[" + index + "].scheduleId: '"
                        + schedule.scheduleId() + "' is already used by schedules[" + first + "]");
            }
            return new Entry.Valid(index, schedule);
        } catch (AgentSpecDocumentException e) {
            return new Entry.Invalid(index, scheduleId, e.getMessage());
        }
    }

    private static AgentSchedule decodeSchedule(DocumentFields entry, boolean strict) {
        String scheduleId = nonBlank(entry, "scheduleId");
        String agentName = nonBlank(entry, "agentName");
        String inputTemplate = entry.string("inputTemplate");
        TriggerType type = entry.enumValue("triggerType", TriggerType.class);
        if (type == null) {
            throw new AgentSpecDocumentException(entry.at("triggerType"), "is required");
        }
        Long intervalSeconds = entry.longValue("intervalSeconds");
        String cronExpression = entry.string("cronExpression");
        Long maxDurationMs = entry.longValue("maxDurationMs");
        Boolean active = strict ? entry.bool("active") : lenientBoolean(entry.raw("active"));

        AgentSchedule.Builder builder = AgentSchedule.builder()
                .scheduleId(scheduleId)
                .agentId(AgentId.of(agentName));
        DocumentFields.set(builder::withInput, inputTemplate);
        DocumentFields.set(builder::maxDurationMs, maxDurationMs);
        DocumentFields.set(builder::active, active);

        switch (type) {
            case INTERVAL -> {
                if (intervalSeconds == null) {
                    throw new AgentSpecDocumentException(entry.at("intervalSeconds"),
                            "is required when triggerType is INTERVAL");
                }
                if (intervalSeconds <= 0) {
                    throw new AgentSpecDocumentException(entry.at("intervalSeconds"), "must be greater than 0");
                }
                if (strict && cronExpression != null) {
                    throw new AgentSpecDocumentException(entry.at("cronExpression"),
                            "is not allowed when triggerType is INTERVAL");
                }
                builder.every(Duration.ofSeconds(intervalSeconds));
            }
            case CRON -> {
                if (cronExpression == null) {
                    throw new AgentSpecDocumentException(entry.at("cronExpression"),
                            "is required when triggerType is CRON");
                }
                if (strict && intervalSeconds != null) {
                    throw new AgentSpecDocumentException(entry.at("intervalSeconds"),
                            "is not allowed when triggerType is CRON");
                }
                validateCron(entry.at("cronExpression"), cronExpression);
                builder.cron(cronExpression);
            }
        }
        if (strict) {
            entry.finish();
        }
        return guard(entry.path(), builder::build);
    }

    private static String nonBlank(DocumentFields entry, String field) {
        String value = entry.requireString(field);
        if (value.isBlank()) {
            throw new AgentSpecDocumentException(entry.at(field), "must not be blank");
        }
        return value;
    }

    /** What version 1 files were read with: anything that is not recognisably {@code false} is {@code true}. */
    private static Boolean lenientBoolean(JsonNode value) {
        return value == null ? null : value.asBoolean(true);
    }

    private static void validateCron(String path, String expression) {
        try {
            LocalAgentScheduler.CronEvaluator.validate(expression);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new AgentSpecDocumentException(path, "invalid cron expression: " + e.getMessage(), e);
        }
    }

    /** The {@code scheduleId} of an element, if it is an object with a textual one; used only to label an error. */
    private static String readableScheduleId(JsonNode element) {
        JsonNode id = element == null ? null : element.get("scheduleId");
        return id != null && id.isTextual() && !id.asText().isBlank() ? id.asText() : null;
    }
}
