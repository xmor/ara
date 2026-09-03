package io.ara.core.auth;

import io.ara.core.exceptions.AraException;

import java.util.Objects;

/**
 * Thrown when a caller is not permitted to discover or invoke an agent or tool
 * (ADR-033). Carries the typed {@link Reason}, the target that was denied, the scopes it
 * required, and the effective scopes the caller actually held at the point of refusal —
 * enough for a caller to log or surface the denial without re-deriving it.
 *
 * <p><b>Fase 1 defines the type; nothing throws it yet.</b> {@code ScopeVerifier}
 * (ara-runtime, Fase 2) is the first code path that will.
 */
public class AuthorizationException extends AraException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** The target agent is not visible to the caller's scopes at all. */
        AGENT_NOT_VISIBLE,
        /** The target agent is visible but the caller's scopes do not satisfy its {@code requiredScopes}. */
        AGENT_NOT_AUTHORIZED,
        /** The tool is not accessible with the caller's current scopes. */
        TOOL_NOT_AUTHORIZED,
        /** Scopes are satisfied but the target requires an explicit human approval (ADR-033 S4). */
        APPROVAL_REQUIRED
    }

    private final Reason   reason;
    private final String   targetId;
    private final ScopeSet required;
    private final ScopeSet effective;

    public AuthorizationException(Reason reason, String targetId, ScopeSet required, ScopeSet effective) {
        super(buildMessage(reason, targetId, required, effective));
        this.reason    = Objects.requireNonNull(reason, "reason must not be null");
        this.targetId  = Objects.requireNonNull(targetId, "targetId must not be null");
        this.required  = Objects.requireNonNullElse(required, ScopeSet.EMPTY);
        this.effective = Objects.requireNonNullElse(effective, ScopeSet.EMPTY);
    }

    public Reason   reason()    { return reason; }
    public String   targetId()  { return targetId; }
    public ScopeSet required()  { return required; }
    public ScopeSet effective() { return effective; }

    private static String buildMessage(Reason reason, String targetId, ScopeSet required, ScopeSet effective) {
        ScopeSet req = Objects.requireNonNullElse(required, ScopeSet.EMPTY);
        ScopeSet eff = Objects.requireNonNullElse(effective, ScopeSet.EMPTY);
        return reason + " for '" + targetId + "': required=" + req.scopes() + ", effective=" + eff.scopes();
    }
}
