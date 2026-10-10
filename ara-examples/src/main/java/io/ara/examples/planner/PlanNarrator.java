package io.ara.examples.planner;

import com.fasterxml.jackson.databind.JsonNode;
import io.ara.core.agent.AgentEvent;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.ExecutionStep;
import io.ara.examples.support.Ansi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static io.ara.examples.planner.PlannerConsole.json;
import static io.ara.examples.planner.PlannerConsole.section;
import static io.ara.examples.planner.PlannerConsole.wrap;

/**
 * Explains a {@code plan_execute} run on the console while it happens, in plain words: what ARA
 * asks the model, what the model sees in each step, and what each outcome means.
 *
 * <p>The audience is someone running an ARA example for the first time. So a tool call is told as
 * an action ("writes OrderService.java, 12 lines"), not as its JSON arguments, and every event that
 * changes the course of the run (a step that asks to revise the plan, a step that fails) is followed
 * by what ARA does about it.
 *
 * <p>The strategy does not announce "step 3 started", so the narrator infers it: a step is open
 * until a {@code close_step} call ends it, and the plan is the last JSON plan seen, a replan
 * replacing the part of it that is left. A step closed with {@code done} or {@code revise} is
 * finished (its result is kept); one closed with {@code failed} is not, and runs again from the
 * revised plan.
 *
 * <p>Called on the agent's thread, one event at a time; not thread-safe, and it does not need to be.
 */
final class PlanNarrator implements Consumer<AgentEvent> {

    private static final String PAD = "      ";

    private List<String> goals = new ArrayList<>();
    private final Map<String, Integer> numberOfId = new HashMap<>();
    private int finished;
    private boolean stepAnnounced;
    private String lastTool = "";
    private boolean closedSincePlan;
    private int plans, replans, notes, failedSteps, revisedSteps, emptySteps, toolCalls, dependencies;

    @Override
    public void accept(AgentEvent event) {
        if (!(event instanceof AgentEvent.StepRecorded recorded)) return;
        ExecutionStep step = recorded.step();
        switch (step.type()) {
            case THOUGHT -> onThought(step.content());
            case TOOL_CALL -> onToolCall(step);
            case OBSERVATION -> onObservation(step.content());
            case FINAL_ANSWER -> onFinalAnswer(step.content());
            default -> { }
        }
    }

    // ── the plan ─────────────────────────────────────────────────────────────

    private void onThought(String text) {
        JsonNode plan = json(text);
        if (plan == null || !plan.has("steps")) return;      // a step's own words: too chatty to print
        if (plans == 0) firstPlan(plan); else revisedPlan(plan);
        stepAnnounced = false;
    }

    private void firstPlan(JsonNode plan) {
        plans++;
        closedSincePlan = false;
        section("1 · ARA asks the model for a plan");
        explain("ARA does not let the model start working straight away. It first asks for a plan: a list of "
                + "steps, written as JSON, where each step says which earlier steps it needs.");
        goals = new ArrayList<>();
        System.out.println();
        System.out.println(PAD.substring(2) + "The model planned " + plan.path("steps").size() + " steps:");
        printSteps(plan);
        section("2 · ARA runs the plan, one step at a time");
        explain("For each step the model sees only what it needs: the task, the plan, a short result of each "
                + "earlier step and the notes they left. Not the whole conversation so far. When the step is "
                + "over, the model must say how it went by calling close_step: done, failed, or done-but-the-"
                + "plan-must-change (revise).");
    }

