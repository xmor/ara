package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.common.Money;

import java.time.Duration;
import java.util.List;

/** Shared {@link AraAgent} test doubles reused across this package's tests. */
final class PipelineTestAgents {

    private PipelineTestAgents() {}

    static AraAgent echoAgent(String id, String output) {
        AgentId agentId = AgentId.of(id);
        return new AraAgent() {
            @Override public AgentId agentId() { return agentId; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.success(task.taskId(), agentId, output, 1, 0, 0, Duration.ofMillis(1), List.of());
            }
            @Override public void terminate() {}
        };
    }

    /** Like {@link #echoAgent}, but with caller-chosen token/cost figures on its response. */
    static AraAgent tokenAgent(String id, String output, int inputTokens, int outputTokens, double costUsd) {
        AgentId agentId = AgentId.of(id);
        return new AraAgent() {
            @Override public AgentId agentId() { return agentId; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.success(task.taskId(), agentId, output, 1,
                        inputTokens, outputTokens, Money.of(java.math.BigDecimal.valueOf(costUsd), "EUR"),
                        Duration.ofMillis(1), List.of());
            }
            @Override public void terminate() {}
        };
    }

    static AraAgent failingAgent(String id, String reason) {
        AgentId agentId = AgentId.of(id);
        return new AraAgent() {
            @Override public AgentId agentId() { return agentId; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.failure(task.taskId(), agentId, reason, Duration.ofMillis(1));
            }
            @Override public void terminate() {}
        };
    }

    /** Captures the full {@link AgentTask} it was invoked with; {@code volatile} since a
     *  step agent may legitimately be exercised from a different thread than the assertion. */
    static final class CapturingAgent implements AraAgent {
        final AgentId agentId;
        private final String output;
        volatile AgentTask received;

        CapturingAgent(AgentId id, String output) {
            this.agentId = id;
            this.output  = output;
        }

        String lastInput() { return received.input(); }

        @Override public AgentId agentId() { return agentId; }
        @Override public AgentConfig config() { return null; }
        @Override public AgentState currentState() { return AgentState.IDLE; }
        @Override public AgentResponse execute(AgentTask task) {
            this.received = task;
            return AgentResponse.success(task.taskId(), agentId, output, 1, 0, 0, Duration.ofMillis(1), List.of());
        }
        @Override public void terminate() {}
    }
}
