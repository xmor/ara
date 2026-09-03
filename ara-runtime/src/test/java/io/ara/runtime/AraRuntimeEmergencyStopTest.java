package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmProfile;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0069 D4 — the two kill switches on {@link AraRuntime} request cooperative
 * cancellation across sessions <em>without terminating agents</em>. The cancellation
 * mechanism itself (a flag consumed at an iteration boundary, cleared when the next task
 * starts) is covered by {@code StrategyCancellationTest}; here we check the routing:
 * right scope, no exception, agents stay alive and usable afterwards.
 */
class AraRuntimeEmergencyStopTest {

    private static AraRuntime runtime() {
        return AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer("done").build())
                .build();
    }

    private static AraAgent agent(AraRuntime runtime, String id) {
        return runtime.createAgent(AgentConfig.defaults()
                .agentId(AgentId.of(id))
                .agentType("t")
                .primaryLlm(LlmProfile.of("stub"))
                .build());
    }

    @Test
    void emergencyStop_isANoOpOnAnUnknownAgent() {
        try (AraRuntime runtime = runtime()) {
            agent(runtime, "a1");
            assertDoesNotThrow(() -> runtime.emergencyStop(AgentId.of("ghost")));
        }
    }

    @Test
    void emergencyStop_scopedToOneAgent_neitherAgentIsTerminated() {
        try (AraRuntime runtime = runtime()) {
            AraAgent a1 = agent(runtime, "a1");
            AraAgent a2 = agent(runtime, "a2");
            a1.execute(AgentTask.of("hi").withSessionId(SessionId.of("s1")));
            a2.execute(AgentTask.of("hi").withSessionId(SessionId.of("s2")));

            assertDoesNotThrow(() -> runtime.emergencyStop(AgentId.of("a1")));

            assertEquals(2, runtime.agents().size(), "no agent destroyed");
            // both agents still run new work
            assertTrue(a1.execute(AgentTask.of("again").withSessionId(SessionId.of("s1-b"))).isSuccess());
            assertTrue(a2.execute(AgentTask.of("ok").withSessionId(SessionId.of("s2-b"))).isSuccess());
        }
    }

    @Test
    void emergencyStopAll_cancelsEveryAgent_withoutDestroyingAny() {
        try (AraRuntime runtime = runtime()) {
            AraAgent a1 = agent(runtime, "a1");
            AraAgent a2 = agent(runtime, "a2");
            a1.execute(AgentTask.of("hi").withSessionId(SessionId.of("s1")));
            a2.execute(AgentTask.of("hi").withSessionId(SessionId.of("s2")));

            assertDoesNotThrow(runtime::emergencyStopAll);

            assertEquals(2, runtime.agents().size());
            assertTrue(a1.execute(AgentTask.of("x").withSessionId(SessionId.of("s1-b"))).isSuccess());
            assertTrue(a2.execute(AgentTask.of("y").withSessionId(SessionId.of("s2-b"))).isSuccess());
        }
    }
}
