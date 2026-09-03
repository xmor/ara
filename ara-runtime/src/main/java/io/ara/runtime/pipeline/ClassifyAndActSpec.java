package io.ara.runtime.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.runtime.agent.AgentRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A classify-and-act pipeline described as data instead of Java, so adding a category is
 * an edit to a document rather than a recompile and a redeploy.
 *
 * <p>What is declarable here is exactly what is genuinely data: the classifier tiers and
 * their order, the rules of a rule-based tier, the label→worker table, the confidence
 * threshold and where it escalates, and which agent backs each worker. What is not
 * declarable is the agents themselves — they are referenced by name and resolved through
 * an {@link AgentResolver} at bind time. A triage's categories change often; the agents
 * that serve them do not, and describing an LLM agent's prompt, model and contract is a
 * different and much larger problem than describing a dispatch table.
 *
 * <p><strong>The source format is the caller's choice.</strong> This class reads a
 * {@link JsonNode}: {@link #fromJson(String)} handles JSON with the Jackson this module
 * already depends on, and for YAML the caller hands in a node produced by whatever YAML
 * mapper it already has. {@code ara-runtime} deliberately does not grow a YAML
 * dependency for this — {@code jackson-dataformat-yaml} is explicitly excluded elsewhere
 * in this build, and the hand-rolled {@code AraYamlLoader} cannot help because it does
 * not support lists, which this schema is largely made of.
 *
 * <p>Schema:
 * <pre>{@code
 * {
 *   "maxSteps": 3,
 *   "classifiers": [
 *     { "name": "rules", "type": "rules",
 *       "rules": [ { "label": "BILLING", "keywords": ["fattura", "rimborso"] },
 *                  { "label": "TECH",    "regex": "\\bcrash\\b" } ],
 *       "unmatchedLabel": "UNKNOWN",
 *       "routes": { "BILLING": "billing", "TECH": "tech" },
 *       "writeLabelTo": "intent",
 *       "confidenceField": "confidence",
 *       "escalateBelow": 0.5, "escalateTo": "model",
 *       "orElse": "fallback" },
 *
 *     { "name": "model", "type": "agent", "agent": "triage-llm",
 *       "routes": { "BILLING": "billing", "TECH": "tech" },
 *       "writeLabelTo": "intent",
 *       "confidenceField": "confidence",
 *       "escalateBelow": 0.7, "escalateTo": "human",
 *       "orElse": "fallback" },
 *
 *     { "name": "human", "type": "approval",
 *       "labels": ["BILLING", "TECH"], "proposedLabelFrom": "intent",
 *       "timeoutSeconds": 900, "recordOutcomeAs": "approval.outcome",
 *       "unmatchedLabel": "UNKNOWN",
 *       "routes": { "BILLING": "billing", "TECH": "tech" },
 *       "orElse": "fallback" }
 *   ],
 *   "workers": { "billing": "billing-agent", "tech": "tech-agent", "fallback": "triage-queue" }
 * }
 * }</pre>
 *
 * <p>The <strong>first declared classifier is the entry point</strong>, and each
 * subsequent one is reachable only as another's {@code escalateTo} target. Workers are
 * terminal. {@code maxSteps} defaults to one hop per classifier plus one worker, which
 * is the tightest bound the pattern can run under.
 *
 * <p>Every route target, {@code escalateTo} and {@code orElse} is checked against the
 * declared steps as the spec is constructed — a category added to {@code routes} but not
 * to {@code workers} is refused at load time, naming the classifier and the label, rather
 * than surfacing on the first ticket that happens to carry it.
 */
public record ClassifyAndActSpec(
        List<ClassifierSpec> classifiers,
        Map<String, String>  workers,
        int                  maxSteps
) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ClassifyAndActSpec {
        Objects.requireNonNull(classifiers, "classifiers must not be null");
        Objects.requireNonNull(workers,     "workers must not be null");
        if (classifiers.isEmpty()) throw new IllegalArgumentException("at least one classifier is required");
        if (workers.isEmpty())     throw new IllegalArgumentException("at least one worker is required");
        if (maxSteps <= 0)         throw new IllegalArgumentException("maxSteps must be > 0");
        requireEveryTargetDeclared(classifiers, workers);
        requireEscalationFeedsWhatApprovalReads(classifiers);
        classifiers = List.copyOf(classifiers);
        workers     = Map.copyOf(workers);
    }

    // Checked here rather than left to AgentPipeline.Builder.build(): a spec whose routes
    // name steps it does not declare is unusable on its own terms, so it should not be a
    // constructible value at all — and the error can then name the classifier and the
    // label, which the pipeline builder has no way to know about.
    private static void requireEveryTargetDeclared(List<ClassifierSpec> classifiers, Map<String, String> workers) {
        Set<String> steps = new LinkedHashSet<>(workers.keySet());
        classifiers.forEach(c -> steps.add(c.name()));

        for (ClassifierSpec classifier : classifiers) {
            Routing routing = classifier.routing();
            routing.routes().forEach((label, target) -> {
                if (!steps.contains(target)) {
                    throw new IllegalArgumentException("classifier '" + classifier.name() + "' routes label '"
                            + label + "' to '" + target + "', which is neither a classifier nor a worker");
                }
            });
            requireDeclared(steps, routing.orElse(),    classifier.name(), "orElse");
            requireDeclared(steps, routing.escalateTo(), classifier.name(), "escalateTo");
        }
    }

    private static void requireDeclared(Set<String> steps, String target, String classifier, String field) {
        if (target != null && !steps.contains(target)) {
            throw new IllegalArgumentException("classifier '" + classifier + "' has " + field + " = '" + target
                    + "', which is neither a classifier nor a worker");
        }
    }

    // The one coupling nothing else in the spec checks: an approval tier reads its
    // proposed label from a RunState key (ApprovalClassifier.proposedLabelFrom, defaulted
    // to DEFAULT_PROPOSED_LABEL_KEY), and that key is only ever populated by whichever
    // earlier tier's routing.writeLabelTo() wrote it. Nothing forces the two to name the
    // same key — get them out of sync and every human decision silently becomes
    // UNUSABLE_DECISION, because ApprovalClassifier finds nothing to confirm.
    //
    // Scoped to the escalateTo edge deliberately, not to "any tier reachable somehow":
    // escalateTo is the only path the schema exposes for this coupling, and an approval
    // tier that is never an escalation target (declared first, or seeded externally
    // before the pipeline runs) is a legitimate use this check must not reject.
    private static void requireEscalationFeedsWhatApprovalReads(List<ClassifierSpec> classifiers) {
        Map<String, ClassifierSpec> byName = new LinkedHashMap<>();
        classifiers.forEach(c -> byName.put(c.name(), c));

        for (ClassifierSpec source : classifiers) {
            String escalateTo = source.routing().escalateTo();
            if (escalateTo == null) continue;

            ClassifierSpec target = byName.get(escalateTo);
            if (!(target != null && target.source() instanceof Source.Approval approval)) continue;

            String expectedKey = (approval.proposedLabelFrom() != null)
                    ? approval.proposedLabelFrom() : ApprovalClassifier.DEFAULT_PROPOSED_LABEL_KEY;
            String actualKey = source.routing().writeLabelTo();

            if (!expectedKey.equals(actualKey)) {
                throw new IllegalArgumentException("classifier '" + source.name() + "' escalates to approval tier '"
                        + escalateTo + "', which reads the proposed label from RunState key '" + expectedKey
                        + "' (proposedLabelFrom) — but '" + source.name() + "' "
                        + (actualKey == null
                                ? "does not declare writeLabelTo at all"
                                : "writes its label to a different key ('" + actualKey + "')")
                        + ", so every human decision on '" + escalateTo + "' would find nothing to confirm");
            }
        }
    }

    /** Resolves an agent reference from the spec to the agent that serves it. */
    @FunctionalInterface
    public interface AgentResolver {

        /**
         * @param ref the {@code agent} or {@code workers} value from the spec
         * @return the agent; {@code null} or a thrown exception both surface as a load
         *         failure naming the unresolved reference
         */
        AraAgent resolve(String ref);

        /** Resolves against a fixed map — the form tests and small applications want. */
        static AgentResolver of(Map<String, AraAgent> agents) {
            Objects.requireNonNull(agents, "agents must not be null");
            return agents::get;
        }

        /** Resolves each reference as an {@link AgentId} in {@code registry}. */
        static AgentResolver byId(AgentRegistry registry) {
            Objects.requireNonNull(registry, "registry must not be null");
            return ref -> registry.findById(AgentId.of(ref)).orElse(null);
        }
    }

    /** The runtime collaborators a spec cannot carry: who resolves agents, and what the optional tiers need. */
    public record Bindings(AgentResolver agents, ApprovalGate approvalGate, AraTelemetry telemetry) {

        public Bindings {
            Objects.requireNonNull(agents, "agents must not be null");
            if (telemetry == null) telemetry = AraTelemetry.noop();
        }

        /** Bindings with no approval gate — a spec declaring an {@code approval} tier will be rejected. */
        public static Bindings of(AgentResolver agents) {
            return new Bindings(agents, null, AraTelemetry.noop());
        }

        public Bindings withApprovalGate(ApprovalGate gate) {
            return new Bindings(agents, Objects.requireNonNull(gate, "gate must not be null"), telemetry);
        }

        public Bindings withTelemetry(AraTelemetry telemetry) {
            return new Bindings(agents, approvalGate, Objects.requireNonNull(telemetry, "telemetry must not be null"));
        }
    }

    /** What produces a classifier tier's label. */
    public sealed interface Source permits Source.Rules, Source.Agent, Source.Approval {

        /** A {@link RuleClassifier}: rules over the task text, first match wins. */
        record Rules(List<RuleSpec> rules, String unmatchedLabel) implements Source {}

        /** An agent resolved by name — typically a prompted classifier. */
        record Agent(String agentRef) implements Source {}

        /** An {@link ApprovalClassifier}: a human decides. */
        record Approval(
                List<String> labels,
                String       proposedLabelFrom,
                Duration     timeout,
                String       action,
                String       recordOutcomeAs,
                String       unmatchedLabel
        ) implements Source {}
    }

    /** One rule of a {@link Source.Rules} tier: keywords or a regex, never both. */
    public record RuleSpec(String label, List<String> keywords, String regex) {}

    /** How a tier's output becomes the next step — the declarative face of {@link IntentRouter}. */
    public record Routing(
            String              labelField,
            String              confidenceField,
            Map<String, String> routes,
            String              writeLabelTo,
            String              writeConfidenceTo,
            Double              escalateBelow,
            String              escalateTo,
            String              orElse
    ) {}

    /** One classifier tier: its step name, what produces the label, and where the label goes. */
    public record ClassifierSpec(String name, Source source, Routing routing) {}

    // ── Parsing ───────────────────────────────────────────────────────────────

    /** Reads a spec from a JSON document. */
    public static ClassifyAndActSpec fromJson(String json) {
        Objects.requireNonNull(json, "json must not be null");
        try {
            return from(MAPPER.readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("classify-and-act spec is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Reads a spec from an already-parsed document — the entry point for any source
     * format, YAML included: hand in the node your own mapper produced.
     */
    public static ClassifyAndActSpec from(JsonNode root) {
        Objects.requireNonNull(root, "root must not be null");
        if (!root.isObject()) throw new IllegalArgumentException("spec must be a JSON object");

        JsonNode classifiersNode = root.get("classifiers");
        if (classifiersNode == null || !classifiersNode.isArray() || classifiersNode.isEmpty()) {
            throw new IllegalArgumentException("'classifiers' must be a non-empty array");
        }
        List<ClassifierSpec> classifiers = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < classifiersNode.size(); i++) {
            ClassifierSpec spec = readClassifier(classifiersNode.get(i), "classifiers[" + i + "]");
            if (!names.add(spec.name())) {
                throw new IllegalArgumentException("duplicate classifier name: '" + spec.name() + "'");
            }
            classifiers.add(spec);
        }

        Map<String, String> workers = readStringMap(root.get("workers"), "workers");
        if (workers.isEmpty()) throw new IllegalArgumentException("'workers' must be a non-empty object");
        for (String worker : workers.keySet()) {
            if (names.contains(worker)) {
                throw new IllegalArgumentException(
                        "'" + worker + "' is declared both as a classifier and as a worker");
            }
        }

        // One hop per tier plus the worker: the tightest bound this shape can run under,
        // and the one that turns "a route loops back" into a bounded failure.
        int maxSteps = root.hasNonNull("maxSteps") ? root.get("maxSteps").asInt() : classifiers.size() + 1;

        return new ClassifyAndActSpec(classifiers, workers, maxSteps);
    }

    private static ClassifierSpec readClassifier(JsonNode node, String path) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException(path + " must be an object");
        String name = requiredText(node, "name", path);
        String type = requiredText(node, "type", path);

        Source source = switch (type) {
            case "rules"    -> readRules(node, path);
            case "agent"    -> new Source.Agent(requiredText(node, "agent", path));
            case "approval" -> readApproval(node, path);
            default -> throw new IllegalArgumentException(
                    path + ".type must be one of 'rules', 'agent', 'approval' — got '" + type + "'");
        };
        return new ClassifierSpec(name, source, readRouting(node, path));
    }

    private static Source readRules(JsonNode node, String path) {
        JsonNode rulesNode = node.get("rules");
        if (rulesNode == null || !rulesNode.isArray() || rulesNode.isEmpty()) {
            throw new IllegalArgumentException(path + ".rules must be a non-empty array");
        }
        List<RuleSpec> rules = new ArrayList<>();
        for (int i = 0; i < rulesNode.size(); i++) {
            JsonNode r = rulesNode.get(i);
            String rulePath = path + ".rules[" + i + "]";
            if (r == null || !r.isObject()) throw new IllegalArgumentException(rulePath + " must be an object");
            String label = requiredText(r, "label", rulePath);
            List<String> keywords = readStringList(r.get("keywords"), rulePath + ".keywords");
            String regex = optionalText(r, "regex");
            if (keywords.isEmpty() == (regex == null)) {
                throw new IllegalArgumentException(
                        rulePath + " must declare exactly one of 'keywords' or 'regex'");
            }
            if (regex != null) {
                try {
                    Pattern.compile(regex);
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException(rulePath + ".regex is not a valid pattern: " + e.getDescription(), e);
                }
            }
            rules.add(new RuleSpec(label, keywords, regex));
        }
        return new Source.Rules(rules, requiredText(node, "unmatchedLabel", path));
    }

    private static Source readApproval(JsonNode node, String path) {
        List<String> labels = readStringList(node.get("labels"), path + ".labels");
        if (labels.isEmpty()) throw new IllegalArgumentException(path + ".labels must be a non-empty array");
        Long seconds = node.hasNonNull("timeoutSeconds") ? node.get("timeoutSeconds").asLong() : null;
        if (seconds != null && seconds <= 0) {
            throw new IllegalArgumentException(path + ".timeoutSeconds must be > 0");
        }
        return new Source.Approval(
                labels,
                optionalText(node, "proposedLabelFrom"),
                seconds != null ? Duration.ofSeconds(seconds) : null,
                optionalText(node, "action"),
                optionalText(node, "recordOutcomeAs"),
                requiredText(node, "unmatchedLabel", path));
    }

    private static Routing readRouting(JsonNode node, String path) {
        Map<String, String> routes = readStringMap(node.get("routes"), path + ".routes");
        if (routes.isEmpty()) throw new IllegalArgumentException(path + ".routes must be a non-empty object");

        Double escalateBelow = node.hasNonNull("escalateBelow") ? node.get("escalateBelow").asDouble() : null;
        String escalateTo    = optionalText(node, "escalateTo");
        String confidence    = optionalText(node, "confidenceField");

        if ((escalateBelow == null) != (escalateTo == null)) {
            throw new IllegalArgumentException(path + " must declare 'escalateBelow' and 'escalateTo' together");
        }
        if (escalateBelow != null && confidence == null) {
            throw new IllegalArgumentException(
                    path + ".escalateBelow needs 'confidenceField' — there is no value to compare against");
        }
        return new Routing(
                optionalText(node, "labelField"),
                confidence,
                routes,
                optionalText(node, "writeLabelTo"),
                optionalText(node, "writeConfidenceTo"),
                escalateBelow,
                escalateTo,
                requiredText(node, "orElse", path));
    }

    // ── Building ──────────────────────────────────────────────────────────────

    /**
     * Builds the pipeline this spec describes.
     *
     * <p>Routing is already known to be sound — the spec could not have been constructed
     * with a route to an undeclared step — so what can still fail here is only what the
     * spec cannot see: the bindings.
     *
     * @throws IllegalArgumentException if an agent reference does not resolve, or if an
     *                                  {@code approval} tier is declared with no gate in
     *                                  {@code bindings}
     */
    public AgentPipeline build(Bindings bindings) {
        Objects.requireNonNull(bindings, "bindings must not be null");
        AgentPipeline.Builder builder = AgentPipeline.builder().maxSteps(maxSteps);

        for (ClassifierSpec spec : classifiers) {
            builder.classify(spec.name(), agentFor(spec, bindings), routerFor(spec, bindings));
        }
        workers.forEach((step, ref) -> builder.worker(step, resolve(bindings, ref, "workers." + step)));
        return builder.build();
    }

    private AraAgent agentFor(ClassifierSpec spec, Bindings bindings) {
        AgentId id = AgentId.of(spec.name());
        return switch (spec.source()) {
            case Source.Agent agent -> resolve(bindings, agent.agentRef(), "classifier '" + spec.name() + "'");
            case Source.Rules rules -> {
                RuleClassifier.Builder b = RuleClassifier.builder(id);
                for (RuleSpec rule : rules.rules()) {
                    if (rule.regex() != null) {
                        b.whenMatches(rule.label(), Pattern.compile(rule.regex(), Pattern.CASE_INSENSITIVE));
                    } else {
                        b.when(rule.label(), rule.keywords().toArray(String[]::new));
                    }
                }
                applyFieldNames(spec.routing(), b::labelField, b::confidenceField);
                yield b.orElse(rules.unmatchedLabel());
            }
            case Source.Approval approval -> {
                if (bindings.approvalGate() == null) {
                    throw new IllegalArgumentException("classifier '" + spec.name()
                            + "' is of type 'approval' but no ApprovalGate was bound");
                }
                ApprovalClassifier.Builder b = ApprovalClassifier.builder(id, bindings.approvalGate())
                        .labels(approval.labels().toArray(String[]::new));
                if (approval.proposedLabelFrom() != null) b.proposedLabelFrom(approval.proposedLabelFrom());
                if (approval.timeout()           != null) b.timeout(approval.timeout());
                if (approval.action()            != null) b.action(approval.action());
                if (approval.recordOutcomeAs()   != null) b.recordOutcomeAs(approval.recordOutcomeAs());
                applyFieldNames(spec.routing(), b::labelField, b::confidenceField);
                yield b.orElse(approval.unmatchedLabel());
            }
        };
    }

    // A classifier's own output field names and the router's read paths must agree, and
    // declaring them twice is a way to get them wrong: the spec states them once, on the
    // routing, and both sides are configured from there.
    private static void applyFieldNames(Routing routing,
                                        java.util.function.Consumer<String> labelField,
                                        java.util.function.Consumer<String> confidenceField) {
        if (routing.labelField()      != null) labelField.accept(routing.labelField());
        if (routing.confidenceField() != null) confidenceField.accept(routing.confidenceField());
    }

    private IntentRouter routerFor(ClassifierSpec spec, Bindings bindings) {
        Routing routing = spec.routing();
        IntentRouter.Builder builder = (routing.labelField() != null)
                ? IntentRouter.onField(routing.labelField())
                : IntentRouter.onField("intent");
        builder.routes(routing.routes()).telemetry(bindings.telemetry());
        if (routing.writeLabelTo()      != null) builder.writeLabelTo(routing.writeLabelTo());
        if (routing.writeConfidenceTo() != null) builder.writeConfidenceTo(routing.writeConfidenceTo());
        if (routing.confidenceField()   != null) builder.confidenceField(routing.confidenceField());
        if (routing.escalateBelow()     != null) builder.escalateBelow(routing.escalateBelow(), routing.escalateTo());

        // ADR-0072 D5: when the fast-path resolver is bound, tag the classify span for
        // every routed label whose worker is a promoted archived recipe.
        if (bindings.agents() instanceof RecipeCacheResolver rcr) {
            Set<String> hitLabels = routing.routes().entrySet().stream()
                    .filter(e -> {
                        String ref = workers.get(e.getValue());
                        return ref != null && rcr.isCacheHit(ref);
                    })
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet());
            if (!hitLabels.isEmpty()) builder.recipeCacheLabels(hitLabels);
        }
        return builder.orElse(routing.orElse());
    }

    private static AraAgent resolve(Bindings bindings, String ref, String where) {
        AraAgent agent;
        try {
            agent = bindings.agents().resolve(ref);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cannot resolve agent '" + ref + "' for " + where + ": " + e, e);
        }
        if (agent == null) {
            throw new IllegalArgumentException("cannot resolve agent '" + ref + "' for " + where);
        }
        return agent;
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private static String requiredText(JsonNode node, String field, String path) {
        String value = optionalText(node, field);
        if (value == null) throw new IllegalArgumentException(path + "." + field + " is required");
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("'" + field + "' must be a string");
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private static List<String> readStringList(JsonNode node, String path) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) throw new IllegalArgumentException(path + " must be an array");
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            if (!element.isTextual()) throw new IllegalArgumentException(path + " must contain only strings");
            values.add(element.asText());
        }
        return List.copyOf(values);
    }

    private static Map<String, String> readStringMap(JsonNode node, String path) {
        if (node == null || node.isNull()) return Map.of();
        if (!node.isObject()) throw new IllegalArgumentException(path + " must be an object");
        Map<String, String> values = new LinkedHashMap<>();
        node.properties().forEach(entry -> {
            if (!entry.getValue().isTextual()) {
                throw new IllegalArgumentException(path + "." + entry.getKey() + " must be a string");
            }
            values.put(entry.getKey(), entry.getValue().asText());
        });
        return Map.copyOf(values);
    }
}
