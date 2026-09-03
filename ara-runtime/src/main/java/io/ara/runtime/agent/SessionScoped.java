package io.ara.runtime.agent;

import io.ara.core.agent.SessionId;

/**
 * Implemented by agents that manage per-session lifecycle: cancelling an in-flight task
 * on one session, dropping a session's resources immediately, and reporting how many
 * sessions are currently held open (ADR-016).
 *
 * <p>Bundled as one interface rather than three: every implementation found in this
 * codebase that supports any of these three operations supports all three together
 * ({@link AgentInstance}, and {@link ContractEnforcingAgent} forwarding to whatever it
 * wraps) — splitting further would segregate by a client need nothing here actually has.
 *
 * <p>Decorators over an {@link io.ara.core.agent.AraAgent} (e.g. {@code
 * ContractEnforcingAgent}) must implement this interface by delegating to the wrapped
 * agent, so session control stays reachable through {@code AraRuntime} no matter how
 * many layers wrap the underlying {@link AgentInstance} — same pattern as {@link
 * SessionHistoryAware}/{@link RunStateAware}/{@link UserMemoryAware}, for the same
 * reason: {@code AgentFactory} registers the decorator, not the raw instance, whenever a
 * non-empty contract is supplied, so an {@code instanceof AgentInstance} check at a call
 * site would be a bug.
 *
 * <p>An agent with no session concept of its own (e.g. {@code GraphAgent}, a {@code
 * ParallelAgent} fan-out, or any pipeline step) simply does not implement this interface.
 */
public interface SessionScoped {

    /**
     * Cancels the in-flight task on a single {@code sessionId} without terminating the
     * agent or affecting other sessions (ADR-016). Cooperative: the running task stops
     * at its next iteration boundary, and future tasks on the same session (and on all
     * other sessions) keep working normally.
     *
     * @param sessionId the session whose current task should be cancelled
     */
    void terminate(SessionId sessionId);

    /**
     * Immediately removes {@code sessionId}'s in-memory session — conversation history,
     * working memory, and any wiring lease it holds — releasing its resources right away
     * rather than waiting for the TTL sweeper.
     *
     * <p>Unlike {@link #terminate(SessionId)}, which only requests cooperative
     * cancellation of a task already in flight and leaves an idle session untouched,
     * this call always drops the session, in-flight task or not. A caller that wants
     * both — stop the current task <em>and</em> free the session immediately — must
     * call {@link #terminate(SessionId)} first.
     *
     * @param sessionId the session to remove
     */
    void invalidateSession(SessionId sessionId);

    /**
     * Returns how many sessions are currently held open for this agent (ADR-016) — an
     * upper bound, not an instantaneous exact count: a session idle past its TTL is
     * still counted here until the next sweep evicts it.
     *
     * @return the number of currently tracked sessions; never negative
     */
    int activeSessionCount();

    /**
     * Requests cooperative cancellation of the in-flight task on <em>every</em> session
     * of this agent, without terminating the agent (ADR-0069 D4, kill switch). Idle
     * sessions and future tasks are untouched; each running task stops at its next
     * iteration boundary, exactly like {@link #terminate(SessionId)} for one session.
     */
    void cancelAllSessions();
}
