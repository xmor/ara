package io.ara.examples.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.AraRuntime;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;

/**
 * A tiny web front-end for ARA token streaming — the browser equivalent of
 * {@code io.ara.examples.basics.SimpleStreamingExample}.
 *
 * <p>Serves an ARA-styled chat page and one Server-Sent-Events endpoint. A single
 * long-lived agent handles every request, each one running a one-turn streaming task
 * ({@code streamingEnabled(true)} + {@code AgentTask.ofStreaming(...)}); the {@code
 * tokenCallback} writes every token to the response as an SSE {@code token} event, and
 * the page appends it to the bot bubble as it arrives — exactly the gateway → SSE pattern
 * named in {@code AgentTask}'s javadoc.
 *
 * <p>Two optimisations over a naive translate-every-request server, both deliberate
 * demonstrations of the framework's habits: the {@link AgentConfig} is a final immutable
 * record built once and shared by every request (a chat endpoint must not rebuild its
 * agent wiring per message), and apart from the stateless per-task setup the agent is
 * created once at startup — each request rides its own session and the runtime's idle-TTL
 * sweep cleans them up, instead of paying create/destroy agent machinery per request.
 *
 * <p>The chat page keeps a stable per-browser session id and sends it along, so a
 * conversation lives<b> inside the agent's working memory</b> across messages instead of
 * being forgotten after one turn: this is what actually exercises the example's
 * {@link io.ara.runtime.memory.SlidingWindowMemoryManager} budget — past the threshold the
 * oldest turns are evicted (drop_middle) rather than replayed and re-sent on every call.
 *
 * <p>Run {@code main()} (from the IDE, or on the {@code ara-examples} runtime classpath),
 * then open <a href="http://localhost:8080">http://localhost:8080</a>. The page carries a
 * <em>offline / live</em> toggle: the offline stub needs nothing, the live mode hits the
 * model at {@link #LIVE_BASE_URL} (and shows an SSE error if the gateway is unreachable).
 * Options:
 * <ul>
 *   <li>{@code -Dara.web.port=9000} — change the port;</li>
 *   <li>{@code -Dara.api.key=…} / {@code ARA_API_KEY} — API key, if your gateway checks it.</li>
 * </ul>
 *
 * <p>No web framework: JDK {@link HttpServer} on a virtual-thread-per-request executor.
 */
public final class StreamingChatWebExample {

    private static final int    PORT          = Integer.getInteger("ara.web.port", 8080);
    private static final String LIVE_BASE_URL = "http://192.168.1.114:1234/v1";
    private static final String LIVE_MODEL    = "openai/gpt-oss-20b";
    private static final String LIVE_API_KEY  = firstNonBlank(
            System.getProperty("ara.api.key"), System.getenv("ARA_API_KEY"), "not-required");

    /**
     * The working-memory budget both agents are wired with — also the max the page's
     * memory meter reports against. Past this many estimated tokens the
     * {@link io.ara.runtime.memory.SlidingWindowMemoryManager} evicts old turns into
     * the context instead of growing without bound.
     */
    private static final int MEMORY_TOKEN_BUDGET = 2000;

    /**
     * The immutable agent definition, built once and shared by every request. A record of
     * records — constructing one per request would re-run all its validation for nothing.
     * Two are built at startup, identical except for the LLM transport: {@code offline}
     * words come from the local stub, {@code live} comes from {@link #LIVE_BASE_URL}. The
     * page's offline/live toggle just picks which agent to route the request to.
     */
    private static final AgentConfig OFFLINE_AGENT = agentConfig("offline");
    private static final AgentConfig LIVE_AGENT    = agentConfig("live");

    private static AgentConfig agentConfig(String transportId) {
        return AgentConfig.defaults()
                .agentType("doc-assistant")
                .systemPrompt("Sei l'assistente della documentazione di ARA. "
                        + "Rispondi in italiano, in modo conciso e tecnico.")
                .primaryLlm(LlmProfile.builder()
                        .transportId(transportId)
                        .streamingEnabled(true)
                        .build())
                .plannerStrategy("react")
                .maxIterations(4)
                // A working-memory budget activates the SlidingWindowMemoryManager
                // (AraRuntime default wiring, ADR-0086): past the threshold the oldest
                // turns are evicted instead of growing the context without bound.
                // No summarizer agent is registered here, and "summarize" would degrade
                // to drop_middle with a WARN — so ask for drop_middle explicitly.
                // maxConversationTurns stays deliberately loose so the *token* budget
                // (not a turn count) bounds the history: on a long conversation the
                // memory meter climbs green → yellow → red, then plateaus where
                // drop_middle starts evicting old turns.
                .workingMemoryTokenBudget(MEMORY_TOKEN_BUDGET)
                .workingMemoryEviction("drop_middle")
                .maxConversationTurns(200)
                .build();
    }

