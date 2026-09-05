package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.llm.LlmProfile;
import io.ara.core.trace.BlobStore;
import io.ara.core.trace.SpanStatus;
import io.ara.core.trace.TraceSpan;
import io.ara.core.trace.TraceStore;
import io.ara.runtime.stubs.ScriptedLlmClient;
import io.ara.runtime.trace.TraceEmittingAgent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0068 D1: {@code AraRuntime.builder().traceEmission(...)} wires every created agent so
 * each execution appends a run trace — and without it, behaviour is unchanged.
 */
class AraRuntimeTraceEmissionTest {

    private AraAgent agent(AraRuntime runtime, String strategy) {
        return runtime.createAgent(AgentConfig.defaults()
                .agentType("reviewer")
                .primaryLlm(LlmProfile.of("stub"))
                .plannerStrategy(strategy)
                .build());
    }

    @Test
    void everyExecutionAppendsARunTraceWhenEmissionIsConfigured() {
        TraceStore traces = TraceStore.inMemory();
        BlobStore blobs = BlobStore.inMemory();
        ScriptedLlmClient llm = ScriptedLlmClient.script().thenFinalAnswer("LGTM").build();

        AraRuntime runtime = AraRuntime.builder().llmClient(llm).traceEmission(traces, blobs).build();
        try {
            AraAgent a = agent(runtime, "react");
            assertInstanceOf(TraceEmittingAgent.class, a, "the registered agent is trace-instrumented");
            assertInstanceOf(TraceEmittingAgent.class, runtime.agent(a.agentId()).orElseThrow());

            AgentResponse response = a.execute(AgentTask.of("review this diff"));
            assertTrue(response.isSuccess(), () -> "" + response.failureReason());

            List<TraceSpan> spans = traces.findByRunId(response.taskId());
            assertFalse(spans.isEmpty(), "a trace was emitted for the run");

            TraceSpan root = spans.get(0);
            assertEquals(a.agentId().value() + "#run", root.spanId());
            assertInstanceOf(SpanStatus.Completed.class, root.status());
            assertEquals("review this diff",
                    new String(blobs.get(root.promptRef()).orElseThrow(), StandardCharsets.UTF_8));
            assertEquals("LGTM",
                    new String(blobs.get(root.outputRef()).orElseThrow(), StandardCharsets.UTF_8));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void noEmissionByDefault_agentIsNotWrapped() {
        ScriptedLlmClient llm = ScriptedLlmClient.script().thenFinalAnswer("ok").build();
        AraRuntime runtime = AraRuntime.builder().llmClient(llm).build();
        try {
            AraAgent a = agent(runtime, "react");
            assertFalse(a instanceof TraceEmittingAgent, "no trace wrapper without traceEmission(...)");
            assertTrue(a.execute(AgentTask.of("hi")).isSuccess());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void theTraceWrapperStillForwardsHotReconfigurationAndSessionControl() {
        TraceStore traces = TraceStore.inMemory();
        BlobStore blobs = BlobStore.inMemory();
        ScriptedLlmClient llm = ScriptedLlmClient.script()
                .thenFinalAnswer("v1").thenFinalAnswer("v2").build();

        AraRuntime runtime = AraRuntime.builder().llmClient(llm).traceEmission(traces, blobs).build();
        try {
            AraAgent a = agent(runtime, "react");
            AgentResponse first = a.execute(AgentTask.of("first"));
            assertFalse(traces.findByRunId(first.taskId()).isEmpty());

            // hot reconfiguration must pass through TraceEmittingAgent -> AgentInstance
            runtime.reconfigureAgent(a.agentId(),
                    cfg -> AgentConfig.defaults().agentId(cfg.agentId())
                            .agentType("reviewer-v2").primaryLlm(LlmProfile.of("stub"))
                            .plannerStrategy("react").build());
            assertEquals("reviewer-v2", runtime.agent(a.agentId()).orElseThrow().config().agentType());

            // session control also passes through (no exception)
            runtime.conversationHistory(a.agentId(), io.ara.core.agent.SessionId.of("s1"));

            AgentResponse second = a.execute(AgentTask.of("second"));
            assertFalse(traces.findByRunId(second.taskId()).isEmpty(), "traces keep flowing after reconfigure");
        } finally {
            runtime.stop();
        }
    }
}
