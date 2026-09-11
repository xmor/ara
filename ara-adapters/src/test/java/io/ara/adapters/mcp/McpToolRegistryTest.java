package io.ara.adapters.mcp;

import io.ara.core.mcp.McpClient;
import io.ara.core.mcp.McpTool;
import io.ara.core.mcp.McpToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@link McpToolRegistry}'s caching contract, with the single-flight behaviour
 * front and centre: the fetch count is the assertion that carries the weight.
 *
 * <p>The fake below never completes {@code listTools()} on its own — the test controls the
 * completion — so a deterministic race-free check is possible: when N callers miss the cache
 * together, exactly one request may be in flight, and the winner's future is shared by all.
 */
class McpToolRegistryTest {

    /** A {@link McpClient} whose fetch the test completes manually, counting every dispatch. */
    private static final class ControlledMcpClient implements McpClient {
        final AtomicInteger  listToolsCalls = new AtomicInteger();
        final CountDownLatch fetchStarted   = new CountDownLatch(1);
        volatile CompletableFuture<List<McpTool>> latest;

        @Override public CompletableFuture<List<McpTool>> listTools() {
            listToolsCalls.incrementAndGet();
            fetchStarted.countDown();
            CompletableFuture<List<McpTool>> future = new CompletableFuture<>();
            latest = future;
            return future;
        }

        @Override public CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> args) {
            return CompletableFuture.completedFuture(new McpToolResult("ok", false));
        }

        @Override public void close() {}
    }

    private static final McpTool TOOL = new McpTool("search", "searches things", null);

    // ── Single-flight ────────────────────────────────────────────────────────

    @Test
    void concurrentMissesShareASingleInFlightFetch() throws Exception {
        ControlledMcpClient client = new ControlledMcpClient();
        McpToolRegistry registry = new McpToolRegistry(client, Duration.ofSeconds(60));

        int callers = 8;
        CountDownLatch start   = new CountDownLatch(1);
        CountDownLatch invoked = new CountDownLatch(callers);
        CopyOnWriteArrayList<CompletableFuture<List<McpTool>>> obtained = new CopyOnWriteArrayList<>();

        for (int i = 0; i < callers; i++) {
            Thread.startVirtualThread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                obtained.add(registry.getTools());
                invoked.countDown();
            });
        }

        start.countDown();
        assertTrue(client.fetchStarted.await(5, TimeUnit.SECONDS), "the fetch never started");
        assertTrue(invoked.await(5, TimeUnit.SECONDS), "not every caller reached getTools()");

        assertEquals(1, client.listToolsCalls.get(),
                "8 concurrent misses must collapse into one tools/list request");

        client.latest.complete(List.of(TOOL));
        for (CompletableFuture<List<McpTool>> future : obtained) {
            assertEquals(List.of(TOOL), future.get(5, TimeUnit.SECONDS));
        }

        assertEquals(1, client.listToolsCalls.get(),
                "the completed fetch must now serve the cache — no further request");
    }

    @Test
    void releasesTheInFlightSlotOnFailureSoTheNextCallRetries() {
        ControlledMcpClient client = new ControlledMcpClient();
        McpToolRegistry registry = new McpToolRegistry(client, Duration.ofSeconds(60));

        CompletableFuture<List<McpTool>> first = registry.getTools();
        client.latest.completeExceptionally(new RuntimeException("server exploded"));
        assertTrue(first.isCompletedExceptionally());

        // A failed fetch must not stick: the cache stays empty and the slot opens again.
        CompletableFuture<List<McpTool>> retry = registry.getTools();
        assertEquals(2, client.listToolsCalls.get(), "the failed fetch must not be permanently cached");
        client.latest.complete(List.of(TOOL));
        assertEquals(List.of(TOOL), retry.join());
    }

    // ── Cache semantics the single-flight builds on ──────────────────────────

    @Test
    void servesRepeatedCallsFromCacheAndRefetchesAfterInvalidate() {
        ControlledMcpClient client = new ControlledMcpClient();
        McpToolRegistry registry = new McpToolRegistry(client, Duration.ofSeconds(300));
        List<McpTool> tools = List.of(TOOL);

        CompletableFuture<List<McpTool>> first = registry.getTools();
        client.latest.complete(tools);
        assertSame(tools, first.join());

        assertEquals(1, client.listToolsCalls.get());
        assertSame(tools, registry.getTools().join(), "cache hit must not re-fetch");
        assertEquals(1, client.listToolsCalls.get());

        registry.invalidate();
        CompletableFuture<List<McpTool>> afterInvalidate = registry.getTools();
        client.latest.complete(tools);
        assertEquals(2, client.listToolsCalls.get(), "invalidate() must force a re-fetch");
    }

    @Test
    void refetchesAfterTheTtlExpires() throws Exception {
        ControlledMcpClient client = new ControlledMcpClient();
        McpToolRegistry registry = new McpToolRegistry(client, Duration.ofMillis(20));

        CompletableFuture<List<McpTool>> first = registry.getTools();
        client.latest.complete(List.of(TOOL));
        assertEquals(1, client.listToolsCalls.get());

        Thread.sleep(30); // let the 20 ms TTL lapse
        CompletableFuture<List<McpTool>> second = registry.getTools();
        client.latest.complete(List.of(TOOL));
        assertEquals(2, client.listToolsCalls.get(), "an expired snapshot must be re-fetched");
    }

    @Test
    void rejectsInvalidConstructors() {
        ControlledMcpClient client = new ControlledMcpClient();
        assertThrows(IllegalArgumentException.class, () -> new McpToolRegistry(null));
        assertThrows(IllegalArgumentException.class, () -> new McpToolRegistry(client, null));
        assertThrows(IllegalArgumentException.class, () -> new McpToolRegistry(client, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new McpToolRegistry(client, Duration.ofSeconds(-1)));
    }
}