    private void revisedPlan(JsonNode plan) {
        replans++;
        if (!closedSincePlan) {
            emptySteps++;
            int index = Math.min(finished, Math.max(goals.size() - 1, 0));
            System.out.println();
            System.out.println(Ansi.paint(Ansi.BOLD, "  ▶ Step " + (index + 1) + " of " + goals.size() + ": "
                    + (goals.isEmpty() ? "?" : goals.get(index))));
            System.out.println(PAD + Ansi.paint(Ansi.RED, "✘ the model did not manage to do this step"));
            explain("It never closed the step with a usable result (its replies were empty or were not a call "
                    + "to close_step), so there is nothing to keep. For ARA this is a failed step, and the "
                    + "agent allows a replan on failure.");
        }
        goals = new ArrayList<>(goals.subList(0, Math.min(finished, goals.size())));
        closedSincePlan = false;
        section("ARA asks the model for a new plan for the rest");
        explain((finished == 0
                ? "No step is finished yet, so the whole task is planned again:"
                : (finished == 1 ? "Step 1 is" : "Steps 1–" + finished + " are")
                        + " finished and stay as they are, with their results and notes. "
                        + "Only the work still to do is planned again:"));
        printSteps(plan);
        section("2 · ARA goes on with the new plan");
    }

    private void printSteps(JsonNode plan) {
        for (JsonNode node : plan.path("steps")) {
            goals.add(node.path("goal").asText("?"));
            numberOfId.put(node.path("id").asText(""), goals.size());
            List<String> needs = new ArrayList<>();
            boolean resolved = true;
            for (JsonNode entry : node.path("dependsOn")) {
                // Some models put several ids in one string ("s1, s2"): read them as the list they mean.
                for (String id : entry.asText().split("[,\\s]+")) {
                    if (id.isBlank()) continue;
                    Integer number = numberOfId.get(id);
                    if (number == null) resolved = false; else needs.add(String.valueOf(number));
                }
            }
            if (!needs.isEmpty() && replans == 0) dependencies++;
            String note = !resolved ? "   (the model named steps by ids that are not in its own plan)"
                    : needs.isEmpty() ? "" : "   (needs step " + String.join(", ", needs) + ")";
            System.out.printf("%s%2d. %s%s%n", PAD, goals.size(), goals.get(goals.size() - 1),
                    note.isEmpty() ? "" : Ansi.paint(resolved ? Ansi.GREY : Ansi.YELLOW, note));
        }
    }

    // ── one step ─────────────────────────────────────────────────────────────

    private void onToolCall(ExecutionStep step) {
        announceStep();
        if ("close_step".equals(step.toolId())) {
            onClose(step.arguments());
            return;
        }
        toolCalls++;
        lastTool = step.toolId();
        System.out.println(PAD + Ansi.paint(Ansi.CYAN, action(step.toolId(), json(step.arguments()))));
    }

    /** A tool call told as what it does. */
    private static String action(String tool, JsonNode args) {
        String path = args == null ? "" : args.path("path").asText("");
        return switch (tool) {
            case "read_file" -> "reads   " + path;
            case "write_file" -> "writes  " + path + "  (" + plural(lines(args.path("content").asText("")), "line") + ")";
            case "list_files" -> "lists the files in the workspace";
            default -> "runs    " + tool;
        };
    }

    private static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    private static int lines(String content) {
        return content.isEmpty() ? 0 : content.split("\n", -1).length - (content.endsWith("\n") ? 1 : 0);
    }

    private void onObservation(String text) {
        if (text == null || lastTool.isEmpty()) return;
        boolean failed = text.contains("failed") || text.contains("unavailable") || text.contains("error")
                || text.contains("incompatible types") || text.contains("cannot find symbol");
        boolean aCheck = !lastTool.equals("read_file") && !lastTool.equals("write_file") && !lastTool.equals("list_files");
        if (failed) {
            System.out.println(PAD + Ansi.paint(Ansi.RED, "✘ " + lastTool + " answers: ") + firstLine(text));
        } else if (aCheck) {
            System.out.println(PAD + Ansi.paint(Ansi.GREEN, "✔ " + lastTool + " answers: ") + firstLine(text));
        }
        lastTool = "";
    }