    /** The chat page, read once and served from memory instead of the classpath per hit. */
    private static final byte[] STATIC_PAGE = loadStaticPage();

    public static void main(String[] args) throws IOException {

        AraRuntime runtime = AraRuntime.builder()
                // Both transports are registered up front, so the mode can be switched
                // live from the page with no restart. The OpenAI client is just HTTP
                // config until a call is made — a wrong gateway only fails per-request.
                .llmClient("offline", new WordStreamLlmClient())
                .llmClient("live", OpenAiLlmClient.builder()
                        .baseUrl(LIVE_BASE_URL).apiKey(LIVE_API_KEY).modelName(LIVE_MODEL).build())
                .build();
        runtime.start();

        // One long-lived agent per mode for the whole server: each request gets its own
        // session and one-turn task, so the shared agent is never left with stale state —
        // and the runtime's session TTL sweep reclaims the sessions instead of the
        // create/destroy-per-request churn of a naive server.
        AraAgent offlineAgent = runtime.createAgent(OFFLINE_AGENT);
        AraAgent liveAgent    = runtime.createAgent(LIVE_AGENT);

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", StreamingChatWebExample::serveStatic);
        server.createContext("/chat", ex -> streamChat(ex, offlineAgent, liveAgent));
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            runtime.close();   // stop() destroys the registered agents
        }));

        System.out.printf("ARA streaming chat  —  LLM: offline stub / live %s @ %s (switchable from the page)%n",
                LIVE_MODEL, LIVE_BASE_URL);
        System.out.printf("open  http://localhost:%d%n", PORT);
    }

    // ── static page ───────────────────────────────────────────────────────────

    private static void serveStatic(HttpExchange ex) throws IOException {
        if ("/".equals(ex.getRequestURI().getPath()) || "/streaming-chat.html".equals(ex.getRequestURI().getPath())) {
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, STATIC_PAGE.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(STATIC_PAGE); }
            return;
        }
        try (InputStream in = StreamingChatWebExample.class.getResourceAsStream("/web" + ex.getRequestURI().getPath())) {
            if (in == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().add("Content-Type", contentType(ex.getRequestURI().getPath()));
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        }
    }

    /** Loads the single chat page at startup — fail fast if the resource is missing. */
    private static byte[] loadStaticPage() {
        try (InputStream in = StreamingChatWebExample.class.getResourceAsStream("/web/streaming-chat.html")) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource /web/streaming-chat.html");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to load /web/streaming-chat.html", e);
        }
    }

    // ── SSE token stream ──────────────────────────────────────────────────────

    private static void streamChat(HttpExchange ex, AraAgent offlineAgent, AraAgent liveAgent) throws IOException {
        String query   = queryParam(ex.getRequestURI().getRawQuery(), "q");
        String session = queryParam(ex.getRequestURI().getRawQuery(), "s");
        String mode    = queryParam(ex.getRequestURI().getRawQuery(), "mode");

        // The page's offline/live toggle selects the transport per request; the two
        // agents are otherwise identical. Their session stores are separate, so the
        // same session id keeps independent windows per mode.
        AraAgent agent = "live".equalsIgnoreCase(mode) ? liveAgent : offlineAgent;

        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.getResponseHeaders().add("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);              // 0 ⇒ chunked, unknown length
        OutputStream os = ex.getResponseBody();

        if (query == null || query.isBlank()) {
            sse(os, "error", "{\"message\":\"empty query\"}");
            os.close();
            return;
        }

        int[] chunks = {0};

        // A browser-sent session id turns the one-shot stream into a multi-turn
        // conversation: consecutive messages share the same working-memory window
        // (and its token-budget eviction). No id — the task rides an ephemeral session.
        AgentTask task = AgentTask.ofStreaming(query, token -> {
            chunks[0]++;
            try {
                sse(os, "token", "{\"t\":" + jsonString(token) + "}");
            } catch (IOException io) {
                throw new RuntimeException(io);   // client went away — abort the run
            }
        });
        if (session != null && !session.isBlank()) {
            task = task.withSessionId(SessionId.of("web::" + session));
        }

        try {
            long t0 = System.nanoTime();

            AgentResponse resp = agent.execute(task);

            long ms = (System.nanoTime() - t0) / 1_000_000;
            sse(os, "done", "{"
                    + "\"ok\":" + resp.isSuccess()
                    + ",\"chunks\":" + chunks[0]
                    + ",\"ms\":" + ms
                    + ",\"tokens\":" + resp.totalTokens()
                    // inputTokens is what actually reached the model this turn — for the
                    // streaming path the runtime estimates it ~4 chars/token over the
                    // window, the same rule the sliding-window budget uses. The page
                    // renders both numbers as the server sends them (current + budget),
                    // so editing MEMORY_TOKEN_BUDGET here needs no page-side change.
                    + ",\"mem\":" + resp.inputTokens()
                    + ",\"memMax\":" + MEMORY_TOKEN_BUDGET
                    + ",\"answer\":" + jsonString(resp.content())
                    + "}");
        } catch (RuntimeException e) {
            safeSse(os, "error", "{\"message\":" + jsonString(rootMessage(e)) + "}");
        } finally {
            try { os.close(); } catch (IOException ignored) { }
        }
    }

    // ── SSE + misc helpers ────────────────────────────────────────────────────

    private static void sse(OutputStream os, String event, String data) throws IOException {
        os.write(("event: " + event + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static void safeSse(OutputStream os, String event, String data) {
        try { sse(os, event, data); } catch (IOException ignored) { }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return m != null ? m : c.getClass().getSimpleName();
    }

    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css"))  return "text/css; charset=utf-8";
        if (path.endsWith(".js"))   return "application/javascript; charset=utf-8";
        if (path.endsWith(".svg"))  return "image/svg+xml";
        return "application/octet-stream";
    }

    /** Minimal JSON string encoder — enough for tokens and answers. */
    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 8).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default   -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }

    // ── offline stub LLM: streams a canned ARA answer word by word ────────────

    static final class WordStreamLlmClient implements LlmClient {

        private static String answerFor(List<LlmMessage> messages) {
            String q = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
            String lc = q == null ? "" : q.toLowerCase();
            String body;
            if (lc.contains("contratt") || lc.contains("contract")) {
                body = "Un contratto I/O è una catena di processori Java che gira prima e dopo ogni "
                     + "chiamata al modello: sanifica l'input, rimuove i fence markdown, valida il JSON "
                     + "contro uno schema e reda i dati PII. È puro codice, quindi costa zero token.";
            } else if (lc.contains("strateg")) {
                body = "Le strategie sono sei: react, respact, reflact, plan_execute, reflexion e il "
                     + "decoratore rag+react. Si cambiano modificando una sola stringa in "
                     + "AgentConfig.plannerStrategy(...), senza riscrivere il loop.";
            } else if (lc.contains("stream")) {
                body = "Lo streaming si attiva con due cose insieme: streamingEnabled(true) sul profilo "
                     + "e AgentTask.ofStreaming(prompt, tokenCallback). Il callback riceve ogni token "
                     + "mentre execute() è ancora in corso; questa pagina lo inoltra via SSE.";
            } else if (lc.contains("tool")) {
                body = "Un tool implementa AraTool: toolId, description, argumentSchema ed execute. "
                     + "Lo abiliti per agente con enabledTools(...); più chiamate nella stessa risposta "
                     + "vengono dispacciate in parallelo su thread virtuali.";
            } else {
                body = "ARA è un runtime Java 21 per agenti: nessuna annotazione, nessuna reflection, "
                     + "niente Spring. Lo stack di chiamate che debugghi è quello che hai scritto tu. "
                     + "Questa risposta è generata da uno stub offline e trasmessa una parola alla volta.";
            }
            return body;
        }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            String a = answerFor(messages);
            return new LlmCompletion(a, 24, a.length() / 4, "stop", null);
        }

        @Override
        public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
            String answer = answerFor(messages);
            return subscriber -> {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override public void request(long n) { }
                    @Override public void cancel() { }
                });
                try {
                    for (String word : answer.split("(?<= )")) {
                        subscriber.onNext(word);
                        Thread.sleep(45);
                    }
                    subscriber.onComplete();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    subscriber.onError(e);
                }
            };
        }

        @Override
        public String providerId() {
            return "word-stream-stub";
        }
    }

    private StreamingChatWebExample() { }
}
