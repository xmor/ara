package io.ara.examples.basics;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentExecutionContext;
import io.ara.core.agent.AgentInterceptor;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.runtime.AraRuntime;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Token streaming through a full ReAct loop that also calls a tool.
 *
 * <p>Runs offline by default with a stub LLM. Pass {@code live} as the first argument (or
 * {@code -Dara.example.live=true}) to point it at a real OpenAI-compatible endpoint instead
 * — the constants {@link #LIVE_BASE_URL} / {@link #LIVE_MODEL} / {@link #LIVE_API_KEY} are
 * preset for a local LM-Studio-style server ({@code openai/gpt-oss-20b}, no key required).
 *
 * <p>The point worth seeing is that streaming is <em>not</em> just "the final answer, one
 * token at a time": {@code ReactStrategy} streams <strong>every</strong> Think step, so the
 * token callback fires on the reasoning turn that decides to call a tool as well as on the
 * turn that writes the answer.
 *
 * <p>Scenario — a weather assistant:
 * <ol>
 *   <li><b>Turn 1</b> (the tool-deciding step):
 *       <ul>
 *         <li><i>stub</i> — the model thinks out loud, then ends its turn with an ARA inline
 *             tool-call {@code {"tool_id":"get_weather", ...}}; {@code streamAndCollect}
 *             accumulates that text and {@code ToolCallParser} pulls the call back out.</li>
 *         <li><i>live</i> — a real model emits a <em>native</em> function call: the token
 *             stream carries little or no text, so {@code streamAndCollect} sees a blank
 *             result and falls back to {@code complete()} to recover the structured call
 *             (see its javadoc). Both routes end in the same tool dispatch.</li>
 *       </ul></li>
 *   <li><b>Tool</b>: {@code get_weather} returns a canned reading, added to memory as an
 *       {@code Observation:}.</li>
 *   <li><b>Turn 2</b> (streamed for real in both modes): with the observation in context the
 *       model streams a full-sentence {@code FINAL_ANSWER} that ends the loop.</li>
 * </ol>
 *
 * <p>Two things must both be set for the streaming path to run
 * ({@code ReactExecutionSupport.callLlm}):
 * <ul>
 *   <li>{@code LlmProfile.builder().streamingEnabled(true)} — a per-call parameter;</li>
 *   <li>{@code AgentTask.ofStreaming(input, tokenCallback)} — a sink for the tokens.</li>
 * </ul>
 * RUN 2 keeps {@code streamingEnabled(true)} but submits a plain {@code AgentTask.of(...)}
 * with no callback: the identical pipeline then takes the blocking path and only the final
 * {@link AgentResponse} comes back.
 *
 * <p>Note on hooks: {@code InterceptingLlmClient} deliberately leaves {@code stream()}
 * un-instrumented, so {@code AgentInterceptor.beforeThink/afterThink} do <em>not</em> fire
 * on the streaming path, and there is no per-token hook on {@code AgentInterceptor} at all.
 * The token stream is delivered only through {@link AgentTask}'s {@code tokenCallback}
 * (a {@code Consumer<String>}; a gateway turns it into SSE {@code token} events) — that is
 * why {@link StreamProbe} exists and an interceptor cannot replace it. Tool dispatch is
 * <em>not</em> part of the streamed call, so {@code afterToolCall} still fires normally:
 * the tool line in the feed below comes from {@link ObservationInterceptor}.
 *
 * <p>The stub LLM overrides {@link LlmClient#stream} natively and emits word by word with a
 * small delay, the way a real provider's SSE connection would — {@code OpenAiLlmClient},
 * {@code AnthropicLlmClient} et al. do the same over a real socket.
 */
public class StreamingWithToolExample {

    /** Per-token delay in the stub, purely so the streaming is visible in a terminal. */
    private static final long TOKEN_DELAY_MILLIS = 35;

    // ── Live endpoint (used only with the "live" arg or -Dara.example.live=true) ──────────
    /** OpenAI-compatible base URL. {@code /v1} suffix included, as LM Studio / vLLM expect. */
    private static final String LIVE_BASE_URL = "http://192.168.1.114:1234/v1";
    // private static final String LIVE_MODEL = "llama-3.1-8b-instruct";
    private static final String LIVE_MODEL    = "openai/gpt-oss-20b";
    /** LM Studio ignores the key but langchain4j requires a non-blank string. Override with
     *  {@code -Dara.api.key=...} or {@code ARA_API_KEY} if your gateway does check it. */
    private static final String LIVE_API_KEY  = firstNonBlank(
            System.getProperty("ara.api.key"), System.getenv("ARA_API_KEY"), "not-required");

    private static boolean liveRequested(String[] args) {
        return Boolean.getBoolean("ara.example.live")
                || (args.length > 0 && args[0].equalsIgnoreCase("live"));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    public static void main(String[] args) {

        boolean live = liveRequested(args);
        LlmClient llmClient = live
                ? OpenAiLlmClient.builder()
                        .baseUrl(LIVE_BASE_URL)
                        .apiKey(LIVE_API_KEY)
                        .modelName(LIVE_MODEL)
                        .build()
                : new WordByWordLlmClient(TOKEN_DELAY_MILLIS);

        System.out.println("=== ARA — token streaming through a tool-using agent ===");
        System.out.printf("LLM: %s%n%n", live
                ? "LIVE — " + LIVE_MODEL + " @ " + LIVE_BASE_URL
                : "stub — word-by-word (offline). Pass \"live\" to use " + LIVE_MODEL + ".");

        try (AraRuntime runtime = AraRuntime.builder()
                .llmClient("weather-model", llmClient)
                .toolRegistry(new WeatherToolRegistry())
                .interceptors(List.of(new ObservationInterceptor()))
                .build()) {

            runtime.start();

            AgentConfig config = AgentConfig.defaults()
                    .agentType("weather-assistant")
                    .systemPrompt("Sei un assistente meteo. Usa lo strumento get_weather quando serve.")
                    .primaryLlm(LlmProfile.builder()
                            .transportId("weather-model")
                            .streamingEnabled(true)      // ← required for the streaming path
                            .build())
                    .plannerStrategy("react")
                    .enabledTools(List.of("get_weather"))
                    .maxIterations(5)
                    .build();

            AraAgent agent = runtime.createAgent(config);
            System.out.printf("Agent: %s  state=%s%n", agent.agentId().value(), agent.currentState());

            String prompt = "Che tempo fa a Roma? Mi serve sapere se portare l'ombrello.";

            // ── RUN 1 — streaming ─────────────────────────────────────────────────
            System.out.println("\n════════ RUN 1 — streaming ════════");
            System.out.println("User: " + prompt);
            System.out.println(live
                    ? "(turn 1 = native function call: stream is blank, streamAndCollect falls"
                    + " back to complete(); turn 2 streams the answer)"
                    : "(turn 1 = reasoning step, streamed, ending with an ARA inline tool-call;"
                    + " turn 2 streams the answer)");
            System.out.println("Each line below is one tokenCallback invocation, tagged with the time");
            System.out.println("elapsed since agent.execute() was called — so you can see the tokens");
            System.out.println("arrive one at a time, spread over the call, not in a single block.\n");

            // The token feed is delivered only through AgentTask's tokenCallback — the
            // AgentInterceptor SPI has no per-token hook. The tool-call line in the feed
            // below comes from ObservationInterceptor.afterToolCall instead.
            StreamProbe probe = new StreamProbe();
            AgentTask streamingTask = AgentTask.ofStreaming(prompt, probe::onToken);

            System.out.println("┌─ token feed ─────────────────────────────────────────");
            probe.markExecuteStart();
            AgentResponse streamed = agent.execute(streamingTask);
            long afterExecuteMs = probe.elapsedMs();
            System.out.println("└─ agent.execute() returned at t+" + afterExecuteMs + "ms\n");

            probe.printProof(afterExecuteMs, live);

            System.out.println("\nReassembled answer (AgentResponse — same object the blocking path returns):");
            printResult(streamed);
            System.out.println("  note: on the streaming path totalTokens is estimated (~4 chars/token);");
            System.out.println("        providers send no usage counts mid-stream.");

            // ── RUN 2 — same agent, same config, no token sink ───────────────────
            System.out.println("\n════════ RUN 2 — no callback: same pipeline, blocking path ════════");
            System.out.println("User: " + prompt);
            System.out.println("Same agent, streamingEnabled still true — but AgentTask.of() carries no");
            System.out.println("token sink, so callLlm() takes the blocking branch.\n");

            AgentResponse blocking = agent.execute(AgentTask.of(prompt));
            System.out.println("  tokenCallback invocations : 0  (no ofStreaming ⇒ no streaming path)");
            System.out.println("  the whole answer materialised in one step:");
            printResult(blocking);

            runtime.destroyAgent(agent);
        }
    }

    private static void printResult(AgentResponse r) {
        System.out.printf("  success=%s  iterations=%d  totalTokens=%d  elapsed=%dms%n",
                r.isSuccess(), r.iterationsUsed(), r.totalTokens(), r.elapsedTime().toMillis());
        System.out.println("  answer : " + oneLine(r.content()));
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Stream probe — makes the incremental arrival visible and measurable
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * The {@code AgentTask} tokenCallback plus timing bookkeeping. Every invocation is
     * timestamped against {@link #markExecuteStart()} and echoed on its own line, then
     * summarised so the spread over time is unambiguous — a non-streaming path would show a
     * single chunk. There is no per-token hook on {@code AgentInterceptor}; a token sink like
     * this is the only way to observe a stream.
     */
    static final class StreamProbe {

        private long   startNanos;
        private long   firstTokenMs = -1;
        private long   lastTokenMs  = -1;
        private int    chunks       = 0;
        private int    chars        = 0;
        private long   prevMs       = -1;
        private long   maxGapMs     = 0;

        void markExecuteStart() { startNanos = System.nanoTime(); }

        long elapsedMs() { return (System.nanoTime() - startNanos) / 1_000_000; }

        /** tokenCallback. */
        void onToken(String token) {
            long now = elapsedMs();
            if (firstTokenMs < 0) firstTokenMs = now;
            if (prevMs >= 0) maxGapMs = Math.max(maxGapMs, now - prevMs);
            prevMs      = now;
            lastTokenMs = now;
            chunks++;
            chars += token.length();
            System.out.printf("  t+%5dms │ %s%n", now, visible(token));
        }

        void printProof(long afterExecuteMs, boolean live) {
            System.out.println("── streaming proof ───────────────────────────────────");
            System.out.printf("  tokenCallback invocations : %d   (0–1 would mean no streaming)%n", chunks);
            if (chunks == 0) {
                System.out.println("  (no tokens — turn used the blocking path; see streamAndCollect");
                System.out.println("   blank-stream fallback, or streamingEnabled is off)");
                return;
            }
            System.out.printf("  time to first token       : %d ms   (from execute(); includes turn 1)%n",
                    firstTokenMs);
            System.out.printf("  last token                : %d ms%n", lastTokenMs);
            System.out.printf("  spread                    : %d ms across %d chunks, largest gap %d ms%n",
                    lastTokenMs - firstTokenMs, chunks, maxGapMs);
            System.out.printf("  execute() returned        : %d ms   (right after the last token)%n", afterExecuteMs);
            System.out.printf("  chars delivered           : %d%n", chars);
            if (live) {
                System.out.println("  note: in live mode turn 1 is a native function call, so on the");
                System.out.println("        streaming path it costs two round-trips — one streaming attempt");
                System.out.println("        that comes back blank, then complete() to recover the call.");
                System.out.println("        That is most of 'time to first token', not the answer stream.");
            }
        }

        /** Show whitespace boundaries so consecutive chunks visibly concatenate. */
        private static String visible(String token) {
            return "«" + token.replace("\n", "\\n") + "»";
        }
    }

    /** Only prints the tool observation; unlike {@code beforeThink}, {@code afterToolCall}
     *  fires on the streaming path too (tool dispatch is not part of the streamed call). */
    static final class ObservationInterceptor implements AgentInterceptor {
        @Override public void before(AgentExecutionContext ctx, String stepName) { }
        @Override public void after(AgentExecutionContext ctx, String stepName, String result) { }
        @Override public void onError(AgentExecutionContext ctx, String stepName, Throwable t) {
            System.out.println("\n  ✗ " + stepName + ": " + t.getMessage());
        }
        @Override
        public void afterToolCall(AgentExecutionContext ctx, String toolId, String argumentJson, ToolResult result) {
            System.out.println("  ✔ " + toolId + " → " + result.output());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Stub LLM that streams natively, word by word
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Replays a two-turn script. {@link #complete} returns the whole turn at once (used on
     * the blocking path, and as {@code streamAndCollect}'s blank-stream fallback); {@link
     * #stream} emits the same text one word-plus-whitespace chunk at a time.
     */
    static final class WordByWordLlmClient implements LlmClient {

        private static final Pattern CHUNK = Pattern.compile("\\S+\\s*|\\s+");

        private final long tokenDelayMillis;

        WordByWordLlmClient(long tokenDelayMillis) {
            this.tokenDelayMillis = tokenDelayMillis;
        }

        /** Turn 1 until an observation is in context, turn 2 afterwards. */
        private static String scriptFor(List<LlmMessage> messages) {
            boolean hasObservation = messages.stream()
                    .anyMatch(m -> m.content() != null && m.content().contains("Observation:"));
            if (!hasObservation) {
                return "Per rispondere devo conoscere le condizioni attuali della città. "
                     + "Interrogo lo strumento dedicato con la città richiesta.\n"
                     + "{\"tool_id\":\"get_weather\",\"arguments\":{\"city\":\"Roma\"}}";
            }
            return "Action: FINAL_ANSWER\n"
                 + "Answer: A Roma ci sono 24 gradi con cielo sereno e vento debole da nord-ovest. "
                 + "Non sono previste precipitazioni nelle prossime ore, quindi l'ombrello non serve: "
                 + "è una buona giornata per stare all'aperto.";
        }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            return new LlmCompletion(scriptFor(messages), 40, 30, "stop", null);
        }

        @Override
        public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
            String text = scriptFor(messages);
            return subscriber -> {
                AtomicBoolean cancelled = new AtomicBoolean(false);
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) { /* push-based, like TokenStreamPublisher */ }
                    @Override public void cancel() { cancelled.set(true); }
                });
                try {
                    Matcher m = CHUNK.matcher(text);
                    while (m.find()) {
                        if (cancelled.get()) return;
                        subscriber.onNext(m.group());
                        if (tokenDelayMillis > 0) Thread.sleep(tokenDelayMillis);
                    }
                    subscriber.onComplete();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    subscriber.onError(e);
                } catch (RuntimeException e) {
                    subscriber.onError(e);
                }
            };
        }

        @Override
        public String providerId() {
            return "word-by-word-stub";
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // A single get_weather tool
    // ═══════════════════════════════════════════════════════════════════════════

    static final class WeatherToolRegistry implements ToolRegistry {

        private final AraTool weather = new AraTool() {
            @Override public String toolId()      { return "get_weather"; }
            @Override public String description() { return "Restituisce le condizioni meteo attuali per una città."; }
            @Override public String argumentSchema() {
                return "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}";
            }
            @Override
            public ToolResult execute(String argumentJson) {
                String city = argumentJson.matches(".*\"city\"\\s*:\\s*\"[^\"]+\".*")
                        ? argumentJson.replaceAll(".*\"city\"\\s*:\\s*\"([^\"]+)\".*", "$1")
                        : "(sconosciuta)";
                return ToolResult.success("get_weather",
                        city + ": 24°C, sereno, vento 8 km/h NO, nessuna precipitazione prevista");
            }
        };

        @Override
        public List<AraTool> resolveEnabled(List<String> ids) {
            return ids.contains("get_weather") ? List.of(weather) : List.of();
        }

        @Override
        public Optional<AraTool> findById(String toolId) {
            return "get_weather".equals(toolId) ? Optional.of(weather) : Optional.empty();
        }

        @Override
        public ToolResult execute(String toolId, String argumentJson) {
            return findById(toolId)
                    .map(t -> t.execute(argumentJson))
                    .orElseGet(() -> ToolResult.failure(toolId, "Tool not found: " + toolId));
        }
    }
}
