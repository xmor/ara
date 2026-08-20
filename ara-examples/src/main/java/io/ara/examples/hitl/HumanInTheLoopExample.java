package io.ara.examples.hitl;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalNotifier;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.hitl.InMemoryApprovalGate;
import io.ara.runtime.hitl.LoggingApprovalNotifier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runnable tour of the human-in-the-loop (HITL) approval gate, built around a
 * customer-support refund agent — a case where the difference between "the agent
 * decided" and "a human decided" is money leaving an account.
 *
 * <p>The agent has two tools: {@code lookup_order} (read-only) and {@code issue_refund}
 * (moves money, recorded in a {@link RefundLedger}). It runs with
 * {@link AgentConfig.Builder#humanApprovalRequired(boolean) humanApprovalRequired(true)},
 * so {@code AraRuntime} routes <em>every</em> tool dispatch through the configured
 * {@link ApprovalGate}.
 *
 * <p>Gating every call is rarely what a real deployment wants, so the gate here is a
 * {@link PolicyApprovalGate}: it decides <em>whether</em> a human is needed at all
 * (read-only calls and small refunds resolve instantly, without ever reaching a queue),
 * assigns a review deadline proportional to the risk, and fires an
 * {@link ApprovalNotifier} on the ones it escalates. That is the piece the framework
 * deliberately leaves to the application — the built-in
 * {@code ApprovalToolRegistry} knows how to block, not what is worth blocking on.
 *
 * <p>Five scenarios, each running the same agent against the same tools, differing only
 * in what the human does:
 * <ol>
 *   <li><b>Auto-approved</b> — a €20 refund stays under the policy limit; no human is
 *       ever involved and nothing appears in the pending queue.</li>
 *   <li><b>Approved</b> — a €120 refund is escalated and the reviewer approves it.</li>
 *   <li><b>Modified</b> — a €500 refund is escalated and the reviewer caps it at €200;
 *       the tool executes with the <em>reviewer's</em> payload, not the model's.</li>
 *   <li><b>Rejected</b> — the reviewer refuses, and the reason travels back to the model
 *       as an observation, which shapes the answer the customer receives.</li>
 *   <li><b>Timed out</b> — nobody reviews it before the deadline; the agent recovers
 *       instead of hanging, and the ledger stays untouched.</li>
 * </ol>
 * In the last two the ledger is still empty at the end — the interesting proof that a
 * rejection is a real block, not a log line after the fact.
 */
public final class HumanInTheLoopExample {

    /** Refunds at or below this stay with the agent; anything larger needs a human. */
    private static final double AUTO_APPROVE_LIMIT_EUR = 50.00;

    /**
     * How long a reviewer has before the request expires. Seconds here so the demo
     * finishes; a real console would be given minutes or hours — the agent is parked on
     * a virtual thread, so waiting costs a stack, not an OS thread.
     */
    private static final Duration REVIEW_SLA = Duration.ofSeconds(3);

    public static void main(String[] args) throws Exception {
        System.out.println("=== ARA HITL — approval gate on a refund agent ===");
        System.out.printf("Policy: refunds ≤ €%.2f are auto-approved; above that a human reviews "
                        + "within %ds.%n", AUTO_APPROVE_LIMIT_EUR, REVIEW_SLA.toSeconds());

        // 1. Under the policy limit — resolved by the gate itself, no human in the loop.
        runScenario("1 — Auto-approved (under the policy limit)", 20.00, null);

        // 2. Escalated, and the reviewer approves as-is.
        runScenario("2 — Approved by a human", 120.00,
                request -> new ApprovalDecision.Approved());

        // 3. Escalated, and the reviewer approves a *smaller* refund than the model asked for.
        runScenario("3 — Modified by a human (capped at €200)", 500.00,
                request -> new ApprovalDecision.Modified(
                        capAmount(String.valueOf(request.payload()), 200.00)));

        // 4. Escalated and refused — the reason reaches the model.
        runScenario("4 — Rejected by a human", 480.00,
                request -> new ApprovalDecision.Rejected(
                        "order is outside the 30-day return window"));

        // 5. Escalated and ignored — the request expires on its own.
        runScenario("5 — Timed out (no reviewer responds)", 300.00, null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Scenario driver
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Runs one full agent execution against a fresh runtime.
     *
     * @param title       banner for the console output
     * @param requestedEur amount the model will ask {@code issue_refund} for
     * @param reviewer    what the human does with an escalated request, or {@code null}
     *                    for "nobody is watching the queue" — which is scenario 1
     *                    (nothing is ever escalated) and scenario 5 (it expires)
     */
    private static void runScenario(String title, double requestedEur,
                                    Function<ApprovalRequest, ApprovalDecision> reviewer)
            throws InterruptedException {

        header(title);

        RefundLedger ledger = new RefundLedger();
        PolicyApprovalGate gate = new PolicyApprovalGate(
                new InMemoryApprovalGate(), new LoggingApprovalNotifier(),
                AUTO_APPROVE_LIMIT_EUR, REVIEW_SLA);

        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("stub", new RefundLlmClient(requestedEur))
                .toolRegistry(new RefundToolRegistry(ledger))
                // Without this the gate is never consulted, even with
                // humanApprovalRequired(true) on the agent below.
                .approvalGate(gate)
                .build()) {

            runtime.start();

            AgentConfig config = AgentConfig.defaults()
                    .agentType("refund-agent")
                    .systemPrompt("You handle customer refund requests.")
                    .primaryLlm(LlmProfile.of("stub"))
                    .plannerStrategy("react")
                    .enabledTools(List.of("lookup_order", "issue_refund"))
                    .humanApprovalRequired(true)
                    .maxIterations(6)
                    .build();

            AraAgent agent = runtime.createAgent(config);

            // The reviewer runs on its own virtual thread: it polls the gate the way an
            // HTTP gateway or a CLI would, then submits a decision. Meanwhile this thread
            // runs the agent, which parks inside the gate until that decision lands.
            Thread console = reviewer == null
                    ? null
                    : ApprovalConsole.watch(gate, agent.agentId().value(), reviewer);

            AgentResponse response = agent.execute(
                    AgentTask.of("Customer asks for a refund on order " + ORDER_ID + "."));

            if (console != null) console.join();

            System.out.printf("%n  Agent answer : %s%n", response.content());
            System.out.printf("  Ledger       : %s%n", ledger.describe());
            System.out.printf("  Still pending: %d%n", gate.getPendingRequests().size());

            runtime.destroyAgent(agent);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The policy gate — decides whether a human is needed, and how long they have
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * {@link ApprovalGate} decorator that resolves low-risk requests itself and escalates
     * the rest to a delegate gate (here {@link InMemoryApprovalGate}).
     *
     * <p>This is where an application expresses its risk appetite. {@code AraRuntime}
     * routes every tool call of an approval-required agent through the gate, so without
     * a policy in front, a read-only {@code lookup_order} would sit in a queue waiting
     * for a human — approval fatigue that trains reviewers to click "approve" on
     * everything, including the one request that mattered.
     *
     * <p>Two things happen on escalation:
     * <ul>
     *   <li>The request is re-stamped with a risk-proportional deadline and with
     *       {@code metadata} the reviewer needs to decide. The {@code requestId} is
     *       preserved, so {@link #submit} keys still match what the caller was handed.</li>
     *   <li>The {@link ApprovalNotifier} fires. Nothing in the runtime calls a notifier
     *       on its own — connecting the two is the gate's job, and this is the seam
     *       where a {@code WebhookApprovalNotifier} would page a real on-call queue.</li>
     * </ul>
     */
    static final class PolicyApprovalGate implements ApprovalGate {

        private final ApprovalGate     delegate;
        private final ApprovalNotifier notifier;
        private final double           autoApproveLimitEur;
        private final Duration         reviewSla;

        PolicyApprovalGate(ApprovalGate delegate, ApprovalNotifier notifier,
                           double autoApproveLimitEur, Duration reviewSla) {
            this.delegate            = Objects.requireNonNull(delegate);
            this.notifier            = Objects.requireNonNull(notifier);
            this.autoApproveLimitEur = autoApproveLimitEur;
            this.reviewSla           = Objects.requireNonNull(reviewSla);
        }

        @Override
        public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
            String payload = String.valueOf(request.payload());

            if (isReadOnly(request.action())) {
                System.out.printf("  [Policy]   %-13s read-only → auto-approved%n", request.action());
                return CompletableFuture.completedFuture(new ApprovalDecision.Approved());
            }

            double amount = amountOf(payload);
            if (amount <= autoApproveLimitEur) {
                System.out.printf("  [Policy]   %-13s €%.2f ≤ €%.2f limit → auto-approved%n",
                        request.action(), amount, autoApproveLimitEur);
                return CompletableFuture.completedFuture(new ApprovalDecision.Approved());
            }

            System.out.printf("  [Policy]   %-13s €%.2f > €%.2f limit → escalating to a human%n",
                    request.action(), amount, autoApproveLimitEur);

            ApprovalRequest escalated = withReviewContext(request, amount);
            notifier.notify(escalated);
            return delegate.requestApproval(escalated);
        }

        @Override
        public void submit(String requestId, ApprovalDecision decision) {
            delegate.submit(requestId, decision);
        }

        @Override
        public List<ApprovalRequest> getPendingRequests() {
            return delegate.getPendingRequests();
        }

        /**
         * Rebuilds the request with the policy's own deadline and the context a reviewer
         * needs, keeping {@code requestId} stable via the canonical record constructor.
         */
        private ApprovalRequest withReviewContext(ApprovalRequest request, double amount) {
            Map<String, Object> metadata = new LinkedHashMap<>(request.metadata());
            metadata.put("amountEur", amount);
            metadata.put("risk", amount > 250.00 ? "high" : "medium");
            metadata.put("policy", "auto-approve ≤ €%.2f".formatted(autoApproveLimitEur));

            return new ApprovalRequest(
                    request.requestId(),
                    request.agentId(),
                    request.action(),
                    request.payload(),
                    Instant.now().plus(reviewSla),
                    metadata);
        }

        private static boolean isReadOnly(String action) {
            return action.startsWith("lookup_") || action.startsWith("get_");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // The operator side — what a gateway, Slack bot or CLI does
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Stand-in for the human-facing surface: polls {@link ApprovalGate#getPendingRequests()}
     * and resolves the first request belonging to {@code agentId} via
     * {@link ApprovalGate#submit}. A real console does the same two calls behind an HTTP
     * route — the gate instance is simply shared between the runtime and that route.
     */
    static final class ApprovalConsole {

        private ApprovalConsole() {}

        static Thread watch(ApprovalGate gate, String agentId,
                            Function<ApprovalRequest, ApprovalDecision> reviewer) {
            return Thread.ofVirtual().name("approval-console").start(() -> {
                Optional<ApprovalRequest> found = poll(gate, agentId);
                if (found.isEmpty()) {
                    System.out.println("  [Reviewer] nothing to review");
                    return;
                }
                ApprovalRequest request = found.get();

                System.out.printf("  [Reviewer] inbox: agent=%s action=%s risk=%s payload=%s%n",
                        shortId(request.agentId()), request.action(),
                        request.metadata().get("risk"), request.payload());

                // Reviewers are not instant — the agent is parked on a virtual thread
                // for this whole stretch, which is the point of the design.
                sleep(400);

                ApprovalDecision decision = reviewer.apply(request);
                System.out.printf("  [Reviewer] decision: %s%n", describe(decision));
                gate.submit(request.requestId(), decision);
            });
        }

        private static Optional<ApprovalRequest> poll(ApprovalGate gate, String agentId) {
            for (int attempt = 0; attempt < 40; attempt++) {
                Optional<ApprovalRequest> match = gate.getPendingRequests().stream()
                        .filter(r -> r.agentId().equals(agentId))
                        .findFirst();
                if (match.isPresent()) return match;
                sleep(50);
            }
            return Optional.empty();
        }

        private static String describe(ApprovalDecision decision) {
            return switch (decision) {
                case ApprovalDecision.Approved ignored -> "APPROVED as requested";
                case ApprovalDecision.Rejected r       -> "REJECTED — " + r.reason();
                case ApprovalDecision.Modified m       -> "MODIFIED → " + m.newPayload();
            };
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Domain: order book, ledger, tools
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String ORDER_ID    = "A-1001";
    private static final double ORDER_TOTAL = 500.00;

    /** Records refunds that actually executed, so a blocked call is visibly a no-op. */
    static final class RefundLedger {

        private final List<String> entries = new ArrayList<>();

        synchronized void record(String orderId, double amountEur) {
            entries.add("€%.2f on %s".formatted(amountEur, orderId));
        }

        synchronized String describe() {
            return entries.isEmpty() ? "(empty — no money moved)" : String.join(", ", entries);
        }
    }

    /** {@code lookup_order} (read-only) and {@code issue_refund} (moves money). */
    static final class RefundToolRegistry implements ToolRegistry {

        private final List<AraTool> tools;

        RefundToolRegistry(RefundLedger ledger) {
            AraTool lookupOrder = new AraTool() {
                @Override public String toolId()         { return "lookup_order"; }
                @Override public String description()    { return "Returns order details by id."; }
                @Override public String argumentSchema() {
                    return """
                            {"type":"object","properties":{"orderId":{"type":"string"}},"required":["orderId"]}""";
                }

                @Override
                public ToolResult execute(String argumentJson) {
                    System.out.println("  [Tool]     lookup_order  " + argumentJson);
                    return ToolResult.success("lookup_order", json(
                            """
                            {"orderId":"%s","customer":"M. Rossi","item":"Espresso machine",\
                            "totalEur":%.2f,"deliveredDaysAgo":34}""",
                            ORDER_ID, ORDER_TOTAL));
                }
            };

            AraTool issueRefund = new AraTool() {
                @Override public String toolId()         { return "issue_refund"; }
                @Override public String description()    { return "Refunds an amount against an order."; }
                @Override public String argumentSchema() {
                    return """
                            {"type":"object","properties":{"orderId":{"type":"string"},\
                            "amountEur":{"type":"number"}},"required":["orderId","amountEur"]}""";
                }

                @Override
                public ToolResult execute(String argumentJson) {
                    // Reached only for calls the gate let through — approved as requested,
                    // approved under the limit, or approved with the reviewer's payload.
                    double amount = amountOf(argumentJson);
                    System.out.println("  [Tool]     issue_refund  " + argumentJson + "  ← money moves here");
                    ledger.record(ORDER_ID, amount);
                    return ToolResult.success("issue_refund",
                            "refund of €%.2f confirmed on order %s".formatted(amount, ORDER_ID));
                }
            };

            this.tools = List.of(lookupOrder, issueRefund);
        }

        @Override
        public List<AraTool> resolveEnabled(List<String> enabledToolIds) {
            return tools.stream().filter(t -> enabledToolIds.contains(t.toolId())).toList();
        }

        @Override
        public List<AraTool> all() {
            return tools;
        }

        @Override
        public Optional<AraTool> findById(String toolId) {
            return tools.stream().filter(t -> t.toolId().equals(toolId)).findFirst();
        }

        @Override
        public ToolResult execute(String toolId, String argumentJson) {
            return findById(toolId)
                    .map(t -> t.execute(argumentJson))
                    .orElseGet(() -> ToolResult.failure(toolId, "Tool not found: " + toolId));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Stub LLM
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Offline stand-in for a model, driven by the observations already in the window:
     * look the order up, ask for the refund, then answer.
     *
     * <p>The final answer is built from the last observation rather than from a canned
     * string, which is what makes the rejection and timeout scenarios worth reading: the
     * reviewer's reason comes back through {@code ToolResult.failure} as an ordinary
     * observation, so the model can tell the customer why — the same round trip a real
     * model would get.
     */
    static final class RefundLlmClient implements LlmClient {

        private final double requestedEur;

        RefundLlmClient(double requestedEur) {
            this.requestedEur = requestedEur;
        }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            List<String> observations = messages.stream()
                    .map(LlmMessage::content)
                    .filter(c -> c.startsWith("Observation:"))
                    .toList();

            return switch (observations.size()) {
                case 0  -> toolCall("lookup_order", json("{\"orderId\":\"%s\"}", ORDER_ID));
                case 1  -> toolCall("issue_refund",
                        json("{\"orderId\":\"%s\",\"amountEur\":%.2f}", ORDER_ID, requestedEur));
                default -> finalAnswer(observations.getLast());
            };
        }

        @Override
        public String providerId() {
            return "stub";
        }

        private static LlmCompletion toolCall(String toolId, String argumentJson) {
            return new LlmCompletion(
                    "I should call " + toolId + ".", 20, 15, "tool_calls",
                    "{\"tool_id\":\"%s\",\"arguments\":%s}".formatted(toolId, argumentJson));
        }

        /** Turns the last observation into a customer-facing answer. */
        private static LlmCompletion finalAnswer(String lastObservation) {
            String body = lastObservation.substring("Observation: ".length());
            int failureMarker = body.indexOf("failed — ");

            String answer = failureMarker >= 0
                    ? "I could not process the refund: " + body.substring(failureMarker + "failed — ".length())
                    : "Done — " + body;

            return new LlmCompletion("Action: FINAL_ANSWER\nAnswer: " + answer, 40, 30, "stop", null);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private static final Pattern AMOUNT = Pattern.compile("\"amountEur\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");

    /**
     * Formats JSON with {@link Locale#ROOT}, so {@code %.2f} stays {@code 120.00} rather
     * than becoming {@code 120,00} — which is what {@code String.formatted} would produce
     * under an Italian or German default locale, and it would not parse back as a number.
     */
    private static String json(String template, Object... args) {
        return String.format(Locale.ROOT, template, args);
    }

    /** Naive amount extraction — enough for a demo payload, not a JSON parser. */
    private static double amountOf(String argumentJson) {
        Matcher matcher = AMOUNT.matcher(argumentJson);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : 0.0;
    }

    /**
     * Produces the payload a reviewer approves instead of the model's, capped at
     * {@code maxEur}. {@link ApprovalDecision.Modified} payloads reach the tool only when
     * they are {@code String}s — {@code ApprovalToolRegistry} falls back to the original
     * arguments for anything else, so this returns the argument JSON the tool expects.
     */
    private static String capAmount(String argumentJson, double maxEur) {
        double capped = Math.min(amountOf(argumentJson), maxEur);
        return AMOUNT.matcher(argumentJson)
                .replaceFirst(json("\"amountEur\":%.2f", capped));
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void header(String title) {
        System.out.println("\n" + "─".repeat(78));
        System.out.println("Scenario " + title);
        System.out.println("─".repeat(78));
    }

    private HumanInTheLoopExample() {}
}
