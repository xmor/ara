package io.ara.adapters.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Factory for {@link AraMcpClientAdapter}.
 *
 * <p>Supported transports:
 * <ul>
 *   <li><b>SSE</b>             — {@link #fromSse(String)}: connects to a running MCP server via the legacy HTTP+SSE transport</li>
 *   <li><b>Streamable HTTP</b> — {@link #fromStreamableHttp(String)}: connects via the current MCP HTTP transport (single {@code /mcp} endpoint, optional bearer auth)</li>
 *   <li><b>STDIO</b>           — {@link #fromStdio(String, String...)}: launches a local subprocess</li>
 * </ul>
 *
 * <p>Every factory method performs the MCP {@code initialize()} handshake synchronously
 * before returning. Call these outside of hot paths.
 *
 * <p>Note: {@code io.modelcontextprotocol.client.McpClient} is the SDK builder entry point,
 * distinct from {@code io.ara.core.mcp.McpClient} (ARA interface).
 *
 * <p>Example — SSE:
 * <pre>{@code
 * AraMcpClientAdapter client = McpClientFactory.fromSse("http://localhost:3000/sse");
 * McpToolRegistry registry = new McpToolRegistry(client);
 * }</pre>
 *
 * <p>Example — STDIO:
 * <pre>{@code
 * AraMcpClientAdapter client = McpClientFactory.fromStdio(
 *     "npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp");
 * }</pre>
 */
public final class McpClientFactory {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final JacksonMcpJsonMapper DEFAULT_JSON_MAPPER =
            new JacksonMcpJsonMapper(new ObjectMapper());

    private McpClientFactory() {
        throw new UnsupportedOperationException("utility class");
    }

    private static Executor defaultExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Builds a synchronous SDK client over an already-configured transport, performs the
     * mandatory MCP {@code initialize()} handshake, and wraps it as an {@link AraMcpClientAdapter}.
     * Every factory method shares this tail — only the transport construction differs.
     */
    private static AraMcpClientAdapter connect(McpClientTransport transport, Executor executor) {
        McpSyncClient sdkClient = McpClient.sync(transport)
                .requestTimeout(DEFAULT_TIMEOUT)
                .build();
        sdkClient.initialize();
        return new AraMcpClientAdapter(sdkClient, executor);
    }

    // ── SSE transport ─────────────────────────────────────────────────────────

    /**
     * Connects to an MCP server via SSE.
     *
     * @param serverUrl base URL of the MCP server (e.g. {@code http://localhost:3000/sse})
     */
    public static AraMcpClientAdapter fromSse(String serverUrl) {
        return fromSse(serverUrl, defaultExecutor());
    }

    /**
     * Connects to an MCP server via SSE using a custom executor.
     */
    public static AraMcpClientAdapter fromSse(String serverUrl, Executor executor) {
        var transport = HttpClientSseClientTransport.builder(serverUrl)
                .jsonMapper(DEFAULT_JSON_MAPPER)
                .build();
        return connect(transport, executor);
    }

    // ── Streamable HTTP transport ────────────────────────────────────────────

    /**
     * Connects to an MCP server via the current "Streamable HTTP" transport — a single
     * endpoint (default {@code /mcp}, appended automatically to {@code serverUrl}) that
     * replaced the legacy HTTP+SSE transport {@link #fromSse} speaks. This is what
     * graphify's shared HTTP server (see the project's "Shared HTTP server" docs) exposes
     * via {@code python -m graphify.serve ... --transport http}.
     *
     * @param serverUrl base URL of the MCP server, e.g. {@code http://localhost:8080}
     *                  (no trailing {@code /mcp} — the transport appends it)
     */
    public static AraMcpClientAdapter fromStreamableHttp(String serverUrl) {
        return fromStreamableHttp(serverUrl, null, defaultExecutor());
    }

    /**
     * Connects via Streamable HTTP, sending {@code Authorization: Bearer <bearerToken>}
     * on every request — required when the server was started with {@code --api-key}
     * (or {@code GRAPHIFY_API_KEY}).
     *
     * @param serverUrl   base URL of the MCP server
     * @param bearerToken bearer token, or {@code null}/blank if the server has no auth configured
     */
    public static AraMcpClientAdapter fromStreamableHttp(String serverUrl, String bearerToken) {
        return fromStreamableHttp(serverUrl, bearerToken, defaultExecutor());
    }

    /**
     * Connects via Streamable HTTP using a custom executor.
     */
    public static AraMcpClientAdapter fromStreamableHttp(String serverUrl, String bearerToken, Executor executor) {
        var transportBuilder = HttpClientStreamableHttpTransport.builder(serverUrl)
                .jsonMapper(DEFAULT_JSON_MAPPER);
        if (bearerToken != null && !bearerToken.isBlank()) {
            transportBuilder.customizeRequest(request -> request.header("Authorization", "Bearer " + bearerToken));
        }
        var transport = transportBuilder.build();
        return connect(transport, executor);
    }

    // ── STDIO transport ───────────────────────────────────────────────────────

    /**
     * Launches a local MCP server subprocess and connects via STDIO.
     *
     * @param command first element of the command line (e.g. {@code "npx"})
     * @param args    remaining arguments
     */
    public static AraMcpClientAdapter fromStdio(String command, String... args) {
        return fromStdio(defaultExecutor(), command, args);
    }

    /**
     * Launches a local MCP server subprocess using a custom executor.
     */
    public static AraMcpClientAdapter fromStdio(Executor executor, String command, String... args) {
        var fullCommand = new ArrayList<String>();
        fullCommand.add(command);
        fullCommand.addAll(List.of(args));

        var params = ServerParameters.builder(fullCommand.getFirst())
                .args(fullCommand.subList(1, fullCommand.size()))
                .build();
        var transport = new StdioClientTransport(params, DEFAULT_JSON_MAPPER);
        return connect(transport, executor);
    }
}
