# io.ara.runtime.pipeline

Sequences a chain of `AraAgent` steps into a single unit of work, with optional
conditional routing — and, since `PipelineStrategy`/`PipelineAgents` were rebuilt to
host pipelines inside a real `AgentInstance`, makes that unit of work a first-class
`AraAgent` in its own right, indistinguishable from any other agent to the rest of ARA.

## Classes

| Class | Role |
|---|---|
| `AgentPipeline` | The orchestrator: declares named steps, wires optional routers between them, runs the sequence. Not an `AraAgent` itself — no identity, no config, no lifecycle. |
| `PipelineStep` | Package-private record: one named step's agent, optional input shaper, optional router — the single source of truth per step (see "Per-step input shaping" below). |
| `PipelineExecution` | Immutable snapshot of the run-so-far, passed to routers *and* input shapers: the original `AgentTask`, `List<StepResult>` history, step count. Exposes `state()` (the run's shared `RunState`) and `resultOf(stepName)` alongside `lastOutput()`. |
| `PipelineResult` | The outcome of a full `AgentPipeline.run(...)` call: success/failure, final output, the *last* step's full `AgentResponse`, the full `stepHistory` (every step's own `AgentResponse`), executed step names, `total*` token/cost aggregates, elapsed time. |
| `PipelineStrategy` | Package-private `ExecutionStrategy` that adapts an `AgentPipeline` to run inside an `AgentInstance`. |
| `PipelineAgents` | Public factory: `PipelineAgents.of(pipeline)` → an `AraAgent` backed by a real `AgentInstance` hosting a `PipelineStrategy`. The only class most callers ever touch directly besides `AgentPipeline` itself. |
| `ParallelAgent` | Public `AraAgent` that fans a task out to N member agents concurrently and merges their responses — see "Fan-out within a step" below. |

`PipelineAgents` is deliberately plural, not `PipelineAgent` — a near-mirror of
`AgentPipeline` (same two words, swapped) reads as an easy mix-up, and this class is a
static factory, not an agent instance itself. The plural also matches this codebase's
existing convention for that kind of utility class (`AraAgents`, used e.g. by
`LocalAgentScheduler` for `executeAsync(...)`).

## `AgentPipeline`

Two ways to build one:

- **`AgentPipeline.builder()`** — free-form: `.step(name, agent)` in declaration order,
  optional `.route(stepName, execution -> nextStepName)` per step, and an optional
  `.step(name, agent, execution -> builtTask)` overload to override how that step's task
  is built — see "Per-step input shaping" below. No router on a step means "advance to
  the next declared step, or stop if this was the last one." `.route(...)` throws
  `IllegalArgumentException` if `stepName` was never declared via `.step(...)` — it used
  to silently create an orphaned router entry that could never fire; a typo in a step
  name is now a build-time failure, not a pipeline that quietly runs sequentially forever.
- **`AgentPipeline.fsmBuilder()`** — declarative FSM framing over the same builder:
  `.state(name, agent)`, `.initial(name)` (defaults to the first declared state),
  `.terminal(names...)` (ends the pipeline successfully), `.transition(from, to)` or
  `.transition(from, execution -> next)`. Compiles down to a plain `Builder` internally
  — terminal states get a router that always returns `null`, everything else gets its
  declared transition.

**Routing contract**: a router receives the *post-step* `PipelineExecution` and returns
the next step's name, or `null`/blank to end the pipeline immediately (success). Routers
are the only thing that ever sees pipeline-level state — the step agents themselves only
ever see a plain `AgentTask`, unaware they're part of a pipeline at all. This is
deliberate: validation/transformation belongs in each agent's own `AgentContract`, not in
the pipeline.

**Two `run` overloads**:
- `run(String initialInput)` — convenience; wraps the string in `AgentTask.of(...)` and
  delegates to the other overload.
