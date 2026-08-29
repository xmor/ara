package io.ara.examples.pipeline;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AraAgents;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.RunState;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.hitl.InMemoryApprovalGate;
import io.ara.runtime.pipeline.AgentPipeline;
import io.ara.runtime.pipeline.ApprovalClassifier;
import io.ara.runtime.pipeline.IntentRouter;
import io.ara.runtime.pipeline.PipelineResult;
import io.ara.runtime.pipeline.RuleClassifier;
import io.ara.runtime.stubs.ScriptedLlmClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The <b>Classify-and-Act</b> pattern scaled up to a real triage front end: the task is
 * still classified once and handed to exactly one worker, but the classification is a
 * <b>cascade of three tiers</b>, each cheaper and less certain than the next, and control
 * falls through to the next tier only when the current one is not confident enough.
 *
 * <p>For the pattern at its smallest — one classifier, one router, four workers, no
 * runtime — see {@code ClassifyAndActExample} in this package. This example adds the two
 * things that turn it into a production triage: confidence-driven escalation, and a human
 * tier.
 *
 * <ol>
 *   <li><b>Rules</b> ({@link RuleClassifier}) — keyword matching, zero cost, zero
 *       latency, no model involved at all. Resolves the obvious tickets outright.</li>
 *   <li><b>A language model</b> — a real {@link AraAgent} backed by an LLM (here a
 *       {@link ScriptedLlmClient} standing in for one, so the example runs offline with
 *       no API key). Reached only when no rule matched.</li>
 *   <li><b>A human</b> ({@link ApprovalClassifier}) — reached only when the model itself
 *       reports low confidence. Blocks on an {@link io.ara.core.hitl.ApprovalGate} until
 *       an operator decides, or the request times out.</li>
 * </ol>
 *
 * <p>Each tier is one {@link AgentPipeline.Builder#classify(String, AraAgent,
 * IntentRouter)} step routing to one of four workers declared with {@link
 * AgentPipeline.Builder#worker(String, AraAgent)}. A tier escalates via {@link
 * IntentRouter.Builder#escalateBelow(double, String)} whenever its confidence falls below
 * a threshold — for the rules tier that means "no keyword matched"; for the model tier,
 * "the model said so".
 *
 * <p>Two wiring details this example exists to make visible, because they are easy to get
 * wrong by hand (see {@code ADR-050} in the private ARA corpus for why each is a
 * deliberate design choice, not an accident of this one class):
 *
 * <ul>
 *   <li><b>Intent routing is exclusive by construction.</b> Every tier declares an
 *       {@code orElse(...)} arm — there is no way to build an {@link IntentRouter}
 *       without one — so a label the model invents, or one no rule recognises, still
 *       lands somewhere named instead of silently ending the pipeline with the
 *       classifier's own raw JSON as the "answer".</li>
 *   <li><b>Classification is shared through {@link RunState}; worker writes are not.</b>
 *       Each classify step calls {@code writeLabelTo("intent")} (and {@code
 *       writeConfidenceTo}/{@code confidenceField} for the two tiers that report a
 *       confidence), so the verdict is visible to whichever tier or worker runs next —
 *       never spliced into the task text. The worker that finally handles the ticket
 *       reads that state but cannot corrupt it: {@link AgentPipeline.Builder#worker} runs
 *       it against an overlay {@code RunState} whose writes are private to that one call
 *       and vanish once {@link AgentPipeline#run} returns.</li>
 * </ul>
 *
 * <p>Three tickets are run through the same pipeline, one per tier that ends up deciding
 * it, so the console output traces the exact path each one took:
 * <ol>
 *   <li>a ticket a keyword rule resolves immediately, at essentially zero cost;</li>
 *   <li>a ticket the rules cannot place but the (scripted) model classifies with high
 *       confidence;</li>
 *   <li>a ticket the model classifies with <em>low</em> confidence, which escalates to
 *       the human tier — here a background thread standing in for a real reviewer,
 *       confirming the model's own guess.</li>
 * </ol>
 */
public final class TicketTriageCascadeExample {

    /** Below this confidence the rules tier's own verdict is not trusted — try the model instead. */
    private static final double RULES_CONFIDENCE_THRESHOLD = 0.5;

    /** Below this confidence the model's own verdict is not trusted — ask a human instead. */
    private static final double MODEL_CONFIDENCE_THRESHOLD = 0.7;

    /** How long the (simulated) reviewer has to decide before an escalated ticket times out. */
    private static final Duration HUMAN_REVIEW_TIMEOUT = Duration.ofSeconds(30);

    public static void main(String[] args) throws Exception {
        System.out.println("=== ARA Classify-and-Act — support-ticket triage (rules → model → human) ===");

        // The approval gate is the hand-off point between this process and whoever
        // reviews escalated tickets. InMemoryApprovalGate is the framework's own
        // in-process implementation — real deployments would back ApprovalGate with a
        // queue an actual reviewer's console polls, but the contract the pipeline code
        // depends on (park until a decision or a timeout arrives) is identical either
        // way, which is the whole point of ApprovalGate being an interface.
        InMemoryApprovalGate approvalGate = new InMemoryApprovalGate();
        watchAndApprove(approvalGate);

        // ── Tier 3: a human, reached only when the model itself is unsure ──────────
        //
        // proposedLabelFrom("intent") is the read side of the same coupling the model
        // tier's writeLabelTo("intent") is the write side of — ClassifyAndActSpec (the
        // declarative form of this same pattern) verifies the two agree at load time,
        // but built by hand here they are simply two calls that must name the same
        // RunState key, which is why both appear explicitly below rather than relying
        // on either builder's default.
        AraAgent humanTier = ApprovalClassifier.builder(AgentId.of("triage-human"), approvalGate)
                .labels("BILLING", "TECHNICAL", "SALES")
                .proposedLabelFrom("intent")
                .timeout(HUMAN_REVIEW_TIMEOUT)
                .recordOutcomeAs("approval.outcome")
                // Nothing recognisable came out of either the rules or the model, and no
                // human resolved it either — the ticket still needs to go somewhere, so
                // it lands in a manual-review queue instead of vanishing.
                .orElse("UNRECOGNISED");

        // ── Tier 1: keyword rules, evaluated first because they cost nothing ────────
        //
        // First match wins, in declaration order — a rule for a more specific label
        // (e.g. "URGENT_TECHNICAL") would need to be declared before a broader one that
        // would also match the same ticket text.
        AraAgent rulesTier = RuleClassifier.builder(AgentId.of("triage-rules"))
                .when("BILLING",   "invoice", "refund", "payment", "charge")
                .when("TECHNICAL", "crash", "stack trace", "won't start", "error")
                .orElse("NO_RULE_MATCHED");

        // The model tier needs a runtime to resolve its LlmClient through, so everything
        // downstream of it is scoped to the try-with-resources block below. A real
        // deployment would configure a genuine provider here (OpenAI, Anthropic, Ollama,
        // ...) instead of ScriptedLlmClient — nothing else in this example would change,
        // because IntentRouter reads the classifier's output the same way regardless of
        // what produced it.
        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script()
                        // Answers the one ticket in this demo that reaches the model tier
                        // (see ticket #2 below) with high confidence, and the one that
                        // reaches it with low confidence (ticket #3) — scripted in the
                        // exact order the two tickets are run further down.
                        .thenFinalAnswer("{\"intent\":\"SALES\",\"confidence\":0.92}")
                        .thenFinalAnswer("{\"intent\":\"TECHNICAL\",\"confidence\":0.35}")
                        .build())
                .build()) {

            // ── Tier 2: a language model, reached when no rule matched ──────────────
            AraAgent modelTier = runtime.createAgent(AgentConfig.defaults()
                    .agentType("triage-model")
                    .systemPrompt("""
                            You triage customer support tickets. Read the ticket and answer with
                            ONLY a JSON object, no other text:
                            {"intent":"BILLING|TECHNICAL|SALES","confidence":0.0-1.0}""")
                    .plannerStrategy("react")
                    .maxIterations(1)   // a classifier makes exactly one call — no tool loop needed
                    .build());

            // ── The four workers — one per category the pipeline can resolve to ─────
            //
            // Deterministic stand-ins built via AraAgents.deterministic(...) (ADR-046):
            // no LlmClient anywhere in their construction, so — unlike an AraAgent wired
            // to a model — it is impossible for one of these to reason about anything.
            // A real deployment would put a genuine agent (or a plain integration call)
            // behind each of these instead.
            AraAgent billingWorker      = worker("billing-team",  "Routed to Billing. Refund/invoice case opened.");
            AraAgent technicalWorker    = worker("technical-team","Routed to Technical Support. Priority ticket filed.");
            AraAgent salesWorker        = worker("sales-team",    "Routed to Sales. Account manager notified.");
            AraAgent manualReviewWorker = worker("manual-queue",  "Placed in the manual-review queue for a human to triage.");

            // ── The pipeline: three classify tiers cascading into four workers ──────
            AgentPipeline triage = AgentPipeline.builder()

                    // Tier 1 — rules. writeLabelTo/writeConfidenceTo publish the verdict
                    // to RunState under "intent"/"confidence" so later tiers (and the
                    // eventual worker) can read it; escalateBelow sends anything the
                    // rules could not place on to the model tier instead of guessing.
                    .classify("classify-by-rules", rulesTier, IntentRouter.onField("intent")
                            .route("BILLING",   "route-to-billing")
                            .route("TECHNICAL", "route-to-technical")
                            .route("SALES",     "route-to-sales")
                            .writeLabelTo("intent")
                            .confidenceField("confidence")
                            .writeConfidenceTo("confidence")
                            .escalateBelow(RULES_CONFIDENCE_THRESHOLD, "classify-by-model")
                            .orElse("route-to-manual-review"))

                    // Tier 2 — the model. Its own writeLabelTo("intent") overwrites
                    // whatever the rules tier left there (which, if we got this far, was
                    // the "found nothing" verdict anyway) with its own classification.
                    .classify("classify-by-model", modelTier, IntentRouter.onField("intent")
                            .route("BILLING",   "route-to-billing")
                            .route("TECHNICAL", "route-to-technical")
                            .route("SALES",     "route-to-sales")
                            .writeLabelTo("intent")
                            .confidenceField("confidence")
                            .writeConfidenceTo("confidence")
                            .escalateBelow(MODEL_CONFIDENCE_THRESHOLD, "classify-by-human")
                            .orElse("route-to-manual-review"))

                    // Tier 3 — a human. No confidence field: a person's decision is
                    // either usable (they approved or corrected the proposed label) or it
                    // is not (they rejected it, or the request timed out). One
                    // consequence: this tier never calls writeConfidenceTo, so after a
                    // human confirms a ticket RunState's "confidence" key still holds the
                    // model's own (superseded) guess — read it as "the last confidence
                    // any tier recorded", not "how sure the deciding tier was".
                    .classify("classify-by-human", humanTier, IntentRouter.onField("intent")
                            .route("BILLING",   "route-to-billing")
                            .route("TECHNICAL", "route-to-technical")
                            .route("SALES",     "route-to-sales")
                            .orElse("route-to-manual-review"))

                    // The four workers. Each is declared with worker(...), not the more
                    // general step(...): worker(...) both feeds the worker the ORIGINAL
                    // ticket text (not whatever JSON verdict the last classify tier
                    // produced) and ends the pipeline right there — the "exactly one
                    // worker handles it, no supervisory loop" half of the pattern.
                    .worker("route-to-billing",        billingWorker)
                    .worker("route-to-technical",      technicalWorker)
                    .worker("route-to-sales",          salesWorker)
                    .worker("route-to-manual-review",  manualReviewWorker)

                    // Three classify tiers plus one worker: the tightest bound this
                    // three-tier cascade can possibly need, and a concrete answer to
                    // "how many hops before this pipeline gives up" that a caller can
                    // reason about instead of guessing.
                    .maxSteps(4)
                    .build();

            // ── Run three tickets, one per tier that ends up deciding it ────────────
            runTicket(triage, "I was charged twice on my last invoice, please refund the extra amount.");
            runTicket(triage, "Do you have a plan that fits a 50-person team? We're evaluating options.");
            runTicket(triage, "The app just shows a blank screen since this morning, nothing I do fixes it.");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Driving one ticket through the pipeline and reporting what happened
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Runs a single ticket through {@code triage} and prints the path it took, the
     * worker's answer, and the classification that ended up recorded in {@link RunState}
     * — the concrete evidence that "state initialisation" is not just an internal
     * bookkeeping detail but something a caller can inspect after the fact.
     *
     * <p>The task is seeded with a real, in-memory {@link RunState} rather than left at
     * the default {@link AgentTask#of(String)} would carry ({@link RunState#noop()}):
     * without this, every {@code writeLabelTo}/{@code writeConfidenceTo} call along the
     * cascade would be silently discarded, and — more consequentially — the human tier
     * would find no proposed label to confirm, since it reads {@code proposedLabelFrom}
     * from the very same store. Running a classify-and-act pipeline through a real
     * {@code AraRuntime} session would bind this automatically; called directly, as
     * here, the caller is the one responsible for it.
     */
    private static void runTicket(AgentPipeline triage, String ticketText) {
        AgentTask task = AgentTask.of(ticketText)
                .withRunContext(new RunContext(Map.of(), Map.of(), RunState.inMemory()));

        PipelineResult result = triage.run(task);

        System.out.println();
        System.out.println("Ticket      : " + ticketText);
        System.out.println("Path taken  : " + result.stepsExecuted());
        System.out.println("Outcome     : " + (result.success() ? result.finalOutput() : "FAILED — " + result.failureReason()));

        RunState state = task.runContext().state();
        System.out.println("Intent      : " + state.get("intent", String.class).orElse("(never classified)"));
        // "The last confidence any tier recorded", not "how sure the deciding tier was":
        // the human tier never writes this key, so once a ticket escalates all the way to
        // a person, what prints here is still the model's own (overruled) guess.
        System.out.println("Confidence  : " + state.get("confidence", Double.class)
                .map(String::valueOf)
                .orElse("(no confidence recorded — the deciding tier was rules-only)"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The simulated human reviewer
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Stands in for a real reviewer's console: watches {@link InMemoryApprovalGate} for
     * a pending request and, as soon as one appears, approves it outright — confirming
     * whatever label the model tier proposed. A real operator surface would instead
     * render {@link ApprovalRequest#payload()} (an {@link
     * ApprovalClassifier.PendingClassification}, carrying the ticket text, the model's
     * proposed label and the full label vocabulary) for a person to act on, and call
     * {@link InMemoryApprovalGate#submit} with whatever they decide — {@link
     * ApprovalDecision.Approved}, {@link ApprovalDecision.Modified} with a different
     * label, or {@link ApprovalDecision.Rejected}.
     *
     * <p>Runs on its own virtual thread so the pipeline's own thread — parked inside
     * {@link ApprovalClassifier} waiting on this same gate — is free to actually park;
     * polling from the same thread that is doing the waiting would deadlock.
     */
    private static void watchAndApprove(InMemoryApprovalGate gate) {
        Thread.ofVirtual().name("simulated-reviewer").start(() -> {
            try {
                // A short poll loop rather than a blocking wait: ApprovalGate exposes no
                // "notify me when something arrives" primitive, only a point-in-time
                // snapshot via getPendingRequests() — the same primitive a real
                // dashboard would poll on an interval, just much shorter here so the
                // example finishes quickly.
                for (int attempt = 0; attempt < 100; attempt++) {
                    List<ApprovalRequest> pending = gate.getPendingRequests();
                    if (!pending.isEmpty()) {
                        ApprovalRequest request = pending.get(0);
                        System.out.println("  [Reviewer] approving pending request " + request.requestId());
                        gate.submit(request.requestId(), new ApprovalDecision.Approved());
                        return;
                    }
                    TimeUnit.MILLISECONDS.sleep(50);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Workers
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Builds a deterministic worker that always answers {@code fixedAnswer} — good
     * enough to demonstrate routing and state isolation, which is what this example is
     * about. A production worker would instead be a real {@code AraAgent} that acts on
     * the ticket (files it in a ticketing system, drafts a reply, opens a refund) using
     * {@link AgentTask#input()} — the original ticket text, guaranteed by {@code
     * worker(...)} regardless of which tier ended up classifying it.
     */
    private static AraAgent worker(String workerName, String fixedAnswer) {
        return AraAgents.deterministic(AgentId.of(workerName), task -> fixedAnswer);
    }

    private TicketTriageCascadeExample() {}
}
