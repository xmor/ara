package io.ara.core.agent;

import io.ara.core.auth.ExecutionContext;
import io.ara.core.auth.ScopeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 §3.2/§3.3 — the one precedence every task-scope reader shares: the
 * {@link ExecutionContext}'s effective scopes first (the on-behalf-of intersection,
 * already attenuated at each delegation hop), then the bare {@link
 * RunContext#SCOPES_KEY} set, then empty with no channel. Extracted from {@code
 * ScopeFilteringToolRegistry} so the delegation pre-check and the tool filter cannot
 * drift into two different answers about the same task.
 */
class TaskScopesTest {

    @Test
    void nullTask_resolvesToEmpty_withNoChannel() {
        TaskScopes.Resolved resolved = TaskScopes.of((AgentTask) null);

        assertEquals(ScopeSet.EMPTY, resolved.scopes());
        assertFalse(resolved.channelPresent());
    }

    @Test
    void plainTask_withoutAnyChannel_resolvesToEmpty_withNoChannel() {
        TaskScopes.Resolved resolved = TaskScopes.of(AgentTask.of("go"));

        assertEquals(ScopeSet.EMPTY, resolved.scopes());
        assertFalse(resolved.channelPresent());
    }

    @Test
    void bareScopesKey_resolvesToThatSet_withAChannel() {
        AgentTask task = AgentTask.of("go")
                .withAttachment(RunContext.SCOPES_KEY, ScopeSet.of("finance:read"));

        TaskScopes.Resolved resolved = TaskScopes.of(task);

        assertEquals(ScopeSet.of("finance:read"), resolved.scopes());
        assertTrue(resolved.channelPresent());
    }

    @Test
    void executionContext_winsOverTheBareScopesKey() {
        // Both channels set: the execution context is the stricter, better-informed
        // authority (identity + attenuation already applied), so it must win outright.
        AgentTask task = AgentTask.of("go")
                .withAttachment(RunContext.SCOPES_KEY, ScopeSet.of("finance:read", "ops"))
                .withExecutionContext(ExecutionContext.ofUserDelegation(
                        "agent-1", ScopeSet.of("finance:read", "finance:write"),
                        "user-1", ScopeSet.of("finance:read")));

        TaskScopes.Resolved resolved = TaskScopes.of(task);

        assertEquals(ScopeSet.of("finance:read"),
                resolved.scopes(), "{finance:read,finance:write} ∩ {finance:read} = {finance:read}");
        assertTrue(resolved.channelPresent());
    }

    @Test
    void executionContextAlone_resolvesToItsEffectiveScopes() {
        AgentTask task = AgentTask.of("go").withExecutionContext(ExecutionContext.ofUserDelegation(
                "agent-1", ScopeSet.of("finance:read", "finance:write"),
                "user-1", ScopeSet.of("finance:read")));

        assertEquals(ScopeSet.of("finance:read"), TaskScopes.of(task).scopes());
        assertTrue(TaskScopes.of(task).channelPresent());
    }

    @Test
    void runContextForm_matchesTheTaskForm() {
        AgentTask task = AgentTask.of("go")
                .withAttachment(RunContext.SCOPES_KEY, ScopeSet.of("ops"));

        assertEquals(TaskScopes.of(task), TaskScopes.of(task.runContext()));
    }
}
