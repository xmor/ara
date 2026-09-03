package io.ara.runtime.hitl;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.hitl.ApprovalTimeoutException;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.Reversibility;
import io.ara.core.tool.SideEffects;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.core.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ApprovalToolRegistry}, the decorator that puts a human between the
 * reasoning loop and a tool call.
 *
 * <p>The gate is faked rather than driven through {@link InMemoryApprovalGate}: what is
 * under test is how a decision is <em>translated</em> into a dispatch or a
 * {@link ToolResult}, which is independent of how the decision was reached.
 *
 * <p>The assertion that matters most in every negative case is not the returned failure
 * but {@code delegate.calls().isEmpty()} — a gate that returns the right message while
 * still performing the action it was meant to withhold would be worse than no gate.
 */
class ApprovalToolRegistryTest {

    // ── fixtures ──────────────────────────────────────────────────────────────

    /** Records every dispatch that reaches it, so "never called" is assertable. */
    private static final class RecordingRegistry implements ToolRegistry {
        record Call(String toolId, String args, boolean withTask) {}

        final List<Call> calls = new CopyOnWriteArrayList<>();
        volatile ToolSpec spec;   // ADR-0067: the classification specFor(...) reports, if any
        private final AraTool tool = new AraTool() {
            @Override public String toolId() { return "delete_record"; }
            @Override public String description() { return "deletes a record"; }
            @Override public String argumentSchema() { return "{\"type\":\"object\"}"; }
            @Override public ToolResult execute(String argumentJson) {
                return ToolResult.success(toolId(), "deleted");
            }
        };

        @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(tool); }
        @Override public List<AraTool> all() { return List.of(tool); }
        @Override public Optional<AraTool> findById(String id) {
            return tool.toolId().equals(id) ? Optional.of(tool) : Optional.empty();
        }

        @Override public Optional<ToolSpec> specFor(String toolId) {
            return Optional.ofNullable(spec != null && spec.toolId().equals(toolId) ? spec : null);
        }

        @Override public ToolResult execute(String toolId, String argumentJson) {
            calls.add(new Call(toolId, argumentJson, false));
            return ToolResult.success(toolId, "deleted:" + argumentJson);
        }

        @Override public ToolResult execute(String toolId, String argumentJson, AgentTask task) {
            calls.add(new Call(toolId, argumentJson, true));
            return ToolResult.success(toolId, "deleted:" + argumentJson);
        }

