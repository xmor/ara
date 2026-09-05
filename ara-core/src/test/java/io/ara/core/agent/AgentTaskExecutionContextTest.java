package io.ara.core.agent;

import io.ara.core.auth.ExecutionContext;
import io.ara.core.auth.ScopeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 6 §6.2 (`docs/adr/ADR-033-implementation-plan.md`, `ara-private`) —
 * {@link AgentTask#executionContext()} and {@link AgentTask#withExecutionContext}.
 */
class AgentTaskExecutionContextTest {

    @Test
    void everyExistingFactory_defaultsToEmpty_zeroBehaviorChange() {
        assertTrue(AgentTask.of("hi").executionContext().isEmpty());
        assertTrue(AgentTask.ofStreaming("hi", t -> {}).executionContext().isEmpty());
    }

    @Test
    void withExecutionContext_isVisibleBothViaTheAccessorAndTheRunContextOpaqueKey() {
        ExecutionContext ctx = ExecutionContext.ofUserDelegation(
                "agent-1", ScopeSet.of("finance:read", "finance:write"),
                "user-1", ScopeSet.of("finance:read"));

        AgentTask task = AgentTask.of("do the thing").withExecutionContext(ctx);

        // executionContext() is derived from the very same opaque key AgentDelegationTool
        // reads — one storage location, not two that could drift apart.
        assertEquals(ctx, task.executionContext().orElseThrow());
        assertEquals(ctx, task.runContext().opaque(RunContext.EXECUTION_CONTEXT_KEY, ExecutionContext.class));
    }

    @Test
    void withExecutionContext_preservesEveryOtherField() {
        AgentTask original = AgentTask.of("hi").withSessionId(SessionId.of("s1"));
        AgentTask withCtx = original.withExecutionContext(
                ExecutionContext.ofAgent("agent-1", ScopeSet.of("ops")));

        assertEquals(original.taskId(), withCtx.taskId());
        assertEquals(original.input(), withCtx.input());
        assertEquals(original.sessionId(), withCtx.sessionId());
    }

    @Test
    void withRunContext_consistentlyDropsExecutionContext_singleSourceOfTruth() {
        // executionContext() has no storage of its own — it is derived from whatever
        // RunContext is currently attached, so replacing the RunContext cannot leave the
        // two disagreeing (there is nothing left to disagree with).
        ExecutionContext ctx = ExecutionContext.ofAgent("agent-1", ScopeSet.of("ops"));
        AgentTask task = AgentTask.of("hi").withExecutionContext(ctx);

        AgentTask afterRunContextSwap = task.withRunContext(RunContext.empty());

        assertTrue(afterRunContextSwap.executionContext().isEmpty());
    }
}
