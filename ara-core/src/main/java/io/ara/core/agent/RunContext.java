package io.ara.core.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Flow state of the current request, shared by reference by every agent involved in a
 * delegation chain (A → B → C via {@code delegate_task}) — ADR-041 rev. 2.
 *
 * <p>Created once by the top-level caller of {@code agent.execute(task)}; never rebuilt
 * at a delegation hop. {@link io.ara.core.tool.AraTool}s that delegate (e.g.
 * {@code AgentDelegationTool}) pass this same instance to the sub-task instead of
 * copying its contents into a new structure — because it is immutable, sharing the
 * reference across the delegation chain (and across the virtual threads
 * {@code ReactStrategy.dispatchParallel} spawns for concurrent tool calls) is safe: no
 * agent ever mutates a {@code RunContext} it did not create.
 *
 * <p>Two channels, kept strictly separate — never merged into one generic map:
 * <ul>
 *   <li>{@code promptVars} — {@code String} values a {@code PromptShaper} may resolve
 *       into the system prompt (e.g. {@code {{full_name}}}).</li>
 *   <li>{@code opaque} — arbitrary Java objects (e.g. a {@code SecurityContext}) that
 *       must never be automatically interpolated into a prompt. A {@code PromptShaper}
 *       that wants to derive prompt text from an opaque value must do so explicitly.</li>
 * </ul>
 * A single generic {@code Map<String, Object>} would let a naive {@code PromptShaper} —
 * one that blindly iterates every entry and substitutes it into the prompt — leak a
 * {@code SecurityContext}'s string form straight into the LLM's context window. Keeping
 * {@code opaque} in its own typed channel makes that structurally impossible, not just
 * a matter of careful shaper code.
 *
 * <p>A third channel, {@link #state()}, carries a mutable, session-scoped {@link
 * RunState} — the ara equivalent of Agno's {@code session_state}. It follows the same
 * leak-safety rule as {@code opaque}: never auto-interpolated into a prompt, only
 * surfaced through an explicit, visible {@code PromptShaper} step. Unlike {@code
 * promptVars}/{@code opaque}, {@code state} is not copied by the {@code withX} methods
 * below — those thread the same {@code RunState} reference through unchanged, since the
 * whole point is that it stays mutable and shared for the life of the session.
 *
 * <p>A fourth channel, {@link #userMemory()} (ADR-043 rev. 3), is a second {@code
 * RunState} — same shape, same leak-safety rule, same not-copied-by-withX treatment as
 * {@code state} — but scoped to {@link AgentTask#userId()} instead of {@link
 * AgentTask#sessionId()}, so it survives across a user's many sessions instead of just
 * one. Deliberately a distinct instance from {@code state}, not the same {@code RunState}
 * viewed two ways: a delegated sub-agent's visibility into {@code state} is governed by
 * {@code DelegateStateAccess}, while {@code userMemory} is not touched by delegation at
 * all (out of scope for that ADR revision) — conflating the two channels would make that
 * distinction impossible to express.
 *
 * <p>Deliberately excludes a correlation id: {@link AgentTask#correlationId()} already
 * exists for "link this task to a broader multi-agent chain" — duplicating it here
 * would just be two fields answering the same question.
 *
 * <p>What does NOT belong here: per-agent instance configuration (ADR-036,
 * {@code AgentInstanceContext}/{@code InstanceContextStore}) — that is a separate axis
 * that never flows to a delegate. Each agent resolves its own instance parameters from
 * its own store, regardless of what {@code RunContext} it was handed.
 */
public record RunContext(
        Map<String, String> promptVars,
        Map<String, Object> opaque,
        RunState             state,
        RunState             userMemory
) {

    /**
     * {@link #opaque()} key under which the caller's current authorization scopes
     * (an {@link io.ara.core.auth.ScopeSet}) travel across a delegation hop (ADR-0077 D2).
     * Reusing the opaque channel rather than a fifth record field is deliberate: scopes
     * are exactly the "an opaque object such as a {@code SecurityContext}" this channel's
     * javadoc already anticipates, not a genuinely orthogonal lifecycle like
     * {@code state}/{@code userMemory}. {@code AgentDelegationTool} narrows the value
     * ({@code incoming ∩ ownGrantedScopes}) at every hop; nothing sets it today, so the
     * narrowing is inert until an upstream mechanism populates it.
     */
    public static final String SCOPES_KEY = "io.ara.auth.scopes";

    /**
     * {@link #opaque()} key under which the caller's full {@link io.ara.core.auth.ExecutionContext}
     * (ADR-033 Fase 5) travels across a delegation hop — a strict superset of {@link
     * #SCOPES_KEY}: where that key carries only a bare {@code ScopeSet} for the current
     * hop, this one also carries the subject identity (on-behalf-of, Fase 6) across the
     * *whole* chain, not just one hop. {@code AgentDelegationTool} reads and re-attenuates
     * this value exactly as it already does for {@link #SCOPES_KEY}, and sets both on the
     * outgoing message when an incoming {@code ExecutionContext} is present. Absent for
     * every task built before Fase 5 existed — reading it back {@code null} is the normal,
     * zero-behavior-change case, not an error.
     */
    public static final String EXECUTION_CONTEXT_KEY = "io.ara.auth.executionContext";

    public RunContext {
        promptVars = Map.copyOf(Objects.requireNonNullElse(promptVars, Map.of()));
        opaque     = Map.copyOf(Objects.requireNonNullElse(opaque, Map.of()));
        state      = Objects.requireNonNullElse(state, RunState.noop());
        userMemory = Objects.requireNonNullElse(userMemory, RunState.noop());
    }

    /**
     * Convenience constructor for callers that don't set {@code userMemory} — defaults it
     * to {@link RunState#noop()}, same as the compact constructor would for {@code null}.
     */
    public RunContext(Map<String, String> promptVars, Map<String, Object> opaque, RunState state) {
        this(promptVars, opaque, state, RunState.noop());
    }

    /** A {@code RunContext} carrying no flow state — the default for a task with no caller state. */
    public static RunContext empty() {
        return new RunContext(Map.of(), Map.of(), RunState.noop(), RunState.noop());
    }

    /** Returns the prompt variable stored under {@code key}; {@code null} if absent. */
    public String promptVar(String key) {
        return promptVars.get(key);
    }

    /** Returns the prompt variable stored under {@code key}, or {@code fallback} if absent. */
    public String promptVar(String key, String fallback) {
        return promptVars.getOrDefault(key, fallback);
    }

    /** Returns the opaque value stored under {@code key}, cast to {@code type}; {@code null} if absent. */
    public <T> T opaque(String key, Class<T> type) {
        Object v = opaque.get(key);
        return v != null ? type.cast(v) : null;
    }

    /**
     * Returns a new {@code RunContext} with {@code key} added/replaced in {@code
     * promptVars}. Does not mutate this instance — a delegate that wants to add flow
     * state going forward creates a derived {@code RunContext} rather than mutating the
     * one it received.
     */
    public RunContext withPromptVar(String key, String value) {
        Objects.requireNonNull(key, "key must not be null");
        // Removing an absent key would otherwise cost two full map copies (the HashMap
        // here, then Map.copyOf in the compact constructor) to produce an equal value.
        if (value == null && !promptVars.containsKey(key)) return this;
        Map<String, String> merged = new HashMap<>(promptVars);
        if (value == null) merged.remove(key); else merged.put(key, value);
        return new RunContext(merged, opaque, state, userMemory);
    }

    /** Returns a new RunContext with all entries of {@code additions} merged into promptVars. */
    public RunContext withPromptVars(Map<String, String> additions) {
        Objects.requireNonNull(additions, "additions must not be null");
        if (additions.isEmpty()) return this;
        Map<String, String> merged = new HashMap<>(promptVars);
        merged.putAll(additions);
        return new RunContext(merged, opaque, state, userMemory);
    }

    /**
     * Returns a new {@code RunContext} with {@code key} added/replaced in {@code
     * opaque}. {@code value == null} removes the key instead of inserting a null value.
     */
    public RunContext withOpaque(String key, Object value) {
        Objects.requireNonNull(key, "key must not be null");
        if (value == null && !opaque.containsKey(key)) return this;
        Map<String, Object> merged = new HashMap<>(opaque);
        if (value == null) merged.remove(key); else merged.put(key, value);
        return new RunContext(promptVars, merged, state, userMemory);
    }

    /**
     * Returns a new {@code RunContext} carrying {@code newState} instead of this one's.
     * Used by {@code AgentInstance} to bind a session's {@link RunState} onto a fresh
     * task, and by {@code AgentDelegationTool} to enforce {@link DelegateStateAccess}
     * on the state a delegated sub-task receives.
     */
    public RunContext withState(RunState newState) {
        Objects.requireNonNull(newState, "state must not be null");
        return new RunContext(promptVars, opaque, newState, userMemory);
    }

    /**
     * Returns a new {@code RunContext} carrying {@code newUserMemory} instead of this
     * one's. Used by {@code AgentInstance} to bind a user's cross-session {@link
     * RunState} onto a fresh task when it carries a {@link AgentTask#userId()}.
     */
    public RunContext withUserMemory(RunState newUserMemory) {
        Objects.requireNonNull(newUserMemory, "userMemory must not be null");
        return new RunContext(promptVars, opaque, state, newUserMemory);
    }
}
