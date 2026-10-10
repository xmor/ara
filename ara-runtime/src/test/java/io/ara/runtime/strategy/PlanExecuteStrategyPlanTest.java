package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.strategy.PlanPhaseLlmClient.Phase;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@code plan_execute} obtains its plan: the JSON format, what happens when the reply cannot
 * be read, and the native-schema request. Reading itself is covered by {@code PlanReaderTest};
 * these tests cover what the strategy does with the result.
 */
class PlanExecuteStrategyPlanTest {

    private static final String TWO_STEP_JSON = """
            {"steps":[{"id":"s1","goal":"Create Person","dependsOn":[]},
                      {"id":"s2","goal":"Create Address","dependsOn":["s1"]}]}""";

    private static final ToolRegistry NO_TOOLS = new ToolRegistry() {
        @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(); }
        @Override public Optional<AraTool> findById(String id) { return Optional.empty(); }
        @Override public ToolResult execute(String toolId, String argumentJson) {
            return ToolResult.failure(toolId, "no tools in this test");
        }
    };

    private static MemoryManager seededMemory() {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "You are a helpful AI agent.");
        memory.appendToWorkingMemory("user", "build the model");
        return memory;
    }

    private static AgentConfig config(String replanPolicy, boolean nativeJsonSchema) {
        return AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().transportId("stub").nativeJsonSchema(nativeJsonSchema).build())
                .executionTimeout(Duration.ofSeconds(30))
                .maxIterations(20)
                .strategyConfig(new StrategyConfig.PlanExecute(replanPolicy, 8, 3))
                .build();
    }

    private static ExecutionResult run(PlanPhaseLlmClient llm, AgentConfig config) {
        return new PlanExecuteStrategy().execute(
                AgentTask.of("build the model"), llm, seededMemory(), NO_TOOLS, config);
    }

    // ── maxParallelSteps is stored, not yet honoured ─────────────────────────

    @Test
    void maxParallelSteps_aboveOne_stillRunsTheStepsOneAfterTheOther() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEP_JSON);
        AgentConfig parallel = AgentConfig.defaults()
                .primaryLlm(LlmProfile.builder().transportId("stub").build())
                .executionTimeout(Duration.ofSeconds(30))
                .maxIterations(20)
                .strategyConfig(new StrategyConfig.PlanExecute("never", 8, 3, 4))
                .build();

        ExecutionResult result = run(llm, parallel);

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        assertEquals(2, llm.callsIn(Phase.STEP).size());
    }

    // ── a JSON plan is executed ─────────────────────────────────────────────

    @Test
    void aJsonPlan_runsEachGoalAsAStep_inTheWrittenOrder() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEP_JSON);

        ExecutionResult result = run(llm, config("never", false));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        List<PlanPhaseLlmClient.Call> steps = llm.callsIn(Phase.STEP);
        assertEquals(2, steps.size());
        assertTrue(lastUserMessage(steps.get(0)).contains("Execute step 1/2: Create Person"));
        assertTrue(lastUserMessage(steps.get(1)).contains("Execute step 2/2: Create Address"));
    }

    @Test
    void aNumberedListPlan_isStillExecuted() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("1. Create Person\n2. Create Address");

        ExecutionResult result = run(llm, config("never", false));

        assertTrue(result.isSuccess());
        assertEquals(2, llm.callsIn(Phase.STEP).size());
    }

    // ── where the format instruction goes ────────────────────────────────────

    @Test
    void theJsonFormatInstruction_isInTheUserMessage_notTheSystemPrompt() {
        // Measured against gpt-oss-20b behind LM Studio: a system prompt demanding "a JSON object
        // and nothing else" made the server fail every planning call; the same words in the user
        // message worked every time. A planning call that dies before producing a plan cannot be
        // recovered, so the system prompt only names the phase.
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEP_JSON);

        run(llm, config("never", false));

        PlanPhaseLlmClient.Call plan = llm.callsIn(Phase.PLAN).get(0);
        assertFalse(plan.messages().get(0).content().contains("JSON"),
                "the system prompt of the planning call must not ask for JSON");
        assertTrue(lastUserMessage(plan).contains("one JSON object"));
    }

    @Test
    void theReplanFormatInstruction_isInTheUserMessage_too() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .planReplies("{\"steps\":[{\"id\":\"s1\",\"goal\":\"Doomed\",\"dependsOn\":[]}]}")
                .stepReplies("", "STEP_DONE");

        run(llm, config("on_failure", false));

        PlanPhaseLlmClient.Call replan = llm.callsIn(Phase.REPLAN).get(0);
        assertFalse(replan.messages().get(0).content().contains("JSON"));
        assertTrue(lastUserMessage(replan).contains("one JSON object"));
    }

    @Test
    void thePlanner_isShownTheToolsByNameAndDescription_butNotTheirArgumentSchemas() {
        AraTool tool = new AraTool() {
            @Override public String toolId() { return "write_file"; }
            @Override public String description() { return "Writes a file."; }
            @Override public String argumentSchema() { return "{\"SCHEMA_MARKER\":true}"; }
            @Override public ToolResult execute(String argumentJson) { return ToolResult.success(toolId(), "ok"); }
        };
        ToolRegistry registry = new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(tool); }
            @Override public Optional<AraTool> findById(String id) { return Optional.of(tool); }
            @Override public ToolResult execute(String toolId, String argumentJson) { return tool.execute(argumentJson); }
        };
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies(TWO_STEP_JSON);

        new PlanExecuteStrategy().execute(AgentTask.of("build the model"), llm, seededMemory(), registry,
                config("never", false));

        String planner = llm.callsIn(Phase.PLAN).get(0).messages().get(0).content();
        assertTrue(planner.contains("write_file: Writes a file."), "the planner must know what tools exist");
        assertFalse(planner.contains("SCHEMA_MARKER"),
                "schemas are for calling a tool; a JSON-looking schema next to a request for a JSON plan "
                        + "made a local model server fail every planning call");
        String step = llm.callsIn(Phase.STEP).get(0).messages().get(0).content();
        assertTrue(step.contains("SCHEMA_MARKER"), "a step still sees the full catalogue it needs to call tools");
    }

    // ── an unreadable plan is an error, never an invented plan ──────────────

    @Test
    void anUnreadablePlan_withReplanNever_failsWithoutExecutingAnything() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("I would just answer directly.");

        ExecutionResult result = run(llm, config("never", false));

        assertFalse(result.isSuccess());
        assertTrue(result.failureReasonOpt().orElse("").contains("could not be read"),
                () -> "unexpected failure reason: " + result.failureReasonOpt());
        assertEquals(1, llm.callsIn(Phase.PLAN).size(), "no second chance under replanPolicy=never");
        assertTrue(llm.callsIn(Phase.STEP).isEmpty(), "no step may run on an invented plan");
        assertTrue(llm.callsIn(Phase.SYNTHESIS).isEmpty());
    }

    @Test
    void anEmptyPlanReply_isUnreadableToo() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("");

        ExecutionResult result = run(llm, config("never", false));

        assertFalse(result.isSuccess());
        assertTrue(llm.callsIn(Phase.STEP).isEmpty());
    }

    @Test
    void anUnreadablePlan_withReplanOnFailure_isAskedForOnceMore_withTheReason() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .planReplies("I would just answer directly.", TWO_STEP_JSON);

        ExecutionResult result = run(llm, config("on_failure", false));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        List<PlanPhaseLlmClient.Call> plans = llm.callsIn(Phase.PLAN);
        assertEquals(2, plans.size());
        assertFalse(lastUserMessage(plans.get(0)).contains("could not be read as a plan"));
        assertTrue(lastUserMessage(plans.get(1)).contains("could not be read as a plan"),
                "the retry must tell the model what was wrong with its first reply");
        assertEquals(2, llm.callsIn(Phase.STEP).size());
    }

    @Test
    void anUnreadablePlan_askedForTwice_failsAfterTheRetry() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("prose", "more prose");

        ExecutionResult result = run(llm, config("on_failure", false));

        assertFalse(result.isSuccess());
        assertEquals(2, llm.callsIn(Phase.PLAN).size(), "exactly one retry, not a loop");
        assertTrue(llm.callsIn(Phase.STEP).isEmpty());
    }

    @Test
    void theUnreadableAttempt_staysInTheTrace() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient().planReplies("prose", TWO_STEP_JSON);

        ExecutionResult result = run(llm, config("on_failure", false));

        assertTrue(result.steps().stream().anyMatch(step -> "prose".equals(step.content())),
                "both attempts are visible to whoever reads the trace");
    }

    // ── a failed step's replan is read like the first plan ───────────────────

    @Test
    void aRegeneratedPlanInJson_isReadAndExecuted() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .planReplies("{\"steps\":[{\"id\":\"s1\",\"goal\":\"Doomed step\",\"dependsOn\":[]}]}")
                // the doomed step yields nothing, the retried one succeeds
                .stepReplies("", "STEP_DONE")
                .replanReplies("{\"steps\":[{\"id\":\"s1\",\"goal\":\"Better step\",\"dependsOn\":[]}]}");

        ExecutionResult result = run(llm, config("on_failure", false));

        assertTrue(result.isSuccess(), () -> "expected success, got: " + result.failureReasonOpt());
        List<PlanPhaseLlmClient.Call> steps = llm.callsIn(Phase.STEP);
        assertTrue(lastUserMessage(steps.get(steps.size() - 1)).contains("Better step"));
    }

    // ── the native schema ────────────────────────────────────────────────────

    @Test
    void whenTheAgentAsksForNativeSchemaAndTheClientCan_thePlanCallCarriesThePlanSchema() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .nativeStructuredOutput(true).planReplies(TWO_STEP_JSON);

        run(llm, config("never", true));

        assertEquals(PlanReader.SCHEMA, llm.callsIn(Phase.PLAN).get(0).context().outputJsonSchema());
        assertEquals(PlanReader.SCHEMA_NAME, llm.callsIn(Phase.PLAN).get(0).context().outputSchemaName());
        llm.callsIn(Phase.STEP).forEach(call -> assertNull(call.context().outputJsonSchema(),
                "only the plan call is constrained to the plan schema"));
        llm.callsIn(Phase.SYNTHESIS).forEach(call -> assertNull(call.context().outputJsonSchema()));
    }

    @Test
    void whenTheClientCannot_thePlanCallCarriesNoSchema() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .nativeStructuredOutput(false).planReplies(TWO_STEP_JSON);

        run(llm, config("never", true));

        assertNull(llm.callsIn(Phase.PLAN).get(0).context().outputJsonSchema());
    }

    @Test
    void whenTheAgentDidNotAskForNativeSchema_thePlanCallCarriesNoSchema_evenIfTheClientCan() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .nativeStructuredOutput(true).planReplies(TWO_STEP_JSON);

        run(llm, config("never", false));

        assertNull(llm.callsIn(Phase.PLAN).get(0).context().outputJsonSchema(),
                "an agent that never opted in must keep sending the request it has always sent");
    }

    @Test
    void theReplanCall_carriesThePlanSchemaToo() {
        PlanPhaseLlmClient llm = new PlanPhaseLlmClient()
                .nativeStructuredOutput(true)
                .planReplies("{\"steps\":[{\"id\":\"s1\",\"goal\":\"Doomed\",\"dependsOn\":[]}]}")
                .stepReplies("", "STEP_DONE");

        run(llm, config("on_failure", true));

        assertEquals(PlanReader.SCHEMA, llm.callsIn(Phase.REPLAN).get(0).context().outputJsonSchema());
    }

    private static String lastUserMessage(PlanPhaseLlmClient.Call call) {
        List<LlmMessage> messages = call.messages();
        return messages.get(messages.size() - 1).content();
    }
}
