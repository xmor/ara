package io.ara.runtime.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A genuine, independent re-implementation of the three Qdrant REST endpoints {@link
 * QdrantSemanticStore} calls — collection create, point upsert, must-match filtered search
 * — standing in for a real Qdrant daemon, which is not available in this environment
 * (verified 2026-09-08: {@code docker} has no reachable daemon here, and this codebase has
 * no testcontainers dependency).
 *
 * <p>Not a mock of {@link QdrantSemanticStore} and not a call recorder: it actually stores
 * every upserted payload and actually applies the {@code filter.must[].match.value}
 * conditions a search body carries, computed independently of {@link QdrantSemanticStore}'s
 * own code. That independence is the point (ADR-0060 D3 — "not against a mock that would
 * assume correct exactly the part that needs verifying"): a test against this class proves
 * {@link QdrantSemanticStore}'s real HTTP request/response handling round-trips a filter
 * correctly, not merely that its Java code intends to send one.
 *
 * <p>Styled after {@code StubLlmProvider} (ara-adapters) — same loopback {@code HttpServer}
 * idiom already established in this codebase for standing in on a third-party API.
 */
final class FakeQdrantServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpServer server;
    private final List<JsonNode> points = new ArrayList<>();

    private FakeQdrantServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections/", this::handle);
        server.start();
    }

    static FakeQdrantServer start() throws IOException {
        return new FakeQdrantServer();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private synchronized void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            byte[] body = exchange.getRequestBody().readAllBytes();

            if ("POST".equals(method) && path.endsWith("/points/search")) {
                respond(exchange, 200, handleSearch(body));
            } else if ("PUT".equals(method) && path.endsWith("/points")) {
                handleUpsert(body);
                respond(exchange, 200, "{\"status\":\"ok\"}");
            } else if ("PUT".equals(method)) {
                respond(exchange, 200, "{\"status\":\"ok\"}");   // collection create, idempotent
            } else {
                respond(exchange, 404, "{}");
            }
        } finally {
            exchange.close();
        }
    }

    private void handleUpsert(byte[] body) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        for (JsonNode point : root.path("points")) {
            points.add(point.deepCopy());
        }
    }

    private String handleSearch(byte[] body) throws IOException {
        JsonNode root = MAPPER.readTree(body);
        List<String[]> mustMatch = new ArrayList<>();
        for (JsonNode cond : root.path("filter").path("must")) {
            mustMatch.add(new String[]{cond.path("key").asText(), cond.path("match").path("value").asText()});
        }
        int limit = root.path("limit").asInt(10);

        ArrayNode results = MAPPER.createArrayNode();
        int matched = 0;
        for (JsonNode point : points) {
            JsonNode payload = point.path("payload");
            boolean matchesAll = mustMatch.stream()
                    .allMatch(kv -> payload.path(kv[0]).asText("").equals(kv[1]));
            if (matchesAll) {
                ObjectNode hit = MAPPER.createObjectNode();
                hit.set("payload", payload);
                hit.put("score", 1.0);
                results.add(hit);
                if (++matched >= limit) {
                    break;
                }
            }
        }
        ObjectNode responseBody = MAPPER.createObjectNode();
        responseBody.set("result", results);
        return MAPPER.writeValueAsString(responseBody);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length);
        exchange.getResponseBody().write(out);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
