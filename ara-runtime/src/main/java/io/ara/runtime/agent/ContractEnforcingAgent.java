package io.ara.runtime.agent;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentContract;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.ConversationTurn;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.UserId;
import io.ara.core.common.AgentId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Decorator that applies the {@link AgentContract} processor chains before and after
 * every {@code execute()} call, plus optional prompt shaping (ADR-014).
 *
 * <p>Execution order:
 * <ol>
 *   <li>InputProcessor chain — transforms / validates task input</li>
 *   <li>PromptShaper chain — modifies the system prompt (ADR-014)</li>
 *   <li>outputSchema enforcement — appends JSON format instructions (ADR-014)</li>
 *   <li>{@code inner.execute()} — via {@code PromptOverridable} if shaping occurred</li>
 *   <li>OutputProcessor chain — transforms / validates the response</li>
 * </ol>
 *
 * <p>Registered in {@link AgentRegistry} in place of the raw {@code AgentInstance},
 * so any caller — direct Java or {@link io.ara.core.bus.MessageBus} — always
 * passes through the contract enforcement.
 */
public final class ContractEnforcingAgent implements AraAgent, SessionHistoryAware, RunStateAware, UserMemoryAware,
                                                      Reconfigurable, SessionScoped {

    private final AraAgent      inner;
    private final AgentContract contract;

    public ContractEnforcingAgent(AraAgent inner, AgentContract contract) {
        this.inner    = Objects.requireNonNull(inner,    "inner must not be null");
        this.contract = Objects.requireNonNull(contract, "contract must not be null");
    }

    @Override
    public AgentResponse execute(AgentTask task) {
        Objects.requireNonNull(task, "task must not be null");
        return ContractEnforcer.apply(contract, inner, task, Instant.now());
    }

    /** Delegates to the wrapped agent; empty if it does not record session history. */
    @Override
    public List<ConversationTurn> conversationHistory(SessionId sessionId) {
        return inner instanceof SessionHistoryAware h
                ? h.conversationHistory(sessionId)
                : List.of();
    }

    /** Delegates to the wrapped agent; empty if it does not expose session state. */
    @Override
    public Map<String, Object> sessionState(SessionId sessionId) {
        return inner instanceof RunStateAware r ? r.sessionState(sessionId) : Map.of();
    }

    /** Delegates to the wrapped agent; empty if it does not expose cross-session user memory. */
    @Override
    public Map<String, Object> userMemory(UserId userId) {
        return inner instanceof UserMemoryAware u ? u.userMemory(userId) : Map.of();
    }

    @Override public AgentId    agentId()      { return inner.agentId(); }
    @Override public AgentConfig config()      { return inner.config(); }
    @Override public AgentState currentState() { return inner.currentState(); }
    @Override public void       terminate()    { inner.terminate(); }

    /** Delegates to the wrapped agent; no-op if it does not manage sessions. */
    @Override
    public void terminate(SessionId sessionId) {
        if (inner instanceof SessionScoped s) s.terminate(sessionId);
    }

    /** Delegates to the wrapped agent; no-op if it does not manage sessions. */
    @Override
    public void invalidateSession(SessionId sessionId) {
        if (inner instanceof SessionScoped s) s.invalidateSession(sessionId);
    }

    /** Delegates to the wrapped agent; {@code 0} if it does not manage sessions. */
    @Override
    public int activeSessionCount() {
        return inner instanceof SessionScoped s ? s.activeSessionCount() : 0;
    }

    @Override
    public void cancelAllSessions() {
        if (inner instanceof SessionScoped s) s.cancelAllSessions();
    }

    /**
     * Delegates to the wrapped agent. In current usage {@code inner} is always the
     * {@code AgentInstance} this decorator was built together with by {@code
     * AgentFactory}, which always supports hot reconfiguration — the {@code instanceof}
     * check exists for whatever {@link AraAgent} a future caller might wrap directly.
     *
     * @throws UnsupportedOperationException if the wrapped agent does not support it
     */
    @Override
    public void reconfigure(AgentConfig newConfig) {
        if (inner instanceof Reconfigurable r) {
            r.reconfigure(newConfig);
        } else {
            throw new UnsupportedOperationException(
                    "ContractEnforcingAgent wraps an agent that does not support hot reconfiguration");
        }
    }
}
