package io.ara.examples.planner;

import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.llm.LlmClient;
import io.ara.examples.support.Live;
import io.ara.runtime.AraRuntime;

import java.time.Duration;

/**
 * How to give an agent a plan: the {@code plan_execute} strategy on a small coding task.
 *
 * <p>Read {@link #main} from top to bottom, it is the whole recipe in five steps: the tools, the model,
 * the runtime, the agent and the task. What makes the agent plan before it acts is one line,
 * {@code plannerStrategy("plan_execute")}; {@link StrategyConfig.PlanExecute} sets how. Everything
 * else in this package only prints what happens ({@link PlanNarrator}, {@link PlannerConsole},
 * {@link LibraryChecks}).
 *
 * <p>The task builds a tiny Java library in an in-memory workspace: a {@code Book}, a {@code Member},
 * a {@code LoanService} that needs both, a style check, and a README that lists every file. The style
 * service is unreliable on purpose, its first call fails. The console shows what the strategy does that a
 * plain ReAct loop does not:
 *
 * <ol>
 *   <li><b>The plan comes first, as data.</b> The model returns JSON that says which step needs which.</li>
 *   <li><b>Every step has its own small context</b>: the task, the plan, the results of the earlier
 *       steps. Not a transcript that grows with every tool call.</li>
 *   <li><b>A step closes with an outcome and notes.</b> The steps that choose a package or a path leave
 *       it as a note, and the step that needs it reads it, word for word.</li>
 *   <li><b>A failed step replans.</b> With {@code replanPolicy="on_failure"} the strategy plans again only
 *       what is left, and keeps the steps already finished.</li>
 * </ol>
 *
 * <h2>Running it</h2>
 * <p>With no arguments it runs offline, with no key and no network: {@link ScriptedPlannerLlm} plays the
 * model, so the output is the same every time and shows what the strategy does, not what a model decides.
 * Pass {@code live} as the first argument (or {@code -Dara.example.live=true}) to use a real
 * OpenAI-compatible endpoint: LM Studio on {@code 127.0.0.1:1234} with {@code gpt-oss-20b} by default;
 * {@code -Dara.base.url=...} and {@code -Dara.model=...} point it elsewhere, and {@code -Dara.api.key} or
 * {@code ARA_API_KEY} gives the key if the endpoint checks one.
 *
 * <p>A live run is what a model makes of the strategy, and local models are uneven at it. They may do
 * the work of several steps in one, or lose a tool call whose argument is a whole file: the server cannot
 * parse it, drops it, and ARA sees an empty reply and asks again. The client samples at its default
 * temperature of 0.7 because at 0 the retry would send the very same request and fail the same way. The
 * checks at the end read the workspace, so they say whether the task was really done.
 */
public final class PlannerExample {

    /** LM Studio on this machine; override with {@code -Dara.base.url=...} and {@code -Dara.model=...}. */
    private static final String LIVE_BASE_URL = System.getProperty("ara.base.url", "http://127.0.0.1:1234/v1");
    private static final String LIVE_MODEL    = System.getProperty("ara.model", "gpt-oss-20b");

    private static final String TASK = "Build a small Java library: a Book record, a Member record, and a "
            + "LoanService that lends a Book to a Member (it must import both). Run the style check. "
            + "Finish with a README.md that lists every file in the workspace.";

    // The limits that keep a plan_execute run from going on forever.
    private static final int MAX_PLAN_STEPS = 8;       // the plan may have at most this many steps
    private static final int ROUNDS_PER_STEP = 6;      // think, call a tool, read the result: at most this often per step
    private static final int MAX_ITERATIONS = 60;      // model calls in the whole run, planning and replanning included

    public static void main(String[] args) {
        boolean live = Live.requested(args);
        PlannerConsole.banner("ARA plan_execute — a plan with dependencies, notes and a replan",
                live, LIVE_MODEL, LIVE_BASE_URL);

        // 1. The tools the agent may use: files in memory, plus a style check that fails the first time.
        Workspace workspace = new Workspace().withFlakyTool("check_style",
                "Runs the style check on the whole workspace.", 1,
                "style service unavailable, try again later", "Style check passed: 0 issues.");

        // 2. The model: a real endpoint with "live", otherwise a script that plays it.
        LlmClient model = live ? liveClient() : new ScriptedPlannerLlm();

        // 3. The runtime: the model and the tools, wired together.
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient(model)
                .toolRegistry(workspace.registry())
                .build()) {

            // 4. The agent. This is where it is told to plan first.
            AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                    .agentType("library-builder")
                    .systemPrompt("You are an agent working in an in-memory workspace. "
                            + "Use the tools to read and write files; paths use '/' and may be nested.")
                    .plannerStrategy("plan_execute")                          // plan first, then run the plan
                    .strategyConfig(new StrategyConfig.PlanExecute(
                            "on_failure",                                     // plan again if a step fails
                            MAX_PLAN_STEPS, ROUNDS_PER_STEP))
                    .enabledTools(workspace.toolIds())
                    .executionTimeout(Duration.ofMinutes(10))
                    .maxIterations(MAX_ITERATIONS)
                    .build());

            // 5. The task. The listener only watches the steps as they happen; without it the run is the same.
            PlannerConsole.section("the task");
            System.out.println(PlannerConsole.wrap(TASK, "  "));
            PlanNarrator narrator = new PlanNarrator();
            AgentResponse response = agent.execute(AgentTask.of(TASK).withEventListener(narrator));

            // What is left only prints: the summary of the run, and what it really left in the workspace.
            narrator.summary(response, ROUNDS_PER_STEP, MAX_ITERATIONS);
            LibraryChecks.report(workspace);
        }
    }

    private static LlmClient liveClient() {
        return OpenAiLlmClient.builder()
                .baseUrl(LIVE_BASE_URL)
                .apiKey(Live.apiKey("not-required")).modelName(LIVE_MODEL)
                .timeout(Duration.ofMinutes(5)).build();
    }

    private PlannerExample() { }
}
