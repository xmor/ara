package io.ara.examples.pipeline;

import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AraAgents;
import io.ara.core.common.AgentId;
import io.ara.runtime.pipeline.AgentPipeline;
import io.ara.runtime.pipeline.IntentRouter;
import io.ara.runtime.pipeline.PipelineResult;
import io.ara.runtime.pipeline.RuleClassifier;

/**
 * The <b>Classify-and-Act</b> pattern at its smallest: one classifier labels the incoming
 * task, an {@link IntentRouter} turns that label into exactly one worker, and that worker
 * handles it — no supervisory loop, no second opinion, no retry of the classification.
 *
 * <p>The running example is a support-ticket triage: route each ticket to the team that
 * owns it. The classifier here is a {@link RuleClassifier} — keyword matching, no model —
 * so the example runs offline with nothing to configure. Swapping in a model-backed
 * {@link AraAgent} that emits {@code {"intent":"..."}} changes nothing else: the router
 * reads the label the same way whatever produced it.
 *
 * <p>Two builder calls carry the pattern:
 * <ul>
 *   <li>{@link AgentPipeline.Builder#classify(String, AraAgent, IntentRouter)} runs the
 *       classifier and routes on its label. The router's {@code orElse(...)} arm is
 *       mandatory, so a label no route recognises still lands somewhere named instead of
 *       ending the pipeline with the classifier's own JSON as the "answer".</li>
 *   <li>{@link AgentPipeline.Builder#worker(String, AraAgent)} declares a terminal step
 *       that receives the <b>original</b> ticket text (not the classifier's label) and
 *       ends the run — the "exactly one worker handles it" half of the pattern.</li>
 * </ul>
 *
 * <p>For confidence scores, escalation from rules to a model to a human, and worker state
 * isolation, see {@code TicketTriageCascadeExample} in this package.
 */
public final class ClassifyAndActExample {

    public static void main(String[] args) {
        System.out.println("=== ARA Classify-and-Act — support-ticket triage ===");

        // One classifier: keyword rules over the ticket text, first match wins in
        // declaration order. A model-backed AraAgent emitting the same JSON shape would
        // drop in here unchanged — the router downstream cannot tell the difference.
        AraAgent classifier = RuleClassifier.builder(AgentId.of("triage"))
                .when("BILLING",   "invoice", "refund", "payment", "charged")
                .when("TECHNICAL", "crash", "error", "blank screen", "won't start")
                .when("SALES",     "pricing", "plan", "quote", "evaluating")
                .orElse("UNKNOWN");

        // One worker per label the pipeline can resolve to, the else-arc included.
        // Deterministic stand-ins here; a real deployment puts a genuine agent (or a
        // plain integration call) behind each, reading the ticket from AgentTask.input().
        AraAgent billing      = worker("billing-team",   "Routed to Billing. Refund/invoice case opened.");
        AraAgent technical    = worker("technical-team", "Routed to Technical Support. Priority ticket filed.");
        AraAgent sales        = worker("sales-team",     "Routed to Sales. Account manager notified.");
        AraAgent manualReview = worker("manual-queue",   "Placed in the manual-review queue for a human to triage.");

        AgentPipeline triage = AgentPipeline.builder()
                .classify("classify", classifier, IntentRouter.onField("intent")
                        .route("BILLING",   "route-to-billing")
                        .route("TECHNICAL", "route-to-technical")
                        .route("SALES",     "route-to-sales")
                        // Anything the rule set does not recognise (label "UNKNOWN", or
                        // any label with no route) goes here rather than off the end.
                        .orElse("route-to-manual-review"))
                .worker("route-to-billing",       billing)
                .worker("route-to-technical",     technical)
                .worker("route-to-sales",         sales)
                .worker("route-to-manual-review", manualReview)
                // One classify hop plus one worker: the most this pattern can ever need.
                .maxSteps(2)
                .build();

        // Four tickets: one per team, plus one no rule matches — which the mandatory
        // else-arc carries to manual review instead of dropping.
        runTicket(triage, "I was charged twice on my last invoice, please refund the extra amount.");
        runTicket(triage, "The app just shows a blank screen since this morning, nothing I do fixes it.");
        runTicket(triage, "Do you have a plan that fits a 50-person team? We're evaluating options.");
        runTicket(triage, "Thanks for the great support last week, just wanted to say so.");
    }

    /** Runs one ticket through {@code triage} and prints the path it took and the worker's answer. */
    private static void runTicket(AgentPipeline triage, String ticket) {
        PipelineResult result = triage.run(ticket);

        System.out.println();
        System.out.println("Ticket     : " + ticket);
        System.out.println("Path taken : " + result.stepsExecuted());
        System.out.println("Outcome    : " + (result.success()
                ? result.finalOutput()
                : "FAILED — " + result.failureReason()));
    }

    /**
     * A deterministic worker that always answers {@code fixedAnswer} — enough to show the
     * routing. A production worker would be a real {@code AraAgent} acting on the ticket
     * (filing it, drafting a reply, opening a refund) via {@code AgentTask.input()}, which
     * {@code worker(...)} guarantees is the original ticket text.
     */
    private static AraAgent worker(String name, String fixedAnswer) {
        return AraAgents.deterministic(AgentId.of(name), task -> fixedAnswer);
    }

    private ClassifyAndActExample() {}
}
