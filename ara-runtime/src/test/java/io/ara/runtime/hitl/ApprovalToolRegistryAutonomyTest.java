package io.ara.runtime.hitl;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.RunState;
import io.ara.core.autonomy.AutonomyLevel;
import io.ara.core.autonomy.AutonomyPolicy;
import io.ara.core.common.AgentId;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.Reversibility;
import io.ara.core.tool.SideEffects;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import io.ara.core.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0073 D2: the autonomy track record as the additive third disjunct of
 * {@link ApprovalToolRegistry}'s gate decision — condition 2 (level floor) and condition 3
 * (confidence threshold), read from the call's {@link RunState}. The first two disjuncts
 * (agent flag, {@link ToolSpec#approvalRequired()}) keep their existing behaviour and are
 * covered by {@link ApprovalToolRegistryTest}.
 */
class ApprovalToolRegistryAutonomyTest {

    private static final String ARGS = "{}";

    /** Records dispatches and reports a fixed {@link ToolSpec}. */
    private static final class RecordingRegistry implements ToolRegistry {
        final List<String> calls = new CopyOnWriteArrayList<>();
        volatile ToolSpec spec;

        private final AraTool tool = new AraTool() {
            @Override public String toolId() { return "act"; }
            @Override public String description() { return "does a thing"; }
            @Override public String argumentSchema() { return "{\"type\":\"object\"}"; }
            @Override public ToolResult execute(String argumentJson) { return ToolResult.success(toolId(), "ok"); }
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
            calls.add(toolId); return ToolResult.success(toolId, "ok");
        }
        @Override public ToolResult execute(String toolId, String argumentJson, AgentTask task) {
            calls.add(toolId); return ToolResult.success(toolId, "ok");
        }
        @Override public Runnable wrapForPropagation(Runnable task) { return task; }
    }

    /** A gate that fails the test if it is ever asked — the assertion is "was the call gated". */
    private static final class RejectingGate implements ApprovalGate {
        final List<ApprovalRequest> seen = new CopyOnWriteArrayList<>();
        @Override public CompletableFuture<ApprovalDecision> requestApproval(ApprovalRequest request) {
            seen.add(request);
            return CompletableFuture.completedFuture(new ApprovalDecision.Rejected("gated"));
        }
        @Override public void submit(String requestId, ApprovalDecision decision) {
            throw new UnsupportedOperationException();
        }
        @Override public List<ApprovalRequest> getPendingRequests() { return List.copyOf(seen); }
    }

    private static AgentConfig noAgentGate() {
        return AgentConfig.defaults().agentId(AgentId.of("a")).agentType("t").build();
    }

    private static AgentTask taskWith(String intent, Double confidence) {
        RunState state = RunState.inMemory();
        if (intent != null) state.put("intent", intent);
        if (confidence != null) state.put("confidence", confidence);
        return AgentTask.of("do it").withRunContext(new RunContext(Map.of(), Map.of(), state));
    }

    private static ApprovalToolRegistry withPolicy(RecordingRegistry delegate, ApprovalGate gate,
                                                   AutonomyPolicy policy) {
        return new ApprovalToolRegistry(delegate, gate, noAgentGate(),
                ApprovalToolRegistry.DEFAULT_APPROVAL_TIMEOUT, policy);
    }

    // ── condition 2 / 3 fire on the task path ───────────────────────────────

    @Test
    void a0EscalatesAnOtherwiseHarmlessReversibleAction() {
        RecordingRegistry delegate = new RecordingRegistry();
        delegate.spec = ToolSpec.builtin("act", SideEffects.LOCAL_WRITE, new Reversibility.Reversible());
        RejectingGate gate = new RejectingGate();

        ToolResult r = withPolicy(delegate, gate, AutonomyPolicy.fixed(AutonomyLevel.A0))
                .execute("act", ARGS, taskWith("summarise", 1.0));

        assertTrue(r.isFailed());
        assertTrue(delegate.calls.isEmpty(), "A0 must gate every action");
        assertEquals(1, gate.seen.size());
    }

    @Test
    void levelFloorGatesAnActionAboveIt() {
        RecordingRegistry delegate = new RecordingRegistry();
        delegate.spec = ToolSpec.builtin("act", SideEffects.LOCAL_WRITE, new Reversibility.CostlyButReversible());
        RejectingGate gate = new RejectingGate();

        withPolicy(delegate, gate, AutonomyPolicy.fixed(AutonomyLevel.A1))   // floor = Reversible
                .execute("act", ARGS, taskWith("summarise", 1.0));

        assertTrue(delegate.calls.isEmpty(), "CostlyButReversible is above A1's floor");
    }

    @Test
    void confidenceBelowThresholdGates() {
        RecordingRegistry delegate = new RecordingRegistry();
        delegate.spec = ToolSpec.builtin("act", SideEffects.LOCAL_WRITE, new Reversibility.Reversible());

        withPolicy(delegate, new RejectingGate(), AutonomyPolicy.fixed(AutonomyLevel.A1))  // threshold 0.90
                .execute("act", ARGS, taskWith("summarise", 0.5));

        assertTrue(delegate.calls.isEmpty());
    }

    @Test
    void withinFloorAndAboveThreshold_theActionRunsWithoutAGate() {
        RecordingRegistry delegate = new RecordingRegistry();
        delegate.spec = ToolSpec.builtin("act", SideEffects.LOCAL_WRITE, new Reversibility.Reversible());
        RejectingGate gate = new RejectingGate();

        ToolResult r = withPolicy(delegate, gate, AutonomyPolicy.fixed(AutonomyLevel.A2))  // floor Costly, threshold 0.80
                .execute("act", ARGS, taskWith("summarise", 0.95));

        assertTrue(r.isSuccess());
        assertEquals(List.of("act"), delegate.calls);
        assertTrue(gate.seen.isEmpty());
    }

    // ── the "not measurable here" cases: policy adds no gate ────────────────

    @Test
    void noTaskMeansNoAutonomyEvaluation() {
        RecordingRegistry delegate = new RecordingRegistry();
        delegate.spec = ToolSpec.builtin("act", SideEffects.LOCAL_WRITE, new Reversibility.Reversible());
        RejectingGate gate = new RejectingGate();

        withPolicy(delegate, gate, AutonomyPolicy.fixed(AutonomyLevel.A0))
                .execute("act", ARGS);   // no-task overload

        assertEquals(List.of("act"), delegate.calls);
        assertTrue(gate.seen.isEmpty());
    }

    @Test
    void absentTaskClassIsNotMeasurableSoNoGateIsAdded() {
        RecordingRegistry delegate = new RecordingRegistry();
        delegate.spec = ToolSpec.builtin("act", SideEffects.LOCAL_WRITE, new Reversibility.Reversible());
        RejectingGate gate = new RejectingGate();

        withPolicy(delegate, gate, AutonomyPolicy.fixed(AutonomyLevel.A0))
                .execute("act", ARGS, taskWith(null, 1.0));   // no "intent" in state

        assertEquals(List.of("act"), delegate.calls);
        assertTrue(gate.seen.isEmpty());
    }

    @Test
    void noToolSpecMeansTheAutonomyAxisCannotSpeak() {
        RecordingRegistry delegate = new RecordingRegistry();
        delegate.spec = null;   // unclassified tool
        RejectingGate gate = new RejectingGate();

        withPolicy(delegate, gate, AutonomyPolicy.fixed(AutonomyLevel.A0))
                .execute("act", ARGS, taskWith("summarise", 1.0));

        assertEquals(List.of("act"), delegate.calls);
        assertTrue(gate.seen.isEmpty());
    }

    // ── the absolute floor still wins regardless of level ───────────────────

    @Test
    void approvalRequiredToolIsGatedEvenAtA4() {
        RecordingRegistry delegate = new RecordingRegistry();
        delegate.spec = ToolSpec.builtin("act", SideEffects.EXTERNAL_WRITE, new Reversibility.IrreversibleHighImpact());
        RejectingGate gate = new RejectingGate();

        withPolicy(delegate, gate, AutonomyPolicy.fixed(AutonomyLevel.A4))
                .execute("act", ARGS, taskWith("summarise", 1.0));

        assertTrue(delegate.calls.isEmpty());
        assertEquals(1, gate.seen.size());
    }

    // ── no policy configured: pre-ADR-0073 behaviour is unchanged ───────────

    @Test
    void withNoPolicyAnUngatedToolRunsAsBefore() {
        RecordingRegistry delegate = new RecordingRegistry();
        delegate.spec = ToolSpec.builtin("act", SideEffects.LOCAL_WRITE, new Reversibility.Reversible());
        RejectingGate gate = new RejectingGate();

        new ApprovalToolRegistry(delegate, gate, noAgentGate())
                .execute("act", ARGS, taskWith("summarise", 0.0));

        assertEquals(List.of("act"), delegate.calls);
        assertTrue(gate.seen.isEmpty());
    }

    private static void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
    private static void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
