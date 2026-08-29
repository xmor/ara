package io.ara.core.agent;

import io.ara.core.common.AgentId;
import io.ara.core.common.Money;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * An {@link AraAgent} whose work is a plain function of the task — no LLM, no strategy,
 * no planner, no tool registry, no memory.
 *
 * <p>The determinism is expressed by the constructor rather than by a flag: there is
 * nowhere to put an {@code LlmClient}, so an agent built here provably never reasons.
 * The alternative this replaces is an {@code AgentInstance} handed a stub
 * {@code LlmClient} that throws if anything ever calls it — a runtime trap standing in
 * for a compile-time guarantee.
 *
 * <p>Typical members: a renderer producing a report from a template, a normaliser, a
 * validator, a rule-based classifier, an adapter over an existing service. Anything
 * whose answer is computed rather than generated.
 *
 * <p><strong>Reported usage is zero, not absent.</strong> {@link AgentResponse} requires
 * {@code iterationsUsed}/{@code inputTokens}/{@code outputTokens}/{@code estimatedCost}
 * from every agent, so a deterministic run is indistinguishable from a reasoning run
 * that happened to spend nothing. Making that distinction representable means changing
 * the response type itself, which is a separate, wider change than this class.
 *
 * <p><strong>Capabilities are not included.</strong> This agent has no per-session
 * isolation, no busy policy, no cancellation, no telemetry span and no interceptor
 * chain — those live in {@code AgentInstance} and are, for now, inseparable from the
 * reasoning loop. A deterministic unit that needs them still has to be hosted as an
 * {@code AgentInstance} today. Use this class for work that genuinely needs none of
 * them; it is not a drop-in replacement for a hosted agent.
 *
 * <p>{@link #currentState()} always reports {@link AgentState#IDLE} and {@link
 * #terminate()} is a no-op: this agent owns no lifecycle, and gating concurrent {@link
 * #execute} calls behind a shared state field would introduce a concurrency bug rather
 * than prevent one — the same reasoning {@code ParallelAgent} documents. Thread safety
 * is therefore entirely the supplied function's own responsibility.
 *
 * <p>Usage:
 * <pre>{@code
 * AraAgent renderer = AraAgents.deterministic(AgentId.of("renderer"),
 *         task -> engine.render(template, task.input()));
 * }</pre>
 */
public final class FunctionAgent implements AraAgent {

    /** {@code agentType} and {@code plannerStrategy} of the config this class builds when none is supplied. */
    public static final String AGENT_TYPE = "deterministic";

    private final AgentId                           agentId;
    private final AgentConfig                       config;
    private final Function<AgentTask, AgentResponse> body;

    /**
     * @param agentId this agent's identity — also the {@code agentId} on every response it produces
     * @param config  its configuration; {@code agentId} is not cross-checked against
     *                {@code config.agentId()}, the same convention {@code ParallelAgent}
     *                and {@code PipelineAgents} already use
     * @param body    the work; receives the task and returns the full response, so a caller
     *                that has real figures to report (an aggregate over sub-agents, a
     *                measured cost) can set them. Prefer {@link
     *                AraAgents#deterministic(AgentId, Function)} when the work simply
     *                produces text.
     */
    public FunctionAgent(AgentId agentId, AgentConfig config, Function<AgentTask, AgentResponse> body) {
        this.agentId = Objects.requireNonNull(agentId, "agentId must not be null");
        this.config  = Objects.requireNonNull(config,  "config must not be null");
        this.body    = Objects.requireNonNull(body,    "body must not be null");
    }

    /**
     * The {@link AgentConfig} this class builds for an agent whose caller supplied none:
     * {@code agentType} and {@code plannerStrategy} both set to {@value #AGENT_TYPE}.
     *
     * <p>{@code plannerStrategy} is set explicitly rather than left at {@code
     * AgentConfig}'s own default of {@code "react"} — nothing here plans or reasons, and
     * an {@code AgentCard} advertising {@code react} for an agent with no LLM would be a
     * plain lie to whoever reads the registry.
     */
    public static AgentConfig defaultConfig(AgentId agentId) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        return AgentConfig.defaults()
                .agentId(agentId)
                .agentType(AGENT_TYPE)
                .plannerStrategy(AGENT_TYPE)
                .build();
    }

    @Override public AgentId     agentId()      { return agentId; }
    @Override public AgentConfig config()       { return config; }
    @Override public AgentState  currentState() { return AgentState.IDLE; }

    /** No-op: this agent holds no session, no thread and no resource to release. */
    @Override public void terminate() {}

    /**
     * Applies the function to {@code task}.
     *
     * <p>A function that throws produces a failed {@link AgentResponse} rather than
     * propagating: deterministic does not mean infallible — a regex over an unexpected
     * shape, a template that cannot render, an adapter whose backend is down — and every
     * caller of {@code execute} (pipeline steps, the message bus, the registry) is
     * written against a returned failure, not a thrown one.
     */
    @Override
    public AgentResponse execute(AgentTask task) {
        Objects.requireNonNull(task, "task must not be null");
        Instant start = Instant.now();
        try {
            AgentResponse response = body.apply(task);
            if (response == null) {
                return AgentResponse.failure(task.taskId(), agentId,
                        "Deterministic agent returned a null response", Duration.between(start, Instant.now()));
            }
            return response;
        } catch (RuntimeException e) {
            String message = (e.getMessage() == null) ? e.toString() : e.getMessage();
            return AgentResponse.failure(task.taskId(), agentId,
                    "Deterministic agent failed: " + message, Duration.between(start, Instant.now()));
        }
    }

    /**
     * Wraps a text-producing function into one returning a full {@link AgentResponse},
     * timing it and reporting one iteration and zero usage. The bridge behind {@link
     * AraAgents#deterministic(AgentId, Function)}.
     */
    static Function<AgentTask, AgentResponse> respondingWith(AgentId agentId, Function<AgentTask, String> body) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(body,    "body must not be null");
        return task -> {
            Instant start = Instant.now();
            String content = body.apply(task);
            if (content == null) {
                return AgentResponse.failure(task.taskId(), agentId,
                        "Deterministic agent returned null content", Duration.between(start, Instant.now()));
            }
            // The Money overload, explicitly: the (…, int, int, double, …) one is deprecated,
            // and 0 would widen into it silently.
            return AgentResponse.success(task.taskId(), agentId, content,
                    1, 0, 0, Money.ZERO_EUR, Duration.between(start, Instant.now()), List.of());
        };
    }
}
