package io.ara.examples.planner;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The offline "model" of {@link PlannerExample}: it plans the small library, writes each file, reads the
 * package of {@code LoanService} from the note step 1 left, lists the files for the README, and gives up
 * on the style check the first time it fails.
 *
 * <p>It is a script, not a model: it answers the way a sensible model would, so the example prints the
 * same thing on every run without a key or a network. What it writes into the files it reads from the
 * prompt it was given (the notes of earlier steps, the results of its own tool calls), so the output
 * shows whether that information really reached the step.
 *
 * <p>The file has three parts: {@link #complete} tells the four phases of {@code plan_execute} apart
 * (planning, replanning, one step, the final answer); the script itself, what each phase says; and the
 * helpers that read the conversation and build the replies.
 *
 * <p>Stateless: every answer is computed from the messages it is given. How many tools a step has already
 * used is counted from the conversation, not stored, which is what makes it safe to call again after a replan.
 */
final class ScriptedPlannerLlm implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern BOOK_PATH = Pattern.compile("(src/main/java/\\S+?)/Book\\.java");

    // ── the four phases ──────────────────────────────────────────────────────

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
        String system = messages.get(0).content();
        if (system.contains("Produce a short numbered execution plan")) return text(PLAN);
        if (system.contains("Produce a revised plan")) return text(REPLAN);
        if (system.contains("You are executing one step of a plan")) {
            return step(currentGoal(messages), observationsOfThisStep(messages), allText(messages));
        }
        return text("The library module is in place: Book, Member and LoanService, a style check that "
                + "passed on the second try, and a README listing every file.");
    }

    // ── the script ───────────────────────────────────────────────────────────

    private static final String PLAN = """
            {"steps":[
              {"id":"s1","goal":"Create the Book record","dependsOn":[]},
              {"id":"s2","goal":"Create the Member record","dependsOn":[]},
              {"id":"s3","goal":"Create LoanService using Book and Member","dependsOn":["s1","s2"]},
              {"id":"s4","goal":"Run the style check","dependsOn":["s3"]},
              {"id":"s5","goal":"Write README.md listing every file","dependsOn":["s3"]}]}""";

    /** What the planner answers when the style check fails and it is asked for the rest of the plan. */
    private static final String REPLAN = """
            {"steps":[
              {"id":"s4","goal":"Retry the style check","dependsOn":[]},
              {"id":"s5","goal":"Write README.md listing every file","dependsOn":["s4"]}]}""";

    /**
     * The next thing a step does: a tool call, or its closing. {@code results} are the tool results the step
     * already has, so {@code results.size()} says how far along it is; {@code prompt} is everything it was shown.
     */
    private static LlmCompletion step(String goal, List<String> results, String prompt) {
        if (goal.contains("Book record")) return record("Book", "String isbn, String title", results);
        if (goal.contains("Member record")) return record("Member", "String id, String name", results);
        if (goal.contains("LoanService")) return loanService(prompt, results);
        if (goal.contains("style check")) return styleCheck(results);
        return readme(results);
    }

    private static LlmCompletion record(String name, String fields, List<String> results) {
        String path = "src/main/java/library/" + name + ".java";
        if (results.isEmpty()) {
            return call("write_file", Map.of("path", path,
                    "content", "package library;\n\npublic record " + name + "(" + fields + ") { }\n"));
        }
        return close("done", null, name + " is in " + path + " (package library)");
    }

    private static LlmCompletion loanService(String prompt, List<String> results) {
        // The package is not in the task: it is in the note step 1 left, and only there.
        Matcher found = BOOK_PATH.matcher(prompt);
        String directory = found.find() ? found.group(1) : "src/main/java/unknown";
        String pkg = directory.substring("src/main/java/".length()).replace('/', '.');
        String path = directory + "/LoanService.java";
        if (results.isEmpty()) {
            return call("write_file", Map.of("path", path, "content",
                    "package " + pkg + ";\n\nimport " + pkg + ".Book;\nimport " + pkg + ".Member;\n\n"
                            + "public class LoanService {\n    public String lend(Book book, Member member) {\n"
                            + "        return member.name() + \" borrows \" + book.title();\n    }\n}\n"));
        }
        return close("done", null, "LoanService is in " + path + " and imports Book and Member from " + pkg);
    }

    private static LlmCompletion styleCheck(List<String> results) {
        if (results.isEmpty()) return call("check_style", Map.of("scope", "workspace"));
        String last = results.get(results.size() - 1);
        return last.contains("unavailable")
                ? close("failed", "check_style is unavailable: " + last)
                : close("done", null, "style check passed with 0 issues");
    }

    private static LlmCompletion readme(List<String> results) {
        if (results.isEmpty()) return call("list_files", Map.of("directory", "."));
        if (results.size() == 1) {
            StringBuilder content = new StringBuilder("# Library\n\nFiles:\n");
            for (String file : results.get(0).split("\n")) content.append("- ").append(file).append('\n');
            return call("write_file", Map.of("path", "README.md", "content", content.toString()));
        }
        return close("done", null, "README.md lists every file");
    }

    // ── reading the conversation ─────────────────────────────────────────────

    private static String currentGoal(List<LlmMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            String content = messages.get(i).content();
            if (content != null && content.startsWith("Execute step ")) return content;
        }
        return "";
    }

    /** The tool results this step has already received: its own history follows the step instruction. */
    private static List<String> observationsOfThisStep(List<LlmMessage> messages) {
        int from = 0;
        for (int i = 0; i < messages.size(); i++) {
            String content = messages.get(i).content();
            if (content != null && content.startsWith("Execute step ")) from = i;
        }
        return messages.subList(from, messages.size()).stream()
                .map(LlmMessage::content)
                .filter(content -> content != null && content.startsWith("Observation: "))
                .map(content -> content.substring("Observation: ".length()))
                .toList();
    }

    private static String allText(List<LlmMessage> messages) {
        StringBuilder all = new StringBuilder();
        messages.forEach(message -> all.append(message.content()).append('\n'));
        return all.toString();
    }

    // ── building the replies ─────────────────────────────────────────────────

    private static LlmCompletion text(String content) {
        return new LlmCompletion(content, 20, 20, "stop", null);
    }

    private static LlmCompletion call(String toolId, Map<String, ?> arguments) {
        return new LlmCompletion("", 20, 20, "tool_calls", toolCall(toolId, arguments));
    }

    /** Closes the step with {@code close_step}; {@code reason} and {@code notes} may be absent. */
    private static LlmCompletion close(String status, String reason, String... notes) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("status", status);
        if (reason != null) arguments.put("reason", reason);
        if (notes.length > 0) arguments.put("notes", List.of(notes));
        return call("close_step", arguments);
    }

    private static String toolCall(String toolId, Map<String, ?> arguments) {
        try {
            return MAPPER.writeValueAsString(Map.of("tool_id", toolId, "arguments", arguments));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String providerId() {
        return "scripted-planner";
    }
}