    private void onClose(String arguments) {
        JsonNode closing = json(arguments);
        String status = closing == null ? "?" : closing.path("status").asText("?");
        String reason = closing == null ? "" : closing.path("reason").asText("");
        closedSincePlan = true;
        List<String> stepNotes = new ArrayList<>();
        if (closing != null) closing.path("notes").forEach(note -> stepNotes.add(note.asText()));
        notes += stepNotes.size();

        switch (status) {
            case "done" -> {
                finished++;
                System.out.println(PAD + Ansi.paint(Ansi.GREEN, "✔ the model closes the step: done"));
            }
            case "revise" -> {
                finished++;
                revisedSteps++;
                System.out.println(PAD + Ansi.paint(Ansi.YELLOW, "↻ the model closes the step as done, but says the "
                        + "rest of the plan must change:"));
                System.out.println(wrap("\"" + reason + "\"", PAD + "  "));
            }
            case "failed" -> {
                failedSteps++;
                System.out.println(PAD + Ansi.paint(Ansi.RED, "✘ the model closes the step: failed, because"));
                System.out.println(wrap("\"" + reason + "\"", PAD + "  "));
            }
            default -> System.out.println(PAD + "the model closes the step: " + arguments);
        }
        if (!stepNotes.isEmpty()) {
            System.out.println(PAD + Ansi.paint(Ansi.MAGENTA, "it leaves " + stepNotes.size() + " note(s) that every "
                    + "later step will see, word for word:"));
            stepNotes.forEach(note -> System.out.println(wrap("• " + note, PAD + "  ")));
        }
        switch (status) {
            case "revise" -> explain("So ARA keeps this step's work and asks the model to plan the remaining "
                    + "steps again.");
            case "failed" -> explain("ARA does not give up and does not start over: this agent allows a replan on "
                    + "failure, so it keeps the finished steps and asks for a new plan for the rest.");
            default -> { }
        }
        stepAnnounced = false;
    }

    private void announceStep() {
        if (stepAnnounced) return;
        stepAnnounced = true;
        int index = Math.min(finished, Math.max(goals.size() - 1, 0));
        String goal = goals.isEmpty() ? "?" : goals.get(index);
        System.out.println();
        System.out.println(Ansi.paint(Ansi.BOLD, "  ▶ Step " + (index + 1) + " of " + goals.size() + ": " + goal));
    }

    // ── the end ──────────────────────────────────────────────────────────────

    private void onFinalAnswer(String answer) {
        section("3 · ARA asks the model for the final answer");
        explain("Every step is done. ARA gives the model the results and the notes of all the steps, and asks "
                + "for one answer to the original task:");
        System.out.println(wrap(answer == null ? "" : answer, PAD));
    }

    /** What happened, in numbers and in words. */
    void summary(AgentResponse response, int maxRoundsPerStep, int maxIterations) {
        section(response.isSuccess() ? "the run in numbers" : "the run failed");
        if (!response.isSuccess()) {
            System.out.println(wrap(response.failureReason(), "  "));
        }
        System.out.printf("  %d steps finished · %d failed or empty and replanned · %d asked to revise the plan%n",
                finished, failedSteps + emptySteps, revisedSteps);
        System.out.printf("  %d plan + %d replan(s) · %d tool calls · %d notes passed on · %d tokens%n",
                plans, replans, toolCalls, notes, response.totalTokens());

        section("what plan_execute gave you");
        point("A plan before any work, as data: " + dependencies + " step(s) of the first plan said which steps they "
                + "need, so the plan can be shown, checked and followed.");
        point("Small, focused prompts: each step saw the task, the plan and short results — not a transcript "
                + "that grows with every tool call.");
        point(notes + " note(s) carried exact facts (paths, names, signatures) from the step that knew them to "
                + "the steps that needed them.");
        point(replans > 0
                ? "The plan changed " + replans + " time(s) while running, and no finished step was ever redone."
                : "A replan was ready if a step failed or asked for it; none did this time.");
        point("Limits that keep a run from going on forever: " + maxRoundsPerStep + " rounds per step, "
                + maxIterations + " model calls in all, and a deadline.");
    }

    // ── text ─────────────────────────────────────────────────────────────────

    private static void explain(String text) {
        System.out.println(Ansi.paint(Ansi.GREY, wrap(text, "  ")));
    }

    private static void point(String text) {
        System.out.println(wrap("• " + text, "  "));
    }

    private static String firstLine(String text) {
        String line = text.strip().split("\n", 2)[0];
        return line.length() <= 120 ? line : line.substring(0, 117) + "...";
    }
}
