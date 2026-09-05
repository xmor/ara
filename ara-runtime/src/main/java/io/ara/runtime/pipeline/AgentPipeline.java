package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentChain;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.RunState;
import io.ara.core.common.AgentId;
import io.ara.runtime.pipeline.PipelineExecution.StepResult;
import io.ara.runtime.workflow.DataflowScheduler;
import io.ara.runtime.workflow.WorkflowEdge;
import io.ara.runtime.workflow.WorkflowGraph;
import io.ara.runtime.workflow.WorkflowNode;
import io.ara.runtime.workflow.WorkflowResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Thin orchestrator that sequences a chain of {@link AraAgent} steps with optional
 * conditional routing.
 *
 * <p>Each step is identified by a name. After each step, an optional router receives a
 * {@link PipelineExecution} — a typed, immutable snapshot of the full execution history —
 * and returns the name of the next step to execute, enabling conditional branching and
 * retry loops.
 *
 * <p>Agents remain unaware of the pipeline: they always receive a plain {@link AgentTask}
 * with the previous step's output as input. Only routers see the execution context.
 * When run via {@link #run(AgentTask)}, every step's task is derived from the same
 * incoming task via {@link AgentTask#withInput}, so {@code attachments()}, {@code
 * context()}, {@code sessionId()}, and {@code hints()} carry through to every step
 * unchanged — only {@code input()} varies from step to step.
 *
 * <p>Validation and transformation logic belongs in each agent's {@link
 * io.ara.core.agent.AgentContract} — the pipeline itself does not touch payloads.
 *
 * <p>Usage:
 * <pre>{@code
 * AgentPipeline pipeline = AgentPipeline.builder()
 *     .step("analyze", analyst)
 *     .step("write",   writer)
 *     .route("analyze", execution ->
 *         execution.lastOutput().length() < 50 ? "analyze" : "write")
 *     .build();
 *
 * PipelineResult result = pipeline.run("Summarise AI news");
 * }</pre>
 */
public final class AgentPipeline {

    private static final Logger log = LoggerFactory.getLogger(AgentPipeline.class);

    private static final int DEFAULT_MAX_STEPS = 20;

    /** Reserved id for the synthetic entry node every compiled graph gets — see {@link #compile}. */
    private static final String START_NODE = "$pipeline-start$";

    // stepOrder is the correctness-critical source of step order: Map.copyOf does not
    // guarantee iteration order preservation even when the source is a LinkedHashMap,
    // so sequential "advance to the next step" logic must never rely on `steps`' own
    // iteration order — only on this explicit list.
    private final List<String>              stepOrder;
    private final Map<String, PipelineStep> steps;
    private final int                       maxSteps;
    // Resolved, build-time-validated routing targets (ADR-052 D2) — only present for a
    // step that has a router; empty means "never continues" (e.g. worker()).
    private final Map<String, Set<String>>  resolvedTargets;

    private AgentPipeline(Builder builder) {
        this.stepOrder       = List.copyOf(builder.stepOrder);
        this.steps           = Map.copyOf(builder.steps);
        this.maxSteps        = builder.maxSteps;
        this.resolvedTargets = builder.resolvedTargets;
    }

    /**
     * Runs the pipeline starting from the first declared step.
     *
     * @param initialInput the input string for the first step
     * @return the pipeline result; never {@code null}
     */
    public PipelineResult run(String initialInput) {
        Objects.requireNonNull(initialInput, "initialInput must not be null");
        return run(AgentTask.of(initialInput));
    }

