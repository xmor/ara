package io.ara.runtime.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the planner's reply into the list of step goals {@link PlanExecuteStrategy} executes.
 *
 * <p>Two shapes are accepted, tried in this order:
 * <ol>
 *   <li>a JSON object {@code {"steps":[{"id":"s1","goal":"...","dependsOn":[]}, ...]}} — what the
 *       planning prompt asks for, and what a client with native structured output is forced to
 *       produce;</li>
 *   <li>a numbered or bulleted list — what the strategy asked for before the plan was JSON, and
 *       what a model that ignores the JSON instruction usually writes anyway. It is a legitimate
 *       plan in another format, so it is accepted rather than rejected.</li>
 * </ol>
 *
 * <p>{@code id} and {@code dependsOn} are <b>deliberately not read</b>. Steps run in the order
 * they are written and each sees everything before it, exactly as before; the fields are asked
 * for now only so that the plans the models already write carry them, ready for the day steps
 * may run in parallel. Reading a field that changes nothing would create cases (a dependency
 * cycle, a dangling id) that nobody asked this class to handle. The plan text itself stays in
 * the execution trace, which is where the validity of those fields can be measured.
 *
 * <p>An empty result means "unreadable": the reply is neither shape, or has no steps. Deciding
 * what to do about that is the caller's job — this class never invents a plan.
 *
 * <p>Stateless and thread-safe.
 */
final class PlanReader {

    /** Name given to the schema on providers that want one (OpenAI's {@code json_schema.name}). */
    static final String SCHEMA_NAME = "execution_plan";

    /**
     * JSON Schema of the plan, sent as the provider-native {@code response_format} when the client
     * supports it. All three step fields are listed as required: providers that enforce strict
     * schemas demand it, and a model that must always write {@code dependsOn} (an empty list when
     * the step needs nothing) is exactly what a later parallel mode wants to find.
     */
    static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "steps": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "id":        { "type": "string" },
                      "goal":      { "type": "string" },
                      "dependsOn": { "type": "array", "items": { "type": "string" } }
                    },
                    "required": ["id", "goal", "dependsOn"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["steps"],
              "additionalProperties": false
            }""";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern LIST_ITEM = Pattern.compile(
            "(?m)^\\s*(?:\\d+[\\.)]|[-*])\\s+(.+?)\\s*$");

    private PlanReader() {
    }

    /** The step goals in the reply, in order; empty when the reply holds no readable plan. */
    static List<String> read(String reply) {
        if (reply == null || reply.isBlank()) {
            return List.of();
        }
        List<String> fromJson = readJson(reply);
        return fromJson != null ? fromJson : readList(reply);
    }

    /**
     * The goals of a JSON plan, or {@code null} when the reply is not a JSON plan at all (so the
     * caller can try the list shape). An empty list means "was a JSON plan, with no usable step".
     *
     * <p>The JSON is taken from the first {@code '{'} to the last {@code '}'}: models routinely
     * wrap it in a markdown fence or a sentence, and a strict whole-reply parse would throw
     * away a plan that is perfectly clear.
     */
    private static List<String> readJson(String reply) {
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(reply.substring(start, end + 1));
        } catch (Exception notJson) {
            // Not JSON after all — a numbered item that merely contained braces. The list
            // reader decides; there is nothing to report here.
            return null;
        }
        JsonNode steps = root.path("steps");
        if (!steps.isArray()) {
            return null;
        }
        List<String> goals = new ArrayList<>();
        for (JsonNode step : steps) {
            String goal = step.isObject() ? step.path("goal").asText("") : step.asText("");
            if (!goal.isBlank()) {
                goals.add(goal.strip());
            }
        }
        return goals;
    }

    private static List<String> readList(String reply) {
        List<String> goals = new ArrayList<>();
        Matcher matcher = LIST_ITEM.matcher(reply);
        while (matcher.find()) {
            String goal = matcher.group(1).strip();
            if (!goal.isBlank()) {
                goals.add(goal);
            }
        }
        return goals;
    }
}
