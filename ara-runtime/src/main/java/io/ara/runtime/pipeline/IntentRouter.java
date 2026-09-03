package io.ara.runtime.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.RunState;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.core.telemetry.Span;
import io.ara.core.telemetry.SpanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * A {@link AgentPipeline} router that turns a classifier step's output into exactly one
 * next step — the "classify once, then dispatch" shape, where a cheap model labels the
 * incoming task and a single specialised worker handles it with no supervisory loop.
 *
 * <p>Written as a plain {@code Function<PipelineExecution, String>} rather than a new
 * pipeline construct: {@link AgentPipeline}'s loop already calls a router after every
 * step and already hands it the shared {@link RunState}, so nothing about the execution
 * model has to change for this pattern. What this class adds is the three things a
 * hand-written lambda gets wrong:
 *
 * <ol>
 *   <li><b>A mandatory else-arc.</b> {@link Builder#orElse(String)} is the terminal
 *       build method, so a router that silently returns {@code null} for a label the
 *       model invented — ending the pipeline with the classifier's own JSON as the
 *       final answer — is unrepresentable.</li>
 *   <li><b>The state write.</b> The label and its confidence land in {@code RunState}
 *       here, not as a side effect buried in a lambda that is supposed to be pure.
 *       Workers then read the label through {@code RunContext.state()} instead of
 *       having it spliced into their input.</li>
 *   <li><b>A routing span.</b> {@code pipeline.classify} carries the label, the chosen
 *       target, the confidence, and <em>why</em> that target was chosen — which is the
 *       telemetry a triage system is actually judged on, and which a lambda returning a
 *       bare string cannot emit.</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>{@code
 * IntentRouter router = IntentRouter.onField("intent")
 *         .route("TECH",    "tech")
 *         .route("SALES",   "sales")
 *         .route("BILLING", "billing")
 *         .writeLabelTo("intent")
 *         .confidenceField("confidence")
 *         .escalateBelow(0.7, "human")
 *         .telemetry(telemetry)
 *         .orElse("fallback");
 *
 * AgentPipeline triage = AgentPipeline.builder()
 *         .classify("classify", classifier, router)
 *         .worker("tech",     techAgent)
 *         .worker("sales",    salesAgent)
 *         .worker("billing",  billingAgent)
 *         .worker("human",    humanReviewAgent)
 *         .worker("fallback", fallbackAgent)
 *         .build();
 * }</pre>
 *
 * <p>Constraining the classifier's vocabulary is a separate, complementary concern that
 * belongs in its {@code AgentContract} — {@code JsonFieldValueValidator.oneOf("intent",
 * "TECH", "SALES", …)} rejects an out-of-vocabulary label before it ever reaches this
 * router. This router still handles the unknown label rather than assuming that
 * validator is present, because a rejected contract and an unroutable label are
 * different failures with different desired outcomes: the first fails the step, the
 * second takes the else-arc.
 */
public final class IntentRouter implements Function<PipelineExecution, String> {

    private static final Logger log = LoggerFactory.getLogger(IntentRouter.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Why {@link Decision#target()} was chosen — recorded on the span and in the debug log. */
    public enum Reason {
        /** The label matched a declared route. */
        MATCHED,
        /** A label was read, but no route was declared for it — took the else-arc. */
        UNKNOWN_LABEL,
        /** The label field was absent, null, or blank — took the else-arc. */
        MISSING_LABEL,
        /** The classifier's output was not parseable JSON — took the else-arc. */
        UNPARSEABLE_OUTPUT,
        /** Confidence was below the configured threshold — took the escalation arc. */
        LOW_CONFIDENCE,
        /** A threshold was configured but the classifier reported no usable confidence — took the escalation arc. */
        MISSING_CONFIDENCE
    }

    /**
     * The routing decision for one classifier output, before it is reduced to the bare
     * step name {@link #apply} returns.
     *
     * @param target     name of the step to run next; never {@code null}
     * @param label      the label read from the output, or {@code null} if none could be read
     * @param confidence the confidence read from the output, or {@code null} if absent or not configured
     * @param reason     why {@code target} was chosen
     */
    public record Decision(String target, String label, Double confidence, Reason reason) {
        public Decision {
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    private final String              labelPath;      // null = the whole output is the label
    private final Map<String, String> routes;         // keys already normalised
    private final boolean             caseSensitive;
    private final String              fallbackStep;
    private final String              labelStateKey;
    private final String              confidencePath;
    private final String              confidenceStateKey;
    private final Double              minConfidence;  // null = no threshold configured
    private final String              escalationStep;
    private final AraTelemetry        telemetry;
    /** Labels (normalised) whose worker is a recipe-cache hit (ADR-0072 D5); null = attribute not emitted. */
    private final Set<String>         recipeCacheLabels;

    /** Guards the "your state writes are going nowhere" warning so it is logged once per router, not once per task. */
    private final AtomicBoolean noopStateWarned = new AtomicBoolean();

    private IntentRouter(Builder b) {
        this.labelPath          = b.labelPath;
        this.routes             = Map.copyOf(b.routes);
        this.caseSensitive      = b.caseSensitive;
        this.fallbackStep       = b.fallbackStep;
        this.labelStateKey      = b.labelStateKey;
        this.confidencePath     = b.confidencePath;
        this.confidenceStateKey = b.confidenceStateKey;
        this.minConfidence      = b.minConfidence;
        this.escalationStep     = b.escalationStep;
        this.telemetry          = b.telemetry;
        this.recipeCacheLabels  = b.recipeCacheLabels == null ? null
                : b.recipeCacheLabels.stream().map(this::normalise).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Reads the label from a dot-path inside the classifier's JSON output — the same
     * path notation {@code JsonFieldExtractor} uses (e.g. {@code "result.intent"}).
     */
    public static Builder onField(String jsonPath) {
        Objects.requireNonNull(jsonPath, "jsonPath must not be null");
        if (jsonPath.isBlank()) throw new IllegalArgumentException("jsonPath must not be blank");
        return new Builder(jsonPath);
    }

    /**
     * Treats the classifier's whole (trimmed) output as the label — for a classifier
     * prompted to answer with a bare word, or a deterministic one that returns a label
     * directly. Incompatible with {@link Builder#confidenceField(String)}, which needs
     * JSON to read from.
     */
    public static Builder onOutput() {
        return new Builder(null);
    }

    /**
     * Every step name this router can return, including the else-arc and the escalation
     * arc — what {@code AgentPipeline.Builder.build()} checks against the declared steps
     * so an unroutable target is a build-time failure rather than a run-time one.
     */
    public Set<String> targets() {
        var all = new java.util.LinkedHashSet<>(routes.values());
        all.add(fallbackStep);
        if (escalationStep != null) all.add(escalationStep);
        return Set.copyOf(all);
    }

    @Override
    public String apply(PipelineExecution execution) {
        Objects.requireNonNull(execution, "execution must not be null");
        Decision decision = classify(execution.lastOutput());
        writeState(execution.state(), decision);
        emitSpan(decision);
        log.debug("IntentRouter label=[{}] confidence={} → [{}] ({})",
                decision.label(), decision.confidence(), decision.target(), decision.reason());
        return decision.target();
    }

    /**
     * The routing decision for {@code output}, with no state write and no span — the
     * pure core of {@link #apply}, exposed so the mapping can be exercised directly
     * without standing up a {@link PipelineExecution}.
     */
    public Decision classify(String output) {
        JsonNode root = null;
        if (labelPath != null || confidencePath != null) {
            try {
                root = MAPPER.readTree(output == null ? "" : output);
            } catch (Exception e) {
                return new Decision(fallbackStep, null, null, Reason.UNPARSEABLE_OUTPUT);
            }
            if (root == null || root.isMissingNode()) {
                return new Decision(fallbackStep, null, null, Reason.UNPARSEABLE_OUTPUT);
            }
        }

        String label = (labelPath == null)
                ? (output == null ? null : output.trim())
                : textAt(root, labelPath);

        if (label == null || label.isBlank()) {
            return new Decision(fallbackStep, null, null, Reason.MISSING_LABEL);
        }

        Double confidence = (confidencePath == null) ? null : numberAt(root, confidencePath);

        // Checked before the route lookup: a confident-but-unknown label and an
        // unconfident-but-known one are different situations, and the threshold is the
        // stronger signal — a label the model is unsure of should reach a human even
        // when it happens to name a real worker.
        if (minConfidence != null && (confidence == null || confidence < minConfidence)) {
            String target = (escalationStep != null) ? escalationStep : fallbackStep;
            return new Decision(target, label, confidence,
                    confidence == null ? Reason.MISSING_CONFIDENCE : Reason.LOW_CONFIDENCE);
        }

        String target = routes.get(normalise(label));
        return (target == null)
                ? new Decision(fallbackStep, label, confidence, Reason.UNKNOWN_LABEL)
                : new Decision(target, label, confidence, Reason.MATCHED);
    }

    private void writeState(RunState state, Decision decision) {
        if (labelStateKey == null && confidenceStateKey == null) return;

        // RunState.noop() is what a bare AgentTask.of(...) carries: a pipeline run
        // outside a session would discard these writes silently, and the worker reading
        // the label back would see nothing. Say so once rather than let it look like a
        // classifier bug.
        if (state == RunState.noop()) {
            if (noopStateWarned.compareAndSet(false, true)) {
                log.warn("IntentRouter is configured to write the classification to RunState, but this "
                        + "run carries RunState.noop() — the writes are discarded. Run the pipeline through "
                        + "a session, or seed the task with RunContext.withState(RunState.inMemory()).");
            }
            return;
        }
        if (labelStateKey != null && decision.label() != null) {
            state.put(labelStateKey, decision.label());
        }
        if (confidenceStateKey != null && decision.confidence() != null) {
            state.put(confidenceStateKey, decision.confidence());
        }
    }

    private void emitSpan(Decision decision) {
        Span span = telemetry.spanBuilder("pipeline.classify")
                .setAttribute("routing.target",  decision.target())
                .setAttribute("routing.reason",  decision.reason().name())
                .setAttribute("routing.matched", decision.reason() == Reason.MATCHED)
                .startSpan();
        try {
            if (decision.label()      != null) span.setAttribute("routing.label", decision.label());
            if (decision.confidence() != null) span.setAttribute("routing.confidence", decision.confidence());
            if (recipeCacheLabels != null && decision.label() != null) {
                // ADR-0072 D5: only emitted when a RecipeCacheResolver was bound, so a
                // plain Classify-and-Act span is unchanged.
                span.setAttribute("routing.recipe_cache_hit",
                        recipeCacheLabels.contains(normalise(decision.label())));
            }
            span.setStatus(SpanStatus.OK);
        } finally {
            span.end();
        }
    }

    private String normalise(String label) {
        String trimmed = label.trim();
        return caseSensitive ? trimmed : trimmed.toUpperCase(Locale.ROOT);
    }

    private static JsonNode nodeAt(JsonNode root, String path) {
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            if (node == null || node.isMissingNode()) return null;
            node = node.get(part);
        }
        return (node == null || node.isNull() || node.isMissingNode()) ? null : node;
    }

    private static String textAt(JsonNode root, String path) {
        JsonNode node = nodeAt(root, path);
        if (node == null) return null;
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static Double numberAt(JsonNode root, String path) {
        JsonNode node = nodeAt(root, path);
        return (node != null && node.isNumber()) ? node.asDouble() : null;
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Assembles an {@link IntentRouter}. {@link #orElse(String)} both names the else-arc
     * and builds the router: there is no {@code build()} that would let the else-arc be
     * forgotten.
     */
    public static final class Builder {

        private final String labelPath;

        private final Map<String, String> routes = new LinkedHashMap<>();
        private boolean      caseSensitive      = false;
        private String       fallbackStep;
        private String       labelStateKey;
        private String       confidencePath;
        private String       confidenceStateKey;
        private Double       minConfidence;
        private String       escalationStep;
        private AraTelemetry telemetry = AraTelemetry.noop();
        private Set<String>  recipeCacheLabels;

        private Builder(String labelPath) {
            this.labelPath = labelPath;
        }

        /**
         * Marks which of {@link #routes(Map) the routed labels} resolve to a recipe-cache
         * hit, so the {@code pipeline.classify} span carries {@code routing.recipe_cache_hit}
         * for them (ADR-0072 D5). Set by {@code ClassifyAndActSpec} when a
         * {@code RecipeCacheResolver} is bound; unset otherwise, and then the attribute is
         * never emitted. Labels are matched with the same case rule as {@link #route}.
         */
        public Builder recipeCacheLabels(Set<String> labels) {
            this.recipeCacheLabels = labels == null ? null : Set.copyOf(labels);
            return this;
        }

        /**
         * Maps one classifier label to the step that handles it. Labels are matched
         * case-insensitively unless {@link #caseSensitive()} is set.
         *
         * @throws IllegalArgumentException if the same label is mapped twice
         */
        public Builder route(String label, String stepName) {
            Objects.requireNonNull(label,    "label must not be null");
            Objects.requireNonNull(stepName, "stepName must not be null");
            String key = caseSensitive ? label.trim() : label.trim().toUpperCase(Locale.ROOT);
            if (routes.putIfAbsent(key, stepName) != null) {
                throw new IllegalArgumentException("Duplicate route for label '" + label + "'");
            }
            return this;
        }

        /** Adds every entry of {@code labelToStep} via {@link #route(String, String)}. */
        public Builder routes(Map<String, String> labelToStep) {
            Objects.requireNonNull(labelToStep, "labelToStep must not be null");
            labelToStep.forEach(this::route);
            return this;
        }

        /**
         * Matches labels exactly instead of case-insensitively. Call it before {@link
         * #route}: the normalisation is applied to route keys as they are added, so
         * flipping it afterwards would leave already-registered keys upper-cased.
         *
         * @throws IllegalStateException if routes were already declared
         */
        public Builder caseSensitive() {
            if (!routes.isEmpty()) {
                throw new IllegalStateException(
                        "caseSensitive() must be called before route()/routes() — the routes already "
                        + "declared were normalised under the previous setting");
            }
            this.caseSensitive = true;
            return this;
        }

        /** Stores the label under {@code stateKey} in the run's {@link RunState}. */
        public Builder writeLabelTo(String stateKey) {
            this.labelStateKey = Objects.requireNonNull(stateKey, "stateKey must not be null");
            return this;
        }

        /**
         * Reads a numeric confidence from this dot-path in the classifier's JSON output.
         * On its own this only publishes the value (to the span, and to {@link
         * #writeConfidenceTo(String)} if set); {@link #escalateBelow(double, String)}
         * is what makes it route.
         */
        public Builder confidenceField(String jsonPath) {
            this.confidencePath = Objects.requireNonNull(jsonPath, "jsonPath must not be null");
            return this;
        }

        /** Stores the confidence under {@code stateKey} in the run's {@link RunState}. */
        public Builder writeConfidenceTo(String stateKey) {
            this.confidenceStateKey = Objects.requireNonNull(stateKey, "stateKey must not be null");
            return this;
        }

        /**
         * Routes to {@code stepName} — typically a human-review or approval step —
         * whenever the reported confidence is below {@code threshold}, or when no
         * confidence could be read at all. Requires {@link #confidenceField(String)}.
         */
        public Builder escalateBelow(double threshold, String stepName) {
            this.minConfidence  = threshold;
            this.escalationStep = Objects.requireNonNull(stepName, "stepName must not be null");
            return this;
        }

        /** Emits the {@code pipeline.classify} span through this telemetry. Default: {@link AraTelemetry#noop()}. */
        public Builder telemetry(AraTelemetry telemetry) {
            this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
            return this;
        }

        /**
         * Names the else-arc — the step that runs when the label is unknown, absent, or
         * unreadable — and builds the router.
         *
         * @throws IllegalStateException if no route was declared, or if a confidence
         *                               threshold was set without a confidence field
         */
        public IntentRouter orElse(String fallbackStep) {
            this.fallbackStep = Objects.requireNonNull(fallbackStep, "fallbackStep must not be null");
            if (routes.isEmpty()) {
                throw new IllegalStateException("IntentRouter needs at least one route(label, step)");
            }
            if (minConfidence != null && confidencePath == null) {
                throw new IllegalStateException(
                        "escalateBelow() requires confidenceField() — there is no value to compare against");
            }
            if (confidencePath != null && labelPath == null) {
                throw new IllegalStateException(
                        "confidenceField() requires onField(...) — onOutput() has no JSON to read a confidence from");
            }
            return new IntentRouter(this);
        }
    }
}
