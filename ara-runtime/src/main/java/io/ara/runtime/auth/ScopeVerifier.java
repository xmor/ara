package io.ara.runtime.auth;

import io.ara.core.agent.AraAgent;
import io.ara.core.auth.AuthorizationException;
import io.ara.core.auth.ScopeSet;
import io.ara.core.hitl.ApprovalDecision;
import io.ara.core.hitl.ApprovalGate;
import io.ara.core.hitl.ApprovalRequest;
import io.ara.core.tool.AraTool;

import java.time.Duration;
import java.util.Objects;

/**
 * Stateless scope-check logic (ADR-033 Fase 2) — the first code path that actually
 * throws {@link AuthorizationException}, closing the gap Fase 1's types left open
 * ("nothing enforces scopes yet").
 *
 * <p>Every check is a no-op today for any agent/tool that has not declared
 * {@code visibleToScopes}/{@code requiredScopes}: an empty declared set is "open to
 * everyone" on both sides of {@link ScopeSet#visibleTo}/{@link ScopeSet#grants} — so
 * calling these methods against the current, entirely unconfigured agent population
 * changes nothing. Enforcement only activates the moment a caller opts in by setting one
 * of those fields.
 */
public final class ScopeVerifier {

    private ScopeVerifier() {}

    /**
     * @throws AuthorizationException {@code AGENT_NOT_VISIBLE} if {@code target}'s
     *                                {@code visibleToScopes} shares nothing with {@code effective}
     */
    public static void checkVisible(AraAgent target, ScopeSet effective) {
        Objects.requireNonNull(target,    "target must not be null");
        Objects.requireNonNull(effective, "effective must not be null");
        ScopeSet visibleTo = ScopeSet.of(target.config().visibleToScopes());
        if (!visibleTo.visibleTo(effective)) {
            throw new AuthorizationException(AuthorizationException.Reason.AGENT_NOT_VISIBLE,
                    target.agentId().value(), visibleTo, effective);
        }
    }

    /**
     * @throws AuthorizationException {@code AGENT_NOT_AUTHORIZED} if {@code effective} does
     *                                not satisfy {@code target}'s {@code requiredScopes}
     */
    public static void checkAuthorized(AraAgent target, ScopeSet effective) {
        Objects.requireNonNull(target,    "target must not be null");
        Objects.requireNonNull(effective, "effective must not be null");
        ScopeSet required = ScopeSet.of(target.config().requiredScopes());
        if (!effective.grants(required)) {
            throw new AuthorizationException(AuthorizationException.Reason.AGENT_NOT_AUTHORIZED,
                    target.agentId().value(), required, effective);
        }
    }

    /** Time an operator has to decide before a Fase-7 invocation-approval request expires. */
    public static final Duration DEFAULT_APPROVAL_TIMEOUT = Duration.ofMinutes(30);

    /**
     * ADR-033 Fase 7 (S4) — human-in-the-loop gate on <em>invoking</em> {@code target},
     * reusing the existing ADR-048 {@link ApprovalGate}/{@link ApprovalDecision} machinery
     * rather than a parallel mechanism. Distinct from {@code ApprovalToolRegistry}, which
     * gates {@code target}'s own <em>outgoing</em> tool calls — this gates the act of
     * delegating <em>to</em> {@code target} in the first place, checked only after {@link
     * #checkAuthorized} has already passed (scopes are necessary but not sufficient).
     *
     * <p>A no-op — same as calling this method not existing — unless {@code target.config()
     * .requiresApproval()} is {@code true} <em>and</em> {@code gate} is non-null: an agent
     * that never opted in, or a caller with no {@link ApprovalGate} configured, sees zero
     * behavior change.
     *
     * @throws AuthorizationException {@code APPROVAL_REQUIRED} if the gate rejects, times
     *                                out, or otherwise fails to produce {@link
     *                                ApprovalDecision.Approved}/{@link ApprovalDecision.Modified}
     */
    public static void checkApproved(AraAgent target, ApprovalGate gate, String actorId, ScopeSet effective) {
        Objects.requireNonNull(target,    "target must not be null");
        Objects.requireNonNull(effective, "effective must not be null");
        if (gate == null || !target.config().requiresApproval()) {
            return;
        }
        Objects.requireNonNull(actorId, "actorId must not be null when an ApprovalGate is configured");

        ApprovalRequest request = ApprovalRequest.of(
                actorId, "invoke:" + target.agentId().value(), effective.scopes(), DEFAULT_APPROVAL_TIMEOUT);

        ApprovalDecision decision;
        try {
            decision = gate.requestApproval(request).join();
        } catch (Exception e) {
            throw new AuthorizationException(AuthorizationException.Reason.APPROVAL_REQUIRED,
                    target.agentId().value(), ScopeSet.EMPTY, effective);
        }

        if (decision instanceof ApprovalDecision.Rejected) {
            throw new AuthorizationException(AuthorizationException.Reason.APPROVAL_REQUIRED,
                    target.agentId().value(), ScopeSet.EMPTY, effective);
        }
        // Approved or Modified: this is an authorization gate, not a payload-rewriting one
        // (that is ApprovalToolRegistry's job on the tool-call path) — either lets the
        // invocation proceed with its original arguments.
    }

    /**
     * @throws AuthorizationException {@code TOOL_NOT_AUTHORIZED} if {@code effective} does
     *                                not satisfy {@code tool}'s {@code requiredScopes}
     */
    public static void checkTool(AraTool tool, ScopeSet effective) {
        Objects.requireNonNull(tool,      "tool must not be null");
        Objects.requireNonNull(effective, "effective must not be null");
        ScopeSet required = ScopeSet.of(tool.requiredScopes());
        if (!effective.grants(required)) {
            throw new AuthorizationException(AuthorizationException.Reason.TOOL_NOT_AUTHORIZED,
                    tool.toolId(), required, effective);
        }
    }
}
