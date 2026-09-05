package io.ara.runtime.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AgentView;
import io.ara.core.agent.DelegateStateAccess;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.RunState;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;
import io.ara.core.auth.ExecutionContext;
import io.ara.core.auth.ScopeSet;
import io.ara.core.bus.AgentMessage;
import io.ara.core.bus.MessageBus;
import io.ara.core.common.AgentId;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolResult;

import java.time.Duration;
import java.util.Objects;

/**
 * {@link AraTool} that lets an agent delegate a sub-task to a peer agent on the
 * same node via the {@link MessageBus}.
 *
 * <p>When the LLM decides to call this tool the ReAct loop will:
 * <ol>
 *   <li>Parse {@code agent_id} and {@code task} from the JSON arguments.</li>
 *   <li>Call {@link MessageBus#request} — which blocks the virtual thread until
 *       the target agent returns its answer.</li>
 *   <li>Return the answer as an {@code Observation} to the LLM.</li>
 * </ol>
 *
 * <h2>Usage in a system prompt</h2>
 * <pre>
 *   To delegate work to another agent call:
 *   {"tool_id":"delegate_task","arguments":{"agent_id":"writer-0","task":"Summarise: ..."}}
 * </pre>
 *
 * <h2>Error handling</h2>
 * <ul>
 *   <li>Agent not found → {@link ToolResult#failure} with a descriptive message.</li>
 *   <li>Agent busy (not IDLE) → {@link ToolResult#failure}.</li>
 *   <li>Timeout (default {@value #DEFAULT_TIMEOUT_SEC}s) → {@link ToolResult#failure}.</li>
 *   <li>Target agent's task failed → success=true with the failure content
 *       so the LLM can reason about it.</li>
 * </ul>
 *
 * <h2>Adding to an agent</h2>
 * <p>Include {@code "delegate_task"} in the agent's {@code enabledTools} list and
 * ensure the {@link io.ara.core.tool.ToolRegistry} in use contains this tool.  The simplest way is
 * to wrap an existing registry with {@link DelegatingToolRegistry}:
 * <pre>{@code
 * ToolRegistry registry = new DelegatingToolRegistry(baseRegistry, messageBus, selfAgentId);
 * }</pre>
 */
public final class AgentDelegationTool implements AraTool {

    public static final String TOOL_ID = "delegate_task";
    /** Default reply timeout, used when no explicit {@link Duration} is configured. */
    public static final int DEFAULT_TIMEOUT_SEC = 120;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MessageBus          bus;
    private final String              selfId;
    private final Duration            timeout;
    private final DelegateStateAccess stateAccess;
    private final SessionStore        sessionStore;
    private final ScopeSet            ownGrantedScopes;
    private final AgentView           agentView;

    /**
     * @param bus    the local message bus
     * @param selfId the id of the agent that owns this tool instance
     *               (used as {@code senderId} in the outgoing message)
     */
    public AgentDelegationTool(MessageBus bus, String selfId) {
        this(bus, selfId, Duration.ofSeconds(DEFAULT_TIMEOUT_SEC), DelegateStateAccess.OVERLAY);
    }

    /**
     * @param bus     the local message bus
     * @param selfId  the id of the agent that owns this tool instance
     * @param timeout maximum time to wait for the delegate agent's reply
     */
    public AgentDelegationTool(MessageBus bus, String selfId, Duration timeout) {
        this(bus, selfId, timeout, DelegateStateAccess.OVERLAY);
    }

    /**
     * @param bus         the local message bus
     * @param selfId      the id of the agent that owns this tool instance
     * @param timeout     maximum time to wait for the delegate agent's reply
     * @param stateAccess what the delegate sees of and can do to this agent's {@link RunState}
     */
    public AgentDelegationTool(MessageBus bus, String selfId, Duration timeout, DelegateStateAccess stateAccess) {
        this(bus, selfId, timeout, stateAccess, SessionStore.noop());
    }

