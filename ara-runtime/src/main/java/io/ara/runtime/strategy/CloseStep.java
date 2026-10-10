package io.ara.runtime.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The reserved {@code close_step} tool: how a step of a {@link PlanExecuteStrategy} plan says
 * how it ended, and what the next steps must know.
 *
 * <p>Before it, a step ended by writing {@code STEP_DONE} in its text, which left no way to say
 * "I could not do this", "the plan needs changing", or "I created {@code Person} in
 * {@code io/github/xmor/ara/Person.java}" — the only channel between steps was the result text,
 * cut to 600 characters, so an exact fact written at the end of a long answer was lost.
 *
 * <p>Why a tool and not a JSON block at the end of the text: calling a tool is what models do
 * most reliably, and the strategy already reads tool calls from native and inline replies. A
 * block that must be complete, last and alone in the reply is the shape that small models get
 * wrong. The tool is shown to the step's model only; it never enters the agent's tool registry
 * and is never executed — the strategy intercepts the call. {@link #TOOL}'s {@code execute}
 * exists only to satisfy the interface, and says so if anything ever reaches it.
 *
 * <p>Stateless and thread-safe; everything here is immutable.
 */
final class CloseStep {

    static final String TOOL_ID = "close_step";

    /** At most this many notes per step. */
    static final int MAX_NOTES = 5;

    /** At most this many characters per note. */
    static final int MAX_NOTE_CHARS = 300;

    /** How a step ended. */
    enum Status { DONE, FAILED, REVISE }

    /**
     * An accepted closing.
     *
     * @param reason why, for {@link Status#FAILED} and {@link Status#REVISE}; {@code null} for done
     * @param result the step's result as the model wrote it, or {@code null} to use its last text
     * @param notes  facts for the later steps; never null, already trimmed
     */
    record Closing(Status status, String reason, String result, List<String> notes) { }

    /** The outcome of reading a {@code close_step} call. */
    sealed interface Parsed {
        /** The call is valid. */
        record Accepted(Closing closing) implements Parsed { }

        /** The call is refused; {@code correction} is sent back to the model and the step stays open. */
        record Refused(String correction) implements Parsed { }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "status": { "type": "string", "enum": ["done", "failed", "revise"] },
                "reason": { "type": "string",
                            "description": "Required for failed and revise: what went wrong, or what must change" },
                "result": { "type": "string",
                            "description": "Optional: the step's result, if different from your last message" },
                "notes":  { "type": "array", "maxItems": %d,
                            "items": { "type": "string", "maxLength": %d },
                            "description": "Short facts later steps need and cannot guess: file paths, names you chose, decisions" }
              },
              "required": ["status"]
            }""".formatted(MAX_NOTES, MAX_NOTE_CHARS);

    /** What the step's model is shown; see the class comment for why it is not in the registry. */
    static final AraTool TOOL = new AraTool() {
        @Override public String toolId() { return TOOL_ID; }

        @Override public String description() {
            return "Closes the current plan step. Call it once, when the step is finished or cannot be "
                    + "finished. status=done: the step is complete. status=failed: it could not be done "
                    + "(give a reason). status=revise: it is done but the remaining steps need changing "
                    + "(give a reason).";
        }

        @Override public String argumentSchema() { return SCHEMA; }

        @Override public ToolResult execute(String argumentJson) {
            return ToolResult.failure(TOOL_ID, TOOL_ID + " is handled by the plan_execute strategy "
                    + "and must never be executed through a tool registry");
        }
    };

    private CloseStep() {
    }

    static boolean isCall(ToolCallParser.ToolCallRequest call) {
        return TOOL_ID.equals(call.toolId());
    }

    /** Reads the arguments of a {@code close_step} call, or says what to correct. */
    static Parsed parse(String argumentJson) {
        JsonNode arguments;
        try {
            arguments = MAPPER.readTree(argumentJson == null ? "" : argumentJson);
        } catch (Exception notJson) {
            return refuse("the arguments are not valid JSON");
        }
        if (arguments == null || !arguments.isObject()) {
            return refuse("the arguments must be a JSON object");
        }
        Status status = statusOf(arguments.path("status").asText(""));
        if (status == null) {
            return refuse("status must be one of: done, failed, revise");
        }
        String reason = blankToNull(arguments.path("reason").asText(""));
        if (status != Status.DONE && reason == null) {
            return refuse("status " + status.name().toLowerCase(Locale.ROOT) + " needs a reason");
        }
        List<String> notes = new ArrayList<>();
        String notesProblem = collectNotes(arguments.path("notes"), notes);
        if (notesProblem != null) {
            return refuse(notesProblem);
        }
        String result = blankToNull(arguments.path("result").asText(""));
        return new Parsed.Accepted(new Closing(status, reason, result, List.copyOf(notes)));
    }

    /**
     * Fills {@code into} with the non-blank notes and returns {@code null}, or returns what is
     * wrong. Too many or too long is refused rather than cut: a note is an exact fact, and a fact
     * cut in half is worse than none. The model is told the limit and can shorten it in place.
     */
    private static String collectNotes(JsonNode notes, List<String> into) {
        if (notes.isMissingNode() || notes.isNull()) {
            return null;
        }
        if (!notes.isArray()) {
            return "notes must be an array of strings";
        }
        for (JsonNode node : notes) {
            String note = node.asText("").strip();
            if (note.isEmpty()) {
                continue;
            }
            if (note.length() > MAX_NOTE_CHARS) {
                return "a note is " + note.length() + " characters; each note must be at most "
                        + MAX_NOTE_CHARS + " — shorten it";
            }
            into.add(note);
        }
        if (into.size() > MAX_NOTES) {
            return "there are " + into.size() + " notes; at most " + MAX_NOTES + " are allowed — "
                    + "keep only what the later steps cannot do without";
        }
        return null;
    }

    /** The notes as a block to put in a prompt, or {@code null} when there are none. */
    static String formatNotes(List<String> notes) {
        if (notes.isEmpty()) {
            return null;
        }
        StringBuilder block = new StringBuilder("Notes from earlier steps (exact facts — rely on them):");
        notes.forEach(note -> block.append("\n  - ").append(note));
        return block.toString();
    }

    private static Parsed refuse(String problem) {
        return new Parsed.Refused("close_step was refused: " + problem
                + ". The step is still open — call close_step again with corrected arguments.");
    }

    private static Status statusOf(String text) {
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "done" -> Status.DONE;
            case "failed" -> Status.FAILED;
            case "revise" -> Status.REVISE;
            default -> null;
        };
    }

    private static String blankToNull(String text) {
        return text.isBlank() ? null : text.strip();
    }
}
