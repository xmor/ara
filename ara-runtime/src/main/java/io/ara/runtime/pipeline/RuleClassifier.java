package io.ara.runtime.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AraAgents;
import io.ara.core.agent.FunctionAgent;
import io.ara.core.common.AgentId;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Builds a deterministic classifier agent for {@link IntentRouter}: rules over the task's
 * text, evaluated in declaration order, first match wins.
 *
 * <p>The cheap half of a triage front end. A large share of real tickets are decided by a
 * word — "rimborso", "stack trace", "preventivo" — and paying an LLM round trip to learn
 * that costs latency and money on the easy cases while adding a way to be wrong about
 * them. This produces an ordinary {@link AraAgent} that emits the same JSON shape a
 * prompted classifier would, so it drops into a {@code classify(...)} step without the
 * router, the workers or the pipeline knowing the difference.
 *
 * <p>Usage:
 * <pre>{@code
 * AraAgent rules = RuleClassifier.builder(AgentId.of("triage-rules"))
 *         .when("BILLING", "fattura", "rimborso", "pagamento")
 *         .when("TECH",    "crash", "stack trace", "non si avvia")
 *         .whenMatches("SALES", Pattern.compile("preventiv|listin", Pattern.CASE_INSENSITIVE))
 *         .orElse("UNKNOWN");
 * }</pre>
 *
 * <p>Emits {@code {"intent":"BILLING","confidence":1.0}} when a rule fired and
 * {@code {"intent":"UNKNOWN","confidence":0.0}} when none did — field names configurable.
 * Those two confidence values are what make the cascade work: point an
 * {@code IntentRouter.escalateBelow(0.5, "llmClassify")} at a second {@code classify(...)}
 * step and the rules answer what they know, while everything else reaches the model.
 *
 * <pre>{@code
 * AgentPipeline triage = AgentPipeline.builder()
 *         .classify("rules", rules, IntentRouter.onField("intent")
 *                 .route("BILLING", "billing").route("TECH", "tech").route("SALES", "sales")
 *                 .confidenceField("confidence")
 *                 .escalateBelow(0.5, "llmClassify")   // no rule fired → ask the model
 *                 .orElse("fallback"))
 *         .classify("llmClassify", llmClassifier, IntentRouter.onField("intent")
 *                 .route("BILLING", "billing").route("TECH", "tech").route("SALES", "sales")
 *                 .orElse("fallback"))
 *         .worker("billing", billingAgent)
 *         .worker("tech",    techAgent)
 *         .worker("sales",   salesAgent)
 *         .worker("fallback", fallbackAgent)
 *         .maxSteps(3)
 *         .build();
 * }</pre>
 *
 * <p>First match wins, in declaration order — so a rule for {@code TECH_URGENT} must be
 * declared before the one for {@code TECH} that would also match it. This is the
 * priority-ordered, mutually exclusive evaluation the pattern needs and that a set of
 * graph edge conditions cannot express.
 */
public final class RuleClassifier {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Confidence emitted when a rule fired. Rules are certain by construction. */
    public static final double MATCHED_CONFIDENCE = 1.0;

    /** Confidence emitted when no rule fired — low on purpose, so {@code escalateBelow} catches it. */
    public static final double UNMATCHED_CONFIDENCE = 0.0;

    private RuleClassifier() {}

    public static Builder builder(AgentId agentId) {
        return new Builder(agentId);
    }

    private record Rule(String label, Predicate<String> test) {}

    /**
     * Assembles a {@link RuleClassifier}. {@link #orElse(String)} both names the label
     * emitted when no rule fires and builds the agent — the same shape {@link
     * IntentRouter.Builder#orElse(String)} uses, and for the same reason: a classifier
     * with no answer for an unmatched input has no useful behaviour left.
     */
    public static final class Builder {

        private final AgentId    agentId;
        private final List<Rule> rules = new ArrayList<>();

        private String      labelField      = "intent";
        private String      confidenceField = "confidence";
        private AgentConfig config;