    /**
     * @param bus          the local message bus
     * @param selfId       the id of the agent that owns this tool instance
     * @param timeout      maximum time to wait for the delegate agent's reply
     * @param stateAccess  what the delegate sees of and can do to this agent's {@link RunState}
     * @param sessionStore used to back the delegate's own {@link RunState} layer with
     *                     {@link RunState#persisting} under its derived session (see
     *                     {@link #delegateSessionId}) instead of a throwaway in-memory
     *                     one, whenever the caller itself has a real {@link SessionId}
     */
    public AgentDelegationTool(MessageBus bus, String selfId, Duration timeout, DelegateStateAccess stateAccess,
                                SessionStore sessionStore) {
        this(bus, selfId, timeout, stateAccess, sessionStore, ScopeSet.EMPTY);
    }

    /**
     * @param ownGrantedScopes this agent's own granted scopes (ADR-0077 D2). Across a
     *                         delegation hop the scope set handed to the delegate is
     *                         {@code incoming ∩ ownGrantedScopes} — never wider than the
     *                         caller, never wider than this agent. Defaults to
     *                         {@link ScopeSet#EMPTY}; while nothing upstream sets the
     *                         {@link RunContext#SCOPES_KEY} value the narrowing is inert.
     */
    public AgentDelegationTool(MessageBus bus, String selfId, Duration timeout, DelegateStateAccess stateAccess,
                                SessionStore sessionStore, ScopeSet ownGrantedScopes) {
        this(bus, selfId, timeout, stateAccess, sessionStore, ownGrantedScopes, null);
    }

    /**
     * @param agentView (ADR-033 Fase 3 §3.3) checked before every delegation attempt: a
     *                  recipient {@link AgentView#findById} cannot find — not visible to
     *                  this agent's scopes, or not authorized to invoke, or simply not
     *                  registered — fails the tool call before {@code bus.request} is ever
     *                  attempted. {@code null} (the default on every shorter constructor)
     *                  skips this pre-check entirely — today's behavior, where visibility
     *                  is never verified and only {@link LocalMessageBus}'s own {@code
     *                  requiredScopes} check (the dispatch-time recipient-scope check,
     *                  ADR-033) applies at actual dispatch.
     */
    public AgentDelegationTool(MessageBus bus, String selfId, Duration timeout, DelegateStateAccess stateAccess,
                                SessionStore sessionStore, ScopeSet ownGrantedScopes, AgentView agentView) {
        this.bus              = Objects.requireNonNull(bus,    "bus must not be null");
        this.selfId           = Objects.requireNonNull(selfId, "selfId must not be null");
        this.timeout          = Objects.requireNonNull(timeout, "timeout must not be null");
        this.stateAccess      = Objects.requireNonNull(stateAccess, "stateAccess must not be null");
        this.sessionStore     = Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        this.ownGrantedScopes = Objects.requireNonNullElse(ownGrantedScopes, ScopeSet.EMPTY);
        this.agentView        = agentView;
    }

    @Override
    public String toolId() {
        return TOOL_ID;
    }

    @Override
    public String description() {
        return "Delegates a sub-task to another agent on the same node and returns its answer. "
                + "Use to decompose complex work across specialised peer agents. "
                + "The target agent must be registered and idle.";
    }

