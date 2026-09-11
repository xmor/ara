package io.ara.adapters.mcp;

import io.ara.core.mcp.McpClient;
import io.ara.core.mcp.McpTool;
import io.ara.core.mcp.McpToolResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe cache layer over {@link McpClient}.
 *
 * <p>The tool list is fetched lazily on first access and cached for {@code ttl}
 * (default 60 s). The cache is invalidated immediately when {@link #invalidate()}
 * is called — intended to be wired to the SDK's {@code ToolListChanged} notification.
 *
 * <p>Concurrent misses are collapsed into a single in-flight {@code tools/list} request
 * (single-flight): when N agents warm up in parallel every one sees an empty cache, and
 * without this each would fire its own fetch at the same freshly-started server.
 *
 * <p>Usage:
 * <pre>{@code
 * McpToolRegistry registry = new McpToolRegistry(McpClientFactory.fromSse("http://localhost:3000/sse"));
 *
 * // As AraTool instances wired into an agent:
 * List<AraTool> mcpTools = registry.getTools().join().stream()
 *     .map(t -> new McpAraTool(t, registry))
 *     .toList();
 * }</pre>
 */
public class McpToolRegistry {

    static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final McpClient client;
    private final Duration ttl;
    private final AtomicReference<CachedSnapshot> cache = new AtomicReference<>(null);
    private final AtomicReference<CompletableFuture<List<McpTool>>> inFlight = new AtomicReference<>(null);

    public McpToolRegistry(McpClient client) {
        this(client, DEFAULT_TTL);
    }

    public McpToolRegistry(McpClient client, Duration ttl) {
        if (client == null) throw new IllegalArgumentException("client must not be null");
        if (ttl == null || ttl.isNegative() || ttl.isZero())
            throw new IllegalArgumentException("ttl must be positive");
        this.client = client;
        this.ttl    = ttl;
    }

    /**
     * Returns the cached tool list, fetching from the server if the cache is
     * empty or expired.
     */
    public CompletableFuture<List<McpTool>> getTools() {
        CachedSnapshot snapshot = cache.get();
        if (snapshot != null && !snapshot.isExpired(ttl)) {
            return CompletableFuture.completedFuture(snapshot.tools());
        }
        return refresh();
    }

    /**
     * Invokes a tool on the underlying {@link McpClient} (no caching).
     *
     * @param name tool name as returned by {@link #getTools()}
     * @param args arguments conforming to the tool's input schema
     */
    public CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> args) {
        return client.callTool(name, args);
    }

    /**
     * Invalidates the cached tool list. The next {@link #getTools()} call will
     * trigger a fresh fetch.
     *
     * <p>Wire this to the SDK's {@code ToolListChanged} notification to stay
     * in sync with a dynamic server.
     */
    public void invalidate() {
        cache.set(null);
    }

    /**
     * Fetches the tool list, guaranteeing that at most one {@code tools/list} request is
     * in flight at a time. Only the thread that wins the {@code inFlight} slot actually
     * dispatches the network call; the others attach to its future.
     */
    private CompletableFuture<List<McpTool>> refresh() {
        while (true) {
            CompletableFuture<List<McpTool>> pending = inFlight.get();
            if (pending != null) {
                return pending;
            }
            CompletableFuture<List<McpTool>> candidate = new CompletableFuture<>();
            if (inFlight.compareAndSet(null, candidate)) {
                client.listTools().whenComplete((tools, error) -> {
                    if (error == null) {
                        cache.set(new CachedSnapshot(tools, Instant.now()));
                        candidate.complete(tools);
                    } else {
                        candidate.completeExceptionally(error);
                    }
                    inFlight.compareAndSet(candidate, null);
                });
                return candidate;
            }
            // Lost the CAS — the winner may already have completed and released the slot,
            // in which case inFlight is empty again (or a fresh winner is racing us). Loop
            // instead of returning a now-null slot (completion order is not guaranteed).
        }
    }

    private record CachedSnapshot(List<McpTool> tools, Instant fetchedAt) {
        boolean isExpired(Duration ttl) {
            return Instant.now().isAfter(fetchedAt.plus(ttl));
        }
    }
}