        private Builder(AgentId agentId) {
            this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
        }

        /**
         * Assigns {@code label} when the task's text contains any of {@code keywords},
         * compared case-insensitively.
         */
        public Builder when(String label, String... keywords) {
            Objects.requireNonNull(label,    "label must not be null");
            Objects.requireNonNull(keywords, "keywords must not be null");
            if (keywords.length == 0) {
                throw new IllegalArgumentException("when('" + label + "') needs at least one keyword");
            }
            List<String> needles = new ArrayList<>(keywords.length);
            for (String k : keywords) {
                Objects.requireNonNull(k, "keyword must not be null");
                if (k.isBlank()) {
                    throw new IllegalArgumentException("when('" + label + "') was given a blank keyword");
                }
                needles.add(k.toLowerCase(Locale.ROOT));
            }
            return whenever(label, text -> {
                String haystack = text.toLowerCase(Locale.ROOT);
                return needles.stream().anyMatch(haystack::contains);
            });
        }

        /** Assigns {@code label} when {@code pattern} is found anywhere in the task's text. */
        public Builder whenMatches(String label, Pattern pattern) {
            Objects.requireNonNull(pattern, "pattern must not be null");
            return whenever(label, text -> pattern.matcher(text).find());
        }

        /** Assigns {@code label} when {@code test} accepts the task's text — the general form. */
        public Builder whenever(String label, Predicate<String> test) {
            Objects.requireNonNull(label, "label must not be null");
            Objects.requireNonNull(test,  "test must not be null");
            rules.add(new Rule(label, test));
            return this;
        }

        /** Name of the emitted label field. Default {@code "intent"} — must match the router's {@code onField(...)}. */
        public Builder labelField(String name) {
            this.labelField = Objects.requireNonNull(name, "name must not be null");
            return this;
        }

        /** Name of the emitted confidence field. Default {@code "confidence"}. */
        public Builder confidenceField(String name) {
            this.confidenceField = Objects.requireNonNull(name, "name must not be null");
            return this;
        }

        /**
         * Uses a caller-supplied {@link AgentConfig} instead of {@link
         * FunctionAgent#defaultConfig(AgentId)} — for a classifier that needs its own
         * {@code name()}/{@code description()}/{@code tags()} in the registry.
         */
        public Builder config(AgentConfig config) {
            this.config = Objects.requireNonNull(config, "config must not be null");
            return this;
        }

        /**
         * Names the label emitted when no rule fires — reported with {@link
         * #UNMATCHED_CONFIDENCE} — and builds the agent.
         *
         * @throws IllegalStateException if no rule was declared
         */
        public AraAgent orElse(String unmatchedLabel) {
            Objects.requireNonNull(unmatchedLabel, "unmatchedLabel must not be null");
            if (rules.isEmpty()) {
                throw new IllegalStateException("RuleClassifier needs at least one when(...)/whenMatches(...) rule");
            }
            List<Rule>  frozen = List.copyOf(rules);
            String      lf     = labelField;
            String      cf     = confidenceField;
            AgentConfig cfg    = (config != null) ? config : FunctionAgent.defaultConfig(agentId);

            // task.input() is non-null and non-blank: AgentTask's compact constructor
            // rejects both, and withInput(...) rebuilds through it.
            return AraAgents.deterministic(agentId, cfg, task -> {
                String text = task.input();
                for (Rule rule : frozen) {
                    if (rule.test().test(text)) {
                        return json(lf, rule.label(), cf, MATCHED_CONFIDENCE);
                    }
                }
                return json(lf, unmatchedLabel, cf, UNMATCHED_CONFIDENCE);
            });
        }

        // Built through Jackson rather than string concatenation: labels and field names
        // are caller-supplied, and a quote or backslash in one would otherwise produce
        // output the router then reports as UNPARSEABLE_OUTPUT.
        private static String json(String labelField, String label, String confidenceField, double confidence) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put(labelField, label);
            node.put(confidenceField, confidence);
            return node.toString();
        }
    }
}