- `run(AgentTask task)` — the real implementation. Every step receives
  `task.withInput(currentStepInput)`, not a fresh `AgentTask.of(...)` — so
  `attachments()`, `context()`, `sessionId()`, and `hints()` on the incoming task survive
  into *every* step untouched; only `input()` changes step to step. All steps also share
  `task.taskId()`, which is a free correlation key across the whole run in logs/traces —
  grep one taskId, see every step.

**`maxSteps`** (default 20) is a hard cap on total step *executions*, including retries
via a looping router — not a cap on distinct step names. A router that always returns its
own step name will hit this and fail the pipeline with an explicit "exceeded maximum step
count" reason rather than looping forever.

## Per-step input shaping

By default a step receives `execution.task().withInput(execution.lastOutput())` — the
raw output of whichever step ran immediately before it, nothing more. That is often not
enough: a step may need to combine *two* earlier named steps, not just the last one. The
third `step(...)` overload accepts a `Function<PipelineExecution, AgentTask>` that builds
the whole task instead:

```java
AgentPipeline pipeline = AgentPipeline.builder()
        .step("analyze",  analystAgent)
        .step("critique", criticAgent)
        // "write" sees BOTH "analyze" and "critique" by name, not just "critique"'s
        // raw output — impossible with the default lastOutput()-only behavior.
        .step("write", writerAgent, execution -> execution.task().withInput(
                "Topic: "       + execution.resultOf("analyze").orElseThrow().output()
                + " / Improve: " + execution.resultOf("critique").orElseThrow().output()))
        .build();
```

Internally, `stepOrder`/`steps`/`routers` used to be three independently-mutated parallel
`Map`/`List` structures keyed by the same step name — the classic "wrong data structure"
smell: nothing enforced that a name present in one was present in the others, and adding
a fourth per-step concern (this one) would have meant a fourth parallel map. They're now
a single `Map<String, PipelineStep>` (`stepOrder` survives only as a `List<String>`
derived once at construction, needed because `Map.copyOf` does not guarantee it preserves
insertion order even from a `LinkedHashMap` source — sequential "advance to next step"
logic must never rely on the map's own iteration order). `.route(...)` now reads-and-
replaces a `PipelineStep` in that one map instead of writing into a separate one.

## `RunState` is shared across every step, by construction

Every step's task derives from the *same* `PipelineExecution.task()` (directly, or via a
custom input shaper — both paths start from `execution.task()`), so `execution.state()`
— the run's `RunState` (ADR-041 rev. 3) — is the identical object for every step. A step
that writes `execution.state().put(...)` (or, more commonly, a tool it calls does) makes
that value visible to every later step's router *and* input shaper, no wiring required:

```java
.step("read", readerAgent, execution -> execution.task().withInput(
        execution.state().get("factFromEarlierStep", String.class).orElse("(nothing yet)")))
```

This is deliberately unconditional — unlike `AgentDelegationTool`'s `DelegateStateAccess`
(`SHARED`/`OVERLAY`/`ISOLATED`, a policy the *delegating* agent chooses per delegate),
pipeline steps have no equivalent knob. The two are different axes: delegation is
LLM-driven and crosses an authorization boundary (a sub-agent the caller may not fully
trust), so it needs a policy; a pipeline's steps are declared by the same caller that
built the `AgentPipeline` in the first place, so there is no boundary to police — sharing
by construction is the correct default, not a simplification pending a future policy.

## `PipelineExecution` / `PipelineResult`

- `PipelineExecution.task()` — the original `AgentTask` passed to `run(...)`. Carries the
  canonical component; `initialInput()` is a derived accessor (`task().input()`) kept for
  the pre-input-shaping call sites that only ever needed the string.
- `PipelineExecution.state()` — `task().runContext().state()`, this run's shared
  `RunState` — see "`RunState` is shared across every step" above.
- `PipelineExecution.lastOutput()` — the most recent step's output, or the pipeline's
  original input if no step has run yet. This is what feeds the next step's input when no
  custom input shaper is declared for that step.
- `PipelineExecution.resultOf(stepName)` — most recent result for a given step name,
  searched from the end of history — so inside a retry loop, `resultOf("loop")` always
  gives you the latest attempt, not the first one. The primitive both routers and input
  shapers use to look further back than just the immediately preceding step.
