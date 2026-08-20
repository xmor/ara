package io.ara.runtime.hitl;

import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.hitl.ApprovalTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the request/decide/expire lifecycle of {@link InMemoryApprovalGate}.
 *
 * <p>Waits here are always for something to <em>happen</em> within a generous window,
 * never for something to <em>not</em> happen within a tight one — the former is
 * deterministic under load, the latter is how a suite becomes flaky. Expiries are set
 * in tens of milliseconds and awaited for seconds.
 */
class InMemoryApprovalGateTest {

    private static final Duration LONG_ENOUGH = Duration.ofMinutes(5);
    private static final Duration AWAIT = Duration.ofSeconds(5);

    private static ApprovalRequest request(Duration timeout) {
        return ApprovalRequest.of("agent-1", "delete_record", "{\"id\":42}", timeout);
    }

    // ── decisions ─────────────────────────────────────────────────────────────

    @Test
    void submitApproved_completesTheFuture() throws Exception {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        ApprovalRequest req = request(LONG_ENOUGH);

        CompletableFuture<ApprovalDecision> future = gate.requestApproval(req);
        assertFalse(future.isDone(), "future must stay pending until a decision arrives");

        gate.submit(req.requestId(), new ApprovalDecision.Approved());

        assertInstanceOf(ApprovalDecision.Approved.class,
                future.get(AWAIT.toSeconds(), TimeUnit.SECONDS));
    }

    @Test
    void submitRejected_carriesTheReasonThrough() throws Exception {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        ApprovalRequest req = request(LONG_ENOUGH);
        CompletableFuture<ApprovalDecision> future = gate.requestApproval(req);

        gate.submit(req.requestId(), new ApprovalDecision.Rejected("not on a Friday"));

        ApprovalDecision decision = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
        assertEquals("not on a Friday",
                assertInstanceOf(ApprovalDecision.Rejected.class, decision).reason());
    }

    @Test
    void submitModified_carriesTheNewPayloadThrough() throws Exception {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        ApprovalRequest req = request(LONG_ENOUGH);
        CompletableFuture<ApprovalDecision> future = gate.requestApproval(req);

        gate.submit(req.requestId(), new ApprovalDecision.Modified("{\"id\":42,\"softDelete\":true}"));

        ApprovalDecision decision = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
        assertEquals("{\"id\":42,\"softDelete\":true}",
                assertInstanceOf(ApprovalDecision.Modified.class, decision).newPayload());
    }

    // ── pending-request bookkeeping ───────────────────────────────────────────

    @Test
    void pendingRequest_isVisibleWhileWaiting_andGoneAfterTheDecision() {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        ApprovalRequest req = request(LONG_ENOUGH);

        assertTrue(gate.getPendingRequests().isEmpty());
        gate.requestApproval(req);

        List<ApprovalRequest> pending = gate.getPendingRequests();
        assertEquals(1, pending.size());
        assertEquals(req.requestId(), pending.getFirst().requestId());
        assertEquals("delete_record", pending.getFirst().action());

        gate.submit(req.requestId(), new ApprovalDecision.Approved());

        assertTrue(gate.getPendingRequests().isEmpty(),
                "a decided request must not linger in the pending list");
    }

    @Test
    void concurrentRequests_areIndependent() throws Exception {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        ApprovalRequest first = request(LONG_ENOUGH);
        ApprovalRequest second = request(LONG_ENOUGH);

        CompletableFuture<ApprovalDecision> firstFuture = gate.requestApproval(first);
        CompletableFuture<ApprovalDecision> secondFuture = gate.requestApproval(second);
        assertEquals(2, gate.getPendingRequests().size());

        gate.submit(second.requestId(), new ApprovalDecision.Rejected("no"));

        assertInstanceOf(ApprovalDecision.Rejected.class,
                secondFuture.get(AWAIT.toSeconds(), TimeUnit.SECONDS));
        assertFalse(firstFuture.isDone(), "deciding one request must not resolve another");
        assertEquals(1, gate.getPendingRequests().size());
    }

    @Test
    void getPendingRequests_returnsASnapshot_notALiveView() {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        gate.requestApproval(request(LONG_ENOUGH));

        List<ApprovalRequest> snapshot = gate.getPendingRequests();
        gate.requestApproval(request(LONG_ENOUGH));

        assertEquals(1, snapshot.size(), "the earlier snapshot must not grow");
        assertEquals(2, gate.getPendingRequests().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(request(LONG_ENOUGH)));
    }

    // ── expiry ────────────────────────────────────────────────────────────────

