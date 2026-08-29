package io.ara.runtime.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static io.ara.runtime.pipeline.PipelineTestAgents.echoAgent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RuleClassifier}: the rule evaluation itself, the JSON it emits, and
 * the two pipeline shapes it exists for — a triage with no LLM at all, and a cascade
 * where the rules answer what they know and everything else reaches the model.
 */
class RuleClassifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AgentId ID = AgentId.of("triage-rules");

    private static AraAgent classifier() {
        return RuleClassifier.builder(ID)
                .when("BILLING", "fattura", "rimborso", "pagamento")
                .when("TECH",    "crash", "stack trace", "non si avvia")
                .whenMatches("SALES", Pattern.compile("preventiv|listin", Pattern.CASE_INSENSITIVE))
                .orElse("UNKNOWN");
    }

    private static JsonNode classify(AraAgent agent, String input) {
        AgentResponse response = agent.execute(AgentTask.of(input));
        assertTrue(response.isSuccess(), "classification must not fail: " + response.failureReason());
        try {
            return MAPPER.readTree(response.content());
        } catch (Exception e) {
            throw new AssertionError("classifier emitted invalid JSON: " + response.content(), e);
        }
    }

    // ── Rule evaluation ───────────────────────────────────────────────────────

    @Test
    void a_keyword_rule_assigns_its_label() {
        assertEquals("BILLING", classify(classifier(), "Vorrei un rimborso").path("intent").asText());
    }

    @Test
    void keywords_match_case_insensitively_and_anywhere_in_the_text() {
        assertEquals("TECH", classify(classifier(), "L'app fa CRASH all'avvio").path("intent").asText());
    }

    @Test
    void a_regex_rule_assigns_its_label() {
        assertEquals("SALES", classify(classifier(), "Potete mandarmi un preventivo?").path("intent").asText());
    }

    @Test
    void a_predicate_rule_assigns_its_label() {
        AraAgent agent = RuleClassifier.builder(ID)
                .whenever("LONG", text -> text.length() > 10)
                .orElse("SHORT");

        assertEquals("LONG",  classify(agent, "abbastanza lungo").path("intent").asText());
        assertEquals("SHORT", classify(agent, "corto").path("intent").asText());
    }

    @Test
    void the_first_matching_rule_wins_in_declaration_order() {
        AraAgent agent = RuleClassifier.builder(ID)
                .when("TECH_URGENT", "crash in produzione")
                .when("TECH",        "crash")
                .orElse("UNKNOWN");

        assertEquals("TECH_URGENT", classify(agent, "crash in produzione da stamattina").path("intent").asText(),
                "the more specific rule was declared first, so it must win over the one that also matches");
    }

    @Test
    void a_matched_rule_reports_full_confidence() {
        assertEquals(RuleClassifier.MATCHED_CONFIDENCE,
                classify(classifier(), "Vorrei un rimborso").path("confidence").asDouble());
    }

    @Test
    void an_unmatched_input_gets_the_else_label_at_zero_confidence() {
        JsonNode result = classify(classifier(), "Buongiorno, come state?");

        assertEquals("UNKNOWN", result.path("intent").asText());
        assertEquals(RuleClassifier.UNMATCHED_CONFIDENCE, result.path("confidence").asDouble(),
                "low on purpose, so IntentRouter.escalateBelow catches it");
    }

    @Test
    void keyword_matching_is_plain_substring_matching_word_boundaries_need_a_regex() {
        assertEquals("TECH", classify(classifier(), "Il crashaggio non c'entra").path("intent").asText(),
                "when(...) is substring matching: 'crash' fires inside 'crashaggio'");

        AraAgent wordBounded = RuleClassifier.builder(ID)
                .whenMatches("TECH", Pattern.compile("\\bcrash\\b", Pattern.CASE_INSENSITIVE))
                .orElse("UNKNOWN");
        assertEquals("UNKNOWN", classify(wordBounded, "Il crashaggio non c'entra").path("intent").asText(),
                "whenMatches(...) is the way to demand a whole word");
    }

    // ── Emitted shape ─────────────────────────────────────────────────────────

    @Test
    void field_names_are_configurable_to_match_the_routers_paths() {
        AraAgent agent = RuleClassifier.builder(ID)
                .when("TECH", "crash")
                .labelField("category").confidenceField("score")
                .orElse("UNKNOWN");

        JsonNode result = classify(agent, "crash");
        assertEquals("TECH", result.path("category").asText());
        assertEquals(1.0,    result.path("score").asDouble());
        assertTrue(result.path("intent").isMissingNode());
    }

    @Test
    void a_label_containing_json_metacharacters_is_escaped_not_concatenated() {
        AraAgent agent = RuleClassifier.builder(ID)
                .when("SAY_\"HI\"", "ciao")
                .orElse("UNKNOWN");

        // classify() would throw on invalid JSON before reaching this assertion.
        assertEquals("SAY_\"HI\"", classify(agent, "ciao").path("intent").asText());
    }

    @Test
    void it_is_a_deterministic_agent_that_reports_no_llm_usage() {
        AgentResponse response = classifier().execute(AgentTask.of("rimborso"));

        assertEquals("deterministic", classifier().config().plannerStrategy());
        assertEquals(0, response.inputTokens());
        assertEquals(0, response.outputTokens());
    }

    // ── Builder validation ────────────────────────────────────────────────────

    @Test
    void a_classifier_with_no_rules_is_rejected() {
        assertThrows(IllegalStateException.class, () -> RuleClassifier.builder(ID).orElse("UNKNOWN"));
    }

    @Test
    void a_keyword_rule_with_no_keywords_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> RuleClassifier.builder(ID).when("TECH"));
    }

    @Test
    void a_blank_keyword_is_rejected_because_it_would_match_everything() {
        assertThrows(IllegalArgumentException.class, () -> RuleClassifier.builder(ID).when("TECH", "crash", "  "));
    }

    // ── Pipeline shapes ───────────────────────────────────────────────────────

    @Test
    void a_full_triage_dispatches_with_no_llm_anywhere_in_the_run() {
        AgentPipeline triage = AgentPipeline.builder()
                .classify("classify", classifier(), IntentRouter.onField("intent")
                        .route("BILLING", "billing").route("TECH", "tech").route("SALES", "sales")
                        .orElse("fallback"))
                .worker("billing",  echoAgent("billing",  "billing handled"))
                .worker("tech",     echoAgent("tech",     "tech handled"))
                .worker("sales",    echoAgent("sales",    "sales handled"))
                .worker("fallback", echoAgent("fallback", "escalated"))
                .maxSteps(2)
                .build();

        assertEquals("billing handled", triage.run("Vorrei un rimborso sulla fattura").finalOutput());
        assertEquals("tech handled",    triage.run("L'app fa crash").finalOutput());
        assertEquals("escalated",       triage.run("Buongiorno").finalOutput());
    }

    @Test
    void an_unmatched_input_cascades_to_the_model_while_a_matched_one_never_reaches_it() {
        PipelineTestAgents.CapturingAgent llmClassifier =
                new PipelineTestAgents.CapturingAgent(AgentId.of("llm"), "{\"intent\":\"SALES\"}");

        AgentPipeline triage = AgentPipeline.builder()
                .classify("rules", classifier(), IntentRouter.onField("intent")
                        .route("BILLING", "billing").route("TECH", "tech").route("SALES", "sales")
                        .confidenceField("confidence")
                        .escalateBelow(0.5, "llmClassify")
                        .orElse("fallback"))
                .classify("llmClassify", llmClassifier, IntentRouter.onField("intent")
                        .route("BILLING", "billing").route("TECH", "tech").route("SALES", "sales")
                        .orElse("fallback"))
                .worker("billing",  echoAgent("billing",  "billing handled"))
                .worker("tech",     echoAgent("tech",     "tech handled"))
                .worker("sales",    echoAgent("sales",    "sales handled"))
                .worker("fallback", echoAgent("fallback", "escalated"))
                .maxSteps(3)
                .build();

        PipelineResult matched = triage.run("Vorrei un rimborso");
        assertEquals(List.of("rules", "billing"), matched.stepsExecuted());
        assertNull(llmClassifier.received, "a rule decided it — the model must never have been called");

        PipelineResult cascaded = triage.run("Avete qualcosa per la mia azienda?");
        assertEquals(List.of("rules", "llmClassify", "sales"), cascaded.stepsExecuted());
        assertEquals("Avete qualcosa per la mia azienda?", llmClassifier.lastInput(),
                "the escalated step must see the original text, not the rules' verdict");
    }
}
