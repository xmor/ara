package io.ara.runtime.strategy;

import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Phase 1 of {@link PlanExecuteStrategy}, and its re-run after a step goes wrong: asking the
 * model for a plan, reading the reply, and rebuilding the part of the plan still ahead.
 *
 * <p>Split out of the strategy because it is a self-contained piece of work — a request, a
 * reader ({@link PlanReader}) and a decision about an unreadable answer — that shares nothing
 * with running a step except the per-run carriers {@link PlanRun} and {@link PlanTally}.
 *
 * <p>Stateless: every method is static and takes everything it needs, so the strategy's
 * singleton can be shared by concurrent tasks. Thread-safe.
 */
final class PlanPlanning {

    private static final Logger log = LoggerFactory.getLogger(PlanPlanning.class);

    private PlanPlanning() {
    }

    // The system prompt only names the phase; the JSON format lives in the user message
    // (PLAN_FORMAT). The first sentence is kept verbatim from when the plan was a numbered list:
    // the strategy's tests tell the planning call apart from the step and synthesis calls by it.
    //
    // Why the split: measured against a local gpt-oss-20b behind LM Studio, a system prompt that
    // demands "a JSON object and nothing else" made the server fail the request with a parse
    // error on every attempt (5 of 5), while the identical instruction in the user message
    // worked every time (10 of 10), as did the plain numbered-list prompt. The cause is in how
    // that stack parses the model's output, not in this strategy — but a planning call that
    // dies before producing a plan is the one failure this class cannot recover from, and
    // moving the format sentence costs nothing anywhere else. (Alternative discarded: describing
    // the JSON in prose instead of showing it — the same failure.)
    private static final String PLAN_SUFFIX = """

    Produce a short numbered execution plan.
    Do not solve the task yet, only plan.
    """;

    /** Appended to the planning request (the user message): the shape the reply must have. */
    private static final String PLAN_FORMAT = """


    Reply with the plan as one JSON object and nothing else — no explanations, no prose:
    {"steps":[{"id":"s1","goal":"<one concrete step>","dependsOn":[]}]}
    - "id": a short unique label (s1, s2, ...).
    - "goal": one concrete step.
    - "dependsOn": the ids of the steps whose results this step needs; [] when it needs none.""";

    private static final String REPLAN_SUFFIX = """

    Produce a revised plan for the remaining work.
    Do not solve the task, only plan.
    """;

    /** Appended to the replanning request (the user message); see {@link #PLAN_SUFFIX} for why not the system prompt. */
    private static final String REPLAN_FORMAT = """


    Reply with the revised plan as one JSON object, in the same format and with nothing else:
    {"steps":[{"id":"s1","goal":"<one concrete step>","dependsOn":[]}]}""";

    /** Appended to the planning request when the previous reply could not be read as a plan. */
    private static final String UNREADABLE_PLAN_FEEDBACK = """


    Your previous reply could not be read as a plan. Reply with the JSON object only,
    in the format described above, with at least one step.""";

    // ── Planning ───────────────────────────────────────────────────────────────

    /** How phase 1 ended: the steps to execute, or the failed result to hand back as it is. */
    sealed interface Planning {
        record Planned(List<String> steps) implements Planning { }
        record Failed(ExecutionResult result) implements Planning { }
    }

    /**
     * Phase 1. Asks for a plan and, when the reply holds none, either fails the task or — under
     * {@code replanPolicy = "on_failure"} — asks once more, telling the model what was wrong.
     *
     * <p>There is deliberately no third outcome in which the strategy quietly plans a single
     * "solve the task directly" step: that turns {@code plan_execute} into a plain answer
     * while its name, its trace and its cost all claim a plan was made. A reply nobody could
     * read is a planning failure, and is reported as one.
     */
    static Planning plan(PlanRun run, PlanTally tally, int maxPlanSteps, boolean retryUnreadable) {
        Planning outcome = askForPlan(run, tally, maxPlanSteps, null);
        if (retryUnreadable && isUnreadable(outcome)) {
            outcome = askForPlan(run, tally, maxPlanSteps, UNREADABLE_PLAN_FEEDBACK);
        }
        if (isUnreadable(outcome)) {
            return new Planning.Failed(tally.fail(
                    "The plan could not be read: the planner's reply was neither a JSON plan "
                            + "nor a numbered list of steps"));
        }
        return outcome;
    }

