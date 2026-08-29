package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.RunState;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.runtime.telemetry.RecordingTelemetry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static io.ara.runtime.pipeline.PipelineTestAgents.echoAgent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ClassifyAndActSpec}: that a spec parses, that what it builds actually
 * dispatches, that a category can be added by editing the document alone, and that every
 * way of writing an unusable spec fails at load time rather than on the ticket that first
 * exercises it.
 */
class ClassifyAndActSpecTest {

    /** Answers every approval immediately with a fixed decision. */
    private record FixedGate(ApprovalDecision decision) implements ApprovalGate {
        @Override public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest r) {
            return CompletableFuture.completedFuture(decision);
        }
        @Override public void submit(String requestId, ApprovalDecision d) { throw new UnsupportedOperationException(); }
        @Override public List<ApprovalRequest> getPendingRequests() { return List.of(); }
    }

    private static final String THREE_TIER = """
        {
          "classifiers": [
            { "name": "rules", "type": "rules",
              "rules": [ { "label": "BILLING", "keywords": ["fattura", "rimborso"] },
                         { "label": "SALES",   "regex": "preventiv|listin" } ],
              "unmatchedLabel": "UNKNOWN",
              "routes": { "BILLING": "billing", "SALES": "sales" },
              "writeLabelTo": "intent",
              "confidenceField": "confidence",
              "escalateBelow": 0.5, "escalateTo": "model",
              "orElse": "fallback" },

            { "name": "model", "type": "agent", "agent": "triage-llm",
              "routes": { "BILLING": "billing", "SALES": "sales" },
              "writeLabelTo": "intent",
              "confidenceField": "confidence",
              "escalateBelow": 0.7, "escalateTo": "human",
              "orElse": "fallback" },

            { "name": "human", "type": "approval",
              "labels": ["BILLING", "SALES"], "proposedLabelFrom": "intent",
              "timeoutSeconds": 900, "recordOutcomeAs": "approval.outcome",
              "unmatchedLabel": "UNKNOWN",
              "routes": { "BILLING": "billing", "SALES": "sales" },
              "orElse": "fallback" }
          ],
          "workers": { "billing": "billing-agent", "sales": "sales-agent", "fallback": "triage-queue" }
        }
        """;

    private static ClassifyAndActSpec.Bindings bindings(String llmOutput, ApprovalDecision decision) {
        Map<String, AraAgent> agents = Map.of(
                "triage-llm",    echoAgent("triage-llm",    llmOutput),
                "billing-agent", echoAgent("billing-agent", "billing handled"),
                "sales-agent",   echoAgent("sales-agent",   "sales handled"),
                "triage-queue",  echoAgent("triage-queue",  "queued for triage"));
        return ClassifyAndActSpec.Bindings.of(ClassifyAndActSpec.AgentResolver.of(agents))
                .withApprovalGate(new FixedGate(decision));
    }

    private static AgentTask task(String text) {
        return AgentTask.of(text).withRunContext(new RunContext(Map.of(), Map.of(), RunState.inMemory()));
    }

    // ── What the spec builds actually runs ────────────────────────────────────

    @Test
    void a_rule_decides_and_no_other_tier_is_reached() {
        AgentPipeline pipeline = ClassifyAndActSpec.fromJson(THREE_TIER)
                .build(bindings("{\"intent\":\"SALES\",\"confidence\":0.9}", new ApprovalDecision.Approved()));

        PipelineResult result = pipeline.run(task("Vorrei un rimborso sulla fattura"));

        assertEquals(List.of("rules", "billing"), result.stepsExecuted());
        assertEquals("billing handled", result.finalOutput());
    }

    @Test
    void an_unmatched_input_cascades_to_the_model_tier() {
        AgentPipeline pipeline = ClassifyAndActSpec.fromJson(THREE_TIER)
                .build(bindings("{\"intent\":\"SALES\",\"confidence\":0.9}", new ApprovalDecision.Approved()));

        PipelineResult result = pipeline.run(task("Avete qualcosa per la mia azienda?"));

        assertEquals(List.of("rules", "model", "sales"), result.stepsExecuted());
    }

    @Test
    void an_unsure_model_cascades_to_the_human_tier() {
        AgentPipeline pipeline = ClassifyAndActSpec.fromJson(THREE_TIER)
                .build(bindings("{\"intent\":\"SALES\",\"confidence\":0.2}", new ApprovalDecision.Modified("BILLING")));

        PipelineResult result = pipeline.run(task("Avete qualcosa per la mia azienda?"));

        assertEquals(List.of("rules", "model", "human", "billing"), result.stepsExecuted(),
                "three tiers, and the human overruled the model");
        assertEquals("billing handled", result.finalOutput());
    }

    @Test
    void maxSteps_defaults_to_one_hop_per_tier_plus_one_worker() {
        assertEquals(4, ClassifyAndActSpec.fromJson(THREE_TIER).maxSteps());
    }

    @Test
    void an_explicit_maxSteps_overrides_the_default() {
        String json = THREE_TIER.replaceFirst("\\{", "{ \"maxSteps\": 9,");
        assertEquals(9, ClassifyAndActSpec.fromJson(json).maxSteps());
    }

    @Test
    void the_router_decision_is_still_traced_when_the_pipeline_came_from_a_spec() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        ClassifyAndActSpec.fromJson(THREE_TIER)
                .build(bindings("{\"intent\":\"SALES\",\"confidence\":0.9}", new ApprovalDecision.Approved())
                        .withTelemetry(telemetry))
                .run(task("Vorrei un rimborso"));

        var span = telemetry.spansNamed("pipeline.classify").getFirst();
        assertEquals("BILLING", span.attributes().get("routing.label"));
        assertEquals("billing", span.attributes().get("routing.target"));
    }

    // ── The point of the whole thing ──────────────────────────────────────────

    @Test
    void adding_a_category_is_an_edit_to_the_document_with_no_java_change() {
        String withTech = THREE_TIER
                .replace("{ \"label\": \"BILLING\", \"keywords\": [\"fattura\", \"rimborso\"] },",
                         "{ \"label\": \"BILLING\", \"keywords\": [\"fattura\", \"rimborso\"] },\n"
                       + "{ \"label\": \"TECH\", \"keywords\": [\"crash\"] },")
                .replace("\"routes\": { \"BILLING\": \"billing\", \"SALES\": \"sales\" }",
                         "\"routes\": { \"BILLING\": \"billing\", \"SALES\": \"sales\", \"TECH\": \"tech\" }")
                .replace("\"fallback\": \"triage-queue\"", "\"tech\": \"tech-agent\", \"fallback\": \"triage-queue\"");

        Map<String, AraAgent> agents = new java.util.HashMap<>(Map.of(
                "triage-llm",    echoAgent("triage-llm",    "{\"intent\":\"SALES\",\"confidence\":0.9}"),
                "billing-agent", echoAgent("billing-agent", "billing handled"),
                "sales-agent",   echoAgent("sales-agent",   "sales handled"),
                "triage-queue",  echoAgent("triage-queue",  "queued for triage")));
        agents.put("tech-agent", echoAgent("tech-agent", "tech handled"));

        PipelineResult result = ClassifyAndActSpec.fromJson(withTech)
                .build(ClassifyAndActSpec.Bindings.of(ClassifyAndActSpec.AgentResolver.of(agents))
                        .withApprovalGate(new FixedGate(new ApprovalDecision.Approved())))
                .run(task("L'app fa crash"));

        assertEquals(List.of("rules", "tech"), result.stepsExecuted());
        assertEquals("tech handled", result.finalOutput());
    }

    // ── Failing at load time, not on the first ticket ─────────────────────────

    /**
     * Asserts the spec is refused for a reason of its own. Every reference resolves, so
     * an agent-resolution failure cannot mask the error under test.
     */
    private static void assertRejected(String json, String expectedFragment) {
        ClassifyAndActSpec.AgentResolver everything = ref -> echoAgent(ref, "x");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClassifyAndActSpec.fromJson(json).build(ClassifyAndActSpec.Bindings.of(everything)));
        assertTrue(e.getMessage().contains(expectedFragment),
                "expected a message mentioning '" + expectedFragment + "', was: " + e.getMessage());
    }

    private static final String MINIMAL = """
        { "classifiers": [ { "name": "rules", "type": "rules",
                             "rules": [ { "label": "A", "keywords": ["a"] } ],
                             "unmatchedLabel": "U",
                             "routes": { "A": "wa" },
                             "orElse": "wf" } ],
          "workers": { "wa": "agent-a", "wf": "agent-f" } }
        """;

    @Test
    void a_route_to_an_undeclared_worker_fails_at_load_time() {
        String json = MINIMAL.replace("\"routes\": { \"A\": \"wa\" }", "\"routes\": { \"A\": \"nope\" }");
        assertRejected(json, "nope");
    }

    @Test
    void an_unresolvable_agent_reference_names_itself_and_where_it_was_used() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClassifyAndActSpec.fromJson(MINIMAL)
                        .build(ClassifyAndActSpec.Bindings.of(ClassifyAndActSpec.AgentResolver.of(Map.of()))));
        assertTrue(e.getMessage().contains("agent-a") || e.getMessage().contains("agent-f"), e.getMessage());
        assertTrue(e.getMessage().contains("workers."), e.getMessage());
    }

    @Test
    void an_approval_tier_without_a_bound_gate_is_refused() {
        String json = """
            { "classifiers": [ { "name": "human", "type": "approval", "labels": ["A"],
                                 "unmatchedLabel": "U", "routes": { "A": "wa" }, "orElse": "wf" } ],
              "workers": { "wa": "agent-a", "wf": "agent-f" } }
            """;
        Map<String, AraAgent> agents = Map.of("agent-a", echoAgent("a", "x"), "agent-f", echoAgent("f", "x"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClassifyAndActSpec.fromJson(json)
                        .build(ClassifyAndActSpec.Bindings.of(ClassifyAndActSpec.AgentResolver.of(agents))));
        assertTrue(e.getMessage().contains("ApprovalGate"), e.getMessage());
    }

    @Test
    void malformed_json_is_reported_as_such() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ClassifyAndActSpec.fromJson("{ not json"));
        assertTrue(e.getMessage().contains("not valid JSON"), e.getMessage());
    }

    @Test
    void an_unknown_classifier_type_lists_the_ones_that_exist() {
        assertRejected(MINIMAL.replace("\"type\": \"rules\"", "\"type\": \"magic\""), "'rules', 'agent', 'approval'");
    }

    @Test
    void a_rule_declaring_both_keywords_and_a_regex_is_refused() {
        assertRejected(MINIMAL.replace("{ \"label\": \"A\", \"keywords\": [\"a\"] }",
                                       "{ \"label\": \"A\", \"keywords\": [\"a\"], \"regex\": \"a\" }"),
                "exactly one of 'keywords' or 'regex'");
    }

    @Test
    void a_rule_declaring_neither_is_refused() {
        assertRejected(MINIMAL.replace("{ \"label\": \"A\", \"keywords\": [\"a\"] }", "{ \"label\": \"A\" }"),
                "exactly one of 'keywords' or 'regex'");
    }

    @Test
    void an_invalid_regex_is_refused_at_load_time_not_on_the_first_task() {
        assertRejected(MINIMAL.replace("\"keywords\": [\"a\"]", "\"regex\": \"[\""), "not a valid pattern");
    }

    @Test
    void escalateBelow_and_escalateTo_must_be_declared_together() {
        assertRejected(MINIMAL.replace("\"orElse\": \"wf\"", "\"escalateBelow\": 0.5, \"orElse\": \"wf\""),
                "together");
    }

    @Test
    void escalateBelow_without_a_confidence_field_is_refused() {
        assertRejected(MINIMAL.replace("\"orElse\": \"wf\"",
                        "\"escalateBelow\": 0.5, \"escalateTo\": \"wf\", \"orElse\": \"wf\""),
                "confidenceField");
    }

    // ── The escalateTo → approval label-key coupling ─────────────────────────
    //
    // Nothing else in the spec checks that an escalating tier's writeLabelTo and the
    // approval tier's proposedLabelFrom name the same RunState key — get them out of
    // sync and every human decision silently becomes UNUSABLE_DECISION. These pin the
    // check added alongside worker() isolation.

    @Test
    void an_escalating_tier_that_writes_a_different_key_than_the_approval_tier_reads_is_refused() {
        String json = """
            { "classifiers": [
                { "name": "rules", "type": "rules",
                  "rules": [ { "label": "A", "keywords": ["a"] } ],
                  "unmatchedLabel": "U",
                  "routes": { "A": "wa" },
                  "writeLabelTo": "category",
                  "confidenceField": "confidence",
                  "escalateBelow": 0.5, "escalateTo": "human",
                  "orElse": "wf" },
                { "name": "human", "type": "approval", "labels": ["A"],
                  "proposedLabelFrom": "intent",
                  "unmatchedLabel": "U", "routes": { "A": "wa" }, "orElse": "wf" }
              ],
              "workers": { "wa": "agent-a", "wf": "agent-f" } }
            """;
        assertRejected(json, "reads the proposed label from RunState key 'intent'");
    }

    @Test
    void an_escalating_tier_that_declares_no_writeLabelTo_is_refused() {
        String json = """
            { "classifiers": [
                { "name": "rules", "type": "rules",
                  "rules": [ { "label": "A", "keywords": ["a"] } ],
                  "unmatchedLabel": "U",
                  "routes": { "A": "wa" },
                  "confidenceField": "confidence",
                  "escalateBelow": 0.5, "escalateTo": "human",
                  "orElse": "wf" },
                { "name": "human", "type": "approval", "labels": ["A"],
                  "unmatchedLabel": "U", "routes": { "A": "wa" }, "orElse": "wf" }
              ],
              "workers": { "wa": "agent-a", "wf": "agent-f" } }
            """;
        assertRejected(json, "does not declare writeLabelTo at all");
    }

    @Test
    void matching_keys_via_the_default_proposedLabelFrom_are_accepted() {
        // Neither tier declares "proposedLabelFrom"/mismatched keys explicitly: the
        // escalating tier writes to "intent", and ApprovalClassifier's own default
        // read key is also "intent" — this must be accepted, not merely tolerated.
        String json = """
            { "classifiers": [
                { "name": "rules", "type": "rules",
                  "rules": [ { "label": "A", "keywords": ["a"] } ],
                  "unmatchedLabel": "U",
                  "routes": { "A": "wa" },
                  "writeLabelTo": "intent",
                  "confidenceField": "confidence",
                  "escalateBelow": 0.5, "escalateTo": "human",
                  "orElse": "wf" },
                { "name": "human", "type": "approval", "labels": ["A"],
                  "unmatchedLabel": "U", "routes": { "A": "wa" }, "orElse": "wf" }
              ],
              "workers": { "wa": "agent-a", "wf": "agent-f" } }
            """;
        Map<String, AraAgent> agents = Map.of("agent-a", echoAgent("a", "x"), "agent-f", echoAgent("f", "x"));

        assertDoesNotThrow(() -> ClassifyAndActSpec.fromJson(json)
                .build(ClassifyAndActSpec.Bindings.of(ClassifyAndActSpec.AgentResolver.of(agents))
                        .withApprovalGate(new FixedGate(new ApprovalDecision.Approved()))));
    }

    @Test
    void an_explicit_matching_key_is_accepted() {
        String json = """
            { "classifiers": [
                { "name": "rules", "type": "rules",
                  "rules": [ { "label": "A", "keywords": ["a"] } ],
                  "unmatchedLabel": "U",
                  "routes": { "A": "wa" },
                  "writeLabelTo": "category",
                  "confidenceField": "confidence",
                  "escalateBelow": 0.5, "escalateTo": "human",
                  "orElse": "wf" },
                { "name": "human", "type": "approval", "labels": ["A"],
                  "proposedLabelFrom": "category",
                  "unmatchedLabel": "U", "routes": { "A": "wa" }, "orElse": "wf" }
              ],
              "workers": { "wa": "agent-a", "wf": "agent-f" } }
            """;
        Map<String, AraAgent> agents = Map.of("agent-a", echoAgent("a", "x"), "agent-f", echoAgent("f", "x"));

        assertDoesNotThrow(() -> ClassifyAndActSpec.fromJson(json)
                .build(ClassifyAndActSpec.Bindings.of(ClassifyAndActSpec.AgentResolver.of(agents))
                        .withApprovalGate(new FixedGate(new ApprovalDecision.Approved()))));
    }

    @Test
    void an_approval_tier_that_is_never_an_escalation_target_is_not_checked() {
        // Declared first, reachable only as the pipeline's entry point — a legitimate
        // use (the proposed label is seeded into RunState from outside the pipeline)
        // that this check must not reject just because no tier's writeLabelTo matches.
        String json = """
            { "classifiers": [
                { "name": "human", "type": "approval", "labels": ["A"],
                  "proposedLabelFrom": "whatever-was-seeded-externally",
                  "unmatchedLabel": "U", "routes": { "A": "wa" }, "orElse": "wf" }
              ],
              "workers": { "wa": "agent-a", "wf": "agent-f" } }
            """;
        Map<String, AraAgent> agents = Map.of("agent-a", echoAgent("a", "x"), "agent-f", echoAgent("f", "x"));

        assertDoesNotThrow(() -> ClassifyAndActSpec.fromJson(json)
                .build(ClassifyAndActSpec.Bindings.of(ClassifyAndActSpec.AgentResolver.of(agents))
                        .withApprovalGate(new FixedGate(new ApprovalDecision.Approved()))));
    }

    @Test
    void a_missing_required_field_names_its_path() {
        assertRejected(MINIMAL.replace("\"orElse\": \"wf\"", "\"unused\": \"wf\""), "classifiers[0].orElse");
    }

    @Test
    void empty_routes_are_refused_because_such_a_tier_can_only_take_its_else_arc() {
        assertRejected(MINIMAL.replace("\"routes\": { \"A\": \"wa\" }", "\"routes\": {}"), "routes");
    }

    @Test
    void duplicate_classifier_names_are_refused() {
        String tier = "{ \"name\": \"rules\", \"type\": \"rules\", "
                + "\"rules\": [ { \"label\": \"A\", \"keywords\": [\"a\"] } ], "
                + "\"unmatchedLabel\": \"U\", \"routes\": { \"A\": \"wa\" }, \"orElse\": \"wf\" }";
        String json = "{ \"classifiers\": [ " + tier + ", " + tier + " ], "
                + "\"workers\": { \"wa\": \"agent-a\", \"wf\": \"agent-f\" } }";
        assertRejected(json, "duplicate classifier name");
    }

    @Test
    void a_name_used_for_both_a_classifier_and_a_worker_is_refused() {
        assertRejected(MINIMAL.replace("\"wa\": \"agent-a\"", "\"rules\": \"agent-a\""),
                "both as a classifier and as a worker");
    }

    @Test
    void an_empty_classifier_list_or_worker_map_is_refused() {
        assertThrows(IllegalArgumentException.class,
                () -> ClassifyAndActSpec.fromJson("{ \"classifiers\": [], \"workers\": { \"a\": \"b\" } }"));
        assertThrows(IllegalArgumentException.class,
                () -> ClassifyAndActSpec.fromJson(MINIMAL.replace("{ \"wa\": \"agent-a\", \"wf\": \"agent-f\" }", "{}")));
    }

    @Test
    void resolving_by_id_against_a_registry_works_as_the_other_resolver_does() {
        AgentRegistryFixture fixture = new AgentRegistryFixture();
        AraAgent found = fixture.resolver().resolve("billing-agent");

        assertNotNull(found);
        assertEquals(AgentId.of("billing-agent"), found.agentId());
        assertNull(fixture.resolver().resolve("nobody"), "an unknown id resolves to null, not an exception");
    }

    /** Wraps a real {@link io.ara.runtime.agent.AgentRegistry} so the registry-backed resolver is exercised. */
    private static final class AgentRegistryFixture {
        private final io.ara.runtime.agent.AgentRegistry registry = new io.ara.runtime.agent.AgentRegistry();

        AgentRegistryFixture() {
            // A real agent, not the echo double: AgentRegistry reads config().agentType(),
            // which the doubles in this package leave null.
            registry.register(io.ara.core.agent.AraAgents.deterministic(
                    AgentId.of("billing-agent"), t -> "billing handled"));
        }

        ClassifyAndActSpec.AgentResolver resolver() {
            return ClassifyAndActSpec.AgentResolver.byId(registry);
        }
    }
}
