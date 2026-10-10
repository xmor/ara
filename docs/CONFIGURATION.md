# Configuration reference

[← back to README](../README.md)

- [AgentConfig — identity](#identity--who-the-agent-is)
- [AgentConfig — LLM](#llm--which-models-to-use-and-how-to-select-them)
- [AgentConfig — execution](#execution--how-tasks-run)
- [AgentConfig — memory](#memory--working-memory-and-conversation)
- [Sessions & concurrency](#sessions--concurrency)
- [Agent Instance Context](#agent-instance-context--private-per-agent-data)
- [Agent scheduling](#agent-scheduling)
- [Agents as documents](#agents-as-documents--define-an-agent-in-a-file)

---

`AgentConfig` is an immutable record that fully describes how an agent is built and
behaves at runtime. It is composed of four sub-records with single responsibility —
`AgentIdentity`, `LlmConfig`, `ExecutionConfig`, `MemoryConfig` — but you always build it
through the flat builder: `AgentConfig.defaults()...build()`.

## Identity — who the agent is

| Builder method | Default | Description |
|---|---|---|
| `agentId(AgentId)` | auto-generated | Unique identifier of the agent instance |
| `agentType(String)` | `"generic"` | Logical type/role of the agent (e.g. `"translator"`, `"analyst"`) |
| `name(String)` | `""` | Human-readable display name |
| `description(String)` | `""` | Free-text description of what the agent does |
| `version(String)` | `"1.0.0"` | Version tag of the agent definition |
| `tags(List<String>)` | empty | Arbitrary labels for grouping/lookup |
| `systemPrompt(String)` | `"You are a helpful AI agent."` | System prompt sent on every LLM call (further shaped by `PromptShaper`s) |
| `promptCatalogId(String)` | `null` | Resolve the system prompt from a prompt catalog instead of inlining it |

For an agent that sets nothing but a role and a prompt, `AgentConfig.of(agentType,
systemPrompt)` builds that config in one call — the same result as
`defaults().agentType(...).systemPrompt(...).build()`.

## LLM — which model(s) to use and how to select them

| Builder method | Default | Description |
|---|---|---|
| `primaryLlm(LlmProfile)` | empty profile | Primary LLM profile; its `modelId` must match a client registered on the runtime |
| `fallbackLlm(LlmProfile)` / `fallbackLlms(List)` | empty | Fallback profiles used according to the selection policy |
| `llmSelectionPolicy(LlmSelectionPolicy)` | `PRIMARY_ONLY` | `PRIMARY_ONLY` — never use fallbacks · `FAILOVER` — on any `shouldFailover()` failure, try the next fallback in declaration order, each candidate carrying a circuit breaker · `ROUND_ROBIN` — distribute calls sequentially across all profiles |
| `logLlmIo(boolean)` | `false` | Log every LLM request/response and each tool call/result at `INFO` |
| `logLlmIoMaxChars(int)` | `1500` | Truncation limit for logged payloads; `0` = no truncation |

Each `LlmProfile` (built with `LlmProfile.builder()`, or `LlmProfile.of("modelId")` for
the shorthand) carries the per-model settings:

| `LlmProfile` field | Default | Description |
|---|---|---|
| `modelId` | `""` | Name of the `LlmClient` registered on the runtime (`AraRuntime.builder().llmClient("name", …)`) |
| `temperature` | `null` | Sampling temperature, range `[0.0, 2.0]`; `null` leaves it to the client, whose own default is `0.7` (set it on the client builder with `temperature(...)`) |
| `topP` | `null` | Nucleus sampling, range `[0.0, 1.0]`; `null` = provider default |
| `maxTokens` | `null` | Max output tokens per completion; `null` = provider default |
| `baseUrl` / `apiKey` / `modelName` | `null` | Optional per-profile overrides of the underlying client's connection settings |
| `streamingEnabled` | `false` | Request streaming completions when the adapter supports it |
| `nativeJsonSchema` | `false` | Use the provider's native structured-output / JSON-schema mode |
| `costInputPer1kTokens` / `costOutputPer1kTokens` (`Money`) | `Money.zero("EUR")` | Unit prices used for cost accounting |
| `costBudget` (`Budget`) | `Budget.unlimited()` | Spending cap for the agent, denominated in `costCurrency` (defaults to `"EUR"`) |
| `reasoningEffort` | `null` | `LOW` / `MEDIUM` / `HIGH`, sent as `reasoning_effort` (OpenAI adapter only; other adapters reject it). **Limit:** a compatible server may accept it and ignore it, and nothing in the response says so — LM Studio with gpt-oss-20b did. For gpt-oss the model's own dial is a line `Reasoning: low\|medium\|high` in the system prompt, which this option does not set |
| `thinkingBudgetTokens` | `null` | Cap on tokens spent thinking (Anthropic adapter only; other adapters reject it) |
| `returnReasoning` | `null` | Record the model's reasoning as `REASONING` steps where the provider returns it. Off unless `true`; the gateway sends it only with `?reasoning=true` |

## Execution — how tasks run

| Builder method | Default | Description |
|---|---|---|
| `plannerStrategy(String)` | `"react"` | Execution strategy: `"react"`, `"respact"`, `"reflact"`, `"plan_execute"`, `"reflexion"`, `"rag+…"` |
| `strategyConfig(StrategyConfig)` | `null` | Typed per-strategy configuration; when set, it also overrides `plannerStrategy` with its own strategy name |
| `enabledTools(List<String>)` | empty | Tool IDs this agent may call — tools are always opt-in per agent |
| `mcpServerIds(List<String>)` | empty | MCP servers whose tools are exposed to this agent |
| `maxIterations(int)` | `10` | Max reasoning-loop iterations (LLM calls) per task before aborting |
| `executionTimeout(Duration)` | 5 minutes | Wall-clock limit for a single task execution |
| `maxTokensPerStep(int)` | `4096` | Token cap requested per LLM call |
| `humanApprovalRequired(boolean)` | `false` | When `true` and an `ApprovalGate` is configured on the runtime, every tool call is routed through the gate before dispatch — the virtual thread parks until a human decision arrives or the request times out |
| `retrieverId(String)` | `null` | Which registered `Retriever` a `"rag+…"` strategy uses; `null` = the runtime's default retriever. Setting it with a non-`rag+` strategy is rejected at construction |
| `knowledgeBaseId(String)` | `null` | Knowledge base the agent searches *as a tool*: combined with `search_documents` in `enabledTools`, it attaches a `KnowledgeBasePromptShaper` |
| `sessionBusyPolicy(SessionBusyPolicy)` | `REJECT` | Same-session concurrency: `REJECT` fails fast with `"Session busy"`, `ENQUEUE` queues FIFO |

## Memory — working memory and conversation

> Two methods in this table are **inert**: setting them has no effect. They are kept only
> so existing source still compiles, and will be removed at a future version bump. Use
> `StrategyConfig` instead — see the note below the table.

| Builder method | Default | Description |
|---|---|---|
| `workingMemoryTokenBudget(int)` | `0` | Token budget for working memory; `0` = unbounded |
| `workingMemoryEviction(String)` | `"drop_middle"` | Eviction policy when the budget is exceeded: `"drop_oldest"`, `"drop_middle"`, or `"summarize"` |
| `maxConversationTurns(int)` | `0` | Max conversation turns kept per session; `0` = unbounded |
| `maxReflections(int)` | `2` | **Inert — setting it has no effect.** Superseded by `StrategyConfig.Reflexion.maxReflections()` / `StrategyConfig.ReflAct.maxReflections()`; kept only for source compatibility |
| `reflectionPrompt(String)` | `null` | **Inert — setting it has no effect.** Superseded by `StrategyConfig.Reflexion.reflectionPrompt()`; kept only for source compatibility |

> To configure reflection behaviour, pass a `StrategyConfig` — the flat `maxReflections` /
> `reflectionPrompt` builder methods above are leftovers from before `StrategyConfig`
> existed and setting them has no effect:
>
> ```java
> .strategyConfig(new StrategyConfig.Reflexion(3, myPrompt, "critic-model"))   // reflexion
> .strategyConfig(new StrategyConfig.ReflAct(3, 2, true, "critic-model"))      // reflact
> ```

---

## Sessions & concurrency

Every task runs inside a **session**. Each `SessionId` owns an isolated state machine and
working memory, so different sessions of the same agent never interfere. Passing no
session id runs in a fresh ephemeral session.

```java
AgentTask t = AgentTask.of("Hello").withSessionId(SessionId.of("user-42"));
AgentResponse r = agent.execute(t);   // synchronous, blocks the caller
```

### Parallel execution

`AraRuntime.submit` runs a task on the shared virtual-thread executor and returns an
`AgentFuture`, so multiple sessions execute concurrently:

```java
AgentFuture f1 = runtime.submit(agent, AgentTask.of("A").withSessionId(SessionId.of("s1")));
AgentFuture f2 = runtime.submit(agent, AgentTask.of("B").withSessionId(SessionId.of("s2")));
f1.get(); f2.get();   // both ran in parallel
```

### Same-session policy — reject vs queue

Two tasks that target the **same** session are governed by
`AgentConfig.sessionBusyPolicy()`. Different sessions always run concurrently regardless
of the policy.

| Policy | Behaviour when the session is already busy |
|---|---|
| `REJECT` (default) | second task fails fast with `"Session busy"` |
| `ENQUEUE` | second task is queued and runs after the first (FIFO) |

```java
AgentConfig cfg = AgentConfig.defaults()
        .agentType("chat")
        .sessionBusyPolicy(SessionBusyPolicy.ENQUEUE)
        .build();
```

### Cancellation & termination

- `agent.terminate(SessionId)` cancels the in-flight task of **one** session without
  affecting other sessions or future tasks. Cancellation is cooperative: the running
  strategy stops at its next boundary and returns a `"Cancelled"` failure.
- `agent.terminate()` shuts the whole agent down permanently (also used by
  `runtime.destroyAgent`).

---

## Agent Instance Context — private per-agent data

Sometimes an agent needs private data — an API key, a tenant id — that must be readable
by **both** prompt shaping and tool execution, but must **never** reach the LLM (not in
the prompt text, not in a tool's JSON argument schema). `AgentInstanceContext` gives every
agent a live, per-agent key-value view backed by a shared `InstanceContextStore`; values
can be updated at any time without recreating the agent.

```java
InstanceContextStore store = new InstanceContextStore();

AraRuntime runtime = AraRuntime.builder()
        .llmClient(llmClient)
        .instanceContextStore(store)
        .toolRegistryFactory(agentCfg -> {
            AgentInstanceContext ctx = store.forAgent(agentCfg.agentId());
            return new SimpleToolRegistry(new MyPrivateApiTool(ctx));
        })
        .build();

store.set(AgentId.of("buddy"), Map.of("api_key", "...", "tenant_id", "acme-corp"));

AgentContract contract = AgentContract.builder()
        .addPromptShaper(PromptTemplate
                .withInstanceContext(store.forAgent(AgentId.of("buddy")))
                .delimiters("{{", "}}"))
        .build();

runtime.createAgent(agentConfig, contract);

// hot update — no reload, no agent recreation; both the shaper and the tool see it
// on their very next invocation
store.set(AgentId.of("buddy"), Map.of("api_key", "...", "tenant_id", "globex-inc"));
```

`MyPrivateApiTool(ctx)` reads `ctx.get("api_key")` inside `execute(...)` — never part of
the tool's `argumentSchema()`, never seen by the LLM. `PromptTemplate.withInstanceContext`
reads the same live view on every `shape()` call (unlike `withDefaults(Map)`, which
freezes its values at construction time).

`AraRuntime.Builder.toolRegistryFactory(...)` is mutually exclusive with the simpler
`toolRegistry(...)` (a single registry shared by every agent) — use it when different
agents need different tool instances. `runtime.instanceContextStore()` exposes the shared
store (auto-created if not supplied); entries are cleared automatically when their agent
is destroyed via `destroyAgent(...)` or `stop()`.

---

## Agent scheduling

`LocalAgentScheduler` is created by the runtime, but agent schedules must be registered
explicitly via `AraRuntime.scheduler()`. A schedule fires either on a fixed interval or on
a cron expression:

```java
// fixed interval
runtime.scheduler().register(AgentSchedule.builder()
        .scheduleId("heartbeat")
        .agentId(agentId)
        .every(Duration.ofMinutes(10))
        .withInput("ping")
        .build());

// cron — every weekday at 09:00
runtime.scheduler().register(AgentSchedule.builder()
        .scheduleId("morning-report")
        .agentId(agentId)
        .cron("0 9 * * MON-FRI")
        .withInput("Generate the daily report")
        .build());
```

The 5-field cron format is `minute hour day-of-month month day-of-week`. Every field
supports the standard syntax — `*`, single values, ranges (`1-5`, `MON-FRI`, wrapping for
day-of-week), steps (`*/15`, `0-30/10`) and comma-separated lists (`1,15,30`,
`MON,WED,FRI`). Day-of-week accepts symbolic names and both `0` and `7` for Sunday. When
day-of-month and day-of-week are both restricted, standard cron OR semantics apply (fires
when either matches). `LocalAgentScheduler` holds schedules in memory — they do not
survive a process restart.

**A schedule never overlaps with itself.** If a trigger fires while that schedule's previous
run is still executing, the tick is dropped and nothing is dispatched. The agent executor is
unbounded (one virtual thread per run), so without this a recurring job whose agent is slower
than its own interval would stack up concurrent executions of itself, each burning LLM calls
and tool dispatches that cannot influence one another. Register several schedules if you do
want several runs of the same agent in flight at once. A dropped tick is not silent: a
`ScheduleExecutionListener` sees the fire, then a completion whose failure reason begins with
`skipped: previous run of this schedule is still in flight`, so it can be told apart from a
genuine agent failure. `triggerNow` is an explicit request rather than a tick and always
dispatches, since the caller is holding the returned future.

---

## Agents as documents — define an agent in a file

An agent can be defined in a file instead of Java. The file is an *agent document*: a tree
that maps one-to-one onto `AgentConfig` (plus the few-shot and schema references of an
`AgentSpec`), read and written through `io.ara.runtime.spec`.

```java
AgentSpecFormats formats = AgentSpecFormats.defaults();      // JSON built in
AgentSpec spec = formats.read(Path.of("triage.json"));        // format chosen by extension

List<String> problems = AgentSpecCheck.problems(spec, runtime); // optional, see below
AraAgent agent = runtime.createAgent(spec.config());

formats.write(spec, Path.of("triage-export.json"));           // and back out
```

```json
{
  "schemaVersion": 1,
  "agent": { "type": "support-triage", "name": "Triage", "systemPrompt": "Classify the request." },
  "llm": { "primary": { "model": "main", "temperature": 0.0 } },
  "execution": { "strategy": { "type": "plan_execute", "maxPlanSteps": 6 },
                 "tools": ["search_documents"], "maxIterations": 6, "timeout": "PT5M" },
  "contract": { "outputSchemaRef": "triage-v1", "outputRepairAttempts": 2 }
}
```

A runnable version is `spec/AgentFromFileExample`.

**Sections.** `agent` (identity; `type` is the only required field and may not be blank or
`"generic"`), `llm`, `execution`, `memory`, `contract` (input/output schema references and
repair attempts) and `fewShotRefs`. The field names follow the tables above: `execution.tools`
is `enabledTools`, `execution.timeout` is `executionTimeout`, and so on. `AgentSpecDocument`'s
javadoc lists them all.

**Editor support.** The format is described by a JSON Schema, packaged in the runtime jar as
`/io/ara/runtime/spec/agent-document.schema.json` (source:
`ara-runtime/src/main/resources/io/ara/runtime/spec/`). Point a file at a copy of it, or at a
URL where you host it, with a top-level `"$schema"` field and an editor will complete field names,
offer the allowed values and flag mistakes as you type; the decoder accepts and ignores that
field. The schema is the first line of defence, not the only one: rules that depend on two
fields (a retriever needs a `rag+` strategy) and
names that do not exist on your runtime are still caught at import and by `AgentSpecCheck`. A
test keeps the schema and the decoder in step.

**Defaults and strictness.**
- A field you leave out takes the `AgentConfig` default, so a hand-written file can be short.
  An export writes every field, so it does not change meaning if a default changes later.
- Types are strict, with no coercion: `"5"` is not an integer, `1` is not `true`.
- An unknown field is an error, with its path (`execution.maxIteration`): a typo never becomes
  a silent default.
- `schemaVersion` is required. A reader rejects a version newer than it knows.
- Durations are ISO-8601 (`"PT5M"`), money is an object with the amount **as a string**
  (`{"amount": "0.002", "currency": "EUR"}`, because a JSON number would lose the scale), a
  budget is `"unlimited"` or `{"cap": {...}}`, enums are their names.
- `strategy` is a name (`"rag+react"`) or an object (`{"type": "plan_execute", ...}`). An
  object whose `type` is none of the built-in names is a custom strategy with free-form `params`.

**Models and tools are names.** `llm.primary.model` is the id of a client registered on the
runtime, never an endpoint, so an API key cannot be written in a file. A profile that carries
an inline transport (`baseUrl`/`modelName`/`apiKey`) cannot be exported, and trying fails
with a message saying so.

**Check the names.** `createAgent` is lenient about two kinds of typo: an unknown tool id is
skipped silently (the agent runs without the tool), and an unknown model id falls back to the
runtime's default client (the agent runs on *another model*). `AgentSpecCheck.problems`
reports both. It is opt-in, because making `createAgent` strict would change behaviour for
every caller that builds configs in Java.

**What a document is not.**
- It is a *definition*: an import always yields a fresh root spec, and an export drops the
  lineage (derivation and status). Anything that must keep a lineage uses the meta-agent's
  own codec.
- It can carry authority: `grantedScopes`, `requiredScopes`, `humanApprovalRequired`,
  `requiresApproval`. Decoding does not decide who may import it, exactly as for a config
  built in Java; treat a document with the trust of a configuration file an operator wrote.
- `agent.id` is exported. Importing the same file twice into one runtime fails (ids are
  unique); remove `id` from the file to get a new instance each time.

**More formats.** A format is an `AgentSpecFormat`: it turns bytes into the document tree and
back, and nothing else (it can be binary). Register it with
`AgentSpecFormats.defaults().with(new MyFormat())`; the agent mapping is not repeated per format.

**Agents that live in another system** (a database with its own schema, say) are imported by
mapping their columns onto a document tree and calling `AgentSpecDocument.decode(JsonNode)`:
you get the type checking, unknown-field detection and error paths without touching
`AgentConfig`'s constructors. Because that makes the field names a contract, any incompatible
change to the document increments `AgentSpecDocument.SCHEMA_VERSION`.