        @Override public Runnable wrapForPropagation(Runnable task) {
            return () -> { calls.add(new Call("__wrapped__", "", false)); task.run(); };
        }
    }

    /** A gate that answers immediately with a canned decision, and records the request. */
    private static final class ScriptedGate implements ApprovalGate {
        private final CompletableFuture<ApprovalDecision> answer;
        final List<ApprovalRequest> seen = new CopyOnWriteArrayList<>();

        ScriptedGate(ApprovalDecision decision) {
            this.answer = CompletableFuture.completedFuture(decision);
        }

        ScriptedGate(Throwable failure) {
            this.answer = CompletableFuture.failedFuture(failure);
        }

        @Override public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
            seen.add(request);
            return answer;
        }

        @Override public void submit(String requestId, ApprovalDecision decision) {
            throw new UnsupportedOperationException();
        }

        @Override public List<ApprovalRequest> getPendingRequests() { return List.copyOf(seen); }
    }

    /** An agent that forces approval on every tool call (the pre-ADR-0067 "in the chain = gate all" behaviour). */
    private static AgentConfig config() {
        return AgentConfig.defaults()
                .agentId(AgentId.of("hitl-agent"))
                .agentType("t")
                .humanApprovalRequired(true)
                .build();
    }

    /** An agent that does NOT force approval — gating is then decided per tool by its ToolSpec (ADR-0067 D6). */
    private static AgentConfig configNoAgentGate() {
        return AgentConfig.defaults()
                .agentId(AgentId.of("hitl-agent"))
                .agentType("t")
                .build();
    }

    private static ApprovalToolRegistry gated(RecordingRegistry delegate, ApprovalGate gate) {
        return new ApprovalToolRegistry(delegate, gate, config());
    }

    private static final String ARGS = "{\"id\":42}";

    // ── the four outcomes ─────────────────────────────────────────────────────

    @Test
    void approved_dispatchesToTheDelegateWithTheOriginalArguments() {
        RecordingRegistry delegate = new RecordingRegistry();
        ToolResult result = gated(delegate, new ScriptedGate(new ApprovalDecision.Approved()))
                .execute("delete_record", ARGS);

        assertTrue(result.isSuccess());
        assertEquals(List.of(new RecordingRegistry.Call("delete_record", ARGS, false)), delegate.calls);
    }

    @Test
    void rejected_returnsAFailureAndNeverTouchesTheDelegate() {
        RecordingRegistry delegate = new RecordingRegistry();
        ToolResult result = gated(delegate, new ScriptedGate(new ApprovalDecision.Rejected("out of policy")))
                .execute("delete_record", ARGS);

        assertTrue(result.isFailed());
        assertTrue(result.error().contains("out of policy"), result.error());
        assertTrue(result.error().contains("delete_record"), result.error());
        assertTrue(delegate.calls.isEmpty(), "a rejected action must not run");
    }

    @Test
    void modified_dispatchesTheOperatorsPayloadInsteadOfTheOriginal() {
        RecordingRegistry delegate = new RecordingRegistry();
        String narrowed = "{\"id\":42,\"softDelete\":true}";

        ToolResult result = gated(delegate, new ScriptedGate(new ApprovalDecision.Modified(narrowed)))
                .execute("delete_record", ARGS);

        assertTrue(result.isSuccess());
        assertEquals(List.of(new RecordingRegistry.Call("delete_record", narrowed, false)), delegate.calls);
    }

    /**
     * {@code Modified.newPayload()} is typed {@code Object}, but the delegate takes a
     * JSON string. A non-string payload falls back to the original arguments rather
     * than dispatching {@code toString()} — the tool receives something it can parse,
     * not something shaped like a Java record dump.
     */
    @Test
    void modified_withANonStringPayload_fallsBackToTheOriginalArguments() {
        RecordingRegistry delegate = new RecordingRegistry();
        ToolResult result = gated(delegate, new ScriptedGate(new ApprovalDecision.Modified(java.util.Map.of("id", 7))))
                .execute("delete_record", ARGS);

        assertTrue(result.isSuccess());
        assertEquals(List.of(new RecordingRegistry.Call("delete_record", ARGS, false)), delegate.calls);
    }

    @Test
    void timeout_returnsAFailureAndNeverTouchesTheDelegate() {
        RecordingRegistry delegate = new RecordingRegistry();
        ApprovalTimeoutException timeout =
                new ApprovalTimeoutException("req-1", Instant.now().minusSeconds(1));

        ToolResult result = gated(delegate, new ScriptedGate(timeout)).execute("delete_record", ARGS);

        assertTrue(result.isFailed());
        assertTrue(result.error().contains("timed out"), result.error());
        assertTrue(delegate.calls.isEmpty(), "a timed-out approval must not run the action");
    }

    /**
     * Any other gate failure — a broken store, a transport error — must also fail
     * closed. The distinction from timeout is only the message.
     */
    @Test
    void anUnexpectedGateFailure_failsClosed() {
        RecordingRegistry delegate = new RecordingRegistry();
        ToolResult result = gated(delegate, new ScriptedGate(new IllegalStateException("store is down")))
                .execute("delete_record", ARGS);

        assertTrue(result.isFailed());
        assertTrue(result.error().contains("Approval gate error"), result.error());
        assertTrue(result.error().contains("store is down"), result.error());
        assertTrue(delegate.calls.isEmpty(), "a broken gate must not let the action through");
    }

    // ── the request handed to the gate ────────────────────────────────────────

    @Test
    void theRequestCarriesTheAgentTheToolAndTheArguments() {
        ScriptedGate gate = new ScriptedGate(new ApprovalDecision.Approved());
        gated(new RecordingRegistry(), gate).execute("delete_record", ARGS);

        assertEquals(1, gate.seen.size());
        ApprovalRequest request = gate.seen.getFirst();
        assertEquals("hitl-agent", request.agentId());
        assertEquals("delete_record", request.action(), "the action is the tool id");
        assertEquals(ARGS, request.payload(), "the payload is the raw argument JSON");
        assertTrue(request.expiresAt().isAfter(Instant.now()));
    }

    @Test
    void theConfiguredTimeoutShapesTheRequestExpiry() {
        ScriptedGate gate = new ScriptedGate(new ApprovalDecision.Approved());
        new ApprovalToolRegistry(new RecordingRegistry(), gate, config(), Duration.ofSeconds(30))
                .execute("delete_record", ARGS);

        Instant expiresAt = gate.seen.getFirst().expiresAt();
        assertTrue(expiresAt.isBefore(Instant.now().plusSeconds(31)),
                "a 30s timeout must not produce the 30-minute default expiry");
    }

    // ── both execute overloads are gated ──────────────────────────────────────

    @Test
    void theTaskAwareOverloadIsGatedToo() {
        RecordingRegistry delegate = new RecordingRegistry();
        ScriptedGate gate = new ScriptedGate(new ApprovalDecision.Approved());

        ToolResult result = gated(delegate, gate)
                .execute("delete_record", ARGS, AgentTask.of("do it"));

        assertTrue(result.isSuccess());
        assertEquals(1, gate.seen.size(), "the task overload must ask for approval as well");
        assertEquals(List.of(new RecordingRegistry.Call("delete_record", ARGS, true)), delegate.calls);
    }

    @Test
    void theTaskAwareOverloadAlsoWithholdsOnRejection() {
        RecordingRegistry delegate = new RecordingRegistry();
        ToolResult result = gated(delegate, new ScriptedGate(new ApprovalDecision.Rejected("no")))
                .execute("delete_record", ARGS, AgentTask.of("do it"));

        assertTrue(result.isFailed());
        assertTrue(delegate.calls.isEmpty());
    }

    // ── everything that is not a dispatch passes through ungated ──────────────

    @Test
    void lookupsAndPropagationPassThroughWithoutAskingForApproval() {
        RecordingRegistry delegate = new RecordingRegistry();
        ScriptedGate gate = new ScriptedGate(new ApprovalDecision.Rejected("should never be consulted"));
        ApprovalToolRegistry registry = gated(delegate, gate);

        assertEquals(1, registry.resolveEnabled(List.of("delete_record")).size());
        assertEquals(1, registry.all().size());
        assertTrue(registry.findById("delete_record").isPresent());
        assertFalse(registry.findById("nope").isPresent());
        registry.wrapForPropagation(() -> {}).run();

        assertTrue(gate.seen.isEmpty(),
                "listing tools is not an action — only dispatch needs a human");
    }

    // ── ADR-0067 D6: per-tool gating, independent of the agent's own flag ─────

    @Test
    void agentWithoutTheFlag_stillGatesAToolClassifiedIrreversibleHighImpact() {
        RecordingRegistry delegate = new RecordingRegistry();
        delegate.spec = ToolSpec.builtin("delete_record", SideEffects.EXTERNAL_WRITE,
                new Reversibility.IrreversibleHighImpact());
        ScriptedGate gate = new ScriptedGate(new ApprovalDecision.Rejected("no"));

        ToolResult result = new ApprovalToolRegistry(delegate, gate, configNoAgentGate())
                .execute("delete_record", ARGS);

        assertEquals(1, gate.seen.size(), "the tool's own classification forces the gate");
        assertTrue(result.isFailed());
        assertTrue(delegate.calls.isEmpty(), "rejected → the delete never runs");
    }

    @Test
    void agentWithoutTheFlag_andNoClassification_dispatchesStraightThrough() {
        RecordingRegistry delegate = new RecordingRegistry();
        ScriptedGate gate = new ScriptedGate(new ApprovalDecision.Rejected("should never be consulted"));

        new ApprovalToolRegistry(delegate, gate, configNoAgentGate()).execute("delete_record", ARGS);

        assertTrue(gate.seen.isEmpty(), "no agent flag, no ToolSpec → no gate");
        assertEquals(List.of(new RecordingRegistry.Call("delete_record", ARGS, false)), delegate.calls);
    }

    @Test
    void agentWithoutTheFlag_andALowRiskClassification_dispatchesStraightThrough() {
        RecordingRegistry delegate = new RecordingRegistry();
        delegate.spec = ToolSpec.builtin("delete_record", SideEffects.LOCAL_WRITE,
                new Reversibility.CostlyButReversible());
        ScriptedGate gate = new ScriptedGate(new ApprovalDecision.Rejected("should never be consulted"));

        new ApprovalToolRegistry(delegate, gate, configNoAgentGate()).execute("delete_record", ARGS);

        assertTrue(gate.seen.isEmpty(), "a non-high-impact tool does not force a gate");
        assertEquals(1, delegate.calls.size());
    }

    @Test
    void specFor_isDelegatedThrough() {
        RecordingRegistry delegate = new RecordingRegistry();
        delegate.spec = ToolSpec.builtin("delete_record", SideEffects.EXTERNAL_WRITE,
                new Reversibility.IrreversibleHighImpact());
        ApprovalToolRegistry registry = new ApprovalToolRegistry(
                delegate, new ScriptedGate(new ApprovalDecision.Approved()), configNoAgentGate());

        assertTrue(registry.specFor("delete_record").orElseThrow().approvalRequired());
        assertTrue(registry.specFor("unknown").isEmpty());
    }

    // ── construction ──────────────────────────────────────────────────────────

    @Test
    void constructorRejectsNullCollaborators() {
        RecordingRegistry delegate = new RecordingRegistry();
        ScriptedGate gate = new ScriptedGate(new ApprovalDecision.Approved());

        assertThrows(NullPointerException.class,
                () -> new ApprovalToolRegistry(null, gate, config()));
        assertThrows(NullPointerException.class,
                () -> new ApprovalToolRegistry(delegate, null, config()));
        assertThrows(NullPointerException.class,
                () -> new ApprovalToolRegistry(delegate, gate, null));
        assertThrows(NullPointerException.class,
                () -> new ApprovalToolRegistry(delegate, gate, config(), null));
    }
}
