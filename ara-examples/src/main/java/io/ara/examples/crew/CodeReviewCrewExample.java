package io.ara.examples.crew;

import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.agent.AgentChain;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.common.Money;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmProfile;
import io.ara.examples.crew.Reviewers.Reviewer;
import io.ara.examples.support.Ansi;
import io.ara.examples.support.Live;
import io.ara.examples.support.Tools;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.pipeline.AgentPipeline;
import io.ara.runtime.pipeline.PipelineAgents;
import io.ara.runtime.stubs.AssociativeLlmClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A <b>code-review crew</b>: three specialist reviewers analyse the same file at the same
 * time, then a lead agent merges their findings into one prioritised report. It is the
 * shortest way to see what ARA means by "a runtime, not a toolkit" — a real multi-agent
 * fan-out, a real merge, a real execution trace, with no annotations and no API key.
 *
 * <p>Why a fan-out and not a sequential chain. The three reviews are independent: security
 * does not need the performance verdict to do its job. Running them one after another would
 * spend three times the latency to produce the same three answers. {@code ParallelAgent}
 * (reached here through {@link AgentPipeline.Builder#parallel}) is the primitive for that:
 * it runs the members concurrently on {@link AraRuntime#executor()}, which is a virtual-thread
 * executor, and folds their responses into one with an {@link AgentChain.MergeStrategy}. The
 * hand-rolled alternative — spawning threads, joining them, concatenating their answers and
 * summing their tokens by hand — is exactly the code the runtime already owns, so the demo
 * does not repeat it.
 *
 * <p>The crew is hosted as a single agent. {@link PipelineAgents#of} wraps the pipeline in a
 * real {@code AgentInstance}, so the whole crew has an id, a session and a lifecycle like any
 * other agent and could itself be delegated to, scheduled or nested. The pipeline's two steps
 * are visible in the console: {@code review} (the fan-out) then {@code synthesize} (the lead).
 *
 * <p>The merge strategy is custom, and deliberately so: it concatenates the three findings —
 * which is what the lead then reads — and also keeps the individual responses, so the summary
 * can report each reviewer's own tokens and time. {@link AgentChain#aggregateSuccess} still
 * does the token/cost accounting of the merged step, so nothing is re-derived by hand.
 *
 * <p>Runs offline by default: an {@link AssociativeLlmClient} replays a script keyed by agent
 * id (each reviewer gets its own answer), and the three tools are deterministic stand-ins
 * with a simulated latency so the concurrency is visible. Pass {@code live} as the first
 * argument (or {@code -Dara.example.live=true}) to run the same crew against a real
 * OpenAI-compatible endpoint, where the model reasons over the source and the tools for real.
 *
 * @see io.ara.examples.pipeline.ClassifyAndActExample — the other side of orchestration: one classifier, one worker
 */
public final class CodeReviewCrewExample {

    private static final String LIVE_BASE_URL = "http://127.0.0.1:1234/v1";
    private static final String LIVE_MODEL    = "qwen3-coder-30b-a3b-instruct";
    /** LM Studio ignores the key but langchain4j wants a non-blank string; override with
     *  {@code -Dara.api.key=...} or {@code ARA_API_KEY} if your gateway checks it. */
    private static final String LIVE_API_KEY  = Live.apiKey("not-required");

    /** Illustrative tariffs, so the crew's cost column is not a row of zeroes. */
    private static final Money INPUT_PRICE  = Money.of("0.0005", "EUR");
    private static final Money OUTPUT_PRICE = Money.of("0.0015", "EUR");

    /** The report the offline lead returns; the live lead writes its own from the findings. */
    private static final String OFFLINE_LEAD_REPORT = """
            1. [SECURITY · high] SQL injection in findOrders — userId is concatenated into the query. Fix: a PreparedStatement with a bound parameter.
            2. [PERFORMANCE · medium] O(n^2) nested loops plus String += in the loop body. Fix: group by customerId in a single pass into a StringBuilder.
            3. [STYLE · low] Terse names (sql, result, i, j) and a magic ',' delimiter. Fix: rename to query/rows and extract a DELIMITER constant.""";

    public static void main(String[] args) {
        boolean live = Live.requested(args);
        LlmClient model = live ? openAi() : offlineScript();

        banner(live);

        // Filled by the merge strategy below, then read by the summary — the only way to
        // report each reviewer separately, since the merged step exposes only their sum.
        List<AgentResponse> reviewerResponses = Collections.synchronizedList(new ArrayList<>());

        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("model", model)
                .toolRegistry(Tools.registry(ReviewTools.all()))
                .build()) {

            runtime.start();

            List<AraAgent> reviewers = Reviewers.ALL.stream()
                    .map(reviewer -> runtime.createAgent(reviewerConfig(reviewer)))
                    .toList();
            AraAgent lead = runtime.createAgent(leadConfig());

            AgentPipeline pipeline = AgentPipeline.builder()
                    .parallel("review", reviewers, runtime.executor(), collectAndJoin(reviewerResponses))
                    .step("synthesize", lead)
                    .build();

            AraAgent crew = PipelineAgents.of(AgentId.of("code-review-crew"), crewConfig(), pipeline);

            System.out.printf("%n▶ task: review %s — %d reviewers fan out, one lead merges%n%n",
                    ReviewTools.FILE_NAME, reviewers.size());

            AgentResponse response = crew.execute(
                    AgentTask.of("Review " + ReviewTools.FILE_NAME + " and report the top issues."));

            printFindings(reviewerResponses);
            printLeadReport(response.content());
            printSummary(response, reviewerResponses);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Wiring
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Merge strategy for the fan-out: joins the three findings into the text the lead reads,
     * and records the individual responses for the summary. Runs once, on one thread, after
     * every member has completed, so the sink needs no locking of its own.
     */
    private static AgentChain.MergeStrategy collectAndJoin(List<AgentResponse> sink) {
        return responses -> {
            sink.addAll(responses);
            String joined = responses.stream()
                    .map(AgentResponse::content)
                    .collect(Collectors.joining("\n\n"));
            return AgentChain.aggregateSuccess(joined, responses);
        };
    }

    private static AgentConfig reviewerConfig(Reviewer reviewer) {
        return AgentConfig.defaults()
                .agentId(AgentId.of(reviewer.agentId()))
                .agentType("code-reviewer")
                .systemPrompt(reviewer.systemPrompt())
                .primaryLlm(profile())
                .plannerStrategy("react")
                .enabledTools(List.of(reviewer.toolId()))
                .maxIterations(4)
                .build();
    }

    private static AgentConfig leadConfig() {
        return AgentConfig.defaults()
                .agentId(AgentId.of(Reviewers.LEAD_AGENT_ID))
                .agentType("review-lead")
                .systemPrompt(Reviewers.LEAD_PROMPT)
                .primaryLlm(profile())
                .plannerStrategy("react")
                .enabledTools(List.of())
                .maxIterations(2)
                .build();
    }

    private static AgentConfig crewConfig() {
        return AgentConfig.defaults()
                .agentId(AgentId.of("code-review-crew"))
                .agentType("code-review-crew")
                .plannerStrategy("pipeline")
                // The pipeline strategy does not forward the steps' cost, so the hosted agent
                // prices the run itself from the totals it does carry (tokens) and these
                // tariffs — without a profile here the crew's cost line would read zero.
                .primaryLlm(profile())
                .build();
    }

    private static LlmProfile profile() {
        return LlmProfile.builder()
                .transportId("model")
                .costInputPer1kTokens(INPUT_PRICE)
                .costOutputPer1kTokens(OUTPUT_PRICE)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Model — live endpoint, or the offline per-agent script
    // ═══════════════════════════════════════════════════════════════════════════

    private static LlmClient openAi() {
        return OpenAiLlmClient.builder()
                .baseUrl(LIVE_BASE_URL)
                .apiKey(LIVE_API_KEY)
                .modelName(LIVE_MODEL)
                .build();
    }

    /**
     * One script per agent id: each reviewer calls its tool once, then answers; the lead
     * answers directly. Keyed by id rather than by call order precisely because the three
     * reviewers run at once — a single shared queue would hand the head of the queue to
     * whichever thread won the race, and the demo would be non-deterministic.
     */
    private static LlmClient offlineScript() {
        AssociativeLlmClient.Builder script = AssociativeLlmClient.script();
        for (Reviewer reviewer : Reviewers.ALL) {
            script.forAgent(reviewer.agentId())
                    .thenToolCall(reviewer.toolId(), "{\"file\":\"" + ReviewTools.FILE_NAME + "\"}")
                    .thenFinalAnswer(reviewer.shortName().toUpperCase() + " — " + reviewer.finding());
        }
        return script.forAgent(Reviewers.LEAD_AGENT_ID)
                .thenFinalAnswer(OFFLINE_LEAD_REPORT)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Presentation
    // ═══════════════════════════════════════════════════════════════════════════

    private static void banner(boolean live) {
        String model = live
                ? "LIVE · " + LIVE_MODEL + " @ " + LIVE_BASE_URL
                : "stub · scripted, offline (pass \"live\" for a real model)";
        System.out.println(Ansi.paint(Ansi.BOLD, "═".repeat(74)));
        System.out.println(Ansi.paint(Ansi.BOLD, "  ARA code-review crew — three specialists, one lead"));
        System.out.println("  " + Ansi.paint(Ansi.GREY, "LLM: " + model));
        System.out.println(Ansi.paint(Ansi.BOLD, "═".repeat(74)));
    }

    private static void printFindings(List<AgentResponse> reviewerResponses) {
        section("specialist findings");
        for (Reviewer reviewer : Reviewers.ALL) {
            find(reviewerResponses, reviewer.agentId()).ifPresent(response ->
                    System.out.printf("%s %s%n",
                            Ansi.paint(reviewer.color(),
                                    String.format("%-14s", reviewer.shortName() + ":")),
                            response.content()));
        }
    }

    private static void printLeadReport(String report) {
        section("lead report");
        for (String paragraph : report.split("\n")) {
            List<String> lines = wrap(paragraph, 70);
            for (int i = 0; i < lines.size(); i++) {
                // Hanging indent: the item's own text sits under its marker, not under "1.".
                System.out.println(i == 0 ? "  " + lines.get(i) : "    " + lines.get(i));
            }
        }
    }

    private static void printSummary(AgentResponse response, List<AgentResponse> reviewerResponses) {
        section("crew summary");

        long reviewerWorkMs = 0;
        for (Reviewer reviewer : Reviewers.ALL) {
            Optional<AgentResponse> found = find(reviewerResponses, reviewer.agentId());
            if (found.isEmpty()) continue;
            AgentResponse each = found.get();
            reviewerWorkMs += each.elapsedTime().toMillis();
            System.out.printf("  %s %2d iters · %4d tok · %-12s · %4d ms%n",
                    Ansi.paint(reviewer.color(), String.format("%-14s", reviewer.shortName())),
                    each.iterationsUsed(), each.totalTokens(), money(each.estimatedCost()),
                    each.elapsedTime().toMillis());
        }

        long wallMs = response.elapsedTime().toMillis();
        System.out.println("  " + Ansi.paint(Ansi.GREY, "─".repeat(70)));
        System.out.printf(Locale.ROOT,
                "  %s 3 reviewers ran at once — %d ms of work in %d ms wall-clock (≈%.1f× overlap)%n",
                Ansi.paint(Ansi.BOLD, "fan-out    "), reviewerWorkMs, wallMs,
                reviewerWorkMs / (double) Math.max(1, wallMs));
        System.out.printf("  %s review → synthesize · %d tok · %s · %d ms%n",
                Ansi.paint(Ansi.BOLD, "whole crew "), response.totalTokens(),
                money(response.estimatedCost()), wallMs);
    }

    private static Optional<AgentResponse> find(List<AgentResponse> responses, String agentId) {
        return responses.stream()
                .filter(r -> r.agentId().value().equals(agentId))
                .findFirst();
    }

    private static void section(String title) {
        int fill = Math.max(1, 60 - title.length());
        System.out.println();
        System.out.println(Ansi.paint(Ansi.BOLD, "── " + title + " " + "─".repeat(fill)));
    }

    private static String money(Money amount) {
        return String.format(Locale.ROOT, "%.5f %s", amount.amount(), amount.currency());
    }

    /** Greedy word wrap — enough for a console report, not a text-formatting library. */
    private static List<String> wrap(String text, int width) {
        if (text.isBlank()) return List.of("");
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.strip().split("\\s+")) {
            if (current.length() > 0 && current.length() + 1 + word.length() > width) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    private CodeReviewCrewExample() { }
}
