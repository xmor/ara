<div align="center">
  <img src="docs/assets/logo.png" alt="ARA — Agent Runtime Architecture" width="320"/>
</div>

# ARA — Agent Runtime Architecture for Java

[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/Maven-3.9%2B-blue.svg)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![Website](https://img.shields.io/badge/Website-ara.open--solutions.it-2b2d42.svg)](http://ara.open-solutions.it)

<!-- *[README in italiano](README.it.md)* -->

ARA is a JVM framework for building autonomous agents and multi-agent systems on Java 21.
It combines LLM integration, tool calling, deterministic I/O contracts, and multi-agent
orchestration in a clean, interface-first architecture — no annotation magic, no reflection,
no framework lock-in.

---

## Table of contents

- [Modules](#modules)
- [What you can build](#what-you-can-build)
- [Requirements](#requirements)
- [Quick start — fully offline](#quick-start--fully-offline-no-llm-needed)
- [Connecting a real LLM](#connecting-a-real-llm)
- [LlmException — typed error handling](#llmexception--typed-error-handling)
- [Tool calling](#tool-calling)
- [AgentContract — deterministic I/O](#agentcontract--deterministic-io)
- [PromptShaper — dynamic system prompt](#promptshaper--dynamic-system-prompt)
- [Multi-agent pipeline](#multi-agent-pipeline) <!-- - [Agent graph](#agent-graph--parallel-branches-and-feedback-loops) -->
- [Execution strategies](#execution-strategies)
- [AgentConfig — configurable agent values](#agentconfig--configurable-agent-values)
- [Sessions & concurrency](#sessions--concurrency)
- [Agent Instance Context](#agent-instance-context--private-per-agent-data-adr-036)
- [Runnable examples](#runnable-examples)
- [Architecture](#architecture)
- [Advanced usage](docs/ADVANCED_README.md)
- [Contributing](#contributing)
- [License](#license)

---

## Modules

| Module          | Description                                                                                                                                                               |
|-----------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ara-core`      | Pure interfaces and domain model: `AraAgent`, `LlmClient`, `LlmException`, `MemoryManager`, `ToolRegistry`, `AgentContract`, `ExecutionStrategy`, …                     |
| `ara-runtime`   | Implementations: `AraRuntime`, the execution strategies (`ReactStrategy`, `ReSpActStrategy`, `ReflActStrategy`, `PlanExecuteStrategy`, `ReflexionStrategy`), `ContractEnforcer`, `AgentPipeline`, `ScriptedLlmClient` stub, built-in processors |
| `ara-adapters`  | LangChain4j-backed `LlmClient` adapters for OpenAI, Anthropic and Ollama. No Kotlin, no OkHttp, no Spring. Declares its own LangChain4j BOM.                            |
| `ara-examples`  | Runnable examples for offline (stub) and live (real LLM) scenarios                                                                                                       |

---

## What you can build

- Single agents with any LLM (OpenAI, Anthropic, Ollama, LM Studio, Groq, …)
- Deterministic I/O contracts: sanitize input, validate output, strip markdown fences — zero tokens consumed
- Multi-agent pipelines with conditional routing and FSM-style state machines
<!-- - Agent graphs with parallel branches and feedback loops -->
- Tool calling from LLM responses, including parallel dispatch on virtual threads (Java 21)
- Conversational agents that ask clarifying questions mid-task (`"respact"`) and self-correcting ones that recover from failed tool calls without restarting (`"reflact"`)
- RAG as a strategy decorator — retrieval before every LLM call, no tool configuration needed
- Fully offline testing with `ScriptedLlmClient`

---

## Requirements

- **Java 21+** (virtual threads used for parallel tool dispatch)
- Maven 3.9+

```bash
mvn clean install -DskipTests
```

---

## Quick start — fully offline (no LLM needed)

Add `ara-runtime` to your `pom.xml` (it depends on `ara-core` transitively):

```xml
<dependency>
    <groupId>io.github.xmor</groupId>
    <artifactId>ara-runtime</artifactId>
    <version>1.0.0</version>
</dependency>
```

`ScriptedLlmClient` replays pre-defined responses — ideal for tests and demos:

```java
import io.ara.core.agent.*;
import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;

try (AraRuntime runtime = AraRuntime.builder()
        .llmClient(ScriptedLlmClient.script()
                .thenFinalAnswer("Virtual threads are lightweight JVM threads.")
                .build())
        .build()) {
        
    AraAgent agent = runtime.createAgent(AgentConfig.defaults()
            .agentType("assistant")
            .systemPrompt("You are a concise technical assistant.")
            .build());

    AgentResponse response = agent.execute(AgentTask.of("Explain virtual threads"));
    System.out.println(response.content());
}
```

---

## Connecting a real LLM

Add `ara-adapters` to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.xmor</groupId>
    <artifactId>ara-adapters</artifactId>
    <version>1.0.0</version>
</dependency>
```

Then use `AraLlmClientFactory` or the individual client builders:

```java
import io.ara.adapters.llm.AraLlmClientFactory;
import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.adapters.llm.anthropic.AnthropicLlmClient;
import io.ara.adapters.llm.ollama.OllamaLlmClient;

// OpenAI
LlmClient gpt4o = AraLlmClientFactory.openAi()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .build();

// Anthropic
LlmClient claude = AraLlmClientFactory.anthropic()
        .apiKey(System.getenv("ANTHROPIC_API_KEY"))
        .model(AnthropicLlmClient.Models.CLAUDE_SONNET_4_6)
        .build();

// Ollama (local, no API key needed)
LlmClient llama = AraLlmClientFactory.ollama()
        .model(OllamaLlmClient.Models.LLAMA_3_2)
        .build();

// OpenAI-compatible endpoint (LM Studio, Groq, Together AI, …)
LlmClient local = AraLlmClientFactory.openAi()
        .baseUrl("http://localhost:1234/v1")
        .apiKey("lm-studio")
        .modelName("llama-3.1-8b-instruct")
        .build();
```

**Multi-provider** — each agent references its provider by name:

```java
AraRuntime runtime = AraRuntime.builder()
        .llmClient("fast",  AraLlmClientFactory.openAi().apiKey(KEY).modelName("gpt-4o-mini").build())
        .llmClient("smart", AraLlmClientFactory.openAi().apiKey(KEY).modelName("gpt-4o").build())
        .llmClient("local", AraLlmClientFactory.ollama().modelName("gpt-oss-20b").build())
        .build();

AgentConfig config = AgentConfig.defaults()
        .agentType("analyst")
        .primaryLlm(LlmProfile.of("smart"))   // zero credentials in AgentConfig
        .build();
```

### LLM I/O logging

Turn on `logLlmIo` to trace every LLM request and response — plus each tool call
and its result — at `INFO`, truncated to `logLlmIoMaxChars` (`0` = no truncation):

```java
AgentConfig config = AgentConfig.defaults()
        .agentType("debug")
        .logLlmIo(true)
        .logLlmIoMaxChars(1000)
        .build();
```

Make sure `INFO` is enabled for `io.ara.runtime.llm.LoggingLlmClient` (LLM I/O)
and `io.ara.runtime.strategy.ReactStrategy` (tool calls) to see the full trace.

### OpenTelemetry tracing

Pass an `AraTelemetry` to `AraRuntime.Builder.telemetry(...)` to get a full trace tree —
one `agent.execute` span per task, with `llm.complete` (one per LLM call) and
`tool.execute` (one per tool dispatch) nested as children in call order:

```java
AraTelemetry telemetry = OtelTelemetryFactory.builder()   // ara-adapters
        .serviceName("my-agent-app")
        .exporter("otlp-http")
        .endpoint("http://localhost:4318")
        .build();

AraRuntime runtime = AraRuntime.builder()
        .llmClient(llmClient)
        .telemetry(telemetry)
        .build();
```

Defaults to `AraTelemetry.noop()` — zero overhead beyond an interface dispatch when
tracing isn't configured. `OtelTelemetryFactory.fromEnvironment()` reads the standard
`OTEL_SERVICE_NAME` / `OTEL_EXPORTER_OTLP_ENDPOINT` / `OTEL_EXPORTER_TYPE` variables.

---

## LlmException — typed error handling

All adapters throw `LlmException` with a typed `ErrorType` so the runtime (and your code)
can distinguish retryable from non-retryable failures:

```java
try {
    AgentResponse resp = agent.execute(task);
} catch (LlmException ex) {
    if (ex.isRetryable()) {
        // rate limit, network error, server 5xx → safe to retry
    } else {
        // auth error, invalid request, context length exceeded → fail fast
    }
    System.out.println(ex.errorType());   // RATE_LIMIT, AUTHENTICATION, NETWORK, …
    System.out.println(ex.provider());    // "OpenAI", "Anthropic", "Ollama"
    System.out.println(ex.statusCode());  // 429, 401, 500, …
}
```

`FailoverLlmClient` in `ara-runtime` uses `isRetryable()` automatically to decide whether
to try the next provider in the chain or abort immediately.

---

## Tool calling

Implement `AraTool` and register it in a `ToolRegistry`:

```java
import io.ara.core.tool.*;

class WeatherTool implements AraTool {
    @Override public String toolId()      { return "get_weather"; }
    @Override public String description() { return "Returns current weather for a city."; }
    @Override public String argumentSchema() {
        return """
               {"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}
               """;
    }
    @Override
    public ToolResult execute(String argumentJson) {
        return ToolResult.success(toolId(), "Milan: Sunny, 22°C");
    }
}
```

`ToolRegistry` is a small interface — implement it over whatever tool collection you have:

```java
class SimpleToolRegistry implements ToolRegistry {
    private final Map<String, AraTool> tools;
    SimpleToolRegistry(AraTool... all) {
        tools = Arrays.stream(all).collect(Collectors.toMap(AraTool::toolId, t -> t));
    }
    @Override public List<AraTool> resolveEnabled(List<String> ids) {
        return ids.stream().map(tools::get).filter(Objects::nonNull).toList();
    }
    @Override public Optional<AraTool> findById(String id) {
        return Optional.ofNullable(tools.get(id));
    }
    @Override public ToolResult execute(String id, String argumentJson) {
        return findById(id).map(t -> t.execute(argumentJson))
                .orElseGet(() -> ToolResult.failure(id, "Tool not found: " + id));
    }
}

AraRuntime runtime = AraRuntime.builder()
        .llmClient("live", gpt4o)
        .toolRegistry(new SimpleToolRegistry(new WeatherTool()))
        .build();

AgentConfig config = AgentConfig.defaults()
        .agentType("travel-assistant")
        .systemPrompt("You are a travel assistant. Use tools to fetch real data.")
        .plannerStrategy("react")
        .enabledTools(List.of("get_weather"))
        .build();
```

Ready-made tools (file, code, git, shell, …) live in the `ara-tools` module — see
[Built-in code and shell tools](#built-in-code-and-shell-tools).

### Parallel tool dispatch (Java 21 virtual threads)

When the LLM requests multiple tools in a single response, ARA dispatches them concurrently
on virtual threads automatically — no configuration needed:

```
LLM response → [get_weather(Rome), get_weather(London)]
                      ↓ virtual thread         ↓ virtual thread
                   300 ms I/O              300 ms I/O
                      └──────────── ~300 ms total ──────────────┘
```

---

## AgentContract — deterministic I/O

`AgentContract` declares a processor chain applied before and after every `execute()`.
Validation and transformation happen in pure Java — zero LLM calls:

```java
import io.ara.core.agent.AgentContract;
import io.ara.runtime.contract.*;

AgentContract contract = AgentContract.builder()
        .addInputProcessor(InputSanitizer.instance())
        .addInputProcessor(ContentTruncator.to(4000))
        .addPromptShaper(PromptTemplate.withDefaults(
                Map.of("date", LocalDate.now().toString())))
        .outputSchema(JsonSchemaValidator.forOutput(SCHEMA))
        .addOutputProcessor(MarkdownFenceStripper.instance())
        .addOutputProcessor(JsonSchemaValidator.forOutput(SCHEMA))
        .build();

AraAgent agent = runtime.createAgent(config, contract);
```

### Built-in processors

**Validation**

| Processor | Function |
|---|---|
| `JsonSchemaValidator.jsonOnly()` | Reject if not valid JSON |
| `JsonSchemaValidator.requiring("a","b")` | Reject if required fields are missing |
| `JsonFieldValueValidator.oneOf("status","A","B")` | Reject if field value not in enum set |
| `JsonFieldValueValidator.inRange("score",0,1)` | Reject if numeric value out of range |
| `MinLengthValidator.atLeast(100)` | Reject if output shorter than N chars |
| `MaxLengthValidator.atMost(500)` | Reject if output exceeds N chars |
| `RegexValidator.matching("\\d+\\.\\d+")` | Reject if payload does not match pattern |

**Transform / extract**

| Processor | Function |
|---|---|
| `MarkdownFenceStripper` | Remove ` ```json ` / ` ``` ` wrappers |
| `JsonFieldExtractor.field("path.sub")` | Extract a dot-path field from JSON |
| `CodeFenceExtractor.java()` | Extract content of ` ```java ` fence |
| `WhitespaceNormalizer` | Collapse multiple spaces/newlines |
| `ContentTruncator.to(4000)` | Hard truncate input to N characters |

**Security**

| Processor | Function |
|---|---|
| `InputSanitizer.instance()` | Block prompt-injection patterns (EN + IT) |
| `PiiRedactor.instance()` | Redact email, phone, tax codes, credit cards, IPv4 |

---

## PromptShaper — dynamic system prompt

`PromptShaper` is applied after the `InputProcessor` chain and before the agent executes.
It modifies the system prompt deterministically — zero tokens consumed.

```java
AgentContract contract = AgentContract.builder()
        // lambda shaper — conditional logic
        .addPromptShaper((prompt, task) -> {
            String policy = TENANT_POLICIES.getOrDefault(
                    task.context().getOrDefault("tenant", "default"), "Standard rules.");
            return prompt + "\n\n[Policy]\n" + policy;
        })
        // PromptTemplate — resolves {key} placeholders from task.context()
        .addPromptShaper(PromptTemplate.withDefaults(Map.of("lang", "english")))
        // strict mode — throws IllegalStateException before any LLM call if placeholder unresolved
        .addPromptShaper(PromptTemplate.instance().strict())
        .build();
```

---

## Multi-agent pipeline

```java
AgentPipeline pipeline = AgentPipeline.fsmBuilder()
        .state("draft",  draftAgent)
        .state("review", reviewAgent)
        .state("revise", reviseAgent)
        .state("done",   doneAgent)
        .initial("draft")
        .terminal("done")
        .transition("draft",  "review")
        .transition("review", execution -> {
            if (execution.lastOutput().contains("APPROVED")) return "done";
            long revises = execution.history().stream()
                    .filter(s -> s.stepName().equals("revise")).count();
            return revises >= 3 ? "done" : "revise";
        })
        .transition("revise", "review")
        .maxSteps(12)
        .build();
```

---

<!--
## Agent graph — parallel branches and feedback loops

The graph model and executor live in the `ara-graph` module
(`io.ara.graph.model` / `io.ara.graph.executor`):

```java
AgentGraph graph = new AgentGraph("review-graph",
        List.of(
                AgentGraphNode.inline("translator", "translator", translatorConfig),
                AgentGraphNode.inline("developer",  "developer",  developerConfig),
                AgentGraphNode.inline("evaluator",  "evaluator",  evaluatorConfig),
                AgentGraphNode.inline("reporter",   "reporter",   reporterConfig)
        ),
        List.of(
                AgentGraphEdge.unconditional("e1", "translator", "developer"),
                AgentGraphEdge.unconditional("e2", "developer",  "evaluator"),
                AgentGraphEdge.back("e3", "evaluator", "developer",
                        new EdgeCondition("output_contains", "RETRY")),
                AgentGraphEdge.unconditional("e4", "evaluator", "reporter")
        )
);

GraphExecutor executor = new GraphExecutor(lifecycleManager, registry);
GraphExecutionResult result = executor.execute(graph, ctx);
```

`GraphExecutor` needs an `AgentLifecycleManager` (the runtime's `AgentFactory`
implements it) and an `AgentRegistry` (`runtime.registry()`). `GraphAgent.of(graph,
executor, runtime.executor())` wraps a whole graph as a regular `AraAgent`, so graphs
can be registered, delegated to, or nested as nodes inside other graphs.

---
-->

## Execution strategies

| Strategy | `plannerStrategy` value | Description |
|---|---|---|
| `ReactStrategy` | `"react"` | Reasoning–Action loop: Think → Act → Observe. The default |
| `ReSpActStrategy` | `"respact"` | ReAct + a **speak** action: converse with the user mid-task without closing it |
| `ReflActStrategy` | `"reflact"` | ReAct + **in-loop** self-correction on tool failures or stalled reasoning |
| `PlanExecuteStrategy` | `"plan_execute"` | Generate a structured plan, then execute each step |
| `ReflexionStrategy` | `"reflexion"` | Generate → critique → revise, restarting the whole episode |
| `RetrievalAugmentedStrategy` | `"rag+<name>"` | Inject retrieved context before every LLM call (`"rag+react"`, `"rag+respact"`, `"rag+plan_execute"`, `"rag+reflact"`) |

**Two flavours of self-critique.** `"reflexion"` is a decorator that reacts to a
*whole failed pass*: it wipes working memory and retries the episode from scratch with
the critique injected. `"reflact"` reacts *inside* one pass — a failed tool call or N
unproductive iterations inject a short course-correction into the same working memory
and the loop simply continues, keeping everything already accomplished. Use `"reflact"`
when individual steps fail recoverably, `"reflexion"` when failure is only detectable
after a complete pass; they compose.

**Conversational turns.** `"respact"` adds a third action alongside reason and act: a
SPEAK turn ends the `execute()` call the way a final answer does, but signals
`StepType.SPEAK` as the last entry in `AgentResponse.steps()` so the caller knows to
await a reply on the same `SessionId` instead of treating the task as finished.
`AgentTask.withSpeakCallback(...)` delivers those messages in real time.

```java
AgentConfig config = AgentConfig.defaults()
    .agentType("support-agent")
    .plannerStrategy("reflact")
    .strategyConfig(new StrategyConfig.ReflAct(3, 2, true, "critic-model"))
    .build();
```

All strategies support **cooperative cancellation**: interrupting the executing
thread (see `terminate(SessionId)` below) makes the strategy stop at its next
iteration/phase boundary and return a `"Cancelled"` result. All of them also record a
full **execution trace** in `AgentResponse.steps()` — including the partial trace on
failure paths.

Implementation details, trigger semantics and a "when to use which" guide live in
[`io/ara/runtime/strategy/README.md`](ara-runtime/src/main/java/io/ara/runtime/strategy/README.md).
Registering your own `ExecutionStrategy` on the runtime is covered in
[`docs/ADVANCED_README.md`](docs/ADVANCED_README.md).

---

## AgentConfig — configurable agent values

`AgentConfig` is an immutable record that fully describes how an agent is built and
behaves at runtime. It is composed of four sub-records with single responsibility
— `AgentIdentity`, `LlmConfig`, `ExecutionConfig`, `MemoryConfig` — but you
always build it through the flat builder: `AgentConfig.defaults()...build()`.

### Identity — who the agent is

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

### LLM — which model(s) to use and how to select them

| Builder method | Default | Description |
|---|---|---|
| `primaryLlm(LlmProfile)` | empty profile | Primary LLM profile (see table below); its `modelId` must match a client registered on the runtime |
| `fallbackLlm(LlmProfile)` / `fallbackLlms(List)` | empty | Fallback profiles used according to the selection policy |
| `llmSelectionPolicy(LlmSelectionPolicy)` | `PRIMARY_ONLY` | `PRIMARY_ONLY` — never use fallbacks · `FAILOVER` — on any retryable failure, try the next fallback in declaration order · `ROUND_ROBIN` — distribute calls sequentially across all profiles |
| `logLlmIo(boolean)` | `false` | Log every LLM request/response and each tool call/result at `INFO` (see [LLM I/O logging](#llm-io-logging)) |
| `logLlmIoMaxChars(int)` | `1500` | Truncation limit for logged payloads; `0` = no truncation |

Each `LlmProfile` (built with `LlmProfile.builder()`, or `LlmProfile.of("modelId")` for
the shorthand) carries the per-model settings:

| `LlmProfile` field | Default | Description |
|---|---|---|
| `modelId` | `""` | Name of the `LlmClient` registered on the runtime (`AraRuntime.builder().llmClient("name", …)`) |
| `temperature` | `0.4` | Sampling temperature, range `[0.0, 2.0]` |
| `topP` | `null` | Nucleus sampling, range `[0.0, 1.0]`; `null` = provider default |
| `maxTokens` | `null` | Max output tokens per completion; `null` = provider default |
| `baseUrl` / `apiKey` / `modelName` | `null` | Optional per-profile overrides of the underlying client's connection settings |
| `streamingEnabled` | `false` | Request streaming completions when the adapter supports it |
| `nativeJsonSchema` | `false` | Use the provider's native structured-output / JSON-schema mode |
| `costInputPer1kTokens` / `costOutputPer1kTokens` (`Money`) | `Money.zero("EUR")` | Unit prices used for cost accounting; see [`docs/cost-budget.md`](docs/cost-budget.md) |
| `costBudget` (`Budget`) | `Budget.unlimited()` | Spending cap for the agent, denominated in `costCurrency` (defaults to `"EUR"`) |

### Execution — how tasks run

| Builder method | Default | Description |
|---|---|---|
| `plannerStrategy(String)` | `"react"` | Execution strategy: `"react"`, `"respact"`, `"reflact"`, `"plan_execute"`, `"reflexion"`, `"rag+…"` |
| `strategyConfig(StrategyConfig)` | `null` | Typed per-strategy configuration; when set, it also overrides `plannerStrategy` with its own strategy name |
| `enabledTools(List<String>)` | empty | Tool IDs this agent may call — tools are always opt-in per agent |
| `mcpServerIds(List<String>)` | empty | MCP servers whose tools are exposed to this agent |
| `maxIterations(int)` | `10` | Max reasoning-loop iterations (LLM calls) per task before aborting |
| `executionTimeout(Duration)` | 5 minutes | Wall-clock limit for a single task execution |
| `maxTokensPerStep(int)` | `4096` | Token cap requested per LLM call |
| `humanApprovalRequired(boolean)` | `false` | Route sensitive actions through the HITL approval gate (`AgentState.WAITING`) |
| `knowledgeBaseId(String)` | `null` | Knowledge base to use for RAG retrieval / `search_documents` |
| `sessionBusyPolicy(SessionBusyPolicy)` | `REJECT` | Same-session concurrency: `REJECT` fails fast with `"Session busy"`, `ENQUEUE` queues FIFO (see [Same-session policy](#same-session-policy--reject-vs-queue)) |

### Memory — working memory and conversation

| Builder method | Default | Description |
|---|---|---|
| `workingMemoryTokenBudget(int)` | `0` | Token budget for working memory; `0` = unbounded |
| `workingMemoryEviction(String)` | `"drop_middle"` | Eviction policy when the budget is exceeded: `"drop_oldest"`, `"drop_middle"`, or `"summarize"` |
| `maxConversationTurns(int)` | `0` | Max conversation turns kept per session; `0` = unbounded |
| `maxReflections(int)` | `2` | **Inert — no strategy reads it.** Superseded by `StrategyConfig.Reflexion.maxReflections()` / `StrategyConfig.ReflAct.maxReflections()`; kept only for source compatibility |
| `reflectionPrompt(String)` | `null` | **Inert — no strategy reads it.** Superseded by `StrategyConfig.Reflexion.reflectionPrompt()`; kept only for source compatibility |

> To configure reflection behaviour, pass a `StrategyConfig` — the flat `maxReflections`/
> `reflectionPrompt` builder methods above are leftovers from before `StrategyConfig`
> existed and setting them has no effect:
>
> ```java
> .strategyConfig(new StrategyConfig.Reflexion(3, myPrompt, "critic-model"))   // reflexion
> .strategyConfig(new StrategyConfig.ReflAct(3, 2, true, "critic-model"))      // reflact
> ```

---

## Sessions & concurrency

Every task runs inside a **session**. Each `SessionId` owns an isolated
state machine and working memory, so different sessions of the same agent never
interfere. Passing no session id runs in a fresh ephemeral session.

```java
AgentTask t = AgentTask.of("Hello").withSessionId(SessionId.of("user-42"));
AgentResponse r = agent.execute(t);   // synchronous, blocks the caller
```

### Parallel execution

`AraRuntime.submit` runs a task on the shared virtual-thread executor and returns
an `AgentFuture`, so multiple sessions execute concurrently:

```java
AgentFuture f1 = runtime.submit(agent, AgentTask.of("A").withSessionId(SessionId.of("s1")));
AgentFuture f2 = runtime.submit(agent, AgentTask.of("B").withSessionId(SessionId.of("s2")));
f1.get(); f2.get();   // both ran in parallel
```

### Same-session policy — reject vs queue

Two tasks that target the **same** session are governed by
`AgentConfig.sessionBusyPolicy()`. Different sessions always run concurrently
regardless of the policy.

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

- `agent.terminate(SessionId)` cancels the in-flight task of **one** session
  without affecting other sessions or future tasks. Cancellation is cooperative:
  the running strategy stops at its next boundary and returns a `"Cancelled"`
  failure.
- `agent.terminate()` shuts the whole agent down permanently (also used by
  `runtime.destroyAgent`).

---

## Agent Instance Context — private per-agent data

Sometimes an agent needs private data — an API key, a tenant id — that must be
readable by **both** prompt shaping and tool execution, but must **never** reach the
LLM (not in the prompt text, not in a tool's JSON argument schema). `AgentInstanceContext`
gives every agent a live, per-agent key-value view backed by a shared
`InstanceContextStore`; values can be updated at any time without recreating the agent.

```java
InstanceContextStore store = new InstanceContextStore();

AraRuntime runtime = AraRuntime.builder()
        .llmClient(llmClient)
        .instanceContextStore(store)
        .toolRegistryFactory(agentCfg -> {
            AgentInstanceContext ctx = store.forAgent(agentCfg.agentId());
            // any ToolRegistry implementation holding the tool instance
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
agents need different tool instances. `runtime.instanceContextStore()` exposes the
shared store (auto-created if not supplied); entries are cleared automatically when
their agent is destroyed via `destroyAgent(...)` or `stop()`.

---

## Runnable examples

All examples are in `ara-examples`.

| Class | LLM | What it shows |
|---|---|---|
| `basics/AraSimpleExample` | stub | End-to-end: ReAct loop, tool call, interceptor, agent reuse |
| `basics/AraSimpleExampleLive` | **live** | Same as above but with a real LLM via `OpenAiLlmClient` |

### Running `AraSimpleExampleLive`

Set the three constants at the top of the class, then run `main()`:

```java
private static final String BASE_URL = "http://localhost:1234/v1";  // null for OpenAI hosted
private static final String API_KEY  = System.getenv("OPENAI_API_KEY");
private static final String MODEL    = "gpt-4o-mini";
```

Expected output:

```
=== ARA Agent Runtime — Live Demo ===

Agent created  : b8aa77ba-…
Initial state  : IDLE

Submitting task: What does the echo tool say about 'Hello ARA'?

  [EchoTool] executing with: Hello ARA
  [Interceptor] ← after(Executing) result=The echo tool outputs "Hello ARA".

=== Result ===
Success        : true
Final state    : DONE
Content        : The echo tool outputs "Hello ARA".
Iterations     : 2
Tokens         : 563
Elapsed        : 1417ms
```

---

## Why ARA
- **Plain Java, no magic.** Pure interfaces — zero annotations, zero reflection, no Kotlin
  runtime, no Spring. The call stack you debug is the call stack you wrote. True of
  `ara-core`, `ara-runtime` and `ara-adapters`; the optional `ara-gateway` module (Javalin,
  for its native path-param routing and first-class SSE) is the one exception — it pulls in
  Jetty and a Kotlin runtime, but only for whoever explicitly adds it.
- **Deterministic I/O contracts.** `AgentContract` validates, sanitises and transforms in
  plain Java before and after every call, spending **zero tokens**. No other framework in
  the table below ships an equivalent.
- **Java 21 by design.** Virtual threads are the concurrency model, not an option: when the
  LLM asks for several tools at once they are dispatched in parallel automatically, with no
  executor to wire up or thread pool to tune.
- **A runtime, not a toolkit.** Execution strategies, FSM pipelines,<!-- agent graphs, --> session
  isolation, human-in-the-loop and cost budgets come in the box rather than assembled from
  parts.
- **Built on LangChain4j, not against it.** Provider integration is inherited through
  `ara-adapters`, so you get LangChain4j's provider coverage *plus* the runtime on top.

### Capabilities that rarely come as one piece
- **Human-in-the-loop as a runtime primitive** — `AgentState.WAITING`, an approval gate and
  pluggable notifiers. `ApprovalDecision` is a sealed interface, so handling approve /
  reject / modify exhaustively is enforced by the compiler, not by convention.
- **Private per-agent data**  —
  `AgentInstanceContext` holds API keys or tenant ids that **both** prompt shaping and tool
  execution can read, and that never reach the LLM: not in the prompt text, not in a tool's
  argument schema. Values update live, without recreating the agent.
- **Per-agent cost accounting** — `costInputPer1kTokens` / `costOutputPer1kTokens` and a
  `costBudget` cap are part of the LLM profile, not an afterthought.
- **Session isolation and cooperative cancellation** — every task runs in a session with its
  own state machine and working memory. `SessionBusyPolicy` decides reject-vs-queue, and
  `terminate(SessionId)` cancels one session without touching the others.
- **A full execution trace, always** — `AgentResponse.steps()` records the reasoning and
  tool trace on every run, including the partial trace when a run fails.
- **Conversation and self-correction inside the loop** — `"respact"` lets an agent ask a
  clarifying question mid-task and resume on the same session; `"reflact"` recovers from a
  failed tool call without discarding what the run already accomplished. Most frameworks
  make you restart the episode.
  
### Knowledge base / RAG retrieval (Qdrant or in-memory)
`KnowledgeBaseService` provides semantic search over documents. The default store is `InMemoryDocumentStore`
(no external dependency). For persistent vector search add a Qdrant config:

```java
QdrantConfig qdrant = QdrantConfig.builder()
        .host("localhost").port(6334)
        .collectionName("my-docs")
        .build();

KnowledgeBaseService kb = KnowledgeBaseService.builder()
        .documentStore(new QdrantSemanticStore(qdrant))
        .build();

AraRuntime runtime = AraRuntime.builder()
        .llmClient("live", gpt4o)
        .knowledgeBase(kb)
        .build();
```

Enable RAG retrieval by setting `plannerStrategy("rag+react")` on the `AgentConfig` and
registering the `search_documents` tool.

### Agent scheduling
`LocalAgentScheduler` is created by the runtime but agent schedules must be registered explicitly
via `AraRuntime.scheduler()`:

```java
runtime.scheduler().schedule(agentId, AgentSchedule.fixedRate(Duration.ofMinutes(10)));
```
## Contributing

Code style is enforced through [`docs/CODING-GUIDELINES.md`](docs/CODING-GUIDELINES.md) —
simplicity first, no premature abstractions, short single-responsibility functions,
honest naming, and a taxonomy of what to keep vs. avoid in comments. Read it before
opening a PR.

ARA is developed with heavy AI assistance under human architectural review. Design
decisions are recorded as ADRs in [`docs/adr/`](docs/adr/), and every change is expected to
pass the checklist in [`docs/CODING-GUIDELINES.md`](docs/CODING-GUIDELINES.md). Commits
carry `Co-Authored-By` trailers where that applies.

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
