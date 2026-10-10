package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.ToolCallEntry;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.strategy.PlanPhaseLlmClient.Call;
import io.ara.runtime.strategy.PlanPhaseLlmClient.Phase;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a step of {@code plan_execute} ends: through the reserved {@code close_step} tool, with a
 * status and notes for the steps that follow. The reading of the tool's arguments is covered by
 * {@code CloseStepTest}; these tests cover what the strategy does with each outcome.
 */
class PlanExecuteStrategyCloseStepTest {

    private static final String TWO_STEPS = "1. Create Person\n2. Create Address";
    private static final String PERSON_NOTE = "Person is in io/github/xmor/ara/Person.java";

    // ── scripted replies ─────────────────────────────────────────────────────

    /** A reply that closes the step with the given arguments, after writing {@code text}. */
    private static LlmCompletion closing(String text, String argumentsJson) {
        return new LlmCompletion(text, 5, 5, "tool_calls",
                "{\"tool_id\":\"close_step\",\"arguments\":" + argumentsJson + "}");
    }

    private static LlmCompletion closing(String argumentsJson) {
        return closing("", argumentsJson);
    }

    private static String done(String... notes) {
        List<String> quoted = new ArrayList<>();
        for (String note : notes) quoted.add("\"" + note + "\"");
        return "{\"status\":\"done\",\"notes\":[" + String.join(",", quoted) + "]}";
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** Counts how many times it ran, to prove a sibling call of close_step still happens. */
    private static final class CountingTool implements AraTool {
        int runs;

        @Override public String toolId() { return "echo"; }
        @Override public String description() { return "echoes"; }
        @Override public String argumentSchema() { return "{}"; }
        @Override public ToolResult execute(String argumentJson) {
            runs++;
            return ToolResult.success(toolId(), "echoed");
        }
    }

    private static ToolRegistry registryOf(AraTool... tools) {
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(tools); }
            @Override public Optional<AraTool> findById(String id) {
                return List.of(tools).stream().filter(tool -> tool.toolId().equals(id)).findFirst();
            }
            @Override public ToolResult execute(String toolId, String argumentJson) {
                return findById(toolId).map(tool -> tool.execute(argumentJson))
                        .orElseGet(() -> ToolResult.failure(toolId, "unknown tool"));
            }
        };
    }

    private static MemoryManager seededMemory() {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are a helpful AI agent.");
        memory.appendToWorkingMemory("user", "build the model");
        return memory;
    }

    private static AgentConfig config(String replanPolicy) {
        return AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().transportId("stub").build())
                .executionTimeout(Duration.ofSeconds(30))
                .maxIterations(30)
                .strategyConfig(new StrategyConfig.PlanExecute(replanPolicy, 8, 3))
                .build();
    }

    private static ExecutionResult run(PlanPhaseLlmClient llm, String replanPolicy, ToolRegistry tools) {
        return new PlanExecuteStrategy().execute(
                AgentTask.of("build the model"), llm, seededMemory(), tools, config(replanPolicy));
    }

    private static ExecutionResult run(PlanPhaseLlmClient llm, String replanPolicy) {
        return run(llm, replanPolicy, registryOf());
    }

    private static boolean anyMessageContains(Call call, String fragment) {
        return call.messages().stream().anyMatch(message -> message.content().contains(fragment));
    }

    // ── done, and the notes it leaves ────────────────────────────────────────

    @Test
    void aStepClosedWithDone_endsInOneCall_andItsNotesReachTheNextStep() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEPS)
                .stepCompletions(closing("Created Person.", done(PERSON_NOTE)), closing("Created Address.", done()));

        ExecutionResult result = run(llm, "never");

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        List<Call> steps = llm.callsIn(Phase.STEP);
        assertEquals(2, steps.size(), "closing a step ends it: one call per step");
        assertFalse(anyMessageContains(steps.get(0), PERSON_NOTE));
        assertTrue(anyMessageContains(steps.get(1), PERSON_NOTE), "the next step must see the note, whole");
    }

    @Test
    void notesAreHandedOverWhole_evenWhenTheResultThatCarriedThemIsLong() {
        // The result of a step is shortened to 600 characters for the steps after it; a note is not.
        String longResult = "x".repeat(2_000);
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEPS)
                .stepCompletions(closing(longResult, done(PERSON_NOTE)), closing("ok", done()));

        run(llm, "never");

        Call second = llm.callsIn(Phase.STEP).get(1);
        assertTrue(anyMessageContains(second, PERSON_NOTE));
        assertFalse(anyMessageContains(second, longResult), "the long result is still shortened");
    }

    @Test
    void notesAccumulateInOrder_acrossSteps() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("1. A\n2. B\n3. C")
                .stepCompletions(closing(done("first fact")), closing(done("second fact")), closing(done()));

        run(llm, "never");

        Call third = llm.callsIn(Phase.STEP).get(2);
        String block = third.messages().stream().map(LlmMessage::content)
                .filter(content -> content.startsWith("Notes from earlier steps")).findFirst().orElseThrow();
        assertTrue(block.indexOf("first fact") < block.indexOf("second fact"));
    }

    @Test
    void theNotesReachTheSynthesis() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEPS)
                .stepCompletions(closing("a", done(PERSON_NOTE)), closing("b", done()));

        run(llm, "never");

        assertTrue(anyMessageContains(llm.callsIn(Phase.SYNTHESIS).get(0), PERSON_NOTE));
    }

    @Test
    void aStepThatActedThroughToolsAndWroteNothing_isStillADoneStep() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEPS)
                .stepCompletions(closing(done(PERSON_NOTE)), closing(done()));

        ExecutionResult result = run(llm, "never");

        assertTrue(result.isSuccess(), "done decides the outcome, not the presence of text");
        assertTrue(anyMessageContains(llm.callsIn(Phase.STEP).get(1), "(completed, no written result)"));
    }

    @Test
    void theResultArgument_winsOverTheTextTheModelWrote() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEPS)
                .stepCompletions(
                        closing("chatter that is not the result", "{\"status\":\"done\",\"result\":\"the real result\"}"),
                        closing(done()));

        run(llm, "never");

        Call second = llm.callsIn(Phase.STEP).get(1);
        assertTrue(anyMessageContains(second, "the real result"));
        assertFalse(anyMessageContains(second, "chatter that is not the result"));
    }

    @Test
    void withoutAResultArgument_theTextOfTheClosingMessageIsTheResult() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEPS)
                .stepCompletions(closing("Created Person with two fields.", done()), closing(done()));

        run(llm, "never");

        assertTrue(anyMessageContains(llm.callsIn(Phase.STEP).get(1), "Created Person with two fields."));
    }

    @Test
    void aStepClosedTheOldWay_withStepDoneInTheText_stillWorks_withoutNotes() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEPS)
                .stepReplies("Made it. STEP_DONE");

        ExecutionResult result = run(llm, "never");

        assertTrue(result.isSuccess());
        assertFalse(anyMessageContains(llm.callsIn(Phase.STEP).get(1), "Notes from earlier steps"));
    }

    // ── failed ───────────────────────────────────────────────────────────────

    @Test
    void failed_withReplanNever_failsTheTaskWithTheModelsReason() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEPS)
                .stepCompletions(closing("{\"status\":\"failed\",\"reason\":\"the disk is read-only\"}"));

        ExecutionResult result = run(llm, "never");

        assertFalse(result.isSuccess());
        String reason = result.failureReasonOpt().orElse("");
        assertTrue(reason.contains("reported that it failed: the disk is read-only"), reason);
        assertEquals(1, llm.callsIn(Phase.STEP).size());
    }

    @Test
    void failed_withReplanOnFailure_replansAndContinues_withTheNotesAndTheReason() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("1. A\n2. B")
                .stepCompletions(
                        closing("did A", done(PERSON_NOTE)),
                        closing("{\"status\":\"failed\",\"reason\":\"B cannot work\"}"),
                        closing("did B differently", done()))
                .replanReplies("1. B differently");

        ExecutionResult result = run(llm, "on_failure");

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        Call replan = llm.callsIn(Phase.REPLAN).get(0);
        assertTrue(anyMessageContains(replan, "B cannot work"), "the planner must be told why");
        assertTrue(anyMessageContains(replan, PERSON_NOTE), "and what was learned so far");
    }

    @Test
    void aReplan_leavesItsPlanInTheTrace_likeTheFirstPlanDoes() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("1. A\n2. B")
                .stepCompletions(
                        closing("did A", done()),
                        closing("{\"status\":\"failed\",\"reason\":\"B cannot work\"}"),
                        closing("did B differently", done()))
                .replanReplies("1. B differently");

        ExecutionResult result = run(llm, "on_failure");

        List<String> thoughts = result.steps().stream()
                .filter(step -> step.type() == io.ara.core.agent.StepType.THOUGHT)
                .map(io.ara.core.agent.ExecutionStep::content).toList();
        assertTrue(thoughts.contains("1. A\n2. B"), "the first plan");
        assertTrue(thoughts.contains("1. B differently"), "and the revised one: " + thoughts);
    }

    // ── revise ───────────────────────────────────────────────────────────────

    @Test
    void revise_keepsTheStepsResult_andReplansOnlyWhatIsAhead() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("1. A\n2. B\n3. C")
                .stepCompletions(
                        closing("did A", "{\"status\":\"revise\",\"reason\":\"B is unnecessary\"}"),
                        closing("did C instead", done()))
                .replanReplies("1. C instead");

        ExecutionResult result = run(llm, "on_failure");

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        Call replan = llm.callsIn(Phase.REPLAN).get(0);
        assertTrue(anyMessageContains(replan, "asked for the remaining plan to be revised: B is unnecessary"));
        assertTrue(anyMessageContains(replan, "did A"), "the finished step's result is kept");
        List<Call> steps = llm.callsIn(Phase.STEP);
        assertEquals(2, steps.size(), "A ran once; the revised plan then ran 'C instead' — B never ran");
        assertTrue(anyMessageContains(steps.get(1), "Execute step 2/2: C instead"));
    }

    @Test
    void revise_withReplanNever_continuesTheSamePlan_andKeepsTheReasonAsANote() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEPS)
                .stepCompletions(
                        closing("did it", "{\"status\":\"revise\",\"reason\":\"use records\"}"),
                        closing(done()));

        ExecutionResult result = run(llm, "never");

        assertTrue(result.isSuccess());
        assertTrue(llm.callsIn(Phase.REPLAN).isEmpty());
        assertTrue(anyMessageContains(llm.callsIn(Phase.STEP).get(1), "use records"),
                "the reason must not be lost: the next step sees it as a note");
    }

    @Test
    void revise_whenTheRevisedPlanCannotBeRead_continuesTheOriginalPlan_withANote() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEPS)
                .stepCompletions(
                        closing("did it", "{\"status\":\"revise\",\"reason\":\"use records\"}"),
                        closing(done()))
                .replanReplies("no idea, sorry");

        ExecutionResult result = run(llm, "on_failure");

        assertTrue(result.isSuccess());
        assertTrue(anyMessageContains(llm.callsIn(Phase.STEP).get(1), "the original plan continues"));
    }

    @Test
    void revise_onTheLastStep_hasNothingToReplan_andIsNoted() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("1. Only")
                .stepCompletions(closing("did it", "{\"status\":\"revise\",\"reason\":\"too late\"}"));

        ExecutionResult result = run(llm, "on_failure");

        assertTrue(result.isSuccess());
        assertTrue(llm.callsIn(Phase.REPLAN).isEmpty());
        assertTrue(anyMessageContains(llm.callsIn(Phase.SYNTHESIS).get(0), "too late"));
    }

    // ── refused closings keep the step open ──────────────────────────────────

    @Test
    void aRefusedClosing_keepsTheStepOpen_andTheModelIsToldWhy() {
        String sixNotes = done("1", "2", "3", "4", "5", "6");
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("1. Only")
                .stepCompletions(closing(sixNotes), closing(done("kept")));

        ExecutionResult result = run(llm, "never");

        assertTrue(result.isSuccess());
        List<Call> steps = llm.callsIn(Phase.STEP);
        assertEquals(2, steps.size());
        assertTrue(anyMessageContains(steps.get(1), "close_step was refused"));
        assertTrue(anyMessageContains(llm.callsIn(Phase.SYNTHESIS).get(0), "kept"));
        assertFalse(anyMessageContains(llm.callsIn(Phase.SYNTHESIS).get(0), "\n  - 6"),
                "notes of a refused closing are not filed");
    }

    @Test
    void aStepThatKeepsBeingRefused_endsAtTheRoundLimit() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("1. Only")
                .stepCompletions(closing("{\"status\":\"nonsense\"}"));

        ExecutionResult result = run(llm, "never");

        assertFalse(result.isSuccess());
        assertEquals(3, llm.callsIn(Phase.STEP).size(), "maxStepRoundsPerStep rounds, then the step ends");
    }

    // ── close_step next to other tools, native ids ──────────────────────────

    private static LlmCompletion nativeTurn(ToolCallEntry... entries) {
        ToolCallEntry first = entries[0];
        return new LlmCompletion("", 5, 5, "tool_calls",
                "{\"tool_id\":\"" + first.toolId() + "\",\"arguments\":" + first.argumentJson() + "}",
                first.toolCallId(), List.of(entries));
    }

    @Test
    void closeStepListedBeforeAnotherTool_stillLetsTheOtherToolRun() {
        CountingTool echo = new CountingTool();
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("1. Only")
                .stepCompletions(nativeTurn(
                        new ToolCallEntry("c1", "close_step", done("n")),
                        new ToolCallEntry("c2", "echo", "{}")));

        ExecutionResult result = run(llm, "never", registryOf(echo));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertEquals(1, echo.runs, "closing the step must not make its sibling's effect vanish");
    }

    @Test
    void closeStepListedAfterAnotherTool_closesTheStepInTheSameTurn() {
        CountingTool echo = new CountingTool();
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("1. Only")
                .stepCompletions(nativeTurn(
                        new ToolCallEntry("c1", "echo", "{}"),
                        new ToolCallEntry("c2", "close_step", done())));

        run(llm, "never", registryOf(echo));

        assertEquals(1, echo.runs);
        assertEquals(1, llm.callsIn(Phase.STEP).size(), "no extra round is needed to close the step");
    }

    @Test
    void aNativeClosing_isRecordedInTheHistoryAsAPairedCall_whenRefused() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("1. Only")
                .stepCompletions(
                        nativeTurn(new ToolCallEntry("c1", "close_step", "{\"status\":\"failed\"}")),
                        nativeTurn(new ToolCallEntry("c2", "close_step", done())));

        run(llm, "never");

        Call retry = llm.callsIn(Phase.STEP).get(1);
        assertTrue(retry.messages().stream().anyMatch(message -> "c1".equals(message.toolCallId())),
                "the refused call and its correction are replayed with the provider's call id");
    }

    // ── what each model is shown ─────────────────────────────────────────────

    @Test
    void theStepsModel_isTold_aboutCloseStep_butThePlannerIsNot() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("1. Only")
                .stepCompletions(closing(done()));

        run(llm, "never", registryOf(new CountingTool()));

        assertTrue(llm.callsIn(Phase.STEP).get(0).messages().get(0).content().contains("close_step"));
        assertFalse(llm.callsIn(Phase.PLAN).get(0).messages().get(0).content().contains("close_step"),
                "planning a task must not offer the model a tool that only closes steps");
    }

    @Test
    void anAgentToolNamedCloseStep_failsTheTaskBeforeAnyCall() {
        AraTool clash = new AraTool() {
            @Override public String toolId() { return "close_step"; }
            @Override public String description() { return "the agent's own"; }
            @Override public String argumentSchema() { return "{}"; }
            @Override public ToolResult execute(String argumentJson) { return ToolResult.success(toolId(), "x"); }
        };
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient();

        ExecutionResult result = run(llm, "never", registryOf(clash));

        assertFalse(result.isSuccess());
        assertTrue(result.failureReasonOpt().orElse("").contains("reserves the tool name 'close_step'"));
        assertTrue(llm.calls().isEmpty(), "nothing must be sent to the model");
    }
}
