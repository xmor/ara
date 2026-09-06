package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.stubs.ScriptedLlmClient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0077 D2, closed gap: before this fix, {@code AraRuntime}'s auto-injected {@code
 * DelegatingToolRegistry}/{@code AgentDelegationTool} always narrowed a delegation to
 * {@code ScopeSet.EMPTY} regardless of {@link AgentConfig#grantedScopes()} — the
 * attenuation formula ({@code incoming ∩ ownGrantedScopes}) was correct but had nothing
 * real to narrow: {@code ownGrantedScopes} was never wired from the agent's own config,
 * and {@code incoming} was never seeded from anything either. Both are fixed now:
 * {@code AgentInstance.execute} seeds {@code incoming} from the coordinator's own
 * {@code grantedScopes} on a fresh top-level task, and {@code AraRuntime} wires that same
 * {@code grantedScopes} as {@code ownGrantedScopes} on the delegation tool it injects.
 *
 * <p>Exercises the REAL, fully auto-wired path — {@code AraRuntime.createAgent} + the
 * built-in {@code delegate_task} tool — not a hand-built {@code AgentDelegationTool} like
 * {@link io.ara.runtime.bus.ExecutionContextDelegationChainTest} (which predates this fix
 * and exists specifically because the auto-wired path couldn't do this yet).
 */
class AraRuntimeGrantedScopesWiringTest {

    @Test
    void coordinatorsOwnGrantedScopes_flowThroughTheBuiltInDelegateTaskTool_toAScopedRecipient() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("coordinator-model", ScriptedLlmClient.script()
                        .thenToolCall("delegate_task", "{\"agent_id\":\"target\",\"task\":\"go\"}")
                        .thenFinalAnswer("coordinator done")
                        .build())
                .llmClient("target-model", ScriptedLlmClient.script()
                        .thenFinalAnswer("target ok")
                        .build())
                .defaultLlmClient("coordinator-model")
                .build();

        runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("target"))
                .systemPrompt("target")
                .primaryLlm(LlmProfile.of("target-model"))
                .plannerStrategy("react")
                .requiredScopes(List.of("finance:read"))
                .maxIterations(4)
                .build());

        AraAgent coordinator = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("coordinator"))
                .systemPrompt("coordinator")
                .primaryLlm(LlmProfile.of("coordinator-model"))
                .plannerStrategy("react")
                .enabledTools(List.of("delegate_task"))
                .grantedScopes(List.of("finance:read"))
                .maxIterations(4)
                .build());

        // A fresh top-level task: no prior hop, no executeOnBehalfOf. Before this fix,
        // the delegation would have been narrowed to EMPTY regardless of grantedScopes,
        // and "target" (requiredScopes=[finance:read]) would have rejected it — the
        // observation would carry a denial, never the target's own final answer.
        AgentResponse response = coordinator.execute(AgentTask.of("run the job"));

        assertTrue(response.isSuccess(), response.failureReason());
        String allSteps = response.steps().stream().map(ExecutionStep::content)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(allSteps.contains("target ok"),
                "the delegation must have actually reached target's own ReAct loop: " + allSteps);
        assertFalse(allSteps.contains("AGENT_NOT_AUTHORIZED") || allSteps.contains("denied"),
                "must not have been rejected for insufficient scope: " + allSteps);
    }

    @Test
    void coordinatorWithNoGrantedScopes_stillCannotDelegateToAScopedRecipient_noRegression() {
        AraRuntime runtime = AraRuntime.builder()
                .llmClient("coordinator-model", ScriptedLlmClient.script()
                        .thenToolCall("delegate_task", "{\"agent_id\":\"target\",\"task\":\"go\"}")
                        .thenFinalAnswer("coordinator done")
                        .build())
                .llmClient("target-model", ScriptedLlmClient.script()
                        .thenFinalAnswer("target ok")
                        .build())
                .defaultLlmClient("coordinator-model")
                .build();

        runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("target"))
                .systemPrompt("target")
                .primaryLlm(LlmProfile.of("target-model"))
                .plannerStrategy("react")
                .requiredScopes(List.of("finance:read"))
                .maxIterations(4)
                .build());

        // No grantedScopes at all — the pre-existing, still-default case.
        AraAgent coordinator = runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of("coordinator"))
                .systemPrompt("coordinator")
                .primaryLlm(LlmProfile.of("coordinator-model"))
                .plannerStrategy("react")
                .enabledTools(List.of("delegate_task"))
                .maxIterations(4)
                .build());

        AgentResponse response = coordinator.execute(AgentTask.of("run the job"));

        String allSteps = response.steps().stream().map(ExecutionStep::content)
                .reduce("", (a, b) -> a + "\n" + b);
        assertFalse(allSteps.contains("target ok"),
                "an unscoped coordinator must not be able to reach a scoped recipient: " + allSteps);
    }
}
