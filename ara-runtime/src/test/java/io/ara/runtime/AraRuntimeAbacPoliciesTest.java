package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AraAgent;
import io.ara.core.auth.ActionAttributes;
import io.ara.core.auth.AuthorizationException;
import io.ara.core.auth.EnvironmentAttributes;
import io.ara.core.auth.PolicyEvaluationContext;
import io.ara.core.auth.ResourceAttributes;
import io.ara.core.auth.ScopeSet;
import io.ara.core.auth.SubjectAttributes;
import io.ara.core.common.AgentId;
import io.ara.runtime.auth.AuthorizationService;
import io.ara.runtime.auth.CompositeAbacPolicyEngine;
import io.ara.runtime.auth.policy.ClearancePolicy;
import io.ara.runtime.stubs.ScriptedLlmClient;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 2b §2b.5 — {@code AraRuntime.Builder.abacPolicies(...)} wiring and the
 * {@code AraRuntime.authorizationService()} accessor.
 */
class AraRuntimeAbacPoliciesTest {

    private static AraRuntime runtimeWithoutAbac() {
        return AraRuntime.builder()
                .llmClient("model", ScriptedLlmClient.script().thenFinalAnswer("unused").build())
                .build();
    }

    @Test
    void withoutAbacPolicies_authorizationServiceIsStillNonNull_butAbacDisabled() {
        AuthorizationService service = runtimeWithoutAbac().authorizationService();
        assertFalse(service.abacEnabled());
    }

    @Test
    void abacPolicies_buildsAConfiguredEngine_reachableViaAuthorizationService() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("model", ScriptedLlmClient.script().thenFinalAnswer("unused").build())
                .abacPolicies(policies -> policies
                        .add("clearance", ClearancePolicy.standard())
                        .combineWith(CompositeAbacPolicyEngine.CombiningAlgorithm.DENY_OVERRIDES))
                .build();

        AuthorizationService service = runtime.authorizationService();
        assertTrue(service.abacEnabled());
    }

    @Test
    void configuredClearancePolicy_deniesAnUnderQualifiedCaller_throughAuthorizationService() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("model", ScriptedLlmClient.script().thenFinalAnswer("unused").build())
                .abacPolicies(policies -> policies.add("clearance", ClearancePolicy.standard()))
                .build();

        AraAgent target = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("target"))
                .systemPrompt("target")
                .primaryLlm(io.ara.core.llm.LlmProfile.of("model"))
                .plannerStrategy("react")
                .maxIterations(2)
                .build());

        PolicyEvaluationContext ctx = new PolicyEvaluationContext(
                new SubjectAttributes("caller", "t", null, "STANDARD", ScopeSet.EMPTY),
                new ResourceAttributes("target", "t", null, "CONFIDENTIAL", false),
                ActionAttributes.INVOKE,
                new EnvironmentAttributes(Instant.now(), 0, "req-1"),
                null);

        AuthorizationException e = assertThrows(AuthorizationException.class,
                () -> runtime.authorizationService().authorize(ScopeSet.EMPTY, target, ctx));
        assertEquals(AuthorizationException.Reason.ABAC_POLICY_DENIED, e.reason());
    }
}
