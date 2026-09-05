package io.ara.runtime.workflow;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.agent.AgentInstance;
import io.ara.runtime.interceptor.AgentInterceptorChain;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.strategy.ExecutionPlanner;

import java.util.List;
import java.util.Objects;

/**
 * Factory that wraps a {@link Workflow} as an {@link AraAgent} (ADR-052 D7) — the same
 * move {@link io.ara.runtime.pipeline.PipelineAgents} makes for {@code AgentPipeline}.
 * The returned agent is a real {@code AgentInstance} hosting a {@link WorkflowStrategy},
 * so it gets {@code AgentInstance}'s full lifecycle for free instead of the hand-rolled
 * {@code GraphAgent} this retires (see {@link WorkflowStrategy}'s Javadoc).
 *
 * <p>Minimal usage:
 * <pre>{@code
 * AraAgent workflowAgent = WorkflowAgents.of(workflow);
 * AgentResponse response = workflowAgent.execute(AgentTask.of("start the run"));
 * }</pre>
 */
public final class WorkflowAgents {

    private WorkflowAgents() {}

    /** Creates an {@link AraAgent} with an auto-generated id and default config. */
    public static AraAgent of(Workflow workflow) {
        AgentId id = AgentId.generate();
        AgentConfig config = AgentConfig.defaults()
                .agentId(id)
                .agentType("workflow")
                // Explicit rather than left at AgentConfig's own default ("react") — see
                // PipelineAgents.of(AgentPipeline) for why.
                .plannerStrategy(WorkflowStrategy.DEFAULT_STRATEGY_NAME)
                .build();
        return of(id, config, workflow);
    }

    /**
     * Creates an {@link AraAgent} with an explicit id and config. {@code
     * config.plannerStrategy()} is read but never required to be any particular value —
     * see {@link io.ara.runtime.pipeline.PipelineAgents#of(AgentId, AgentConfig, io.ara.runtime.pipeline.AgentPipeline)}.
     */
    public static AraAgent of(AgentId agentId, AgentConfig config, Workflow workflow) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(config,  "config must not be null");
        Objects.requireNonNull(workflow, "workflow must not be null");

        WorkflowStrategy strategy = new WorkflowStrategy(workflow, config.plannerStrategy());
        ExecutionPlanner planner  = ExecutionPlanner.builder().register(strategy).build();

        return new AgentInstance(
                config,
                new NoopLlmClient(),
                sessionId -> new InMemoryMemoryManager(),
                ToolRegistry.empty(),
                planner,
                new AgentInterceptorChain(List.of())
        );
    }

    /**
     * Stand-in {@link LlmClient} required by {@code AgentInstance}'s constructor — mirrors
     * {@code PipelineAgents}'s own package-private {@code NoopLlmClient}. {@link
     * WorkflowStrategy} never calls it.
     */
    static final class NoopLlmClient implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext ctx) throws LlmException {
            throw new UnsupportedOperationException(
                    "WorkflowStrategy never calls the LLM client directly — this indicates a bug if reached");
        }

        @Override
        public String providerId() {
            return "workflow";
        }
    }
}
