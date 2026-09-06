package io.ara.runtime.auth;

import io.ara.core.agent.AraAgent;
import io.ara.core.auth.AbacPolicyEngine;
import io.ara.core.auth.AuthorizationException;
import io.ara.core.auth.PolicyDecision;
import io.ara.core.auth.PolicyEvaluationContext;
import io.ara.core.auth.ScopeSet;

/**
 * Facade combining ADR-033's two authorization layers: the OAuth-style scope check
 * (Fasi 1-9, always applied) and the opt-in ABAC layer (Fase 2b, applied only when an
 * {@link AbacPolicyEngine} is configured) — `docs/adr/ADR-033-implementation-plan.md`
 * §2b.4, `ara-private`.
 *
 * <p>Deviates from the plan's own sketch in one respect: {@code ScopeVerifier} is a
 * stateless utility class of {@code static} methods (ADR-033 Fase 2), not something to
 * hold an instance of — this calls those static methods directly rather than through a
 * held field that couldn't exist as sketched.
 *
 * <p>Not wired automatically into {@link io.ara.runtime.bus.LocalMessageBus}: {@code
 * AgentConfig} carries no {@code dataClassification}/{@code clearanceLevel}/{@code
 * tenantId} fields for a {@link PolicyEvaluationContext} to be populated from
 * automatically at dispatch time, and inventing placeholder values there would be worse
 * than not integrating at all — a security check that looks wired but silently
 * evaluates fabricated data. A caller that has its own way of deriving {@code
 * SubjectAttributes}/{@code ResourceAttributes} for its domain constructs the {@link
 * PolicyEvaluationContext} itself and calls {@link #authorize} explicitly.
 */
public final class AuthorizationService {

    private final AbacPolicyEngine abacEngine;   // null = ABAC disabled

    /** @param abacEngine {@code null} disables the ABAC layer entirely — only the scope check runs. */
    public AuthorizationService(AbacPolicyEngine abacEngine) {
        this.abacEngine = abacEngine;
    }

    /** {@code true} if an {@link AbacPolicyEngine} was configured (constructor arg non-null). */
    public boolean abacEnabled() {
        return abacEngine != null;
    }

    /**
     * Runs the scope check unconditionally, then — only if ABAC is enabled — the
     * configured {@link AbacPolicyEngine}.
     *
     * @throws AuthorizationException {@code AGENT_NOT_AUTHORIZED} from the scope check
     *                                (see {@link ScopeVerifier#checkAuthorized}), or
     *                                {@code ABAC_POLICY_DENIED} if scopes pass but a
     *                                configured policy denies
     */
    public void authorize(ScopeSet callerScopes, AraAgent target, PolicyEvaluationContext ctx) {
        ScopeVerifier.checkAuthorized(target, callerScopes);

        if (abacEngine != null) {
            PolicyDecision decision = abacEngine.evaluate(ctx);
            if (decision == PolicyDecision.DENY) {
                throw new AuthorizationException(AuthorizationException.Reason.ABAC_POLICY_DENIED,
                        target.agentId().value(), ScopeSet.EMPTY, callerScopes);
            }
        }
    }
}
