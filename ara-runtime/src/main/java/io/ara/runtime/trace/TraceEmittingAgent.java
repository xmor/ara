package io.ara.runtime.trace;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.ConversationTurn;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.UserId;
import io.ara.core.common.AgentId;
import io.ara.core.trace.BlobStore;
import io.ara.core.trace.TraceSpan;
import io.ara.core.trace.TraceStore;
import io.ara.runtime.agent.Reconfigurable;
import io.ara.runtime.agent.RunStateAware;
import io.ara.runtime.agent.SessionHistoryAware;
import io.ara.runtime.agent.SessionScoped;
import io.ara.runtime.agent.UserMemoryAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Wraps an {@link AraAgent} and appends a run trace to a {@link TraceStore} after every
 * {@link #execute} — the first ADR-0068 D1 emission point, via {@link TraceProjection}.
 * Purely observational: it never changes the {@link AgentResponse}, and a failure inside
 * trace emission is swallowed (logged) so it can never break execution.
 *
 * <p>Meant to be the <em>outermost</em> decorator (wraps {@code ContractEnforcingAgent} or
 * a bare {@code AgentInstance}). It forwards the optional marker interfaces
 * ({@code SessionScoped}, {@code Reconfigurable}, {@code SessionHistoryAware},
 * {@code RunStateAware}, {@code UserMemoryAware}) to the delegate the same way
 * {@code ContractEnforcingAgent} does, so it is safe to register in the {@code AgentRegistry}
 * in place of the agent it wraps. Callers that already hold the {@link AgentResponse} can
 * skip the wrapper and call {@link #emit}.
 */
public final class TraceEmittingAgent implements AraAgent, SessionHistoryAware, RunStateAware,
                                                 UserMemoryAware, Reconfigurable, SessionScoped {

    private static final Logger log = LoggerFactory.getLogger(TraceEmittingAgent.class);

    private final AraAgent delegate;
    private final TraceStore traces;
    private final BlobStore blobs;

    public TraceEmittingAgent(AraAgent delegate, TraceStore traces, BlobStore blobs) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.traces   = Objects.requireNonNull(traces, "traces must not be null");
        this.blobs    = Objects.requireNonNull(blobs, "blobs must not be null");
    }

    @Override
    public AgentResponse execute(AgentTask task) {
        AgentResponse response;
        try {
            response = delegate.execute(task);
        } catch (RuntimeException thrown) {
            safeAppend(List.of(TraceProjection.failedByException(task, delegate.agentId().value(), thrown, blobs)));
            throw thrown;
        }
        safeAppend(safeProject(task, response));
        return response;
    }

    /**
     * Appends the trace for one already-completed execution — for callers that hold the
     * {@link AgentResponse} without wrapping the agent.
     */
    public static void emit(AgentTask task, AgentResponse response, TraceStore traces, BlobStore blobs) {
        Objects.requireNonNull(traces, "traces must not be null");
        try {
            TraceProjection.project(task, response, blobs).forEach(traces::append);
        } catch (RuntimeException e) {
            log.warn("trace emission failed for task {}: {}", response.taskId(), e.getMessage());
        }
    }

    private List<TraceSpan> safeProject(AgentTask task, AgentResponse response) {
        try {
            return TraceProjection.project(task, response, blobs);
        } catch (RuntimeException e) {
            log.warn("trace projection failed for task {}: {}", response.taskId(), e.getMessage());
            return List.of();
        }
    }

    private void safeAppend(List<TraceSpan> spans) {
        for (TraceSpan span : spans) {
            try {
                traces.append(span);
            } catch (RuntimeException e) {
                log.warn("trace append failed for span {}: {}", span.spanId(), e.getMessage());
            }
        }
    }

    @Override public AgentId agentId() { return delegate.agentId(); }
    @Override public AgentConfig config() { return delegate.config(); }
    @Override public AgentState currentState() { return delegate.currentState(); }
    @Override public void terminate() { delegate.terminate(); }

    // ── optional marker interfaces forwarded to the delegate (same pattern as ContractEnforcingAgent) ──

    @Override
    public List<ConversationTurn> conversationHistory(SessionId sessionId) {
        return delegate instanceof SessionHistoryAware h ? h.conversationHistory(sessionId) : List.of();
    }

    @Override
    public Map<String, Object> sessionState(SessionId sessionId) {
        return delegate instanceof RunStateAware r ? r.sessionState(sessionId) : Map.of();
    }

    @Override
    public Map<String, Object> userMemory(UserId userId) {
        return delegate instanceof UserMemoryAware u ? u.userMemory(userId) : Map.of();
    }

    @Override
    public void terminate(SessionId sessionId) {
        if (delegate instanceof SessionScoped s) s.terminate(sessionId);
    }

    @Override
    public void invalidateSession(SessionId sessionId) {
        if (delegate instanceof SessionScoped s) s.invalidateSession(sessionId);
    }

    @Override
    public int activeSessionCount() {
        return delegate instanceof SessionScoped s ? s.activeSessionCount() : 0;
    }

    @Override
    public void cancelAllSessions() {
        if (delegate instanceof SessionScoped s) s.cancelAllSessions();
    }

    @Override
    public void reconfigure(AgentConfig newConfig) {
        if (delegate instanceof Reconfigurable r) {
            r.reconfigure(newConfig);
        } else {
            throw new UnsupportedOperationException(
                    "TraceEmittingAgent wraps an agent that does not support hot reconfiguration");
        }
    }
}