- `PipelineResult.lastResponse()` is still the **full** `AgentResponse` from only the
  *last* executed step, kept for callers that only ever cared about that. For anything
  spanning the whole run, `PipelineExecution.StepResult` now carries the full
  `AgentResponse` for *its* step too, and `PipelineResult.stepHistory()` retains every
  step's `StepResult` (including a step that ultimately failed) — so
  `PipelineResult.totalInputTokens()` / `totalOutputTokens()` / `totalTokens()` /
  `totalCost()` sum across every step, not just the last one. `stepsExecuted()` is
  simply `stepHistory().stream().map(StepResult::stepName).toList()`.

## `PipelineAgents` / `PipelineStrategy` — pipeline as a real agent

### Why this isn't a hand-rolled `AraAgent`

An earlier version of this factory (then a class named `PipelineAgent`, singular)
implemented `AraAgent` directly: its own `AgentId`/`AgentConfig`, a single
`volatile AgentState state` field guarding concurrent `execute()` calls, and manual
`PipelineResult` → `AgentResponse` translation. That design re-derived — badly and
partially — a lifecycle `AgentInstance` already owns:

- **No per-session concurrency**: one shared `state` field meant a second concurrent
  `execute()` call threw `IllegalStateException("not IDLE")` regardless of whether it
  was even for a different session.
- **No context propagation**: the pipeline only ever received `task.input()` as a bare
  string; `attachments()`, `context()`, `sessionId()` never reached the step agents.
- Every future gap in that hand-rolled lifecycle (cancellation, busy policy, telemetry
  spans, …) would have to be independently re-derived and would silently drift from
  `AgentInstance`'s real behavior.

The fix: **don't re-derive the lifecycle, host inside the one that already exists.**
`PipelineStrategy` is a normal `ExecutionStrategy` — the exact same extension point
`ReactStrategy`/`PlanExecuteStrategy`/`ReflexionStrategy` use — and `PipelineAgents.of(...)`
builds a real `AgentInstance` around it instead of a bespoke class. Every property listed
above now falls out of `AgentInstance` for free: per-session isolation and
`sessionBusyPolicy()` (ADR-016), cooperative cancellation, the `agent.execute` telemetry
span, the interceptor chain — none of it is re-implemented here.

### `PipelineStrategy`

Package-private; the only thing `PipelineAgents` builds it for. `execute(task, llm,
memory, tools, config)` calls `pipeline.run(task)` and translates `PipelineResult` into
`ExecutionResult`, using `PipelineResult.totalInputTokens()`/`totalOutputTokens()` for
token counts (summed across every step, see above) and concatenating every step's own
execution-step trace, in execution order.

`llm`, `memory`, and `tools` are received (the `ExecutionStrategy` contract requires
them) but never touched — a pipeline's actual work happens inside its step agents, each
of which resolves its own collaborators independently. `AgentInstance` still seeds
working memory with a system prompt and resolves an `LlmClient` before calling `execute`;
harmless, unread bookkeeping, not a correctness concern.

**Why `strategyName()` is a constructor parameter, not a constant**: `PipelineAgents.of(id,
config, pipeline)` accepts an arbitrary caller-supplied `AgentConfig`, whose
`plannerStrategy()` might already be set to anything (or left at `AgentConfig`'s own
default, `"react"`). Rather than forcing every caller to also remember
`.plannerStrategy("pipeline")`, the dedicated `ExecutionPlanner` built for that one agent
registers `PipelineStrategy` under *whatever name the config already carries* — so
`planner.select(config)` always finds an exact match, regardless of what's in `config`,
and never falls through to `ExecutionPlanner`'s own `"react"` default (which wouldn't be
registered in this single-strategy planner at all, and would throw). The convenience
factory `PipelineAgents.of(pipeline)` explicitly sets `plannerStrategy(PipelineStrategy.DEFAULT_STRATEGY_NAME)`
(`"pipeline"`) on the config it builds, purely so logs/telemetry say something
meaningful ("selected strategy [pipeline]") instead of misleadingly implying a ReAct loop
ran.

