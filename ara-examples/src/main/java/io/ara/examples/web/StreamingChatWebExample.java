package io.ara.examples.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
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
 * <p>Serves an ARA-styled chat page and one Server-Sent-Events endpoint. Each request
 * runs a one-turn streaming agent ({@code streamingEnabled(true)} +
 * {@code AgentTask.ofStreaming(...)}); the {@code tokenCallback} writes every token to the
 * response as an SSE {@code token} event, and the page appends it to the bot bubble as it
 * arrives — exactly the gateway → SSE pattern named in {@code AgentTask}'s javadoc.
 *
 * <p>Run {@code main()} (from the IDE, or on the {@code ara-examples} runtime classpath),
 * then open <a href="http://localhost:8080">http://localhost:8080</a>. Options:
 * <ul>
 *   <li>{@code live} as the first arg, or {@code -Dara.example.live=true} — use the real
 *       model at {@link #LIVE_BASE_URL} instead of the offline stub;</li>
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

    public static void main(String[] args) throws IOException {

        boolean live = Boolean.getBoolean("ara.example.live")
                || (args.length > 0 && args[0].equalsIgnoreCase("live"));

        LlmClient llm = live
                ? OpenAiLlmClient.builder()
                        .baseUrl(LIVE_BASE_URL).apiKey(LIVE_API_KEY).modelName(LIVE_MODEL).build()
                : new WordStreamLlmClient();

        AraRuntime runtime = AraRuntime.builder().llmClient("model", llm).build();
        runtime.start();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", StreamingChatWebExample::serveStatic);
        server.createContext("/chat", ex -> streamChat(ex, runtime));
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            runtime.close();
        }));

        System.out.printf("ARA streaming chat  —  LLM: %s%n",
                live ? "LIVE " + LIVE_MODEL + " @ " + LIVE_BASE_URL : "offline word-by-word stub");
        System.out.printf("open  http://localhost:%d%n", PORT);
    }

    // ── static page ───────────────────────────────────────────────────────────

    private static void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if ("/".equals(path)) path = "/streaming-chat.html";
        try (InputStream in = StreamingChatWebExample.class.getResourceAsStream("/web" + path)) {
            if (in == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().add("Content-Type", contentType(path));
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        }
    }

    // ── SSE token stream ──────────────────────────────────────────────────────

    private static void streamChat(HttpExchange ex, AraRuntime runtime) throws IOException {
        String query = queryParam(ex.getRequestURI().getRawQuery(), "q");

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

        AraAgent agent = runtime.createAgent(AgentConfig.defaults()
                .agentType("doc-assistant")
                .systemPrompt("Sei l'assistente della documentazione di ARA. "
                        + "Rispondi in italiano, in modo conciso e tecnico.")
                .primaryLlm(LlmProfile.builder()
                        .transportId("model")
                        .streamingEnabled(true)
                        .build())
                .plannerStrategy("react")
                .maxIterations(4)
                .build());

        try {
            long   t0     = System.nanoTime();
            int[]  chunks = {0};

            AgentResponse resp = agent.execute(AgentTask.ofStreaming(query, token -> {
                chunks[0]++;
                try {
                    sse(os, "token", "{\"t\":" + jsonString(token) + "}");
                } catch (IOException io) {
                    throw new RuntimeException(io);   // client went away — abort the run
                }
            }));

            long ms = (System.nanoTime() - t0) / 1_000_000;
            sse(os, "done", "{"
                    + "\"ok\":" + resp.isSuccess()
                    + ",\"chunks\":" + chunks[0]
                    + ",\"ms\":" + ms
                    + ",\"tokens\":" + resp.totalTokens()
                    + ",\"answer\":" + jsonString(resp.content())
                    + "}");
        } catch (RuntimeException e) {
            safeSse(os, "error", "{\"message\":" + jsonString(rootMessage(e)) + "}");
        } finally {
            runtime.destroyAgent(agent);
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
