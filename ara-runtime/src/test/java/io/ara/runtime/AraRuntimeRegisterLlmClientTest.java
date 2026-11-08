package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmConfig;
import io.ara.core.llm.LlmProfile;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.runtime.factory.DefaultLlmRouter;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** A client registered on a running runtime is usable by agents created afterwards. */
class AraRuntimeRegisterLlmClientTest {

    private static LlmClient answering(String text) {
        return ScriptedLlmClient.script().thenFinalAnswer(text).build();
    }

    private static AraRuntime started() {
        AraRuntime runtime = AraRuntime.builder().llmClient("first", answering("dal primo")).build();
        runtime.start();
        return runtime;
    }

    private static AraAgent agentOn(AraRuntime runtime, String id, String clientId) {
        return runtime.createAgent(AgentConfig.defaults().agentId(AgentId.of(id)).agentType("t")
                .primaryLlm(LlmProfile.of(clientId)).plannerStrategy("react").build());
    }

    @Test
    void aRuntimeWithNoClientBuildsAndStarts_andGetsItsFirstModelWhileRunning() {
        AraRuntime runtime = AraRuntime.builder().build();
        runtime.start();
        try {
            assertTrue(runtime.llmClients().isEmpty());

            runtime.registerLlmClient("lmstudio", answering("dal primo modello"));

            AgentResponse response = agentOn(runtime, "a", "lmstudio").execute(AgentTask.of("ciao"));
            assertEquals("dal primo modello", response.content());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void onAnEmptyRuntime_anAgentRunBeforeItsModelExists_failsThatRun_notTheRuntime() {
        AraRuntime runtime = AraRuntime.builder().build();
        runtime.start();
        try {
            AraAgent agent = agentOn(runtime, "a", "lmstudio");
            boolean failed;
            try {
                failed = !agent.execute(AgentTask.of("ciao")).isSuccess();
            } catch (RuntimeException e) {
                failed = true;
            }
            assertTrue(failed, "no model is registered under 'lmstudio' yet");

            runtime.registerLlmClient("lmstudio", answering("ora c'è"));
            assertEquals("ora c'è", agentOn(runtime, "b", "lmstudio").execute(AgentTask.of("ciao")).content());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void onAnEmptyRuntime_theFirstRegisteredClientBecomesTheDefault() {
        AraRuntime runtime = AraRuntime.builder().build();
        runtime.start();
        try {
            runtime.registerLlmClient("lmstudio", answering("dal default"));
            runtime.registerLlmClient("other", answering("dall'altro"));

            // an agent asking for a model nobody registered falls back to the default, as with a builder client
            assertEquals("dal default", agentOn(runtime, "a", "unregistered").execute(AgentTask.of("ciao")).content());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void onAnEmptyRuntime_anExplicitDefaultStaysTheDefault() {
        AraRuntime runtime = AraRuntime.builder().defaultLlmClient("chosen").build();
        runtime.start();
        try {
            runtime.registerLlmClient("first", answering("dal primo"));
            runtime.registerLlmClient("chosen", answering("da quello scelto"));

            assertEquals("da quello scelto", agentOn(runtime, "a", "unregistered").execute(AgentTask.of("ciao")).content());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void aRuntimeBuiltWithClients_keepsItsDefaultWhenMoreAreRegistered() {
        AraRuntime runtime = started();
        try {
            runtime.registerLlmClient("second", answering("dal secondo"));

            assertEquals("dal primo", agentOn(runtime, "a", "unregistered").execute(AgentTask.of("ciao")).content());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void aDefaultNamingNoneOfTheGivenClients_isStillATypoCaughtAtBuild() {
        assertThrows(IllegalStateException.class, () -> AraRuntime.builder()
                .llmClient("first", answering("x")).defaultLlmClient("frist").build());
    }

    @Test
    void aClientRegisteredAfterStartIsUsedByANewAgent() {
        AraRuntime runtime = started();
        try {
            assertFalse(runtime.llmClients().containsKey("second"));
            runtime.registerLlmClient("second", answering("dal secondo"));

            assertTrue(runtime.llmClients().containsKey("second"), "the read-only view follows the registration");
            AgentResponse response = agentOn(runtime, "a", "second").execute(AgentTask.of("ciao"));
            assertEquals("dal secondo", response.content());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void theRegisteredClientIsInstrumentedLikeTheBuiltInOnes() {
        AraRuntime runtime = started();
        try {
            LlmClient raw = answering("x");
            runtime.registerLlmClient("second", raw);
            assertNotSame(raw, runtime.llmClients().get("second"));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void anIdCannotBeRegisteredTwiceNorBlank() {
        AraRuntime runtime = started();
        try {
            runtime.registerLlmClient("second", answering("x"));
            assertThrows(IllegalStateException.class, () -> runtime.registerLlmClient("second", answering("y")));
            assertThrows(IllegalStateException.class, () -> runtime.registerLlmClient("first", answering("y")));
            assertThrows(IllegalArgumentException.class, () -> runtime.registerLlmClient(" ", answering("y")));
            assertThrows(NullPointerException.class, () -> runtime.registerLlmClient("third", null));
            assertFalse(runtime.llmClients().containsKey("third"));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void anIdPublishedThroughUpdateSharedModelIsTakenToo() {
        AraRuntime runtime = AraRuntime.builder().llmClient("first", answering("1"))
                .llmClientFactory(transport -> answering("from transport")).build();
        runtime.start();
        try {
            runtime.updateSharedModel("shared", new io.ara.core.llm.LlmTransport("http://localhost:1", "k", "m"));
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> runtime.registerLlmClient("shared", answering("y")));
            assertEquals("LLM client 'shared' is already registered", e.getMessage());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void theViewCannotBeWrittenThrough() {
        AraRuntime runtime = started();
        try {
            assertThrows(UnsupportedOperationException.class,
                    () -> runtime.llmClients().put("x", answering("x")));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void aRouterOverALiveMapFindsClientsAddedLater() {
        Map<String, LlmClient> live = new ConcurrentHashMap<>();
        LlmClient first = answering("1");
        live.put("first", first);
        DefaultLlmRouter router = DefaultLlmRouter.over(live, "first", null, AraTelemetry.noop());

        LlmClient later = answering("2");
        live.put("later", later);
        LlmClient selected = router.select(LlmConfig.of("later"),
                io.ara.core.llm.LlmCallContext.from(AgentConfig.defaults().agentId(AgentId.of("a")).agentType("t").build()));
        assertSame(later, selected);
    }

    @Test
    void theSnapshotConstructorStillIgnoresLaterChanges() {
        Map<String, LlmClient> source = new ConcurrentHashMap<>();
        LlmClient first = answering("1");
        source.put("first", first);
        DefaultLlmRouter router = new DefaultLlmRouter(source, "first", null, AraTelemetry.noop());
        source.put("later", answering("2"));
        // an id the snapshot does not hold falls back to the default client
        assertSame(first, router.select(LlmConfig.of("later"),
                io.ara.core.llm.LlmCallContext.from(AgentConfig.defaults().agentId(AgentId.of("a")).agentType("t").build())));
    }
}