    private static boolean isUnreadable(Planning outcome) {
        return outcome instanceof Planning.Planned planned && planned.steps().isEmpty();
    }

    /**
     * One planning call. A reply with no readable step comes back as {@code Planned} with an
     * empty list so that {@link #plan} decides whether to retry; every other failure (cancel,
     * iteration limit, LLM error, budget) is final and comes back as {@code Failed}.
     *
     * @param feedback text appended to the request to explain why the previous reply was
     *                 refused, or {@code null} on the first attempt
     */
    private static Planning askForPlan(PlanRun run, PlanTally tally, int maxPlanSteps, String feedback) {
        if (PlanRun.cancelled()) {
            return new Planning.Failed(tally.fail("Cancelled"));
        }
        if (tally.iterations >= run.maxIterations()) {
            return new Planning.Failed(tally.fail("Max iterations reached before planning"));
        }
        tally.iterations++;

        LlmCompletion completion;
        try {
            completion = ReactExecutionSupport.completeWithRetry(
                    run.llm(), buildPlanningMessages(run, maxPlanSteps, feedback), planningContext(run),
                    run.deadline(), run.config(), run.task().taskId());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new Planning.Failed(tally.fail("Cancelled"));
        } catch (Exception e) {
            return new Planning.Failed(
                    tally.fail("Planning failed: " + ReactExecutionSupport.describeLlmFailure(e)));
        }
        run.checkTimeout();
        tally.addUsage(completion);
        ExecutionResult budgetExceeded = ReactExecutionSupport.chargeRunBudget(
                run.config(), run.task(), completion.promptTokens(), completion.outputTokens(),
                tally.iterations, tally.promptTokens, tally.outputTokens, tally.steps);
        if (budgetExceeded != null) {
            return new Planning.Failed(budgetExceeded);
        }

        if (ReactExecutionSupport.shouldRecordReasoning(run.config(), completion)) {
            tally.record(ExecutionStep.reasoning(completion.reasoning(), tally.iterations));
        }
        tally.record(ExecutionStep.thought(
                completion.text() != null ? completion.text() : "", tally.iterations));

        List<String> steps = PlanReader.read(completion.text());
        if (steps.size() > maxPlanSteps) {
            log.debug("Plan truncated from {} to {} steps (maxPlanSteps={})",
                    steps.size(), maxPlanSteps, maxPlanSteps);
            steps = steps.subList(0, maxPlanSteps);
        }
        return new Planning.Planned(steps);
    }

    /**
     * The context for a call that produces a plan (the first one and any replan): when the agent
     * asked for provider-native structured output ({@code LlmProfile.nativeJsonSchema}) and the
     * client can honour it, the plan schema travels as the provider's {@code response_format},
     * which is what makes a small local model reliably emit JSON. This replaces whatever output
     * schema the task itself carries — that one describes the agent's final answer, not a plan,
     * and must not constrain the planner.
     *
     * <p>Both conditions are required, not just the client's capability. Forcing a
     * {@code response_format} on every planning call of an agent that never asked for the native
     * path would change the request that agent has always sent, to a provider that may enforce
     * the schema strictly; the prompt's own JSON instruction is what everyone else gets.
     * (Alternative discarded: setting the native flag on the planning context unconditionally.
     * It needs a new public setter on {@code LlmCallContext} for a behaviour nobody opted into.)
     */
    private static LlmCallContext planningContext(PlanRun run) {
        if (!run.ctx().nativeJsonSchema() || !run.llm().supportsNativeStructuredOutput()) {
            return run.ctx();
        }
        return run.ctx().withOutputSchema(PlanReader.SCHEMA, PlanReader.SCHEMA_NAME, false);
    }

    // ── Re-planning ────────────────────────────────────────────────────────────

