package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentChain;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.RunState;
import io.ara.core.common.AgentId;
import io.ara.runtime.pipeline.PipelineExecution.StepResult;
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

    // stepOrder is the correctness-critical source of step order: Map.copyOf does not
    // guarantee iteration order preservation even when the source is a LinkedHashMap,
    // so sequential "advance to the next step" logic must never rely on `steps`' own
    // iteration order — only on this explicit list.
    private final List<String>              stepOrder;
    private final Map<String, PipelineStep> steps;
    private final int                       maxSteps;

    private AgentPipeline(Builder builder) {
        this.stepOrder = List.copyOf(builder.stepOrder);
        this.steps     = Map.copyOf(builder.steps);
        this.maxSteps  = builder.maxSteps;
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

        String        currentStep  = stepOrder.get(0);
        AgentResponse lastResponse = null;
        int           stepCount    = 0;
        List<StepResult> history   = new ArrayList<>();

        while (currentStep != null) {
            if (stepCount >= maxSteps) {
                log.warn("Pipeline exceeded maxSteps={}", maxSteps);
                return PipelineResult.failure(
                        "Pipeline exceeded maximum step count of " + maxSteps,
                        lastResponse, history, elapsed(start));
            }

            PipelineStep step = steps.get(currentStep);
            if (step == null) {
                return PipelineResult.failure(
                        "Unknown step: '" + currentStep + "' — declared steps: "
                                + String.join(", ", stepOrder),
                        lastResponse, history, elapsed(start));
            }

            PipelineExecution execution = new PipelineExecution(task, history, stepCount);
            AgentTask stepTask;
            try {
                stepTask = step.buildTask(execution);
            } catch (RuntimeException e) {
                log.warn("Pipeline step [{}] input shaper threw", currentStep, e);
                return PipelineResult.failure(
                        "Step '" + currentStep + "' input shaper threw: " + e,
                        lastResponse, history, elapsed(start));
            }

            log.debug("Pipeline step [{}] input.len={}", currentStep, stepTask.input().length());
            Instant stepStart = Instant.now();
            lastResponse = step.agent().execute(stepTask);
            stepCount++;

            // Recorded for every attempted step — success or failure — so token/cost
            // totals and stepsExecuted() both reflect what actually ran, not just the
            // steps that completed successfully.
            history = new ArrayList<>(history);
            history.add(new StepResult(currentStep, lastResponse.content(),
                    Duration.between(stepStart, Instant.now()), lastResponse));

            if (!lastResponse.isSuccess()) {
                log.warn("Pipeline step [{}] failed: {}", currentStep, lastResponse.failureReason());
                return PipelineResult.failure(
                        "Step '" + currentStep + "' failed: " + lastResponse.failureReason(),
                        lastResponse, history, elapsed(start));
            }

            // Determine next step via router, or advance sequentially
            Function<PipelineExecution, String> router = step.router();
            if (router != null) {
                PipelineExecution executionAfter = new PipelineExecution(task, history, stepCount);
                String nextStep = router.apply(executionAfter);
                log.debug("Pipeline router [{}] → [{}]", currentStep, nextStep);
                currentStep = (nextStep == null || nextStep.isBlank()) ? null : nextStep;
            } else {
                // Advance to the next declared step, or end if this was the last
                int idx = stepOrder.indexOf(currentStep);
                currentStep = (idx >= 0 && idx + 1 < stepOrder.size()) ? stepOrder.get(idx + 1) : null;
            }
        }

        return PipelineResult.success(
                lastResponse != null ? lastResponse.content() : "",
                lastResponse, history, elapsed(start));
    }

    private static Duration elapsed(Instant start) {
        return Duration.between(start, Instant.now());
    }

    public static Builder    builder()    { return new Builder(); }
    public static FsmBuilder fsmBuilder() { return new FsmBuilder(); }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static final class Builder {

        private final List<String>              stepOrder   = new ArrayList<>();
        private final Map<String, PipelineStep> steps       = new LinkedHashMap<>();
        private final Map<String, IntentRouter> classifiers = new LinkedHashMap<>();
        private int maxSteps = DEFAULT_MAX_STEPS;

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
            return route(name, execution -> null);
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
            classifiers.put(name, router);
            return route(name, router);
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
            classifiers.forEach((stepName, router) -> router.targets().stream()
                    .filter(target -> !steps.containsKey(target))
                    .findFirst()
                    .ifPresent(target -> {
                        throw new IllegalStateException(
                                "classify('" + stepName + "') routes to undeclared step '" + target
                                        + "' — declared steps: " + String.join(", ", stepOrder));
                    }));
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

            // Routers: terminal → stop, explicit transitions, else sequential
            for (String state : states.keySet()) {
                if (terminals.contains(state)) {
                    b.route(state, __ -> null);
                } else if (transitions.containsKey(state)) {
                    b.route(state, transitions.get(state));
                }
            }

            return b.build();
        }
    }
}