### `PipelineAgents`

Pure factory (private constructor, static methods only):

- `of(AgentPipeline pipeline)` — auto-generated `AgentId`, default `AgentConfig`
  (`agentType("pipeline")`, `plannerStrategy("pipeline")`).
- `of(AgentId agentId, AgentConfig config, AgentPipeline pipeline)` — explicit identity
  and config, e.g. to set `sessionBusyPolicy()`, `systemPrompt()` (unused by the strategy
  but still seeded into working memory — see above), or any other `AgentConfig` field a
  caller wants to control.

Internally builds one `AgentInstance` per call, wired with:
- a throwing `NoopLlmClient` stand-in (`AgentInstance`'s constructor rejects a null
  client when no router is supplied; `PipelineStrategy` never calls it, so it throws
  loudly on `complete()` rather than silently returning a bogus completion if some future
  change accidentally wires an LLM call into the pipeline path),
- a fresh `InMemoryMemoryManager` per session (never read by the strategy, just satisfies
  `AgentInstance`'s per-session memory factory contract),
- `ToolRegistry.empty()` (the pipeline dispatches no tools itself — its steps do, each
  through their own registry),
- a fresh, single-strategy `ExecutionPlanner` (see above),
- an empty `AgentInterceptorChain`.

A `PipelineAgents`-built agent can be:
- registered in `AgentRegistry` and discovered like any other agent,
- used as a node inside an `AgentGraph` (`ara-graph`),
- delegated to via `AgentDelegationTool` by a supervisor LLM,
- nested inside another pipeline as a step (pipeline-in-pipeline),
- protected by ADR-033 scope-based authorization —

all without any special-casing anywhere else in ARA, because it's a plain `AraAgent`
from every caller's point of view.

## Usage

```java
AgentPipeline pipeline = AgentPipeline.fsmBuilder()
        .state("draft",  draftAgent)
        .state("review", reviewAgent)
        .state("done",   doneAgent)
        .initial("draft")
        .terminal("done")
        .transition("draft",  "review")
        .transition("review", exec ->
                exec.lastOutput().contains("APPROVED") ? "done" : "draft")
        .build();

AraAgent pipelineAgent = PipelineAgents.of(pipeline);
registry.register(pipelineAgent);

// Attachments, context, and sessionId now reach every step (draft/review/done),
// not just the initial input string.
AgentTask task = AgentTask.of("Write a report on AI trends")
        .withSessionId(SessionId.of("user-42-session"))
        .withAttachment("securityContext", securityContext);

AgentResponse response = pipelineAgent.execute(task);
```

With per-step input shaping and shared `RunState` (see above):

```java
AgentPipeline pipeline = AgentPipeline.builder()
        .step("extract", extractAgent)   // writes a fact via a tool: execution.state().put("topic", ...)
        .step("draft",   draftAgent)
        .step("polish", polishAgent, execution -> execution.task().withInput(
                "Draft: " + execution.resultOf("draft").orElseThrow().output()
                + " | Topic: " + execution.state().get("topic", String.class).orElse("unknown")))
        .build();
```

Pipeline-in-pipeline:

```java
AraAgent inner = PipelineAgents.of(innerPipeline);

AgentPipeline outer = AgentPipeline.builder()
        .step("enrich",  enrichAgent)
        .step("process", inner)     // a whole pipeline, used as a single step
        .step("format",  formatAgent)
        .build();
```

## Fan-out within a step

`AgentPipeline` itself is strictly sequential — one step at a time, by construction (see
"Two `run` overloads" above). Running several agents concurrently on the same input is a
step's own business, not the pipeline's: `ParallelAgent` is a plain `AraAgent` that does this,
so it composes with everything a pipeline step already composes with, and `AgentPipeline.Builder#parallel`
is a thin convenience over `step(name, new ParallelAgent(...))`:

```java
AgentPipeline pipeline = AgentPipeline.builder()
        .parallel("gather",
                List.of(sourceA, sourceB, sourceC),
                runtime.executor(),
                AgentChain.MergeStrategy.joining("\n\n"))
        .step("summarize", summarizerAgent)
        .build();
```

Every member receives the identical task — same `attachments()`, `sessionId()`, and
`runContext()` (including `RunState`, ADR-041) the step itself would have received — and the
merged `AgentResponse` becomes that step's output, feeding `"summarize"` exactly like any
other step's output would. Members that write to `runContext().state()` race with each other;
use `RunState.merge` there, not `put`.

Aggregation reuses the same primitives as `AgentFuture.allOf` elsewhere in ARA:
`AgentChain.MergeStrategy` (`joining`, `rawJsonConcat`, `firstWins`, or your own) decides how N
responses become one, and `AgentChain.FailurePolicy` (`FAIL_FAST` default, `PARTIAL_OK`,
`REQUIRE_ALL`) decides what a partial failure means for the step — which, transitively, is
what it means for the pipeline, since a failed step fails the whole run.

`ParallelAgent.currentState()` always reports `IDLE` and never gates concurrent `execute()`
calls behind a shared field — seeded by that hand-rolled `PipelineAgent`'s single `volatile
AgentState` bug described above ("No per-session concurrency"). Nested fan-out
(`ParallelAgent` inside `ParallelAgent`, or a pipeline whose own step is a `ParallelAgent`)
works for the same reason everything else in this package composes: it is just an `AraAgent`.

By default `ParallelAgent` builds its own `AgentConfig.defaults().agentType("parallel")`
internally — for a caller that needs a custom `agentType()`, `tags()`, or other config the
default doesn't set, every constructor has an overload that takes an explicit `AgentConfig`
instead, the same shape `PipelineAgents.of(agentId, config, pipeline)` already offered:

```java
AgentConfig config = AgentConfig.defaults()
        .agentId(AgentId.of("gather"))
        .agentType("research-fanout")
        .build();

ParallelAgent gather = new ParallelAgent(AgentId.of("gather"), config,
        List.of(sourceA, sourceB, sourceC), runtime.executor(),
        AgentChain.MergeStrategy.joining("\n\n"));
```

## Advanced usage

### Bounded retry loop with a graceful give-up

`maxSteps` is a blunt safety net — it fails the whole pipeline once every step in the
run, across every loop, hits the cap. A step that should retry a *bounded* number of
times and then fall back to something else can check `PipelineExecution.attemptsOf(name)`
— how many times `name` has already run, counted from `history()` — instead of
maintaining its own counter:

```java
AgentPipeline pipeline = AgentPipeline.builder()
        .step("generate", generatorAgent)
        .step("validate", validatorAgent)
        .step("giveUp",   fallbackAgent)
        .route("validate", execution -> {
            if (execution.lastOutput().contains("VALID")) {
                return null; // done — validation passed
            }
            return execution.attemptsOf("generate") < 3 ? "generate" : "giveUp";
        })
        .maxSteps(12) // safety net well above the 3 intended retries; should never fire
        .build();
```

`giveUp` still runs through the normal step machinery — its output becomes
`PipelineResult.finalOutput()` on a *successful* run, distinct from hitting `maxSteps`
(which ends the pipeline in `failure()` instead). Reserve `maxSteps` for genuinely
unbounded loops (a bug in a router, an LLM that never emits the expected token) rather
than as the primary way to bound an intentional retry.

For state that isn't simply "how many times did this step run" — an accumulating score,
a value a *tool* sets mid-step, anything that needs to survive independently of step
re-execution — `RunState.merge` (see above) is still the right, concurrency-safe
primitive. `attemptsOf` only covers the step-count case, which is common enough on its
own to deserve a named primitive instead of every caller re-deriving it from `history()`.

### Multi-way branching (not just approve/reject)

The FSM builder's binary `transition(from, router)` scales to any number of outcomes —
`terminal(...)` accepts multiple state names, so a single classification step can route
straight to whichever terminal branch applies instead of chaining binary checks:

```java
AgentPipeline pipeline = AgentPipeline.fsmBuilder()
        .state("classify",  classifierAgent)
        .state("billing",   billingAgent)
        .state("technical", technicalAgent)
        .state("general",   generalAgent)
        .initial("classify")
        .terminal("billing", "technical", "general")
        .transition("classify", execution -> switch (execution.lastOutput().trim().toUpperCase()) {
            case "BILLING"   -> "billing";
            case "TECHNICAL" -> "technical";
            default          -> "general";
        })
        .build();
```

Using the free-form `builder()` for the same shape requires an explicit `route(...) ->
null` on each of `billing`/`technical`/`general` — without it, the default "advance to
the next declared step" behavior would run `technical` right after `billing`. The FSM
builder's `terminal(...)` does this for you, which is why it — not the free-form
builder — is the natural fit for "classify, then stop in exactly one branch".

### Inspecting `PipelineResult` after a failure

A failed step doesn't discard the run — `PipelineResult` still carries whatever the last
*attempted* step returned, plus which steps got that far, which is often enough to
surface a partial answer instead of a bare error:

```java
PipelineResult result = pipeline.run(task);
if (!result.success()) {
    log.warn("Pipeline failed after {} step(s) {}: {}",
            result.stepsExecuted().size(), result.stepsExecuted(), result.failureReason());
    return result.lastResponse() != null ? result.lastResponse().content() : "(no output)";
}
return result.finalOutput();
```

## Conventions / gotchas

- **`AgentPipeline` itself carries no identity or config on purpose** — it's a pure
  sequencing engine, reusable and testable (`pipeline.run("input")` → inspect a rich
  `PipelineResult`) without needing to fabricate an `AgentId`/`AgentConfig` just to
  sequence a few steps. Identity/lifecycle is `PipelineAgents`'s job, added by
  composition — the same split ARA already uses for `ExecutionStrategy` (pure algorithm)
  vs. `AgentInstance` (identity + lifecycle).
- **Token/cost reporting on a pipeline agent is summed across every step**, via
  `PipelineResult.totalInputTokens()`/`totalOutputTokens()`/`totalCost()` — see the
  `PipelineResult.lastResponse()` note above. `AgentResponse.totalTokens()` on the
  *outer* pipeline agent's own response (e.g. via `PipelineAgents.of(...)`) reflects
  this same sum, not just the last step, since `PipelineStrategy` feeds it from these
  aggregates.
- **Every step shares the outer task's `taskId()` and `sessionId()`** — by design, for log
  correlation and session continuity. If two *different* pipeline runs need to be
  distinguishable in a single step agent's own logs, give them distinct `sessionId()`s
  (or accept that `taskId()` alone already disambiguates them).
- **A step agent that itself needs per-run isolation** (e.g. its own `AgentInstance` with
  session-scoped memory) gets it automatically, since the shared `sessionId()` is just
  another piece of task data flowing through `withInput(...)` — the step agent's own
  `AgentInstance.sessionManager` is keyed by that same id, independent of the pipeline
  agent's own session bookkeeping.
- **`RunState` writes inside a pipeline step are immediate and visible to every later
  step**, with no delay and no policy — see "`RunState` is shared across every step"
  above. If a step's own agent is later reused *outside* this pipeline (e.g. delegated to
  directly via `AgentDelegationTool`), that call site's `DelegateStateAccess` policy
  applies instead — the unconditional sharing described here is specific to running
  inside an `AgentPipeline`, not a property of the step agent itself.
- **An input shaper that calls `execution.resultOf(name).orElseThrow()` for a step that
  hasn't run yet** (e.g. a typo'd name, or a step later in `stepOrder`) throws
  `NoSuchElementException` at the moment that step's task is built — there is no
  build-time check that an input shaper only references already-executed steps, unlike
  `.route(...)`'s declared-step-name check.
