package io.ara.core.agent;

import io.ara.core.auth.ExecutionContext;
import io.ara.core.auth.ScopeSet;

import java.util.Optional;

/**
 * The authority a task carries, resolved from its {@link RunContext} channels, plus
 * whether it carried any at all (ADR-033 §3.2/§3.3). The two facts are not the same: an
 * empty {@link ScopeSet} that arrived through a channel is a legitimate "this caller has
 * no authority" denial, while no channel at all is usually an integration that declared
 * scopes somewhere but forgot to seed the task. Consumers of the resolution treat those
 * cases differently (fail-closed either way, but with a different explanation), which is
 * why {@link #channelPresent()} travels alongside {@link #scopes()} instead of callers
 * re-deriving it.
 *
 * <p>Precedence, shared by every scope reader so the answers can never disagree: the
 * {@link ExecutionContext} under {@link RunContext#EXECUTION_CONTEXT_KEY} first — its
 * effective scopes are already the on-behalf-of intersection, attenuated at every
 * delegation hop — then the bare {@link ScopeSet} under {@link RunContext#SCOPES_KEY},
 * then nothing. Reading a second, divergent precedence into another call site is exactly
 * the drift this helper exists to prevent.
 */
public final class TaskScopes {

    /** The resolved authority: what the caller may do, and whether any channel said so. */
    public record Resolved(ScopeSet scopes, boolean channelPresent) {}

    private TaskScopes() {}

    /**
     * Resolves the authority {@code task} carries: {@code (ScopeSet.EMPTY, false)} for a
     * {@code null} task (no task, no caller to speak of), otherwise the resolution of its
     * {@link AgentTask#runContext()}.
     */
    public static Resolved of(AgentTask task) {
        return task == null ? new Resolved(ScopeSet.EMPTY, false) : of(task.runContext());
    }

    /**
     * Resolves the authority a task's {@link RunContext} carries: the {@link
     * ExecutionContext}'s effective scopes when present, else the bare {@code SCOPES_KEY}
     * set, else empty with no channel. Both channels live in the opaque map, so a bare
     * {@link RunContext} — not the task — is all this needs; delegation tools hold exactly
     * that inside their {@code Caller}.
     */
    public static Resolved of(RunContext context) {
        if (context == null) {
            return new Resolved(ScopeSet.EMPTY, false);
        }
        Optional<ExecutionContext> ctx = Optional.ofNullable(
                context.opaque(RunContext.EXECUTION_CONTEXT_KEY, ExecutionContext.class));
        if (ctx.isPresent()) {
            return new Resolved(ctx.get().effectiveScopes(), true);
        }
        ScopeSet bare = context.opaque(RunContext.SCOPES_KEY, ScopeSet.class);
        return bare != null ? new Resolved(bare, true) : new Resolved(ScopeSet.EMPTY, false);
    }
}