    /**
     * Runs the pipeline starting from the first declared step, using {@code task} as the
     * template for every step's own task: each step receives {@code task.withInput(...)}
     * with the previous step's output, rather than a bare {@code AgentTask.of(...)} — so
     * {@code attachments()}, {@code context()}, {@code sessionId()}, and {@code hints()}
     * survive into every step, not just the initial input string. All steps share
     * {@code task}'s {@code taskId()}, which doubles as a free correlation key across the
     * whole pipeline run in logs/traces.
     *
     * @param task the task whose {@code input()} seeds the first step
     * @return the pipeline result; never {@code null}
     */
    public PipelineResult run(AgentTask task) {
        Objects.requireNonNull(task, "task must not be null");
        Instant start = Instant.now();

        // ADR-052 D2: compile this run's declared steps/routers into a fresh WorkflowGraph
        // and drive it through DataflowScheduler instead of the old hand-rolled while loop
        // — see compile()/RunAccumulator for why "fresh" (per call, not once at build()).
        RunAccumulator acc = new RunAccumulator(task);
        WorkflowGraph graph = compile(acc);

        WorkflowResult wfResult;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // Every compiled graph fires at most one node at a time (see compile()'s
            // Javadoc), so which executor runs a node's body never introduces the
            // concurrency DataflowScheduler is built for — virtual threads are simply the
            // idiom the rest of the runtime already uses for a bounded-lifetime pool.
            //
            // maxOccurrences is deliberately maxSteps + 1, not maxSteps: it is a per-node
            // backstop the scheduler checks BEFORE dispatching to a node's body — before
            // executeStep()'s own maxSteps check ever runs — so setting it to maxSteps would
            // let it fire first, on whichever single node happens to loop, with its own
            // generic "maxOccurrences exceeded on X" message instead of the pipeline's own
            // "exceeded maximum step count" one. No node can ever occur more than maxSteps
            // times regardless (its own count never exceeds the global one executeStep()
            // caps), so this backstop never actually trips — it only stays out of the way.
            wfResult = new DataflowScheduler(graph, maxSteps + 1).run(task.input(), pool);
        }

        List<StepResult> history = List.copyOf(acc.history);
        AgentResponse lastResponse = acc.lastResponse;
        Duration elapsed = elapsed(start);

