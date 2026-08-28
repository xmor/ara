package io.ara.adapters.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A provider API standing in for OpenAI/Anthropic/Ollama on loopback: serves a canned reply and
 * keeps the request bodies it received.
 *
 * <p>Adapter tests need this rather than a mocked chat model because the behaviour worth
 * pinning down lives past the adapter — in what langchain4j finally serialises, and in how it
 * feeds a reply back. Asserting on the request body catches a value the adapter forgot to
 * forward or a merge that resolved the wrong way; serving a slow multi-chunk reply is the only
 * way to observe what a subscriber sees while tokens are still arriving. Neither costs network
 * or credentials.
 */
public final class StubLlmProvider implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final BlockingQueue<String> received = new ArrayBlockingQueue<>(8);

    private StubLlmProvider(HttpHandler replyWriter) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                received.offer(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            replyWriter.handle(exchange);
            exchange.close();
        });
        server.start();
    }

    /** Starts a stub answering every request with {@code responseBody} in one shot. */
    public static StubLlmProvider answering(String responseBody) throws Exception {
        return new StubLlmProvider(exchange -> {
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
        });
    }

    /**
     * Starts a stub that answers every request with {@code status} and {@code responseBody}.
     *
     * <p>Needed to pin down how an adapter <em>classifies</em> a provider failure, which is
     * not observable from a successful call: whether a malformed request comes back retryable
     * decides whether the strategy above retries it and whether a failover pool walks its
     * fallback list, for a request that can never succeed.
     */
    public static StubLlmProvider failingWith(int status, String responseBody) throws Exception {
        return new StubLlmProvider(exchange -> {
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
        });
    }

    /**
     * Starts a stub that streams {@code lines} as newline-delimited JSON, pausing between each
     * — the shape Ollama's {@code /api/chat} uses when {@code stream} is on.
     *
     * <p>The pause is what makes the stream observable mid-flight: without it the whole reply
     * lands before a subscriber could react to any of it, and a test could not tell a stream
     * that stopped on request from one that had already finished.
     */
    public static StubLlmProvider streamingNdJson(List<String> lines, Duration betweenLines)
            throws Exception {
        return new StubLlmProvider(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);   // 0 = chunked, length unknown up front
            try (OutputStream out = exchange.getResponseBody()) {
                for (String line : lines) {
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(betweenLines.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // The client hung up mid-stream. Expected in cancellation tests.
            }
        });
    }

    /**
     * Starts a stub that streams {@code chunks} as raw text, pausing between each — the shape
     * chatjimmy.ai's {@code /api/chat} uses: one incrementally-delivered text body carrying its
     * own inline markers, not SSE and not newline-delimited JSON.
     */
    public static StubLlmProvider streamingText(List<String> chunks, Duration betweenChunks) throws Exception {
        return new StubLlmProvider(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);   // 0 = chunked, length unknown up front
            try (OutputStream out = exchange.getResponseBody()) {
                for (String chunk : chunks) {
                    out.write(chunk.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(betweenChunks.toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // The client hung up mid-stream. Expected in cancellation tests.
            }
        });
    }

    /** Base URL to point an adapter at. */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The next request body received, parsed. Fails the test if none arrives. */
    public JsonNode nextRequest() throws Exception {
        String body = received.poll(10, TimeUnit.SECONDS);
        assertNotNull(body, "the adapter never sent a request");
        return MAPPER.readTree(body);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
