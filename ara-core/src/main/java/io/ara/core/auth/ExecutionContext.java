package io.ara.core.auth;

import io.ara.core.agent.AraAgent;

import java.util.Objects;

/**
 * Identity of a single call in the system: who is currently acting ({@code actorId}/
 * {@code actorScopes}), and — for delegation on behalf of a user (ADR-033 Fase 6) —
 * whose authority ultimately bounds it ({@code subjectId}/{@code subjectScopes}).
 *
 * <p>ADR-033 Fase 5 (`docs/adr/ADR-033-implementation-plan.md` §5.1, `ara-private`).
 * Supersedes the bare {@link ScopeSet} carried by {@code AgentMessage.senderScopes}
 * (ADR-033 Fase 2) for anything beyond a single direct hop: a {@code ScopeSet} alone
 * cannot distinguish "this agent's own authority" from "the human whose request this
 * ultimately serves", so a chain of M2M-only attenuation has nothing left to intersect
 * against once a user enters the picture. {@code subjectId == null} (the M2M case,
 * {@link #ofAgent}) keeps {@link #effectiveScopes()} equal to {@link #actorScopes}
 * exactly as a bare {@code ScopeSet} would — this type is a strict superset, not a
 * parallel mechanism.
 *
 * @param actorId       the agent currently executing
 * @param actorScopes   that agent's own granted scopes
 * @param subjectId     the user this call is ultimately made on behalf of;
 *                      {@code null} for pure machine-to-machine calls
 * @param subjectScopes the user's own scopes; {@code null}/{@link ScopeSet#EMPTY} when
 *                      {@code subjectId} is {@code null}
 */
public record ExecutionContext(
        String   actorId,
        ScopeSet actorScopes,
        String   subjectId,
        ScopeSet subjectScopes
) {

    public ExecutionContext {
        Objects.requireNonNull(actorId,     "actorId must not be null");
        Objects.requireNonNull(actorScopes, "actorScopes must not be null");
        subjectScopes = Objects.requireNonNullElse(subjectScopes, ScopeSet.EMPTY);
    }

    /** A pure machine-to-machine context: no subject, {@link #effectiveScopes()} == {@code actorScopes}. */
    public static ExecutionContext ofAgent(String actorId, ScopeSet actorScopes) {
        return new ExecutionContext(actorId, actorScopes, null, ScopeSet.EMPTY);
    }

    /** Convenience overload reading {@code actorId} straight off the agent. */
    public static ExecutionContext ofAgent(AraAgent actor) {
        return ofAgent(actor.agentId().value(), ScopeSet.of(actor.config().grantedScopes()));
    }

    /** A context delegated on behalf of a user (ADR-033 Fase 6). */
    public static ExecutionContext ofUserDelegation(String actorId, ScopeSet actorScopes,
                                                     String subjectId, ScopeSet subjectScopes) {
        Objects.requireNonNull(subjectId, "subjectId must not be null for a user-delegation context");
        return new ExecutionContext(actorId, actorScopes, subjectId, subjectScopes);
    }

    /**
     * The scopes this call actually carries: the actor's own scopes when there is no
     * subject (pure M2M — nothing to intersect against), otherwise the intersection with
     * the subject's scopes (Delegation, not Impersonation — the actor can never exceed
     * the user it claims to act for, nor the user exceed the actor's own grant).
     */
    public ScopeSet effectiveScopes() {
        if (subjectId == null || subjectScopes.isEmpty()) return actorScopes;
        return actorScopes.intersect(subjectScopes);
    }

    /**
     * Derives the context for the next hop in a delegation chain: the new actor's
     * effective scopes are this call's {@link #effectiveScopes()} intersected with what
     * the next actor itself holds ({@code nextActorGranted}) — an intersection, so the
     * result is structurally incapable of exceeding either side. {@code subjectId}/
     * {@code subjectScopes} travel unchanged; only the acting agent and its effective
     * authority narrow at each hop.
     */
    public ExecutionContext delegate(String nextActorId, ScopeSet nextActorGranted) {
        ScopeSet attenuated = effectiveScopes().intersect(
                Objects.requireNonNullElse(nextActorGranted, ScopeSet.EMPTY));
        return new ExecutionContext(nextActorId, attenuated, subjectId, subjectScopes);
    }
}