    @Override
    public String argumentSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "agent_id": {
                      "type": "string",
                      "description": "ID of the target agent to delegate to"
                    },
                    "task": {
                      "type": "string",
                      "description": "The sub-task to execute on the target agent"
                    }
                  },
                  "required": ["agent_id", "task"]
                }""";
    }

    @Override
    public ToolResult execute(String argumentJson) {
        return delegate(argumentJson, RunContext.empty(), null);
    }

    /**
     * Delegation with access to the calling agent's {@link AgentTask}. Propagates the
     * caller's {@link AgentTask#runContext()} (ADR-041 rev. 2) — e.g. the {@code
     * SecurityContext} and the prompt-shaper variables — so the delegated sub-task runs
     * with the same context, and the same authorization, as its caller. Without this the
     * recipient would start empty: any tool requiring the {@code SecurityContext} would
     * fail (or, worse, run unrestricted), and a contract that substitutes prompt
     * variables would see them unresolved. {@code promptVars}/{@code opaque} are always
     * passed through unchanged (immutable); the mutable {@link RunState} channel is
     * instead transformed per {@link #stateAccess} — see {@link #delegate}.
     */
    @Override
    public ToolResult execute(String argumentJson, AgentTask task) {
        RunContext callerContext   = task != null ? task.runContext() : RunContext.empty();
        SessionId  callerSessionId = task != null ? task.sessionId() : null;
        return delegate(argumentJson, callerContext, callerSessionId);
    }

    /**
     * Deterministically derives the session the delegate should run under, from the
     * caller's own session and the recipient's id. {@code null} in ({@code
     * callerSessionId} not set — the caller itself has no real session to anchor to)
     * means {@code null} out: the delegate falls back to {@link SessionId#ephemeral()}
     * exactly as it did before session propagation existed. When non-null, the
     * derivation is namespaced per recipient so repeated delegations to the same peer
     * accumulate state/history in one place without colliding with the recipient's own
     * directly-invoked sessions or with delegations to other peers; it composes
     * naturally across delegation chains (A→B→C).
     */
    static SessionId delegateSessionId(SessionId callerSessionId, String recipientId) {
        return callerSessionId != null
                ? SessionId.of(callerSessionId.value() + "::deleg::" + recipientId)
                : null;
    }

    /** The target agent and sub-task parsed from a {@code delegate_task} call's argument JSON. */
    public record DelegationRequest(String recipientAgentId, String task) {}

    /**
     * Parses {@code argumentJson}'s {@code agent_id}/{@code task} fields — the same
     * parsing {@link #delegate} does internally, exposed here so {@code
     * InterceptingToolRegistry} can recognise a {@code delegate_task} call and report it
     * to interceptors ({@code onDelegate}/{@code onDelegateReturn}) with the recipient
     * and sub-task already extracted, instead of re-implementing this parsing itself.
     *
     * @throws RuntimeException if {@code argumentJson} is not a valid {@code
     *         {"agent_id":...,"task":...}} payload
     */
    public static DelegationRequest parseDelegation(String argumentJson) {
        JsonNode args = uncheckedReadTree(argumentJson);
        return new DelegationRequest(args.get("agent_id").asText(), args.get("task").asText());
    }

    private static JsonNode uncheckedReadTree(String argumentJson) {
        try {
            return MAPPER.readTree(argumentJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private ToolResult delegate(String argumentJson, RunContext callerContext, SessionId callerSessionId) {
        String recipientId;
        String taskContent;
        try {
            DelegationRequest parsed = parseDelegation(argumentJson);
            recipientId = parsed.recipientAgentId();
            taskContent = parsed.task();
        } catch (Exception e) {
            return ToolResult.failure(TOOL_ID, "Invalid arguments: " + e.getMessage());
        }

        if (selfId.equals(recipientId)) {
            return ToolResult.failure(TOOL_ID,
                    "Self-delegation is not allowed (agent_id = " + selfId + ")");
        }

        // ADR-033 §3.3: a discovery-time gate, ahead of bus.request's own
        // dispatch-time authorization check (the recipient-scope check on the message bus
        // itself) — findById folds "not registered",
        // "not visible", and "not authorized" into one Optional, deliberately: this tool's
        // failure message doesn't need to distinguish them (LocalMessageBus's own
        // AuthorizationException, reached below if this pre-check is skipped or passes,
        // still names the specific reason when the failure is dispatch-time instead).
        if (agentView != null && agentView.findById(AgentId.of(recipientId)).isEmpty()) {
            return ToolResult.failure(TOOL_ID, "Agent not found: " + recipientId);
        }

        SessionId delegateSessionId = delegateSessionId(callerSessionId, recipientId);

        // The delegate's own RunState layer is backed by SessionStore (persisting()
        // degrades to inMemory()'s behavior when sessionStore is noop()) whenever there
        // is a real session to anchor it to; otherwise there is nothing to persist under,
        // so it stays a throwaway in-memory layer exactly like before session propagation.
        RunState ownLayer = delegateSessionId != null
                ? RunState.persisting(sessionStore, delegateSessionId)
                : RunState.inMemory();

        // Enforce this agent's DelegateStateAccess policy on the outgoing RunState:
        // SHARED forwards the same reference (today's behavior); OVERLAY gives the
        // delegate read-through to our state with writes confined to its own private
        // layer; ISOLATED starts the delegate with nothing of ours.
        RunContext outgoing = switch (stateAccess) {
            case SHARED   -> callerContext;
            case OVERLAY  -> callerContext.withState(RunState.overlay(callerContext.state(), ownLayer));
            case ISOLATED -> callerContext.withState(ownLayer);
        };

        // ADR-0077 D2/D3: non-escalation of permissions across the delegation hop.
        // effectiveScopes = incoming ∩ ownGrantedScopes — an intersection, so the result
        // can never be wider than the caller's scopes nor wider than this agent's, by
        // construction rather than by a runtime check. incoming is null on a first hop
        // (nothing upstream has set the key yet) — this agent's own granted scopes are
        // the effective set in that case, there being nothing yet to intersect against.
        ScopeSet incoming = callerContext.opaque(RunContext.SCOPES_KEY, ScopeSet.class);
        ScopeSet effectiveForThisHop = incoming != null ? incoming.intersect(ownGrantedScopes) : ownGrantedScopes;
        if (incoming != null) {
            outgoing = outgoing.withOpaque(RunContext.SCOPES_KEY, effectiveForThisHop);
        }

        // ADR-033 Fase 5: the full ExecutionContext (subject identity included) is a
        // strict superset of the bare ScopeSet above — read, re-attenuate and propagate it
        // the same way, but only when one is actually present. A caller with only
        // RunContext.SCOPES_KEY set (M2M, no on-behalf-of) never touched this key, so
        // executionContext stays null and every outgoing message below is built exactly
        // as it was before this field existed.
        ExecutionContext incomingContext = callerContext.opaque(RunContext.EXECUTION_CONTEXT_KEY, ExecutionContext.class);
        ExecutionContext outgoingContext = incomingContext != null
                ? incomingContext.delegate(selfId, ownGrantedScopes)
                : null;
        if (outgoingContext != null) {
            outgoing = outgoing.withOpaque(RunContext.EXECUTION_CONTEXT_KEY, outgoingContext);
        }

        try {
            // ADR-033: the same effectiveForThisHop that ADR-0077 threads onward for
            // the *next* hop's own attenuation is also what LocalMessageBus checks against
            // *this* hop's recipient — one value, two consumers, instead of separate scope
            // plumbing for "am I allowed to call this" and "what do I hand my own delegate".
            // When outgoingContext (the on-behalf-of subject identity) is present it
            // supersedes senderScopes at the bus — both are still set so a recipient not
            // yet ExecutionContext-aware still sees a correct senderScopes.
            AgentMessage request = AgentMessage.of(selfId, recipientId, taskContent, outgoing, delegateSessionId)
                    .withSenderScopes(effectiveForThisHop);
            if (outgoingContext != null) {
                request = request.withExecutionContext(outgoingContext);
            }
            AgentMessage reply = bus.request(request, timeout);
            return ToolResult.success(TOOL_ID,
                    "[" + recipientId + "] " + reply.content());
        } catch (java.util.NoSuchElementException e) {
            return ToolResult.failure(TOOL_ID, "Agent not found: " + recipientId);
        } catch (IllegalStateException e) {
            return ToolResult.failure(TOOL_ID,
                    "Agent [" + recipientId + "] is not available: " + e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.failure(TOOL_ID,
                    "Delegation to [" + recipientId + "] failed: " + e.getMessage());
        }
    }
}