    /**
     * Generates a revised plan for steps starting at {@code failedStepIdx}.
     * Returns an empty list if replanning fails or produces no steps.
     */
    static List<String> replan(
            List<String> originalPlan, Map<Integer, String> stepResults,
            ReplanRequest request, PlanRun run, PlanTally tally) {

        tally.iterations++;

        StringBuilder sb = new StringBuilder();
        sb.append("Original task: ").append(run.task().input()).append("\n\n");
        sb.append("Plan execution status:\n");
        for (int i = 0; i < originalPlan.size(); i++) {
            if (i < request.fromStep()) {
                sb.append("  %d. ✓ %s%n".formatted(i + 1, originalPlan.get(i)));
            } else if (i == request.fromStep() && request.failure()) {
                sb.append("  %d. ✗ %s — %s%n".formatted(i + 1, originalPlan.get(i), request.reason()));
            } else {
                sb.append("  %d. (pending) %s%n".formatted(i + 1, originalPlan.get(i)));
            }
        }
        if (!request.failure()) {
            sb.append("\nThe last completed step asked for the remaining plan to be revised: ")
              .append(request.reason()).append("\n");
        }
        if (!stepResults.isEmpty()) {
            sb.append("\nCompleted step results:\n");
            stepResults.forEach((idx, result) -> {
                String truncated = result.length() > 300 ? result.substring(0, 300) + "…" : result;
                sb.append("  Step %d: %s%n".formatted(idx + 1, truncated));
            });
        }
        // Notes are exact facts, so unlike the results above they are never shortened.
        String notes = CloseStep.formatNotes(tally.notes);
        if (notes != null) {
            sb.append("\n").append(notes).append("\n");
        }
        sb.append("\nGenerate a revised plan for the remaining work starting from step ")
           .append(request.fromStep() + 1).append(".").append(REPLAN_FORMAT);

        List<LlmMessage> messages = List.of(
                new LlmMessage("system", run.systemPrompt() + REPLAN_SUFFIX),
                new LlmMessage("user", sb.toString())
        );

        try {
            LlmCompletion completion = ReactExecutionSupport.completeWithRetry(
                    run.llm(), messages, planningContext(run), run.deadline(), run.config(), run.task().taskId());
            run.checkTimeout();
            tally.addUsage(completion);
            ExecutionResult replanBudgetExceeded = ReactExecutionSupport.checkBudget(
                    run.config(), run.task().taskId(),
                    tally.promptTokens, tally.outputTokens, tally.iterations, tally.steps);
            if (replanBudgetExceeded == null) {
                replanBudgetExceeded = ReactExecutionSupport.chargeRunBudget(
                        run.config(), run.task(), completion.promptTokens(), completion.outputTokens(),
                        tally.iterations, tally.promptTokens, tally.outputTokens, tally.steps);
            }
            if (replanBudgetExceeded != null) {
                tally.budgetFailure = replanBudgetExceeded;   // caller returns it
                return List.of();
            }
            // Recorded like the first plan, so the trace and the run's events show what the remaining
            // work was changed to; without it a replan left no mark at all.
            tally.record(ExecutionStep.thought(completion.text() != null ? completion.text() : "", tally.iterations));
            List<String> revised = PlanReader.read(completion.text());
            log.debug("Replan produced {} step(s)", revised.size());
            return revised;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.debug("Cancelled during replanning");
            return List.of();
        } catch (Exception e) {
            log.warn("Replanning failed: {}", ReactExecutionSupport.describeLlmFailure(e));
            return List.of();
        }
    }

    /**
     * Why the remaining plan is being rebuilt.
     *
     * @param fromStep the first step the revised plan replaces (zero-based)
     * @param reason   what went wrong, or what the finished step asked to change
     * @param failure  {@code true} when step {@code fromStep} itself failed; {@code false} when it
     *                 is still pending because the step before it asked for a revision
     */
    record ReplanRequest(int fromStep, String reason, boolean failure) { }

    /**
     * Planning phase: compact prompt asking for the plan as a JSON object.
     *
     * @param feedback why the previous reply was refused, or {@code null} on the first attempt
     */
    private static List<LlmMessage> buildPlanningMessages(PlanRun run, int maxPlanSteps, String feedback) {
        String request = run.task().input() + "\n\n(Produce at most " + maxPlanSteps + " steps.)"
                + PLAN_FORMAT + (feedback != null ? feedback : "");
        return List.of(
                new LlmMessage("system",
                        run.systemPrompt() + run.plannerCatalog() + PLAN_SUFFIX),
                // The planner has to see the attachments too: "summarise this PDF" cannot be
                // broken into steps by a model shown only the words around the document.
                LlmMessage.user(request, run.task().media())
        );
    }

}
