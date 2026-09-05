package io.ara.core.agent;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Execution sub-record of {@link AgentConfig}: how the agent executes a task.
 *
 * <p>I/O contracts ({@code InputProcessor}/{@code OutputProcessor} chains) are
 * orthogonal to this configuration — pass an explicit {@link AgentContract} to
 * {@code AgentFactory.create(config, contract)} instead of describing them here.
 */
public record ExecutionConfig(
        String                    plannerStrategy,
        StrategyConfig            strategyConfig,
        List<String>              enabledTools,
        List<String>              mcpServerIds,
        int                       maxIterations,
        Duration                  executionTimeout,
        int                       maxTokensPerStep,
        boolean                   humanApprovalRequired,
        String                    knowledgeBaseId,
        SessionBusyPolicy         sessionBusyPolicy,
        String                    retrieverId,
        DelegateStateAccess       delegateStateAccess,
        Duration                  sessionTtl,
        List<String>              grantedScopes,
        List<String>              visibleToScopes,
        List<String>              requiredScopes,
        boolean                   requiresApproval
) {
    /** Idle time after which an unused session is reclaimed, when none is configured. */
    public static final Duration DEFAULT_SESSION_TTL = Duration.ofMinutes(30);

    public ExecutionConfig {
        plannerStrategy  = Objects.requireNonNullElse(plannerStrategy, "react");
        enabledTools     = List.copyOf(Objects.requireNonNullElse(enabledTools,     List.of()));
        mcpServerIds     = List.copyOf(Objects.requireNonNullElse(mcpServerIds,     List.of()));
        grantedScopes    = List.copyOf(Objects.requireNonNullElse(grantedScopes,    List.of()));
        visibleToScopes  = List.copyOf(Objects.requireNonNullElse(visibleToScopes,  List.of()));
        requiredScopes   = List.copyOf(Objects.requireNonNullElse(requiredScopes,   List.of()));
        sessionBusyPolicy = Objects.requireNonNullElse(sessionBusyPolicy, SessionBusyPolicy.REJECT);
        delegateStateAccess = Objects.requireNonNullElse(delegateStateAccess, DelegateStateAccess.OVERLAY);
        sessionTtl       = Objects.requireNonNullElse(sessionTtl, DEFAULT_SESSION_TTL);
        Objects.requireNonNull(executionTimeout, "executionTimeout must not be null");
        if (maxIterations < 1)   throw new IllegalArgumentException("maxIterations must be >= 1");
        if (maxTokensPerStep < 1) throw new IllegalArgumentException("maxTokensPerStep must be >= 1");
        if (executionTimeout.isNegative() || executionTimeout.isZero())
            throw new IllegalArgumentException("executionTimeout must be positive");
        if (sessionTtl.isNegative() || sessionTtl.isZero())
            throw new IllegalArgumentException("sessionTtl must be positive");
        if (retrieverId != null && !retrieverId.isBlank() && !plannerStrategy.startsWith("rag+")) {
            throw new IllegalArgumentException(
                    "retrieverId('" + retrieverId + "') is set but plannerStrategy('" + plannerStrategy
                    + "') is not a RAG-augmented strategy (expected a name starting with 'rag+', "
                    + "e.g. 'rag+react'). Either clear retrieverId or select a rag+... strategy.");
        }
    }

    /** Backward-compatible constructor — {@code sessionBusyPolicy} defaults to {@link SessionBusyPolicy#REJECT}. */
    public ExecutionConfig(
            String plannerStrategy, StrategyConfig strategyConfig, List<String> enabledTools,
            List<String> mcpServerIds, int maxIterations, Duration executionTimeout,
            int maxTokensPerStep, boolean humanApprovalRequired, String knowledgeBaseId) {
        this(plannerStrategy, strategyConfig, enabledTools, mcpServerIds, maxIterations,
                executionTimeout, maxTokensPerStep, humanApprovalRequired, knowledgeBaseId,
                SessionBusyPolicy.REJECT, null, DelegateStateAccess.OVERLAY, DEFAULT_SESSION_TTL);
    }

    /** Backward-compatible constructor — {@code retrieverId} defaults to {@code null} (no RAG retriever selected). */
    public ExecutionConfig(
            String plannerStrategy, StrategyConfig strategyConfig, List<String> enabledTools,
            List<String> mcpServerIds, int maxIterations, Duration executionTimeout,
            int maxTokensPerStep, boolean humanApprovalRequired, String knowledgeBaseId,
            SessionBusyPolicy sessionBusyPolicy) {
        this(plannerStrategy, strategyConfig, enabledTools, mcpServerIds, maxIterations,
                executionTimeout, maxTokensPerStep, humanApprovalRequired, knowledgeBaseId,
                sessionBusyPolicy, null, DelegateStateAccess.OVERLAY, DEFAULT_SESSION_TTL);
    }

    /** Backward-compatible constructor — {@code delegateStateAccess} defaults to {@link DelegateStateAccess#OVERLAY}. */
    public ExecutionConfig(
            String plannerStrategy, StrategyConfig strategyConfig, List<String> enabledTools,
            List<String> mcpServerIds, int maxIterations, Duration executionTimeout,
            int maxTokensPerStep, boolean humanApprovalRequired, String knowledgeBaseId,
            SessionBusyPolicy sessionBusyPolicy, String retrieverId) {
        this(plannerStrategy, strategyConfig, enabledTools, mcpServerIds, maxIterations,
                executionTimeout, maxTokensPerStep, humanApprovalRequired, knowledgeBaseId,
                sessionBusyPolicy, retrieverId, DelegateStateAccess.OVERLAY, DEFAULT_SESSION_TTL);
    }

    /** Backward-compatible constructor — {@code sessionTtl} defaults to {@link #DEFAULT_SESSION_TTL}. */
    public ExecutionConfig(
            String plannerStrategy, StrategyConfig strategyConfig, List<String> enabledTools,
            List<String> mcpServerIds, int maxIterations, Duration executionTimeout,
            int maxTokensPerStep, boolean humanApprovalRequired, String knowledgeBaseId,
            SessionBusyPolicy sessionBusyPolicy, String retrieverId,
            DelegateStateAccess delegateStateAccess) {
        this(plannerStrategy, strategyConfig, enabledTools, mcpServerIds, maxIterations,
                executionTimeout, maxTokensPerStep, humanApprovalRequired, knowledgeBaseId,
                sessionBusyPolicy, retrieverId, delegateStateAccess, DEFAULT_SESSION_TTL);
    }

    /**
     * Backward-compatible constructor — the authorization scope lists (ADR-033 Fase 1)
     * all default to empty, i.e. no visibility restriction and no scopes required.
     */
    public ExecutionConfig(
            String plannerStrategy, StrategyConfig strategyConfig, List<String> enabledTools,
            List<String> mcpServerIds, int maxIterations, Duration executionTimeout,
            int maxTokensPerStep, boolean humanApprovalRequired, String knowledgeBaseId,
            SessionBusyPolicy sessionBusyPolicy, String retrieverId,
            DelegateStateAccess delegateStateAccess, Duration sessionTtl) {
        this(plannerStrategy, strategyConfig, enabledTools, mcpServerIds, maxIterations,
                executionTimeout, maxTokensPerStep, humanApprovalRequired, knowledgeBaseId,
                sessionBusyPolicy, retrieverId, delegateStateAccess, sessionTtl,
                List.of(), List.of(), List.of());
    }

    /**
     * Backward-compatible constructor (ADR-033 Fase 7) — {@code requiresApproval} defaults
     * to {@code false}: an agent gates on human approval before it can be invoked via
     * delegation only when explicitly opted in, distinct from {@link #humanApprovalRequired}
     * (which instead gates that agent's own <em>outgoing</em> tool calls, ADR-0067 D6).
     */
    public ExecutionConfig(
            String plannerStrategy, StrategyConfig strategyConfig, List<String> enabledTools,
            List<String> mcpServerIds, int maxIterations, Duration executionTimeout,
            int maxTokensPerStep, boolean humanApprovalRequired, String knowledgeBaseId,
            SessionBusyPolicy sessionBusyPolicy, String retrieverId,
            DelegateStateAccess delegateStateAccess, Duration sessionTtl,
            List<String> grantedScopes, List<String> visibleToScopes, List<String> requiredScopes) {
        this(plannerStrategy, strategyConfig, enabledTools, mcpServerIds, maxIterations,
                executionTimeout, maxTokensPerStep, humanApprovalRequired, knowledgeBaseId,
                sessionBusyPolicy, retrieverId, delegateStateAccess, sessionTtl,
                grantedScopes, visibleToScopes, requiredScopes, false);
    }

    public static ExecutionConfig defaults() {
        return new ExecutionConfig("react", null, List.of(), List.of(),
                10, Duration.ofMinutes(5), 4096, false, null, SessionBusyPolicy.REJECT, null,
                DelegateStateAccess.OVERLAY, DEFAULT_SESSION_TTL,
                List.of(), List.of(), List.of(), false);
    }
}
