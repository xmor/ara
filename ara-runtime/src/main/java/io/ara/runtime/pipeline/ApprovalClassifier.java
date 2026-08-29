package io.ara.runtime.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AraAgents;
import io.ara.core.agent.FunctionAgent;
import io.ara.core.agent.RunState;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalNotifier;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.hitl.ApprovalTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds a classifier agent that asks a <em>human</em> for the label, so a
 * classify-and-act pipeline can escalate what it is not sure about instead of guessing.
 *
 * <p>The third tier of the same cascade. {@link RuleClassifier} answers what a rule
 * covers for free, a prompted classifier answers what a model can decide, and this one
 * answers what neither should: it registers an {@link ApprovalRequest} on the gate,
 * parks until an operator decides, and emits the label in the same JSON shape the other
 * two produce. Router, workers and pipeline cannot tell the three apart.
 *
 * <pre>{@code
 * AraAgent human = ApprovalClassifier.builder(AgentId.of("triage-human"), gate)
 *         .labels("BILLING", "TECH", "SALES")
 *         .proposedLabelFrom("intent")          // what the model guessed, for the operator to confirm
 *         .timeout(Duration.ofMinutes(15))
 *         .notifier(notifier)
 *         .recordOutcomeAs("approval.outcome")
 *         .orElse("UNKNOWN");
 * }</pre>
 *
 * <p>How a decision becomes a label:
 * <ul>
 *   <li>{@link ApprovalDecision.Approved} — the operator confirmed the proposed label,
 *       read from {@link RunState} under {@link Builder#proposedLabelFrom(String)}. With
 *       no proposed label there is nothing to confirm, so this counts as unresolved.</li>
 *   <li>{@link ApprovalDecision.Modified} — the operator chose a different label;
 *       {@code newPayload()} must be a {@code String} within the declared vocabulary.</li>
 *   <li>{@link ApprovalDecision.Rejected} — no label applies; unresolved.</li>
 *   <li>Timeout — nobody decided in time; unresolved.</li>
 * </ul>
 *
 * <p>Resolved outcomes are emitted at {@link #DECIDED_CONFIDENCE}, unresolved ones at
 * {@link #UNRESOLVED_CONFIDENCE} under the {@code orElse} label, so the router's own
 * else-arc carries them to a default queue. Which outcome it was is not guessable from
 * the label alone — a rejection and a timeout produce the same one — so
 * {@link Builder#recordOutcomeAs(String)} puts the {@link Outcome} in {@code RunState}
 * for a later step, and every non-approval is logged.
 *
 * <p><strong>This step blocks.</strong> The calling thread parks on the gate for up to
 * the configured timeout. Parking a virtual thread is cheap, but the pipeline has no
 * notion of suspending and resuming a run: the run holds its thread for the whole
 * window, and a pipeline hosted as an agent holds its session lock too, so concurrent
 * calls on that session meet the configured busy policy until an operator decides.
 * Budget the timeout accordingly — minutes, not hours — and treat the unresolved path
 * as the normal outcome it will regularly be, not an error case.
 */
public final class ApprovalClassifier {

    private static final Logger log = LoggerFactory.getLogger(ApprovalClassifier.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Confidence emitted when a human resolved the classification. */
    public static final double DECIDED_CONFIDENCE = 1.0;

    /** Confidence emitted when they did not — rejected, timed out, or an unusable decision. */
    public static final double UNRESOLVED_CONFIDENCE = 0.0;

    /** Default time an operator has to decide before the request expires. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(15);

    /** Default {@link ApprovalRequest#action()}. */
    public static final String DEFAULT_ACTION = "classify-task";

    /** Default {@link RunState} key {@link Builder#proposedLabelFrom(String)} reads from. */
    public static final String DEFAULT_PROPOSED_LABEL_KEY = "intent";

    private ApprovalClassifier() {}

    /** What the operator did, as recorded by {@link Builder#recordOutcomeAs(String)}. */
    public enum Outcome {
        /** The proposed label was confirmed. */
        APPROVED,
        /** A different label was chosen. */
        MODIFIED,
        /** No label applies. */
        REJECTED,
        /** Nobody decided before the request expired. */
        TIMED_OUT,
        /** A decision arrived but could not be turned into a label — see the logged reason. */
        UNUSABLE_DECISION
    }

    /**
     * The {@link ApprovalRequest#payload()} this classifier submits: everything an
     * operator surface needs to render the question and offer the answers.
     *
     * @param text            the task's own text — what is being classified
     * @param proposedLabel   the label an earlier classifier suggested, or {@code null} if none
     * @param candidateLabels the vocabulary the operator may choose from; never empty
     */
    public record PendingClassification(String text, String proposedLabel, List<String> candidateLabels) {
        public PendingClassification {
            Objects.requireNonNull(text, "text must not be null");
            candidateLabels = List.copyOf(Objects.requireNonNull(candidateLabels, "candidateLabels must not be null"));
        }
    }

    public static Builder builder(AgentId agentId, ApprovalGate gate) {
        return new Builder(agentId, gate);
    }

    /**
     * Assembles an {@link ApprovalClassifier}. {@link #orElse(String)} both names the
     * label emitted when no human answer could be used and builds the agent — the same
     * shape {@link RuleClassifier} and {@link IntentRouter} use, for the same reason: an
     * escalation step with no answer for "nobody decided" has nowhere to send the task.
     */
    public static final class Builder {

        private final AgentId      agentId;
        private final ApprovalGate gate;

        private final Set<String> labels = new LinkedHashSet<>();

        private String           proposedLabelKey = DEFAULT_PROPOSED_LABEL_KEY;
        private String           outcomeKey;
        private Duration         timeout          = DEFAULT_TIMEOUT;
        private String           action           = DEFAULT_ACTION;
        private ApprovalNotifier notifier;
        private String           labelField       = "intent";
        private String           confidenceField  = "confidence";
        private AgentConfig      config;

        private Builder(AgentId agentId, ApprovalGate gate) {
            this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
            this.gate    = Objects.requireNonNull(gate,    "gate must not be null");
        }

        /**
         * The vocabulary the operator may choose from — carried in the request payload so
         * a surface can render it, and enforced on the way back: a {@code Modified}
         * decision naming a label outside this set is unusable, not a new category
         * invented at review time.
         */
        public Builder labels(String... labels) {
            Objects.requireNonNull(labels, "labels must not be null");
            for (String label : labels) {
                Objects.requireNonNull(label, "label must not be null");
                if (label.isBlank()) throw new IllegalArgumentException("label must not be blank");
                this.labels.add(label);
            }
            return this;
        }

        /**
         * {@link RunState} key holding the label an earlier classifier proposed — what an
         * {@code Approved} decision confirms. Default {@code "intent"}, matching
         * {@link IntentRouter.Builder#writeLabelTo(String)}'s usual key.
         */
        public Builder proposedLabelFrom(String stateKey) {
            this.proposedLabelKey = Objects.requireNonNull(stateKey, "stateKey must not be null");
            return this;
        }

        /** Records the {@link Outcome} under this {@link RunState} key. Not recorded when unset. */
        public Builder recordOutcomeAs(String stateKey) {
            this.outcomeKey = Objects.requireNonNull(stateKey, "stateKey must not be null");
            return this;
        }

        /** How long the operator has. Default {@link #DEFAULT_TIMEOUT}. */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout must not be null");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /** {@link ApprovalRequest#action()}. Default {@value #DEFAULT_ACTION}. */
        public Builder action(String action) {
            Objects.requireNonNull(action, "action must not be null");
            if (action.isBlank()) throw new IllegalArgumentException("action must not be blank");
            this.action = action;
            return this;
        }

        /**
         * Notified once the request is registered, so an operator learns there is
         * something to decide. The gate does not notify on its own.
         */
        public Builder notifier(ApprovalNotifier notifier) {
            this.notifier = Objects.requireNonNull(notifier, "notifier must not be null");
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

        /** Uses a caller-supplied {@link AgentConfig} instead of {@link FunctionAgent#defaultConfig(AgentId)}. */
        public Builder config(AgentConfig config) {
            this.config = Objects.requireNonNull(config, "config must not be null");
            return this;
        }

        /**
         * Names the label emitted when no human answer could be used — rejected, timed
         * out, or a decision that named nothing usable — and builds the agent.
         *
         * @throws IllegalStateException if no candidate label was declared
         */
        public AraAgent orElse(String unresolvedLabel) {
            Objects.requireNonNull(unresolvedLabel, "unresolvedLabel must not be null");
            if (labels.isEmpty()) {
                throw new IllegalStateException("ApprovalClassifier needs at least one labels(...) candidate");
            }
            AgentConfig cfg  = (config != null) ? config : FunctionAgent.defaultConfig(agentId);
            Decider     body = new Decider(agentId, gate, List.copyOf(labels), proposedLabelKey, outcomeKey,
                    timeout, action, notifier, labelField, confidenceField, unresolvedLabel);
            return AraAgents.deterministic(agentId, cfg, body::decide);
        }
    }

    /**
     * The classification itself, as an object rather than a lambda so the pieces —
     * asking, interpreting the answer, recording it — stay separately readable.
     */
    private record Decider(
            AgentId          agentId,
            ApprovalGate     gate,
            List<String>     labels,
            String           proposedLabelKey,
            String           outcomeKey,
            Duration         timeout,
            String           action,
            ApprovalNotifier notifier,
            String           labelField,
            String           confidenceField,
            String           unresolvedLabel
    ) {
        /** One warning per built agent, not one per task, when state reads cannot work. */
        private static final AtomicBoolean NOOP_STATE_WARNED = new AtomicBoolean();

        String decide(AgentTask task) {
            RunState state = task.runContext().state();
            warnIfStateIsBlackHole(state);

            Optional<String> proposed = state.get(proposedLabelKey, String.class);
            ApprovalRequest request = buildRequest(task, proposed.orElse(null));
            notify(request);

            Resolution resolution = await(request, proposed.orElse(null));
            if (outcomeKey != null) {
                state.put(outcomeKey, resolution.outcome());
            }
            if (resolution.outcome() != Outcome.APPROVED && resolution.outcome() != Outcome.MODIFIED) {
                log.warn("ApprovalClassifier [{}] did not resolve task [{}]: {} — routing as '{}'",
                        agentId.value(), task.taskId(), resolution.outcome(), unresolvedLabel);
            }
            return json(resolution);
        }

        private ApprovalRequest buildRequest(AgentTask task, String proposedLabel) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("taskId", task.taskId());
            if (proposedLabel != null) metadata.put("proposedLabel", proposedLabel);
            if (task.sessionId() != null) metadata.put("sessionId", task.sessionId().value());
            return ApprovalRequest.of(agentId.value(), action,
                    new PendingClassification(task.input(), proposedLabel, labels), timeout, metadata);
        }

        // The SPI says a notifier must not throw; a broken one still must not sink the
        // classification, because the request is already registered and an operator can
        // decide it through any other surface.
        private void notify(ApprovalRequest request) {
            if (notifier == null) return;
            try {
                notifier.notify(request);
            } catch (RuntimeException e) {
                log.warn("ApprovalClassifier [{}] notifier failed for request [{}] — the request is registered "
                        + "and still decidable", agentId.value(), request.requestId(), e);
            }
        }

        private Resolution await(ApprovalRequest request, String proposedLabel) {
            ApprovalDecision decision;
            try {
                decision = gate.requestApproval(request).join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof ApprovalTimeoutException) {
                    return Resolution.unresolved(Outcome.TIMED_OUT);
                }
                throw e;
            }
            return switch (decision) {
                case ApprovalDecision.Approved ignored -> {
                    if (proposedLabel == null) {
                        log.warn("ApprovalClassifier [{}] got an approval for request [{}] but no proposed label "
                                + "was present under state key '{}' — there was nothing to confirm",
                                agentId.value(), request.requestId(), proposedLabelKey);
                        yield Resolution.unresolved(Outcome.UNUSABLE_DECISION);
                    }
                    if (!labels.contains(proposedLabel)) {
                        log.warn("ApprovalClassifier [{}] got an approval for request [{}] whose proposed label "
                                + "'{}' is outside the declared vocabulary {}",
                                agentId.value(), request.requestId(), proposedLabel, labels);
                        yield Resolution.unresolved(Outcome.UNUSABLE_DECISION);
                    }
                    yield new Resolution(proposedLabel, DECIDED_CONFIDENCE, Outcome.APPROVED);
                }
                case ApprovalDecision.Modified modified -> {
                    if (modified.newPayload() instanceof String chosen && labels.contains(chosen)) {
                        yield new Resolution(chosen, DECIDED_CONFIDENCE, Outcome.MODIFIED);
                    }
                    log.warn("ApprovalClassifier [{}] got a modified decision for request [{}] whose payload is "
                            + "not one of {} — expected a label String, got: {}",
                            agentId.value(), request.requestId(), labels, modified.newPayload());
                    yield Resolution.unresolved(Outcome.UNUSABLE_DECISION);
                }
                case ApprovalDecision.Rejected rejected -> {
                    log.info("ApprovalClassifier [{}] rejected request [{}]: {}",
                            agentId.value(), request.requestId(), rejected.reason());
                    yield Resolution.unresolved(Outcome.REJECTED);
                }
            };
        }

        // The proposed label is READ from state, so a run outside a session does not merely
        // lose a write here (IntentRouter's case, where routing still works with whatever
        // this call's own label was) — every Approved decision on every call becomes
        // unusable forever, because there is never anything to confirm. An operator can
        // confirm the same ticket a hundred times and be silently overruled every time.
        // ERROR, not WARN: this is a permanently defeated HITL step, not a degraded one.
        private void warnIfStateIsBlackHole(RunState state) {
            if (state == RunState.noop() && NOOP_STATE_WARNED.compareAndSet(false, true)) {
                log.error("ApprovalClassifier [{}] reads the proposed label from RunState, but this run carries "
                        + "RunState.noop() — no label will ever be found, so every Approved decision on every "
                        + "call becomes UNUSABLE_DECISION, silently overruling the operator every time. "
                        + "Run the pipeline through a session, or seed the task with "
                        + "RunContext.withState(RunState.inMemory()). (This is logged once per built agent, "
                        + "not once per call — the condition does not go away on its own.)", agentId.value());
            }
        }

        private String json(Resolution resolution) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put(labelField, resolution.label() != null ? resolution.label() : unresolvedLabel);
            node.put(confidenceField, resolution.confidence());
            return node.toString();
        }

        private record Resolution(String label, double confidence, Outcome outcome) {
            static Resolution unresolved(Outcome outcome) {
                return new Resolution(null, UNRESOLVED_CONFIDENCE, outcome);
            }
        }
    }
}