        if (wfResult.ok()) {
            return PipelineResult.success(
                    lastResponse != null ? lastResponse.content() : "", lastResponse, history, elapsed);
        }
        log.warn("Pipeline run failed: {}", wfResult.failureReason());
        return PipelineResult.failure(wfResult.failureReason(), lastResponse, history, elapsed);
    }

    private static Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }

    // ── ADR-052 D2 compiler: Builder's declared steps/routers → a fresh WorkflowGraph ──

    /**
     * Per-{@link #run} mutable context every compiled node's closures share — one fresh
     * instance per call, playing the role the old {@code while} loop's own {@code
     * history}/{@code stepCount} locals played. A fresh {@link WorkflowGraph} is compiled
     * from it too (see {@link #compile}): {@link WorkflowNode#body()}/{@link
     * WorkflowNode#selector()} are plain {@code Function<String,String>} with no context
     * slot of their own to carry a specific run's history through, so the graph — not just
     * this accumulator — has to be rebuilt fresh every call. That's pure in-memory object
     * construction, not I/O, and it's what lets {@link AgentPipeline} itself stay immutable
     * and safely reusable — including concurrently — across repeated {@link #run} calls.
     *
     * <p>Every compiled node executes to completion before the next one starts (D1's
     * activation rule never has more than one node ready at a time in the graphs {@link
     * #compile} produces — no declared step here has more than one <em>live</em>
     * predecessor at once), so mutating {@link #history} without extra locking is safe: the
     * scheduler's own happens-before edge (a {@link java.util.concurrent.Future#get()} per
     * occurrence) is already what orders one step's writes before the next step's reads.
     */
    private static final class RunAccumulator {
        private final AgentTask task;
        private final List<StepResult> history = new ArrayList<>();
        private AgentResponse lastResponse;

        RunAccumulator(AgentTask task) {
            this.task = task;
        }

        PipelineExecution snapshot() {
            return new PipelineExecution(task, history, history.size());
        }

        void record(StepResult result) {
            history.add(result);
            lastResponse = result.response();
        }
    }

    /**
     * Compiles this pipeline's declared steps into a fresh {@link WorkflowGraph} for one
     * {@link #run} call.
     *
     * <p>Every declared step becomes exactly one {@link WorkflowNode}: one with a router
     * gets an edge to each of its {@link #resolvedTargets} (resolved and validated once, in
     * {@link Builder#build()}), {@code back} whenever the target does not come strictly
     * after the source in {@link #stepOrder} — the only way a loop is expressed under D1
     * (see {@link WorkflowEdge#back()}); one with none gets a single forward edge to the
     * next declared step, if any — the old loop's "advance sequentially" default.
     *
     * <p>A synthetic {@value #START_NODE} node — identity body, a selector that always
     * picks {@link #stepOrder}'s first entry — is the graph's only true entry point (the
     * one node with zero incoming edges) and gets an edge to <em>every</em> declared step.
     * That serves two purposes at once: it lets a later step legitimately route back to an
     * earlier one — giving that earlier step a real incoming edge without D1 mistaking it
     * for a second entry point — and it starves any step nothing else ever routes to (D1's
     * deadness cascade kills that step's lone, never-selected {@value #START_NODE} edge,
     * and with it every edge downstream of it), reproducing the old loop's "a step nothing
     * routes to is simply never visited" without a dedicated reachability pass.
     */
    private WorkflowGraph compile(RunAccumulator acc) {
        List<WorkflowNode> nodes = new ArrayList<>();
        List<WorkflowEdge> edges = new ArrayList<>();

        nodes.add(WorkflowNode.routing(START_NODE, input -> input, output -> Set.of(stepOrder.get(0))));
        for (String name : stepOrder) {
            edges.add(WorkflowEdge.of(START_NODE, name));
        }

        for (int i = 0; i < stepOrder.size(); i++) {
            String name = stepOrder.get(i);
            PipelineStep step = steps.get(name);
            Function<String, String> body = ignoredInput -> executeStep(name, step, acc);

            if (step.router() == null) {
                nodes.add(WorkflowNode.of(name, body));
                if (i + 1 < stepOrder.size()) {
                    edges.add(WorkflowEdge.of(name, stepOrder.get(i + 1)));
                }
                continue;
            }

            Set<String> targets = resolvedTargets.get(name);
            Function<String, Set<String>> selector = ignoredOutput -> selectTarget(name, step, targets, acc);
            nodes.add(WorkflowNode.routing(name, body, selector));
            for (String target : targets) {
                boolean back = stepOrder.indexOf(target) <= i;
                edges.add(back ? WorkflowEdge.back(name, target) : WorkflowEdge.of(name, target));
            }
        }

        return new WorkflowGraph(nodes, edges);
    }

    /**
     * Runs one step's agent and records its result — the shared body of every compiled
     * node. A thrown exception here is what {@link DataflowScheduler#run} turns into a
     * {@link io.ara.runtime.workflow.NodeOutcome.Failed}; the message is exactly the one
     * {@link PipelineResult#failureReason()} carried before this class compiled onto
     * {@link WorkflowGraph}, so a caller matching on it is unaffected by the change.
     */
    private String executeStep(String name, PipelineStep step, RunAccumulator acc) {
        if (acc.history.size() >= maxSteps) {
            throw new IllegalStateException("Pipeline exceeded maximum step count of " + maxSteps);
        }
        AgentTask stepTask;
        try {
            stepTask = step.buildTask(acc.snapshot());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Step '" + name + "' input shaper threw: " + e, e);
        }
        log.debug("Pipeline step [{}] input.len={}", name, stepTask.input().length());
        Instant stepStart = Instant.now();
        AgentResponse response = step.agent().execute(stepTask);
        acc.record(new StepResult(name, response.content(), Duration.between(stepStart, Instant.now()), response));
        if (!response.isSuccess()) {
            throw new IllegalStateException("Step '" + name + "' failed: " + response.failureReason());
        }
        return response.content();
    }

    /**
     * Resolves one router's next step from the execution so far (including this step's own
     * just-recorded result — {@link RunAccumulator#record} always runs before this, since
     * {@link DataflowScheduler} evaluates a node's body before its selector), and enforces
     * that a valid runtime return value never names a step outside {@code targets} — the
     * same "Unknown step" failure the old loop reported for a router that returned
     * something no declared step answers to.
     */
    private Set<String> selectTarget(String name, PipelineStep step, Set<String> targets, RunAccumulator acc) {
        String next = step.router().apply(acc.snapshot());
        log.debug("Pipeline router [{}] → [{}]", name, next);
        if (next == null || next.isBlank()) {
            return Set.of();
        }
        if (!targets.contains(next)) {
            throw new IllegalStateException(
                    "Unknown step: '" + next + "' — declared steps: " + String.join(", ", stepOrder));
        }
        return Set.of(next);
    }

    public static Builder    builder()    { return new Builder(); }
    public static FsmBuilder fsmBuilder() { return new FsmBuilder(); }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {

        private final List<String>              stepOrder       = new ArrayList<>();
        private final Map<String, PipelineStep> steps           = new LinkedHashMap<>();
        // Populated only by the typed route(name, targets, router) overload — a step
        // routed through the untyped one is resolved conservatively instead, in build().
        private final Map<String, Set<String>>  explicitTargets = new LinkedHashMap<>();
        private int maxSteps = DEFAULT_MAX_STEPS;
        // Set once, at the end of build() — see the ADR-052 D2 target-resolution block there.
        private Map<String, Set<String>> resolvedTargets;

        private Builder() {}

        /**
         * Adds a named step to the pipeline. Steps execute in declaration order unless
         * a router redirects to a different step name. The step's task is built by
         * default as {@code execution.task().withInput(execution.lastOutput())} — use
         * {@link #step(String, AraAgent, Function)} to customise it.
         */
        public Builder step(String name, AraAgent agent) {
            return step(name, agent, null);
        }

        /**
         * Adds a named step whose task is built by {@code input} instead of the default
         * "previous step's raw output" — e.g. to combine two earlier named steps' results
         * with static text: {@code execution -> execution.task().withInput(
         * execution.resultOf("analyze").get().output() + " / " +
         * execution.resultOf("review").get().output())}.
         */
        public Builder step(String name, AraAgent agent, Function<PipelineExecution, AgentTask> input) {
            Objects.requireNonNull(name,  "step name must not be null");
            Objects.requireNonNull(agent, "agent must not be null");
            if (steps.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate step name: '" + name + "'");
            }
            stepOrder.add(name);
            steps.put(name, new PipelineStep(name, agent, input, null));
            return this;
        }

        /**
         * Adds a step that fans the incoming task out to every agent in {@code members},
         * running them concurrently on {@code executor}, and merges their responses per
         * {@code merge}/{@code failurePolicy} — see {@link ParallelAgent}. Equivalent to
         * {@code step(name, new ParallelAgent(AgentId.of(name), members, executor, merge,
         * failurePolicy))}, kept as a convenience so the common case does not require
         * importing {@code ParallelAgent} directly.
         *
         * <p>Like any other step, its input defaults to the previous step's raw output; use
         * the {@code Function<PipelineExecution, AgentTask>} overload of {@link #step} for a
         * custom shaper (e.g. to fan out over {@link PipelineExecution#initialInput()} instead).
         */
        public Builder parallel(String name, List<AraAgent> members, Executor executor,
                                AgentChain.MergeStrategy merge, AgentChain.FailurePolicy failurePolicy) {
            return step(name, new ParallelAgent(AgentId.of(name), members, executor, merge, failurePolicy));
        }

        /** Convenience overload using {@link AgentChain.FailurePolicy#FAIL_FAST}. */
        public Builder parallel(String name, List<AraAgent> members, Executor executor,
                                AgentChain.MergeStrategy merge) {
            return parallel(name, members, executor, merge, AgentChain.FailurePolicy.FAIL_FAST);
        }

        /**
         * Attaches a conditional router to {@code stepName}.
         *
         * <p>The router receives a {@link PipelineExecution} — a typed snapshot of the full
         * execution history up to and including this step — and returns the name of the next
         * step to execute. Return {@code null} or blank to end the pipeline immediately.
         *
         * @throws IllegalArgumentException if {@code stepName} was never declared via {@link #step}
         */
        public Builder route(String stepName, Function<PipelineExecution, String> router) {
            Objects.requireNonNull(stepName, "stepName must not be null");
            Objects.requireNonNull(router,   "router must not be null");
            PipelineStep existing = steps.get(stepName);
            if (existing == null) {
                throw new IllegalArgumentException(
                        "route(): unknown step '" + stepName + "' — declare it with step(...) first");
            }
            steps.put(stepName, existing.withRouter(router));
            return this;
        }

        /**
         * Attaches a conditional router whose full set of possible return values is
         * declared up front (ADR-052 D2) — every step {@code router} can ever name, so
         * {@link #build()} can wire the compiled graph's edges and reject a target that
         * was never declared as a step, the same way {@link #classify} already does via
         * {@link IntentRouter#targets()}. An empty set means "never continues" — the shape
         * {@link #worker} uses.
         *
         * <p>Prefer this over {@link #route(String, Function)}: a router whose targets are
         * unknown to the builder is wired conservatively (every other declared step), which
         * is always correct but gives up the build-time check on the router's own
         * return value — see the class Javadoc on that overload for when it still applies.
         *
         * @throws IllegalArgumentException if {@code stepName} was never declared via {@link #step}
         * @throws IllegalStateException    at {@link #build()}, if {@code targets} names an undeclared step
         */
        public Builder route(String stepName, Set<String> targets, Function<PipelineExecution, String> router) {
            Objects.requireNonNull(targets, "targets must not be null");
            route(stepName, router);
            explicitTargets.put(stepName, Set.copyOf(targets));
            return this;
        }

        /**
         * Declares a terminal step that receives the pipeline's <em>original</em> input —
         * the worker end of a classify-and-act pipeline, where a classifier picked which
         * one of several mutually exclusive workers handles the task.
         *
         * <p>Three defaults are wrong for that shape, and all three fail silently rather
         * than loudly:
         * <ul>
         *   <li><b>Input.</b> The default is the previous step's output, which here is the
         *       classifier's label: a support agent asked to answer {@code
         *       {"intent":"TECH"}} instead of the ticket. This step gets {@code
         *       execution.initialInput()} instead — the label reaches the worker through
         *       {@code RunContext.state()} (see {@code IntentRouter.writeLabelTo}), not
         *       spliced into its task.</li>
         *   <li><b>Routing.</b> A step with no router advances to the next <em>declared</em>
         *       step, so five sibling workers declared in a row would run one after another
         *       — the classification silently ignored past the first hop. This step ends the
         *       pipeline, which is what "exactly one worker handles it" means.</li>
         *   <li><b>Isolation.</b> A plain {@link #step} shares one mutable {@code RunState}
         *       across the whole run, so a worker could overwrite the classification
         *       bookkeeping a later step (or the caller) still needs — the opposite of the
         *       pattern's "workers operate in isolation." This step runs the worker against
         *       {@link RunState#overlay(RunState, RunState)}: it still <em>reads</em> every
         *       key the classifier tiers wrote (the label, the confidence, anything else),
         *       but its own writes land in a private, run-scoped store that is discarded the
         *       moment {@link AgentPipeline#run} returns — never visible to the caller, to a
         *       sibling worker, or to a later {@code PipelineResult}.</li>
         * </ul>
         *
         * <p>A worker that must publish something back to the caller — an escalation
         * acknowledgement, a decision the pipeline's own result should carry — has to do it
         * through its {@link AgentResponse} content, not {@code RunState}: that channel
         * survives the overlay by design; state writes no longer do.
         *
         * <p>For a worker that should hand off to a further shared step (a formatter, an
         * audit sink) rather than end the run, use {@link #step(String, AraAgent, Function)}
         * with the same input shaper and give it an explicit {@link #route} — {@code step}
         * keeps the single shared {@code RunState} this class deliberately opts out of.
         */
        public Builder worker(String name, AraAgent agent) {
            step(name, agent, execution -> {
                AgentTask task = execution.task().withInput(execution.initialInput());
                RunState isolated = RunState.overlay(execution.state(), RunState.inMemory());
                return task.withRunContext(task.runContext().withState(isolated));
            });
            return route(name, Set.of(), execution -> null);
        }

        /**
         * Adds a classifier step and attaches {@code router} to it in one call —
         * {@code step(name, classifier).route(name, router)}, named for the pattern.
         *
         * <p>Unlike a bare {@link #route}, the router's declared targets are checked
         * against the pipeline's steps in {@link #build()}: a label mapped to a step that
         * was never declared fails at wiring time rather than mid-run, where the pipeline
         * would abort with "Unknown step" only for the inputs that happen to carry that
         * label.
         *
         * <p>Like {@link #worker}, the step is fed {@code execution.initialInput()}. What
         * gets classified is the task that came in, never an intermediate — which is
         * invisible for a single classifier in first position, where the two are the same
         * string, and load-bearing for a second one reached by {@code escalateBelow(...)}:
         * with the default input rule it would classify the first classifier's verdict
         * ({@code {"intent":"UNKNOWN","confidence":0.0}}) instead of the ticket, and answer
         * confidently about the wrong text. To classify something other than the original
         * input — a normalised or enriched form — use {@link #step(String, AraAgent,
         * Function)} with an explicit shaper and {@link #route}.
         */
        public Builder classify(String name, AraAgent classifier, IntentRouter router) {
            Objects.requireNonNull(router, "router must not be null");
            step(name, classifier, execution -> execution.task().withInput(execution.initialInput()));
            return route(name, router.targets(), router);
        }

        /** Sets the hard cap on total step executions (including retries). Default: 20. */
        public Builder maxSteps(int max) {
            if (max <= 0) throw new IllegalArgumentException("maxSteps must be > 0");
            this.maxSteps = max;
            return this;
        }

        public AgentPipeline build() {
            if (stepOrder.isEmpty()) {
                throw new IllegalStateException("AgentPipeline must have at least one step");
            }
            if (steps.containsKey(START_NODE)) {
                throw new IllegalStateException(
                        "'" + START_NODE + "' is reserved for the pipeline's own synthetic entry node (ADR-052 D2)");
            }

            // ADR-052 D2: resolve and validate every router's target set once, here, so
            // compile() can wire a WorkflowGraph edge per target without re-deriving it on
            // every run() call — classify()'s IntentRouter.targets() and the typed route()
            // overload both land in explicitTargets; the untyped route(name, fn) has none,
            // so it falls back to "every other declared step", the always-safe superset a
            // return value can never fall outside of.
            Map<String, Set<String>> resolved = new LinkedHashMap<>();
            for (Map.Entry<String, PipelineStep> e : steps.entrySet()) {
                String stepName = e.getKey();
                if (e.getValue().router() == null) {
                    continue;
                }
                Set<String> targets = explicitTargets.get(stepName);
                if (targets == null) {
                    targets = new LinkedHashSet<>(steps.keySet());
                    targets.remove(stepName);
                }
                for (String target : targets) {
                    if (!steps.containsKey(target)) {
                        throw new IllegalStateException(
                                "route('" + stepName + "') targets undeclared step '" + target
                                        + "' — declared steps: " + String.join(", ", stepOrder));
                    }
                }
                resolved.put(stepName, Set.copyOf(targets));
            }
            this.resolvedTargets = Map.copyOf(resolved);

            return new AgentPipeline(this);
        }
    }

    // ── FsmBuilder ────────────────────────────────────────────────────────────

    /**
     * Declarative FSM-style builder for {@link AgentPipeline}.
     *
     * <p>Maps cleanly to FSM concepts:
     * <ul>
     *   <li><b>state</b>   — a named node backed by an {@link AraAgent}</li>
     *   <li><b>initial</b> — the entry state (defaults to first declared state)</li>
     *   <li><b>terminal</b> — accepting states; the pipeline ends successfully after them</li>
     *   <li><b>transition</b> — guard function that receives {@link PipelineExecution}
     *       and returns the next state name ({@code null} / blank to stop)</li>
     * </ul>
     *
     * <p>Usage:
     * <pre>{@code
     * AgentPipeline pipeline = AgentPipeline.fsmBuilder()
     *     .state("draft",    draftAgent)
     *     .state("review",   reviewAgent)
     *     .state("revise",   reviseAgent)
     *     .state("done",     doneAgent)
     *     .initial("draft")
     *     .terminal("done")
     *     .transition("draft",  "review")     // unconditional
     *     .transition("review", exec ->
     *         exec.lastOutput().contains("APPROVED") ? "done" : "revise")
     *     .transition("revise", "review")     // loop back
     *     .build();
     * }</pre>
     */
    public static final class FsmBuilder {

        private final Map<String, AraAgent>                            states      = new LinkedHashMap<>();
        private final Set<String>                                      terminals   = new LinkedHashSet<>();
        private final Map<String, Function<PipelineExecution, String>> transitions = new LinkedHashMap<>();
        private String initialState;
        private int    maxSteps = DEFAULT_MAX_STEPS;

        private FsmBuilder() {}

        /** Declares a state backed by the given agent. */
        public FsmBuilder state(String name, AraAgent agent) {
            Objects.requireNonNull(name,  "name must not be null");
            Objects.requireNonNull(agent, "agent must not be null");
            if (states.containsKey(name)) {
                throw new IllegalArgumentException("Duplicate state: '" + name + "'");
            }
            states.put(name, agent);
            return this;
        }

        /** Sets the entry state. Defaults to the first declared state if not called. */
        public FsmBuilder initial(String stateName) {
            this.initialState = Objects.requireNonNull(stateName, "stateName must not be null");
            return this;
        }

        /** Marks states as terminal (accepting). The pipeline ends successfully after any of them. */
        public FsmBuilder terminal(String... stateNames) {
            for (String s : stateNames) {
                terminals.add(Objects.requireNonNull(s, "terminal state name must not be null"));
            }
            return this;
        }

        /** Conditional transition: the router receives {@link PipelineExecution} and returns the next state. */
        public FsmBuilder transition(String from, Function<PipelineExecution, String> router) {
            Objects.requireNonNull(from,   "from must not be null");
            Objects.requireNonNull(router, "router must not be null");
            transitions.put(from, router);
            return this;
        }

        /** Unconditional transition: always moves from {@code from} to {@code to}. */
        public FsmBuilder transition(String from, String to) {
            Objects.requireNonNull(to, "to must not be null");
            return transition(from, __ -> to);
        }

        /** Sets the hard cap on total state executions (including loops). Default: 20. */
        public FsmBuilder maxSteps(int max) {
            if (max <= 0) throw new IllegalArgumentException("maxSteps must be > 0");
            this.maxSteps = max;
            return this;
        }

        public AgentPipeline build() {
            if (states.isEmpty()) {
                throw new IllegalStateException("FSM must have at least one state");
            }
            String entry = (initialState != null) ? initialState : states.keySet().iterator().next();
            if (!states.containsKey(entry)) {
                throw new IllegalStateException("Initial state '" + entry + "' not declared");
            }

            Builder b = AgentPipeline.builder().maxSteps(maxSteps);

            // initialState first so the pipeline enters there, then remaining states
            b.step(entry, states.get(entry));
            states.entrySet().stream()
                    .filter(e -> !e.getKey().equals(entry))
                    .forEach(e -> b.step(e.getKey(), e.getValue()));

            // Routers: terminal → stop, explicit transitions, else sequential. The target
            // set for a conditional transition is every declared state — always safe,
            // since a transition function can only ever return one of them (ADR-052 D2).
            for (String state : states.keySet()) {
                if (terminals.contains(state)) {
                    b.route(state, Set.of(), __ -> null);
                } else if (transitions.containsKey(state)) {
                    b.route(state, Set.copyOf(states.keySet()), transitions.get(state));
                }
            }

            return b.build();
        }
    }
}
