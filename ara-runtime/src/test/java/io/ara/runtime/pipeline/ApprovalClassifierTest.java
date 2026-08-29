package io.ara.runtime.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.RunState;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalNotifier;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.hitl.ApprovalTimeoutException;
import io.ara.runtime.hitl.InMemoryApprovalGate;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static io.ara.runtime.pipeline.PipelineTestAgents.echoAgent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ApprovalClassifier}: how each {@link ApprovalDecision} becomes a label,
 * what happens when it cannot, and the escalation shape it exists for — a low-confidence
 * classification reaching a human instead of a worker.
 *
 * <p>Most cases run against a scripted {@link ApprovalGate} so the decision is fixed and
 * the test does not have to synchronise with an operator; two run against the real
 * {@link InMemoryApprovalGate} to prove the parking and the timeout actually work.
 */
class ApprovalClassifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AgentId ID = AgentId.of("triage-human");

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** An {@link ApprovalGate} that answers immediately — with a fixed decision, or by expiring. */
    private static final class ScriptedGate implements ApprovalGate {
        /** {@code null} means "answer with a timeout instead of a decision". */
        private final ApprovalDecision decision;
        final List<ApprovalRequest> seen = new CopyOnWriteArrayList<>();

        ScriptedGate(ApprovalDecision decision) { this.decision = decision; }

        static ScriptedGate timingOut() { return new ScriptedGate(null); }

        @Override public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
            seen.add(request);
            return decision != null
                    ? CompletableFuture.completedFuture(decision)
                    : CompletableFuture.failedFuture(new ApprovalTimeoutException(request));
        }
        @Override public void submit(String requestId, ApprovalDecision decision) { throw new UnsupportedOperationException(); }
        @Override public List<ApprovalRequest> getPendingRequests() { return List.of(); }
    }

    private static AraAgent classifier(ApprovalGate gate) {
        return ApprovalClassifier.builder(ID, gate)
                .labels("BILLING", "TECH", "SALES")
                .proposedLabelFrom("intent")
                .recordOutcomeAs("approval.outcome")
                .orElse("UNKNOWN");
    }

    /** A task carrying a real {@link RunState}, optionally pre-seeded with a proposed label. */
    private static AgentTask task(String text, String proposedLabel) {
        RunState state = RunState.inMemory();
        if (proposedLabel != null) state.put("intent", proposedLabel);
        return AgentTask.of(text).withRunContext(new RunContext(Map.of(), Map.of(), state));
    }

    private static JsonNode classify(AraAgent agent, AgentTask task) {
        AgentResponse response = agent.execute(task);
        assertTrue(response.isSuccess(), "classification must not fail: " + response.failureReason());
        try {
            return MAPPER.readTree(response.content());
        } catch (Exception e) {
            throw new AssertionError("emitted invalid JSON: " + response.content(), e);
        }
    }

    private static ApprovalClassifier.Outcome outcomeOf(AgentTask task) {
        return task.runContext().state()
                .get("approval.outcome", ApprovalClassifier.Outcome.class)
                .orElseThrow(() -> new AssertionError("no outcome recorded"));
    }

    // ── Decision → label ──────────────────────────────────────────────────────

    @Test
    void an_approval_confirms_the_label_the_previous_classifier_proposed() {
        AgentTask task = task("Vorrei un rimborso", "BILLING");

        JsonNode result = classify(classifier(new ScriptedGate(new ApprovalDecision.Approved())), task);

        assertEquals("BILLING", result.path("intent").asText());
        assertEquals(ApprovalClassifier.DECIDED_CONFIDENCE, result.path("confidence").asDouble());
        assertEquals(ApprovalClassifier.Outcome.APPROVED, outcomeOf(task));
    }

    @Test
    void an_approval_with_no_proposed_label_is_unusable_because_there_was_nothing_to_confirm() {
        AgentTask task = task("Buongiorno", null);

        JsonNode result = classify(classifier(new ScriptedGate(new ApprovalDecision.Approved())), task);

        assertEquals("UNKNOWN", result.path("intent").asText());
        assertEquals(ApprovalClassifier.UNRESOLVED_CONFIDENCE, result.path("confidence").asDouble());
        assertEquals(ApprovalClassifier.Outcome.UNUSABLE_DECISION, outcomeOf(task));
    }

    @Test
    void an_approval_of_a_label_outside_the_vocabulary_is_unusable() {
        AgentTask task = task("Contratto NDA", "LEGAL");

        assertEquals("UNKNOWN",
                classify(classifier(new ScriptedGate(new ApprovalDecision.Approved())), task).path("intent").asText());
        assertEquals(ApprovalClassifier.Outcome.UNUSABLE_DECISION, outcomeOf(task));
    }

    @Test
    void a_modified_decision_replaces_the_proposed_label() {
        AgentTask task = task("Vorrei un preventivo", "BILLING");

        JsonNode result = classify(classifier(new ScriptedGate(new ApprovalDecision.Modified("SALES"))), task);

        assertEquals("SALES", result.path("intent").asText());
        assertEquals(ApprovalClassifier.DECIDED_CONFIDENCE, result.path("confidence").asDouble());
        assertEquals(ApprovalClassifier.Outcome.MODIFIED, outcomeOf(task));
    }

    @Test
    void a_modified_decision_naming_a_label_outside_the_vocabulary_is_unusable() {
        AgentTask task = task("Contratto NDA", "BILLING");

        assertEquals("UNKNOWN",
                classify(classifier(new ScriptedGate(new ApprovalDecision.Modified("LEGAL"))), task).path("intent").asText());
        assertEquals(ApprovalClassifier.Outcome.UNUSABLE_DECISION, outcomeOf(task),
                "an operator must not invent a category the pipeline has no worker for");
    }

    @Test
    void a_modified_decision_whose_payload_is_not_a_label_string_is_unusable() {
        AgentTask task = task("Vorrei un rimborso", "BILLING");

        assertEquals("UNKNOWN",
                classify(classifier(new ScriptedGate(new ApprovalDecision.Modified(42))), task).path("intent").asText());
        assertEquals(ApprovalClassifier.Outcome.UNUSABLE_DECISION, outcomeOf(task));
    }

    @Test
    void a_rejection_leaves_the_task_unresolved() {
        AgentTask task = task("Vorrei un rimborso", "BILLING");

        JsonNode result = classify(classifier(new ScriptedGate(new ApprovalDecision.Rejected("non è un ticket"))), task);

        assertEquals("UNKNOWN", result.path("intent").asText());
        assertEquals(ApprovalClassifier.UNRESOLVED_CONFIDENCE, result.path("confidence").asDouble());
        assertEquals(ApprovalClassifier.Outcome.REJECTED, outcomeOf(task));
    }

    @Test
    void a_timeout_leaves_the_task_unresolved_rather_than_failing_the_step() {
        AgentTask task = task("Vorrei un rimborso", "BILLING");

        AgentResponse response = classifier(ScriptedGate.timingOut()).execute(task);

        assertTrue(response.isSuccess(), "a timeout is an outcome, not a step failure");
        assertEquals(ApprovalClassifier.Outcome.TIMED_OUT, outcomeOf(task));
    }

    @Test
    void a_rejection_and_a_timeout_are_indistinguishable_by_label_which_is_why_the_outcome_is_recorded() {
        AgentTask rejected = task("x", "BILLING");
        AgentTask timedOut = task("x", "BILLING");

        classifier(new ScriptedGate(new ApprovalDecision.Rejected("no"))).execute(rejected);
        classifier(ScriptedGate.timingOut()).execute(timedOut);

        assertNotEquals(outcomeOf(rejected), outcomeOf(timedOut));
    }

    // ── The request ───────────────────────────────────────────────────────────

    @Test
    void the_request_carries_the_text_the_proposal_and_the_choices() {
        ScriptedGate gate = new ScriptedGate(new ApprovalDecision.Approved());

        classifier(gate).execute(task("Vorrei un rimborso", "BILLING"));

        ApprovalRequest request = gate.seen.getFirst();
        assertEquals(ID.value(), request.agentId());
        assertEquals(ApprovalClassifier.DEFAULT_ACTION, request.action());

        var payload = assertInstanceOf(ApprovalClassifier.PendingClassification.class, request.payload());
        assertEquals("Vorrei un rimborso", payload.text());
        assertEquals("BILLING", payload.proposedLabel());
        assertEquals(List.of("BILLING", "TECH", "SALES"), payload.candidateLabels());
        assertNotNull(request.metadata().get("taskId"));
    }

    @Test
    void the_notifier_is_told_and_a_broken_one_does_not_sink_the_classification() {
        List<ApprovalRequest> notified = new CopyOnWriteArrayList<>();
        ApprovalNotifier working = notified::add;
        ApprovalNotifier broken  = r -> { throw new IllegalStateException("webhook down"); };

        AraAgent ok = ApprovalClassifier.builder(ID, new ScriptedGate(new ApprovalDecision.Approved()))
                .labels("BILLING").notifier(working).orElse("UNKNOWN");
        AraAgent withBrokenNotifier = ApprovalClassifier.builder(ID, new ScriptedGate(new ApprovalDecision.Approved()))
                .labels("BILLING").notifier(broken).orElse("UNKNOWN");

        assertTrue(ok.execute(task("x", "BILLING")).isSuccess());
        assertEquals(1, notified.size());

        AgentResponse response = withBrokenNotifier.execute(task("x", "BILLING"));
        assertTrue(response.isSuccess(), "the request is registered and still decidable — a dead webhook is not a failure");
        assertTrue(response.content().contains("BILLING"));
    }

    // ── Against the real gate ─────────────────────────────────────────────────

    @Test
    void it_parks_until_an_operator_decides_on_the_real_gate() throws Exception {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        AraAgent agent = ApprovalClassifier.builder(ID, gate)
                .labels("BILLING", "TECH").proposedLabelFrom("intent").timeout(Duration.ofSeconds(30))
                .orElse("UNKNOWN");
        AgentTask task = task("Vorrei un rimborso", "TECH");

        var running = CompletableFuture.supplyAsync(() -> agent.execute(task),
                r -> Thread.ofVirtual().start(r));

        ApprovalRequest pending = awaitPending(gate);
        assertFalse(running.isDone(), "the step must still be parked while nobody has decided");

        gate.submit(pending.requestId(), new ApprovalDecision.Modified("BILLING"));

        AgentResponse response = running.get(5, TimeUnit.SECONDS);
        assertTrue(response.isSuccess());
        assertTrue(response.content().contains("BILLING"));
    }

    @Test
    void the_real_gate_expiring_produces_the_unresolved_label() throws Exception {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        AraAgent agent = ApprovalClassifier.builder(ID, gate)
                .labels("BILLING").proposedLabelFrom("intent").timeout(Duration.ofMillis(150))
                .recordOutcomeAs("approval.outcome")
                .orElse("UNKNOWN");
        AgentTask task = task("Vorrei un rimborso", "BILLING");

        AgentResponse response = CompletableFuture
                .supplyAsync(() -> agent.execute(task), r -> Thread.ofVirtual().start(r))
                .get(5, TimeUnit.SECONDS);

        assertTrue(response.isSuccess());
        assertTrue(response.content().contains("UNKNOWN"));
        assertEquals(ApprovalClassifier.Outcome.TIMED_OUT, outcomeOf(task));
    }

    private static ApprovalRequest awaitPending(InMemoryApprovalGate gate) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            List<ApprovalRequest> pending = gate.getPendingRequests();
            if (!pending.isEmpty()) return pending.getFirst();
            Thread.sleep(10);
        }
        throw new AssertionError("no approval request was registered within 2s");
    }

    // ── Builder validation ────────────────────────────────────────────────────

    @Test
    void a_classifier_with_no_candidate_labels_is_rejected() {
        assertThrows(IllegalStateException.class,
                () -> ApprovalClassifier.builder(ID, new ScriptedGate(new ApprovalDecision.Approved())).orElse("UNKNOWN"));
    }

    @Test
    void a_blank_label_and_a_non_positive_timeout_are_rejected() {
        var builder = ApprovalClassifier.builder(ID, new ScriptedGate(new ApprovalDecision.Approved()));
        assertThrows(IllegalArgumentException.class, () -> builder.labels("BILLING", " "));
        assertThrows(IllegalArgumentException.class, () -> builder.timeout(Duration.ZERO));
    }

    // ── The escalation shape ──────────────────────────────────────────────────

    @Test
    void a_low_confidence_classification_reaches_a_human_and_the_human_picks_the_worker() {
        AraAgent human = ApprovalClassifier.builder(ID, new ScriptedGate(new ApprovalDecision.Modified("SALES")))
                .labels("BILLING", "TECH", "SALES")
                .proposedLabelFrom("intent")
                .orElse("UNKNOWN");

        AgentPipeline triage = AgentPipeline.builder()
                .classify("model", echoAgent("model", "{\"intent\":\"BILLING\",\"confidence\":0.3}"),
                        IntentRouter.onField("intent")
                                .route("BILLING", "billing").route("TECH", "tech").route("SALES", "sales")
                                .writeLabelTo("intent")
                                .confidenceField("confidence")
                                .escalateBelow(0.7, "human")
                                .orElse("fallback"))
                .classify("human", human, IntentRouter.onField("intent")
                        .route("BILLING", "billing").route("TECH", "tech").route("SALES", "sales")
                        .orElse("fallback"))
                .worker("billing",  echoAgent("billing",  "billing handled"))
                .worker("tech",     echoAgent("tech",     "tech handled"))
                .worker("sales",    echoAgent("sales",    "sales handled"))
                .worker("fallback", echoAgent("fallback", "queued for triage"))
                .maxSteps(3)
                .build();

        PipelineResult result = triage.run(task("Avete un listino?", null));

        assertEquals(List.of("model", "human", "sales"), result.stepsExecuted(),
                "the model was unsure, so a human decided — and overruled its guess");
        assertEquals("sales handled", result.finalOutput());
    }

    @Test
    void a_human_who_does_not_answer_sends_the_task_to_the_default_queue_not_to_a_wrong_worker() {
        AraAgent human = ApprovalClassifier.builder(ID, ScriptedGate.timingOut())
                .labels("BILLING", "SALES").proposedLabelFrom("intent").orElse("UNKNOWN");

        AgentPipeline triage = AgentPipeline.builder()
                .classify("model", echoAgent("model", "{\"intent\":\"BILLING\",\"confidence\":0.3}"),
                        IntentRouter.onField("intent")
                                .route("BILLING", "billing").route("SALES", "sales")
                                .writeLabelTo("intent").confidenceField("confidence")
                                .escalateBelow(0.7, "human")
                                .orElse("fallback"))
                .classify("human", human, IntentRouter.onField("intent")
                        .route("BILLING", "billing").route("SALES", "sales")
                        .orElse("fallback"))
                .worker("billing",  echoAgent("billing",  "billing handled"))
                .worker("sales",    echoAgent("sales",    "sales handled"))
                .worker("fallback", echoAgent("fallback", "queued for triage"))
                .maxSteps(3)
                .build();

        PipelineResult result = triage.run(task("Avete un listino?", null));

        assertEquals(List.of("model", "human", "fallback"), result.stepsExecuted());
        assertEquals("queued for triage", result.finalOutput(),
                "an unanswered escalation must not silently fall back to the guess the model was unsure of");
    }
}
