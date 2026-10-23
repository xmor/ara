package io.ara.runtime.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AsyncDelegation;
import io.ara.core.agent.AsyncSupport;
import io.ara.core.agent.AgentView;
import io.ara.core.agent.DelegatedBy;
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
import io.ara.runtime.trace.TraceProjection;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;
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
    private final AsyncDelegation     asyncDelegation;
    private final Function<String, AsyncSupport> recipientSupport;
    private final Function<AgentTask, AgentView> viewSource;

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
        this(bus, selfId, timeout, stateAccess, sessionStore, ownGrantedScopes, agentView, null, id -> null);
    }

    /**
     * @param asyncDelegation   when not {@code null}, this agent may start sub-tasks in the
     *                          background: {@code delegate_task} then accepts {@code "wait": false}
     *                          (and a {@code "label"}), and the task registry its run carries
     *                          holds what it starts. {@code null} (every shorter constructor)
     *                          leaves the tool exactly as it was: no extra parameter in its schema.
     * @param recipientSupport  what a recipient declares about background delegation, by agent id;
     *                          may return {@code null} for "unknown", which counts as
     *                          {@link AsyncSupport#OPTIONAL}. A function and not the agent view,
     *                          because the view is deliberately not wired in production and this
     *                          needs only the declaration, not the right to see the agent.
     */
    public AgentDelegationTool(MessageBus bus, String selfId, Duration timeout, DelegateStateAccess stateAccess,
                                SessionStore sessionStore, ScopeSet ownGrantedScopes, AgentView agentView,
                                AsyncDelegation asyncDelegation, Function<String, AsyncSupport> recipientSupport) {
        this(bus, selfId, timeout, stateAccess, sessionStore, ownGrantedScopes, agentView, asyncDelegation,
                recipientSupport, null);
    }

    /**
     * @param viewSource (ADR-033 §3.3, task-scoped form) the discovery-time pre-check's
     *                   authority, built per task instead of captured at wiring time: the
     *                   task-scoped counterpart of {@link ScopeFilteringToolRegistry}'s
     *                   task-scoped form. May return {@code null} — "this task carries no
     *                   scope channel, so there is no authority to judge by" — and the
     *                   pre-check is then skipped, leaving {@link LocalMessageBus}'s
     *                   dispatch-time check (which names the specific reason) as the only
     *                   gate; a view with empty scopes instead denies every gated
     *                   recipient outright. When both this and a fixed {@code agentView}
     *                   are given, the fixed one wins — the same precedence {@link
     *                   ScopeFilteringToolRegistry} applies, where the fixed form is the
     *                   explicit override and the task-scoped form only fills its absence.
     */
    public AgentDelegationTool(MessageBus bus, String selfId, Duration timeout, DelegateStateAccess stateAccess,
                                SessionStore sessionStore, ScopeSet ownGrantedScopes, AgentView agentView,
                                AsyncDelegation asyncDelegation, Function<String, AsyncSupport> recipientSupport,
                                Function<AgentTask, AgentView> viewSource) {
        this.asyncDelegation  = asyncDelegation;
        this.recipientSupport = Objects.requireNonNull(recipientSupport, "recipientSupport must not be null");
        this.bus              = Objects.requireNonNull(bus,    "bus must not be null");
        this.selfId           = Objects.requireNonNull(selfId, "selfId must not be null");
        this.timeout          = Objects.requireNonNull(timeout, "timeout must not be null");
        this.stateAccess      = Objects.requireNonNull(stateAccess, "stateAccess must not be null");
        this.sessionStore     = Objects.requireNonNull(sessionStore, "sessionStore must not be null");
        this.ownGrantedScopes = Objects.requireNonNullElse(ownGrantedScopes, ScopeSet.EMPTY);
        this.agentView        = agentView;
        this.viewSource       = viewSource;
    }

    @Override
    public String toolId() {
        return TOOL_ID;
    }

    @Override
    public String description() {
        String base = "Delegates a sub-task to another agent on the same node and returns its answer. "
                + "Use to decompose complex work across specialised peer agents. "
                + "The target agent must be registered and idle.";
        return asyncDelegation == null ? base : base + BACKGROUND_GUIDANCE;
    }

    /**
     * Added to the description only for an agent that may delegate in the background. It states
     * the three rules a small model gets wrong without being told — a result is not known until
     * it is delivered, results are told apart by label, open tasks are awaited — and shows one
     * whole sequence, since models that have never seen results arrive later answer worse
     * without an example. The example names no task id on purpose: a literal one ("t-1") was copied by a
     * model that then waited on ids it had invented, because it had never started a task.
     */
    private static final String BACKGROUND_GUIDANCE = """
             Background mode: pass "wait": false to start the sub-task and carry on with your own work. \
            You get a task_id at once, and the result is delivered to you automatically in a later turn, \
            in a message marked as automated. A background task's result is not known until it is \
            delivered: never assume it, and never start work that depends on it before then. Give each \
            task a short "label" so results are not mixed up. Use await_tasks only when you have nothing \
            else to do until a task ends, and cancel_task to stop one. Before your final answer, cancel \
            the background tasks you no longer need; open tasks are awaited. \
            Example: delegate_task {"agent_id":"researcher","task":"find sources","wait":false,"label":"sources"} \
            returns a task_id; do your own work; the result of "sources" arrives in a later turn; then \
            answer using it. Use a task_id only exactly as it was returned to you; never make one up.""";

    @Override
    public String argumentSchema() {
        return asyncDelegation == null ? WAITED_SCHEMA : BACKGROUND_SCHEMA;
    }

    private static final String WAITED_SCHEMA = """
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

    private static final String BACKGROUND_SCHEMA = """
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
                    },
                    "wait": {
                      "type": "boolean",
                      "description": "true (default): wait for the answer. false: start the sub-task in the background and carry on; its result is delivered to you later"
                    },
                    "label": {
                      "type": "string",
                      "description": "A short name for a background task, repeated in its result"
                    }
                  },
                  "required": ["agent_id", "task"]
                }""";

    @Override
    public ToolResult execute(String argumentJson) {
        return delegate(argumentJson, Caller.NONE, agentView);
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
     *
     * <p>The task is also what makes the §3.3 pre-check task-scoped: the effective view
     * is the fixed {@code agentView} when one was given, else {@code viewSource} applied
     * to this task — which may yield {@code null} ("no scope channel on this task"), in
     * which case the pre-check is skipped and dispatch-time authorization on the bus
     * remains the gate.
     */
    @Override
    public ToolResult execute(String argumentJson, AgentTask task) {
        return delegate(argumentJson, task != null ? Caller.of(task) : Caller.NONE, viewFor(task));
    }

    /**
     * The view the §3.3 pre-check consults for this task: the fixed {@code agentView}
     * when provided (the explicit override, mirroring {@link ScopeFilteringToolRegistry}),
     * else the task-scoped source — which is asked only here, where the task exists,
     * because the {@code Caller} a delegation carries holds the run context, not the task.
     */
    private AgentView viewFor(AgentTask task) {
        if (agentView != null) {
            return agentView;
        }
        return viewSource != null && task != null ? viewSource.apply(task) : null;
    }

    /**
     * What a delegation needs to know about the run that is delegating.
     *
     * <p>{@code workflowId} becomes the delegate's correlation id, so the whole chain shares one
     * id and lands in one trace run. It is the caller's <em>run id</em> as the trace defines it
     * ({@link TraceProjection#runIdOf}: correlation id, else session id, else task id), not the
     * caller's raw correlation id: a caller with no correlation id still has a run, and its
     * delegates must join that run rather than start one of their own. An earlier version let
     * every hop take the fresh random id {@code AgentMessage.of} generates; each sub-agent then
     * became a run of its own, and a trace of a run never contained the work it delegated.
     *
     * @param taskId the caller's task, which the delegate is told about as its direct parent
     */
    private record Caller(RunContext context, SessionId sessionId, String workflowId, String taskId) {

        /** A delegation made without a calling task: no context, no session, no run to join. */
        static final Caller NONE = new Caller(RunContext.empty(), null, null, null);

        static Caller of(AgentTask task) {
            return new Caller(task.runContext(), task.sessionId(), TraceProjection.runIdOf(task), task.taskId());
        }
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
     * @throws IllegalArgumentException if {@code argumentJson} is not a valid {@code
     *         {"agent_id":...,"task":...}} payload — the message names the missing or
     *         malformed field explicitly, so the caller (typically the ReAct loop feeding
     *         the failure back to the LLM as an observation) can self-correct on the next
     *         iteration without a raw NPE like {@code Cannot invoke "...JsonNode.asText()"}
     *         (which is what a bare {@code get(...).asText()} chain produced when the
     *         model omitted {@code task}, e.g. after an output-token truncation).
     */
    public static DelegationRequest parseDelegation(String argumentJson) {
        JsonNode args = uncheckedReadTree(argumentJson);
        if (args == null || !args.isObject()) {
            throw new IllegalArgumentException(
                    "arguments must be a JSON object with 'agent_id' and 'task' fields");
        }
        return new DelegationRequest(
                requiredTextField(args, "agent_id"),
                requiredTextField(args, "task"));
    }

    /** Reads a required, non-blank textual field, naming it in the error when absent. */
    private static String requiredTextField(JsonNode args, String field) {
        JsonNode value = args.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "missing required text field '" + field + "' in delegate_task arguments");
        }
        return value.asText();
    }

    private static JsonNode uncheckedReadTree(String argumentJson) {
        try {
            return MAPPER.readTree(argumentJson);
        } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private ToolResult delegate(String argumentJson, Caller caller, AgentView view) {
        DelegationRequest request;
        try {
            request = parseDelegation(argumentJson);
        } catch (Exception e) {
            return ToolResult.failure(TOOL_ID, "Invalid arguments: " + e.getMessage());
        }
        String recipientId = request.recipientAgentId();

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
        // The view arrives already resolved per task (fixed view, or the task-scoped
        // source — whose null means "no scope channel on this task", so there is no
        // authority to pre-check against and the bus stays the only gate).
        if (view != null && view.findById(AgentId.of(recipientId)).isEmpty()) {
            return ToolResult.failure(TOOL_ID, "Agent not found: " + recipientId);
        }

        Mode mode = chooseMode(argumentJson, recipientId);
        if (mode.refusal() != null) {
            return ToolResult.failure(TOOL_ID, mode.refusal());
        }
        // A background task gets a session of its own: two of them aimed at the same agent
        // would otherwise share the derived session and the second would be turned away as
        // "busy" (or queued behind the first, which is not background at all).
        SessionId delegateSessionId = mode.background()
                ? backgroundSessionId(caller.sessionId(), recipientId)
                : delegateSessionId(caller.sessionId(), recipientId);
        AgentMessage message = buildMessage(request, delegateSessionId,
                attenuateForHops(caller.context(), delegateSessionId, caller.taskId()), caller.workflowId());

        return mode.background()
                ? startInBackground(message, caller, label(argumentJson, request), mode.notice())
                : dispatch(message, recipientId);
    }

    /**
     * How one call is to run, and the sentence to tell the model when it differs from what it
     * asked: a refusal, or the mode with an optional notice.
     */
    private record Mode(boolean background, String notice, String refusal) {
        static final Mode WAITED = new Mode(false, null, null);
        static Mode inBackground(String notice) { return new Mode(true, notice, null); }
        static Mode refuse(String reason) { return new Mode(false, null, reason); }
    }

    /**
     * Joins what the calling model asked ({@code wait}) with what the recipient declared
     * ({@link AsyncSupport}) and with whether this agent may delegate in the background at all.
     * The recipient's word wins over the model's in both directions: a recipient that only
     * works waited is refused a background start, and one that only works in the background is
     * started as one even when asked to be waited on.
     */
    private Mode chooseMode(String argumentJson, String recipientId) {
        AsyncSupport support = Objects.requireNonNullElse(recipientSupport.apply(recipientId), AsyncSupport.OPTIONAL);
        boolean asksForBackground = asyncDelegation != null && asksForBackground(argumentJson);
        if (asksForBackground && support == AsyncSupport.FORBIDDEN) {
            return Mode.refuse("Agent [" + recipientId + "] accepts only waited delegation: "
                    + "call delegate_task again without \"wait\": false.");
        }
        if (!asksForBackground && support == AsyncSupport.REQUIRED) {
            return asyncDelegation == null
                    ? Mode.refuse("Agent [" + recipientId + "] runs only in the background, and this agent "
                            + "cannot start background tasks.")
                    : Mode.inBackground("Agent [" + recipientId + "] runs only in the background, "
                            + "so it was started as a background task; its result will be delivered to you.");
        }
        return asksForBackground ? Mode.inBackground(null) : Mode.WAITED;
    }

    /** {@code "wait": false}, as a boolean or as the string a model sometimes writes instead. */
    private static boolean asksForBackground(String argumentJson) {
        JsonNode wait = uncheckedReadTree(argumentJson).get("wait");
        return wait != null && "false".equalsIgnoreCase(wait.asText());
    }

    /** The model's own name for a background task, or the start of its text. */
    private static String label(String argumentJson, DelegationRequest request) {
        JsonNode label = uncheckedReadTree(argumentJson).get("label");
        if (label != null && label.isTextual() && !label.asText().isBlank()) {
            return label.asText();
        }
        String task = request.task();
        return task.length() <= 40 ? task : task.substring(0, 40) + "…";
    }

    /**
     * {@code <caller session>::deleg::<agent>::<fresh id>}: the sessions of waited delegations
     * with a suffix that is new every time. {@code null} without a caller session, like {@link
     * #delegateSessionId}; the bus then gives the task an ephemeral one, also unique.
     */
    private static SessionId backgroundSessionId(SessionId callerSessionId, String recipientId) {
        SessionId waited = delegateSessionId(callerSessionId, recipientId);
        return waited == null ? null : SessionId.of(waited.value() + "::" + UUID.randomUUID());
    }

    private ToolResult startInBackground(AgentMessage message, Caller caller, String label, String notice) {
        BackgroundTasks tasks = BackgroundTasks.from(caller.context()).orElse(null);
        if (tasks == null) {
            // Not an error to hide: starting the task anyway would leave one nobody ever closes.
            return ToolResult.failure(TOOL_ID, "Background delegation is not available in this run: "
                    + "the agent's strategy keeps no task registry. Delegate without \"wait\": false.");
        }
        return BackgroundStart.start(bus, tasks, message, label, notice);
    }

    /**
     * What one hop hands to the bus: the outgoing {@link RunContext} (state layer applied by
     * the {@code stateAccess} policy, then scopes and execution context narrowed) alongside
     * the two values {@link #dispatch} needs but cannot cheaply recover — the effective scope
     * set, which the message carries as its own field and not only inside the context, and
     * the narrowed {@link ExecutionContext}, whose presence decides whether the message needs
     * {@code withExecutionContext} at all.
     *
     * <p>Returned as one value so the context and the message's own fields are always built
     * from the same computation and cannot drift apart.
     */
    private record PreparedHop(RunContext context, ScopeSet effectiveScopes, ExecutionContext executionContext) {}

    /**
     * Builds the {@link RunContext} a delegate receives: this agent's declared state-access
     * treatment, then a non-escalation of permissions across the hop.
     *
     * <p>Pure — reads only its arguments and the final fields {@code stateAccess},
     * {@code ownGrantedScopes} and {@code sessionStore} — so the whole authorization surface
     * of a delegation is one method a reviewer can read top to bottom, instead of being split
     * across the middle of a longer {@code execute}-shaped one. Kept separate from
     * {@link #delegate} for exactly that reason, not to make the line count look better.
     */
    private PreparedHop attenuateForHops(RunContext callerContext, SessionId delegateSessionId, String callerTaskId) {
        RunContext outgoing = applyStateAccess(callerContext, delegateSessionId);

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

        // Unlike the two keys above, which narrow what the caller had, this one is overwritten:
        // the delegate must know its *direct* parent, and the value it would inherit names the
        // caller's own parent instead. Without a calling task there is no parent to name.
        if (callerTaskId != null) {
            outgoing = outgoing.withOpaque(RunContext.DELEGATED_BY_KEY, new DelegatedBy(selfId, callerTaskId));
        }

        // The caller's background tasks are the caller's alone: a delegate, waited or not, starts
        // with none, and cannot see, await or cancel the caller's or its siblings'.
        outgoing = BackgroundTasks.without(outgoing);

        return new PreparedHop(outgoing, effectiveForThisHop, outgoingContext);
    }

    /**
     * Enforces this agent's {@link DelegateStateAccess} policy on the outgoing {@link RunState}:
     * SHARED forwards the same reference (today's behavior); OVERLAY gives the
     * delegate read-through to our state with writes confined to its own private
     * layer; ISOLATED starts the delegate with nothing of ours.
     */
    private RunContext applyStateAccess(RunContext callerContext, SessionId delegateSessionId) {
        // The delegate's own RunState layer is backed by SessionStore (persisting()
        // degrades to inMemory()'s behavior when sessionStore is noop()) whenever there
        // is a real session to anchor it to; otherwise there is nothing to persist under,
        // so it stays a throwaway in-memory layer exactly like before session propagation.
        RunState ownLayer = delegateSessionId != null
                ? RunState.persisting(sessionStore, delegateSessionId)
                : RunState.inMemory();

        return switch (stateAccess) {
            case SHARED   -> callerContext;
            case OVERLAY  -> callerContext.withState(RunState.overlay(callerContext.state(), ownLayer));
            case ISOLATED -> callerContext.withState(ownLayer);
        };
    }

    /** The message one hop hands to the bus, built from the same pieces for a waited and a background call. */
    private AgentMessage buildMessage(DelegationRequest request, SessionId delegateSessionId, PreparedHop hop,
                                      String workflowId) {
        // ADR-033: the same effectiveForThisHop that ADR-0077 threads onward for
        // the *next* hop's own attenuation is also what LocalMessageBus checks against
        // *this* hop's recipient — one value, two consumers, instead of separate scope
        // plumbing for "am I allowed to call this" and "what do I hand my own delegate".
        // When outgoingContext (the on-behalf-of subject identity) is present it
        // supersedes senderScopes at the bus — both are still set so a recipient not
        // yet ExecutionContext-aware still sees a correct senderScopes.
        AgentMessage message = AgentMessage
                .of(selfId, request.recipientAgentId(), request.task(), hop.context(), delegateSessionId)
                .withSenderScopes(hop.effectiveScopes());
        if (hop.executionContext() != null) {
            message = message.withExecutionContext(hop.executionContext());
        }
        // The chain keeps the caller's workflow id (see Caller). Without a calling task the
        // message keeps the fresh id it was born with, exactly as before.
        if (workflowId != null) {
            message = message.withCorrelationId(workflowId);
        }
        return message;
    }

    /**
     * Sends the delegation and renders either the delegate's answer or the reason it could
     * not be obtained. See {@link #failureFor} for why the failures are told apart.
     */
    private ToolResult dispatch(AgentMessage message, String recipientId) {
        try {
            AgentMessage reply = bus.request(message, timeout);
            return ToolResult.success(TOOL_ID,
                    "[" + recipientId + "] " + reply.content());
        } catch (RuntimeException e) {
            return failureFor(recipientId, e);
        }
    }

    /**
     * What a bus failure tells the delegating model. The three cases are not redundant: each maps
     * a distinct failure onto a message that says something different about what to try next
     * (the agent is gone / it is busy / the hop failed outright), and the tool reports a failure
     * as a {@link ToolResult} rather than propagating, because the ReAct loop is built to read a
     * failed tool call as an observation and keep reasoning.
     */
    static ToolResult failureFor(String recipientId, RuntimeException e) {
        if (e instanceof java.util.NoSuchElementException) {
            return ToolResult.failure(TOOL_ID, "Agent not found: " + recipientId);
        }
        if (e instanceof IllegalStateException) {
            return ToolResult.failure(TOOL_ID,
                    "Agent [" + recipientId + "] is not available: " + e.getMessage());
        }
        return ToolResult.failure(TOOL_ID,
                "Delegation to [" + recipientId + "] failed: " + e.getMessage());
    }
}