    @Test
    void expiry_completesExceptionallyWithApprovalTimeoutException() {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        ApprovalRequest req = request(Duration.ofMillis(50));

        CompletableFuture<ApprovalDecision> future = gate.requestApproval(req);

        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> future.get(AWAIT.toSeconds(), TimeUnit.SECONDS));
        ApprovalTimeoutException timeout =
                assertInstanceOf(ApprovalTimeoutException.class, thrown.getCause());
        assertEquals(req.requestId(), timeout.getRequestId());
        assertEquals(req.expiresAt(), timeout.getExpiresAt());
    }

    @Test
    void expiredRequest_isRemovedFromThePendingList() {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        CompletableFuture<ApprovalDecision> future =
                gate.requestApproval(request(Duration.ofMillis(50)));

        assertThrows(CompletionException.class, future::join);
        assertTrue(gate.getPendingRequests().isEmpty(),
                "an expired request must not stay listed as pending");
    }

    @Test
    void alreadyExpiredRequest_timesOutImmediately() {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        // Negative timeout: expiresAt is already in the past when the request is
        // registered. The scheduled delay clamps to zero rather than going negative.
        CompletableFuture<ApprovalDecision> future =
                gate.requestApproval(request(Duration.ofMillis(-1)));

        CompletionException thrown = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(ApprovalTimeoutException.class, thrown.getCause());
    }

    // ── submit on a request that is not waiting ───────────────────────────────

    @Test
    void submit_onAnUnknownRequestId_throws() {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> gate.submit("never-registered", new ApprovalDecision.Approved()));
        assertTrue(thrown.getMessage().contains("never-registered"));
    }

    /**
     * Pins the behaviour the {@code ApprovalGate} javadoc contradicts itself about: it
     * promises that submitting on an already-completed request "is a silent no-op",
     * and in the same breath declares {@code @throws IllegalArgumentException if no
     * pending request exists}. Because the entry is removed the moment the future
     * completes, a timed-out request satisfies both clauses and the throw wins.
     *
     * <p>Asserted as-is rather than fixed: an operator approving a request that has
     * already expired is a real condition an HTTP surface must be able to report, so
     * throwing is the more useful half. The javadoc is what was corrected.
     */
    @Test
    void submit_afterExpiry_throwsBecauseTheEntryIsAlreadyGone() {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        ApprovalRequest req = request(Duration.ofMillis(50));
        CompletableFuture<ApprovalDecision> future = gate.requestApproval(req);

        assertThrows(CompletionException.class, future::join);

        assertThrows(IllegalArgumentException.class,
                () -> gate.submit(req.requestId(), new ApprovalDecision.Approved()));
    }

    /**
     * A second decision on the same request throws rather than being ignored, because
     * the {@code whenComplete} cleanup runs synchronously on the thread that completed
     * the future: by the time the first {@code submit} returns, the entry is already
     * gone and the second call cannot find it.
     *
     * <p>Consequence worth recording: the {@code completed == false} branch inside
     * {@code submit} — the one the javadoc described as a "silent no-op" — is
     * unreachable sequentially. Only a genuine race between two completing threads can
     * reach it, which is what the next test exercises.
     */
    @Test
    void secondDecisionOnTheSameRequest_throws_ratherThanBeingIgnored() throws Exception {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        ApprovalRequest req = request(LONG_ENOUGH);
        CompletableFuture<ApprovalDecision> future = gate.requestApproval(req);

        gate.submit(req.requestId(), new ApprovalDecision.Rejected("first"));

        assertThrows(IllegalArgumentException.class,
                () -> gate.submit(req.requestId(), new ApprovalDecision.Approved()));
        assertEquals("first",
                assertInstanceOf(ApprovalDecision.Rejected.class,
                        future.get(AWAIT.toSeconds(), TimeUnit.SECONDS)).reason());
    }

    /**
     * Several operators decide the same request at once. Exactly one decision must win,
     * every loser must either be silently dropped or told the request is no longer
     * pending — and none may corrupt the outcome or leave the request listed.
     */
    @Test
    void concurrentSubmits_resolveToExactlyOneDecision() throws Exception {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        ApprovalRequest req = request(LONG_ENOUGH);
        CompletableFuture<ApprovalDecision> future = gate.requestApproval(req);

        int submitters = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(submitters);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger tooLate = new AtomicInteger();

        for (int i = 0; i < submitters; i++) {
            final int n = i;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    gate.submit(req.requestId(), new ApprovalDecision.Rejected("r" + n));
                    accepted.incrementAndGet();
                } catch (IllegalArgumentException e) {
                    tooLate.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(AWAIT.toSeconds(), TimeUnit.SECONDS), "submitters did not finish");

        assertEquals(submitters, accepted.get() + tooLate.get(),
                "every submitter must either be accepted or told the request is gone");
        assertTrue(accepted.get() >= 1, "at least the winner must be accepted");

        ApprovalDecision decision = future.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
        assertTrue(assertInstanceOf(ApprovalDecision.Rejected.class, decision).reason().startsWith("r"),
                "the resolved decision must be one that was actually submitted");
        assertTrue(gate.getPendingRequests().isEmpty());
    }

    // ── argument validation ───────────────────────────────────────────────────

    @Test
    void requestApproval_rejectsNull() {
        InMemoryApprovalGate gate = new InMemoryApprovalGate();
        assertThrows(IllegalArgumentException.class, () -> gate.requestApproval(null));
    }
}
