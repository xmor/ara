package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.RunState;
import io.ara.core.common.AgentId;
import io.ara.runtime.telemetry.RecordingTelemetry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static io.ara.runtime.pipeline.PipelineTestAgents.echoAgent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link IntentRouter} and the classify-and-act wiring it enables on
 * {@link AgentPipeline.Builder}.
 *
 * <p>Split in three: the pure {@link IntentRouter#classify(String)} mapping, the builder's
 * refusal to produce an unusable router, and the end-to-end pipeline behaviour (worker
 * input, run state, span, build-time target check).
 */
class IntentRouterTest {

    private static IntentRouter threeWayRouter() {
        return IntentRouter.onField("intent")
                .route("TECH",    "tech")
                .route("SALES",   "sales")
                .route("BILLING", "billing")
                .orElse("fallback");
    }

    private static AgentTask taskWithState(String input) {
        return AgentTask.of(input).withRunContext(new RunContext(Map.of(), Map.of(), RunState.inMemory()));
    }

    // ── Label mapping ─────────────────────────────────────────────────────────

    @Test
    void known_label_routes_to_its_worker() {
        IntentRouter.Decision d = threeWayRouter().classify("{\"intent\":\"SALES\"}");

        assertEquals("sales", d.target());
        assertEquals("SALES", d.label());
        assertEquals(IntentRouter.Reason.MATCHED, d.reason());
        assertNull(d.confidence());
    }

    @Test
    void labels_match_case_insensitively_and_ignore_surrounding_whitespace_by_default() {
        assertEquals("tech", threeWayRouter().classify("{\"intent\":\"  tech \"}").target());
    }

    @Test
    void caseSensitive_router_sends_a_differently_cased_label_to_the_else_arc() {
        IntentRouter router = IntentRouter.onField("intent")
                .caseSensitive()
                .route("TECH", "tech")
                .orElse("fallback");

        assertEquals("tech",     router.classify("{\"intent\":\"TECH\"}").target());
        assertEquals("fallback", router.classify("{\"intent\":\"tech\"}").target());
    }

    @Test
    void label_with_no_declared_route_takes_the_else_arc_and_is_still_reported() {
        IntentRouter.Decision d = threeWayRouter().classify("{\"intent\":\"LEGAL\"}");

        assertEquals("fallback", d.target());
        assertEquals("LEGAL",    d.label(), "the unroutable label is still carried, so it can be logged");
        assertEquals(IntentRouter.Reason.UNKNOWN_LABEL, d.reason());
    }

    @Test
    void absent_blank_and_null_label_fields_all_take_the_else_arc() {
        IntentRouter router = threeWayRouter();

        assertEquals(IntentRouter.Reason.MISSING_LABEL, router.classify("{\"other\":\"TECH\"}").reason());
        assertEquals(IntentRouter.Reason.MISSING_LABEL, router.classify("{\"intent\":\"   \"}").reason());
        assertEquals(IntentRouter.Reason.MISSING_LABEL, router.classify("{\"intent\":null}").reason());
    }

    @Test
    void unparseable_output_takes_the_else_arc_instead_of_throwing() {
        IntentRouter.Decision d = threeWayRouter().classify("I think this is a technical issue.");

        assertEquals("fallback", d.target());
        assertNull(d.label());
        assertEquals(IntentRouter.Reason.UNPARSEABLE_OUTPUT, d.reason());
    }

    @Test
    void nested_label_paths_are_read_with_dot_notation() {
        IntentRouter router = IntentRouter.onField("result.intent")
                .route("TECH", "tech")
                .orElse("fallback");

        assertEquals("tech", router.classify("{\"result\":{\"intent\":\"TECH\"}}").target());
    }

    @Test
    void onOutput_treats_the_whole_trimmed_output_as_the_label() {
        IntentRouter router = IntentRouter.onOutput()
                .route("TECH", "tech")
                .orElse("fallback");

        assertEquals("tech",     router.classify("  TECH\n").target());
        assertEquals("fallback", router.classify("TECHNICAL").target());
    }

    // ── Confidence ────────────────────────────────────────────────────────────

    private static IntentRouter escalatingRouter() {
        return IntentRouter.onField("intent")
                .route("TECH", "tech")
                .confidenceField("confidence")
                .escalateBelow(0.7, "human")
                .orElse("fallback");
    }

    @Test
    void confidence_at_or_above_the_threshold_routes_normally() {
        IntentRouter.Decision d = escalatingRouter().classify("{\"intent\":\"TECH\",\"confidence\":0.7}");

        assertEquals("tech", d.target());
        assertEquals(0.7, d.confidence());
        assertEquals(IntentRouter.Reason.MATCHED, d.reason());
    }

    @Test
    void confidence_below_the_threshold_escalates_even_for_a_known_label() {
        IntentRouter.Decision d = escalatingRouter().classify("{\"intent\":\"TECH\",\"confidence\":0.42}");

        assertEquals("human", d.target());
        assertEquals("TECH",  d.label());
        assertEquals(0.42,    d.confidence());
        assertEquals(IntentRouter.Reason.LOW_CONFIDENCE, d.reason());
    }

    @Test
    void a_configured_threshold_with_no_reported_confidence_escalates_rather_than_assuming_certainty() {
        IntentRouter.Decision d = escalatingRouter().classify("{\"intent\":\"TECH\"}");

        assertEquals("human", d.target());
        assertEquals(IntentRouter.Reason.MISSING_CONFIDENCE, d.reason());
    }

    @Test
    void a_non_numeric_confidence_is_treated_as_absent() {
        assertEquals(IntentRouter.Reason.MISSING_CONFIDENCE,
                escalatingRouter().classify("{\"intent\":\"TECH\",\"confidence\":\"high\"}").reason());
    }

    @Test
    void confidence_is_reported_without_a_threshold_but_does_not_route() {
        IntentRouter router = IntentRouter.onField("intent")
                .route("TECH", "tech")
                .confidenceField("confidence")
                .orElse("fallback");

        IntentRouter.Decision d = router.classify("{\"intent\":\"TECH\",\"confidence\":0.1}");
        assertEquals("tech", d.target());
        assertEquals(0.1, d.confidence());
    }

    // ── Builder validation ────────────────────────────────────────────────────

    @Test
    void a_router_with_no_routes_is_rejected() {
        assertThrows(IllegalStateException.class,
                () -> IntentRouter.onField("intent").orElse("fallback"));
    }

    @Test
    void escalateBelow_without_a_confidence_field_is_rejected() {
        assertThrows(IllegalStateException.class, () -> IntentRouter.onField("intent")
                .route("TECH", "tech")
                .escalateBelow(0.7, "human")
                .orElse("fallback"));
    }

    @Test
    void confidenceField_on_a_raw_output_router_is_rejected() {
        assertThrows(IllegalStateException.class, () -> IntentRouter.onOutput()
                .route("TECH", "tech")
                .confidenceField("confidence")
                .orElse("fallback"));
    }

    @Test
    void caseSensitive_after_routes_are_declared_is_rejected() {
        assertThrows(IllegalStateException.class, () -> IntentRouter.onField("intent")
                .route("TECH", "tech")
                .caseSensitive());
    }

    @Test
    void mapping_the_same_label_twice_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> IntentRouter.onField("intent")
                .route("TECH", "tech")
                .route("tech", "other"));
    }

    @Test
    void targets_covers_the_routes_the_else_arc_and_the_escalation_arc() {
        assertEquals(Map.of("tech", 1, "human", 1, "fallback", 1).keySet(), escalatingRouter().targets());
    }

    // ── Pipeline wiring ───────────────────────────────────────────────────────

    private static AgentPipeline.Builder triageBuilder(IntentRouter router) {
        return AgentPipeline.builder()
                .classify("classify", echoAgent("classifier", "{\"intent\":\"TECH\",\"confidence\":0.9}"), router)
                .worker("tech",     echoAgent("tech",     "tech handled"))
                .worker("sales",    echoAgent("sales",    "sales handled"))
                .worker("billing",  echoAgent("billing",  "billing handled"))
                .worker("fallback", echoAgent("fallback", "escalated to a human"))
                .maxSteps(10);
    }

    @Test
    void classify_dispatches_to_exactly_one_worker_and_ends() {
        PipelineResult result = triageBuilder(threeWayRouter()).build().run("My laptop will not boot");

        assertTrue(result.success());
        assertEquals("tech handled", result.finalOutput());
        // Under a deliberately loose maxSteps: the run stops after the chosen worker
        // because worker() is terminal, not because it hit the cap — the sibling workers
        // declared after "tech" must never run.
        assertEquals(java.util.List.of("classify", "tech"), result.stepsExecuted());
    }

    @Test
    void the_worker_receives_the_original_task_not_the_classifier_label() {
        PipelineTestAgents.CapturingAgent worker =
                new PipelineTestAgents.CapturingAgent(AgentId.of("tech"), "tech handled");

        AgentPipeline pipeline = AgentPipeline.builder()
                .classify("classify", echoAgent("classifier", "{\"intent\":\"TECH\"}"), threeWayRouter())
                .worker("tech",     worker)
                .worker("sales",    echoAgent("sales",    "x"))
                .worker("billing",  echoAgent("billing",  "x"))
                .worker("fallback", echoAgent("fallback", "x"))
                .maxSteps(10)
                .build();

        pipeline.run("My laptop will not boot");

        assertEquals("My laptop will not boot", worker.lastInput());
    }

    @Test
    void the_label_and_confidence_land_in_the_run_state_for_the_worker_to_read() {
        IntentRouter router = IntentRouter.onField("intent")
                .route("TECH", "tech").route("SALES", "sales").route("BILLING", "billing")
                .writeLabelTo("intent")
                .confidenceField("confidence")
                .writeConfidenceTo("confidence")
                .orElse("fallback");

        AgentTask task = taskWithState("My laptop will not boot");
        triageBuilder(router).build().run(task);

        RunState state = task.runContext().state();
        assertEquals("TECH", state.get("intent", String.class).orElseThrow());
        assertEquals(0.9,    state.get("confidence", Double.class).orElseThrow());
    }

    @Test
    void an_unroutable_label_still_records_what_was_seen_in_the_run_state() {
        IntentRouter router = IntentRouter.onField("intent")
                .route("TECH", "tech").route("SALES", "sales").route("BILLING", "billing")
                .writeLabelTo("intent")
                .orElse("fallback");

        AgentTask task = taskWithState("Please review our NDA");
        AgentPipeline pipeline = AgentPipeline.builder()
                .classify("classify", echoAgent("classifier", "{\"intent\":\"LEGAL\"}"), router)
                .worker("tech",     echoAgent("tech",     "x"))
                .worker("sales",    echoAgent("sales",    "x"))
                .worker("billing",  echoAgent("billing",  "x"))
                .worker("fallback", echoAgent("fallback", "escalated to a human"))
                .maxSteps(10)
                .build();

        PipelineResult result = pipeline.run(task);

        assertEquals("escalated to a human", result.finalOutput());
        assertEquals("LEGAL", task.runContext().state().get("intent", String.class).orElseThrow());
    }

    @Test
    void a_noop_run_state_does_not_fail_the_run() {
        IntentRouter router = IntentRouter.onField("intent")
                .route("TECH", "tech").route("SALES", "sales").route("BILLING", "billing")
                .writeLabelTo("intent")
                .orElse("fallback");

        // AgentTask.of(...) carries RunState.noop(): the writes go nowhere, but routing
        // must still work — a pipeline run outside a session is a legitimate way to use this.
        assertTrue(triageBuilder(router).build().run("My laptop will not boot").success());
    }

    @Test
    void the_routing_decision_is_emitted_as_a_span() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        IntentRouter router = IntentRouter.onField("intent")
                .route("TECH", "tech").route("SALES", "sales").route("BILLING", "billing")
                .confidenceField("confidence")
                .telemetry(telemetry)
                .orElse("fallback");

        triageBuilder(router).build().run("My laptop will not boot");

        var spans = telemetry.spansNamed("pipeline.classify");
        assertEquals(1, spans.size());
        Map<String, Object> attrs = spans.getFirst().attributes();
        assertEquals("TECH",    attrs.get("routing.label"));
        assertEquals("tech",    attrs.get("routing.target"));
        assertEquals("MATCHED", attrs.get("routing.reason"));
        assertEquals(true,      attrs.get("routing.matched"));
        assertEquals(0.9,       attrs.get("routing.confidence"));
        assertFalse(attrs.containsKey("routing.recipe_cache_hit"),
                "no recipe cache bound → the ADR-0072 D5 attribute is not emitted");
    }

    @Test
    void the_span_records_a_recipe_cache_hit_for_a_marked_label() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        IntentRouter router = IntentRouter.onField("intent")
                .route("TECH", "tech").route("SALES", "sales").route("BILLING", "billing")
                .confidenceField("confidence")
                .recipeCacheLabels(Set.of("TECH"))   // ADR-0072 D5
                .telemetry(telemetry)
                .orElse("fallback");

        triageBuilder(router).build().run("My laptop will not boot");   // classifier answers TECH

        Map<String, Object> attrs = telemetry.spansNamed("pipeline.classify").getFirst().attributes();
        assertEquals(true, attrs.get("routing.recipe_cache_hit"));
    }

    @Test
    void the_span_records_a_recipe_cache_miss_for_an_unmarked_matched_label() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        IntentRouter router = IntentRouter.onField("intent")
                .route("TECH", "tech").route("SALES", "sales").route("BILLING", "billing")
                .confidenceField("confidence")
                .recipeCacheLabels(Set.of("SALES"))   // marked set is non-empty, but TECH is not in it
                .telemetry(telemetry)
                .orElse("fallback");

        triageBuilder(router).build().run("My laptop will not boot");

        Map<String, Object> attrs = telemetry.spansNamed("pipeline.classify").getFirst().attributes();
        assertEquals(false, attrs.get("routing.recipe_cache_hit"));
    }

    @Test
    void the_span_records_why_the_else_arc_was_taken() {
        RecordingTelemetry telemetry = new RecordingTelemetry();
        IntentRouter router = IntentRouter.onField("intent")
                .route("TECH", "tech").route("SALES", "sales").route("BILLING", "billing")
                .telemetry(telemetry)
                .orElse("fallback");

        AgentPipeline.builder()
                .classify("classify", echoAgent("classifier", "not json at all"), router)
                .worker("tech",     echoAgent("tech",     "x"))
                .worker("sales",    echoAgent("sales",    "x"))
                .worker("billing",  echoAgent("billing",  "x"))
                .worker("fallback", echoAgent("fallback", "x"))
                .maxSteps(10)
                .build()
                .run("whatever");

        Map<String, Object> attrs = telemetry.spansNamed("pipeline.classify").getFirst().attributes();
        assertEquals("fallback",            attrs.get("routing.target"));
        assertEquals("UNPARSEABLE_OUTPUT",  attrs.get("routing.reason"));
        assertEquals(false,                 attrs.get("routing.matched"));
        assertFalse(attrs.containsKey("routing.label"));
    }

    @Test
    void routing_to_an_undeclared_step_fails_at_build_time_not_mid_run() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> AgentPipeline.builder()
                .classify("classify", echoAgent("classifier", "{\"intent\":\"TECH\"}"), threeWayRouter())
                .worker("tech",     echoAgent("tech",     "x"))
                .worker("fallback", echoAgent("fallback", "x"))
                .build());

        assertTrue(e.getMessage().contains("sales") || e.getMessage().contains("billing"),
                "the message must name the missing step, was: " + e.getMessage());
    }
}
