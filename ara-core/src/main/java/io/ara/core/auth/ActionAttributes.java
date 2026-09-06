package io.ara.core.auth;

import java.util.Objects;

/**
 * The action being authorized in an ABAC policy decision (ADR-033 Fase 2b, S8 —
 * `docs/adr/ADR-033-implementation-plan.md` §2b.1, `ara-private`).
 *
 * <p>A plain wrapped {@code String} rather than an enum: a deployment's own policies may
 * need action names this type has no reason to know about in advance (the three constants
 * below cover the cases ARA's own authorization chokepoints — {@code ScopeVerifier},
 * {@code AgentDelegationTool} — currently reason about).
 *
 * @param action the action name; never {@code null} or blank
 */
public record ActionAttributes(String action) {

    /** Invoking an agent directly (the target of {@code ScopeVerifier.checkAuthorized}). */
    public static final ActionAttributes INVOKE = new ActionAttributes("invoke");

    /** Delegating a sub-task to a peer agent (the target of {@code AgentDelegationTool}). */
    public static final ActionAttributes DELEGATE = new ActionAttributes("delegate");

    /** Reading a resource without invoking it (e.g. catalog/discovery). */
    public static final ActionAttributes READ = new ActionAttributes("read");

    public ActionAttributes {
        Objects.requireNonNull(action, "action must not be null");
        if (action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
    }
}
