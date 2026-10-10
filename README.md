<div align="center">

<img src="docs/assets/logo.png" alt="ARA — Agent Runtime Architecture" width="320"/>

# ARA — Agent Runtime Architecture for Java

**Build autonomous AI agents and multi-agent systems in plain Java 21.**
LLM integration, tool calling, deterministic I/O contracts, RAG and human-in-the-loop —
no annotations, no reflection, no Spring, no Kotlin.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.xmor/ara-runtime.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.xmor/ara-runtime)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](LICENSE)
[![Last update](https://img.shields.io/maven-central/last-update/io.github.xmor/ara-runtime.svg?label=Last%20update)](https://central.sonatype.com/artifact/io.github.xmor/ara-runtime)
[![Stars](https://img.shields.io/github/stars/xmor/ara?style=flat&label=Stars)](https://github.com/xmor/ara/stargazers)

[**Quick start**](#quick-start--60-seconds-no-api-key) ·
[**Documentation**](#documentation) ·
[**Examples**](#runnable-examples) ·
[**Website**](http://ara.open-solutions.it) ·
[**Discussions**](https://github.com/xmor/ara/discussions)

</div>

---

ARA is a **Java 21 agent runtime**: a place where agents actually *run*, with sessions,
memory, execution strategies, cost budgets, approval gates and a full execution trace —
not a thin wrapper around a chat completion endpoint.

It works with **OpenAI, Anthropic, Ollama, Mistral, LM Studio, Groq** and any
OpenAI-compatible endpoint, and it is published on **Maven Central** as
[`io.github.xmor:ara-runtime`](https://central.sonatype.com/artifact/io.github.xmor/ara-runtime).

```
Your code ──▶ AraRuntime ──▶ Agent (strategy + contract + session)
                                │
                     ┌──────────┼───────────┐
                     ▼          ▼           ▼
                 LlmClient   Tools      Retriever
             OpenAI/Claude/  (yours,   (in-memory
             Ollama/Mistral  parallel   or Qdrant)
                             on vthreads)
```

---

## Quick start — 60 seconds, no API key

**1. Add the dependency** (check the badge above for the latest version):

```xml
<dependency>
    <groupId>io.github.xmor</groupId>
    <artifactId>ara-runtime</artifactId>
    <version>1.0.3</version>
</dependency>
```

**2. Run an agent offline.** `ScriptedLlmClient` replays canned responses, so the first
run needs no key, no network and no local model:

```java
import io.ara.runtime.AraRuntime;
import io.ara.runtime.stubs.ScriptedLlmClient;

try (var runtime = AraRuntime.builder()
        .llmClient(ScriptedLlmClient.script()
                .thenFinalAnswer("Virtual threads are lightweight JVM threads.")
                .build())
        .build()) {

    System.out.println(runtime.askText("Explain virtual threads"));
}
```

> Virtual threads are lightweight JVM threads.

That is the whole loop, runnable as `basics/MinimalAgentExample`. **What just happened:**
`runtime.askText(...)` runs the prompt on a shared default agent and returns the answer
string directly — the runtime auto-started on first use, and `close()` shuts it down. The
default `react` strategy called the LLM, ended at a final answer, and returned it.

When the agent needs a role, a system prompt, tools or a specific model, create one
explicitly and call `agent.execute(...)`: you get the full `AgentResponse` — iteration
count, token usage, cost and step trace — instead of just the string.
`AgentConfig.of("assistant", "...")` is the one-line form; reach for
`AgentConfig.defaults()` as soon as a third field is involved. See `basics/AraSimpleExample`
for that fuller shape.

**3. Swap in a real model** — one line changes, everything else stays:

```java
LlmClient gpt4o = AraLlmClientFactory.openAi()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .modelName("gpt-4o")
        .build();

// or fully local, no API key:
LlmClient llama = AraLlmClientFactory.ollama()
        .model(OllamaLlmClient.Models.LLAMA_3_2)
        .build();
```

Requires **Java 21+** and **Maven 3.9+**. Add `ara-adapters` for real providers →
[Providers guide](docs/PROVIDERS.md).

<details>
<summary><b>Build from source</b></summary>

```bash
git clone https://github.com/xmor/ara.git && cd ara
mvn clean install -DskipTests
```

</details>

---

## One step further — three agents at once

`crew/CodeReviewCrewExample` is the same runtime doing multi-agent work. Three specialist
reviewers — security, performance, style — run **concurrently on virtual threads**, then a
lead agent merges their findings into a prioritised report. The fan-out is a pipeline step;
there are no threads to wire up:

```java
AgentPipeline pipeline = AgentPipeline.builder()
        .parallel("review", List.of(security, performance, style),
                  runtime.executor(), AgentChain.MergeStrategy.joining("\n\n"))
        .step("synthesize", lead)
        .build();

AraAgent crew = PipelineAgents.of(AgentId.of("code-review-crew"), config, pipeline);
```

Run it offline with `main()`: the console shows the three reviewers starting together on
different threads and finishing in an order decided by their work, then the lead's report
and the crew's token/cost summary. Pass `live` to run the same crew against a real model.

---

## Why ARA

- **Plain Java, no magic.** Pure interfaces — zero annotations, zero reflection, no
  Kotlin runtime, no Spring. The call stack you debug is the call stack you wrote.
- **Deterministic I/O contracts.** `AgentContract` validates, sanitises and transforms
  in plain Java before and after every call, spending **zero tokens**.
- **Java 21 by design.** Virtual threads are the concurrency model, not an option: when
  the LLM asks for several tools at once they are dispatched in parallel automatically,
  with no executor to wire up.
- **A runtime, not a toolkit.** Execution strategies, FSM pipelines, session isolation,
  human-in-the-loop and cost budgets come in the box rather than assembled from parts.
- **Built on LangChain4j, not against it.** Provider integration is inherited through
  `ara-adapters`, so you get LangChain4j's provider coverage *plus* the runtime on top.

### Where ARA fits in the JVM ecosystem

| If you use… | ARA's relationship |
|---|---|
| **LangChain4j** | ARA builds *on* it. `ara-adapters` wraps LangChain4j clients and adds the runtime layer above: sessions, strategies, contracts, HITL. |
| **A Spring-based AI stack** | ARA has no DI container and no Spring dependency. It drops into a Spring app as an ordinary library, or into a plain `main()` with none. |
| **Python agent frameworks** | ARA keeps agentic workloads on the JVM stack you already deploy, monitor and secure — no Python service to operate alongside it. |
| **Raw provider SDKs** | Everything above the HTTP call — retries, failover, circuit breaking, tracing, output validation, approval gates — is already written. |

### Capabilities that rarely come as one piece

- **Human-in-the-loop as a runtime primitive** — an `ApprovalGate` wired into the tool
  dispatch chain; `ApprovalDecision` is a sealed interface, so approve / reject / modify
  is exhaustive at compile time. → [HITL guide](docs/HITL-AND-RAG.md#human-in-the-loop-hitl--approval-gate)
- **Conversation and self-correction inside the loop** — `"respact"` asks a clarifying
  question mid-task and resumes on the same session; `"reflact"` recovers from a failed
  tool call without discarding what the run already accomplished.
- **Private per-agent data** — `AgentInstanceContext` holds API keys or tenant ids that
  both prompt shaping and tool execution can read, and that never reach the LLM.
- **Per-agent cost accounting** — unit prices and a `costBudget` cap are part of the LLM
  profile, not an afterthought.
- **Multimodal input** — attach a PDF or an image and the model reads it natively; a
  provider that can't handle the type fails loudly *before* the request goes out.
- **A full execution trace, always** — `AgentResponse.steps()` records the reasoning and
  tool trace on every run, including the partial trace when a run fails.
- **LLM failover and circuit breaking** — an ordered model chain with a passive breaker
  per candidate, so an outage stops costing a timeout per request.

---

## What you can build

- Single agents on any LLM (OpenAI, Anthropic, Ollama, Mistral, LM Studio, Groq, …)
- Deterministic I/O contracts: sanitize input, validate output, strip markdown fences — zero tokens consumed
- Multi-agent pipelines with conditional routing and FSM-style state machines
- Parallel specialist crews: a fan-out step runs several agents at once on virtual threads and merges their answers with a pluggable strategy
- Classify-and-act triage: one classification decides the single worker that handles the task, escalating from keyword rules to a model to a human as confidence drops — the whole dispatch table loadable as a JSON document
- Tool calling from LLM responses, including parallel dispatch on virtual threads
- Conversational agents that ask clarifying questions mid-task (`"respact"`) and self-correcting ones that recover from failed tool calls without restarting (`"reflact"`)
- RAG as a strategy decorator — retrieval before every LLM call, no tool configuration needed
- Fully offline testing with `ScriptedLlmClient` and `AssociativeLlmClient`

---

## Core concepts in 4 snippets

<details open>
<summary><b>Tool calling</b> — implement <code>AraTool</code>, opt in per agent</summary>

```java
class WeatherTool implements AraTool {
    @Override public String toolId()      { return "get_weather"; }
    @Override public String description() { return "Returns current weather for a city."; }
    @Override public String argumentSchema() {
        return """
               {"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}
               """;
    }
    @Override public ToolResult execute(String argumentJson) {
        return ToolResult.success(toolId(), "Milan: Sunny, 22°C");
    }
}

AgentConfig config = AgentConfig.defaults()
        .agentType("travel-assistant")
        .plannerStrategy("react")
        .enabledTools(List.of("get_weather"))
        .build();
```

When the LLM requests several tools in one response, ARA dispatches them concurrently on
virtual threads — no configuration. → [Tool calling in depth](docs/TOOLS.md)

</details>

<details>
<summary><b>Deterministic I/O contracts</b> — validate and transform without spending tokens</summary>

```java
AgentContract contract = AgentContract.builder()
        .addInputProcessor(InputSanitizer.instance())      // blocks prompt-injection patterns
        .addInputProcessor(ContentTruncator.to(4000))
        .addPromptShaper(PromptTemplate.withDefaults(Map.of("date", LocalDate.now().toString())))
        .outputSchema(JsonSchemaValidator.forOutput(SCHEMA))
        .addOutputProcessor(MarkdownFenceStripper.instance())
        .build();

AraAgent agent = runtime.createAgent(config, contract);
```

Validators, extractors, PII redaction and media limits ship built in.
→ [Contracts & processors](docs/CONTRACTS.md)

</details>

<details>
<summary><b>Multi-agent pipeline</b> — an FSM over agents</summary>

```java
AgentPipeline pipeline = AgentPipeline.fsmBuilder()
        .state("draft",  draftAgent)
        .state("review", reviewAgent)
        .state("revise", reviseAgent)
        .state("done",   doneAgent)
        .initial("draft")
        .terminal("done")
        .transition("draft",  "review")
        .transition("review", execution -> execution.lastOutput().contains("APPROVED") ? "done" : "revise")
        .transition("revise", "review")
        .maxSteps(12)
        .build();
```

`PipelineAgents.of(pipeline)` hosts it inside a real agent, so it gains session
isolation, cancellation and telemetry — and can be nested inside another pipeline.
→ [Pipeline README](ara-runtime/src/main/java/io/ara/runtime/pipeline/README.md)

</details>

<details>
<summary><b>Classify-and-act</b> — triage with confidence-driven escalation</summary>

```java
IntentRouter router = IntentRouter.onField("intent")
        .route("BILLING", "billing")
        .route("TECH",    "tech")
        .confidenceField("confidence")
        .escalateBelow(0.7, "human")       // low confidence → escalate, not guess
        .orElse("fallback");

AgentPipeline triage = AgentPipeline.builder()
        .classify("classify", classifierAgent, router)
        .worker("billing", billingAgent)
        .worker("tech",    techAgent)
        .worker("human",   humanReviewAgent)
        .worker("fallback", fallbackAgent)
        .build();
```

Three interchangeable classifiers fill the same slot — keyword rules (zero tokens), a
prompted model, or a human behind an `ApprovalGate` — and the whole dispatch table can be
loaded from JSON. → [Classify-and-act](ara-runtime/src/main/java/io/ara/runtime/pipeline/README.md)

</details>

---

## Execution strategies

| Strategy | `plannerStrategy` | What it does |
|---|---|---|
| `ReactStrategy` | `"react"` | Reasoning–Action loop: Think → Act → Observe. The default |
| `ReSpActStrategy` | `"respact"` | ReAct + a **speak** action: converse with the user mid-task without closing it |
| `ReflActStrategy` | `"reflact"` | ReAct + **in-loop** self-correction on tool failures or stalled reasoning |
| `PlanExecuteStrategy` | `"plan_execute"` | Generate a structured (JSON) plan, execute each step in its own context, and have each step close with an outcome and notes for the next |
| `ReflexionStrategy` | `"reflexion"` | Generate → critique → revise, restarting the whole episode |
| `RetrievalAugmentedStrategy` | `"rag+<name>"` | Inject retrieved context before every LLM call |

All strategies support cooperative cancellation and record a full execution trace,
including on failure paths. → [Strategy README](ara-runtime/src/main/java/io/ara/runtime/strategy/README.md)

---

## Modules

| Module | What's inside |
|---|---|
| `ara-core` | Pure interfaces and domain model: `AraAgent`, `LlmClient`, `LlmException`, `MemoryManager`, `ToolRegistry`, `AgentContract`, `ExecutionStrategy`, … |
| `ara-runtime` | `AraRuntime`, the execution strategies, `ContractEnforcer`, `AgentPipeline`, the classify-and-act building blocks, the offline LLM stubs and the built-in processors |
| `ara-adapters` | LangChain4j-backed `LlmClient` adapters for OpenAI, Anthropic, Ollama, Mistral and ChatJimmy. No Kotlin, no OkHttp, no Spring |
| `ara-examples` | Runnable examples for offline (stub) and live (real LLM) scenarios |

`ara-gateway` — an optional HTTP layer (Javalin/Jetty) for HTTP-side HITL approvals —
ships **separately** and is not part of this build.

---

## Runnable examples

Everything below lives in `ara-examples` and runs with `main()`.

| Class | LLM | What it shows |
|---|---|---|
| `basics/MinimalAgentExample` | stub | The smallest program: runtime + `askText(...)` — two lines |
| `basics/AraSimpleExample` | stub | End-to-end: ReAct loop, tool call, interceptor, agent reuse |
| `basics/SimpleStreamingExample` | stub / **live** | The smallest streaming agent, tokens printed as they arrive |
| `basics/StreamingWithToolExample` | stub / **live** | Token streaming through a ReAct loop that calls a tool |
| `basics/InterceptorEventsExample` | stub | Every `AgentInterceptor` event in order, around one run |
| `pipeline/ClassifyAndActExample` | none | Classify-and-act at its smallest — no model, no API key |
| `pipeline/TicketTriageCascadeExample` | stub | The three-tier cascade: rules → model → human |
| `contract/StructuredContractExample` | stub | An agent's input *and* output contract: full JSON Schema on both sides with every violation and its path, a refusal as data (`AgentResponse.violation()`, `Phase.callerFault()`), a custom rule with structured issues, and `outputRepairAttempts` sending a rejected answer back — the example checks its own claims |
| `crew/CodeReviewCrewExample` | stub / **live** | Three specialist reviewers fan out on virtual threads; a lead agent merges their findings |
| `hitl/HumanInTheLoopExample` | stub | A tool call parked on an `ApprovalGate` until an operator decides |
| `memory/MemoryAgentExample` | stub | Token-budgeted working memory: summarise, offload, and recall |
| `rag/RagAgentExample` | stub | `rag+react` over an `InMemoryDocumentStore`, plus delegation |
| `failover/FailoverExample` | stub | Failover and circuit breaking across LLM and embedding endpoints |
| `multimodal/MultimodalInputExample` | **live** | A PDF to Mistral and an image to Ollama, one provider-agnostic method |
| `spec/AgentFromFileExample` | stub | An agent defined in a JSON file: load it, check the names it refers to, run it, write it back |
| `spec/ImportAgentFromJsonExample` | stub | A fully described agent (fallback model, prices, spend cap, strategy, tools, memory) imported from a JSON file on the classpath, then run |
| `events/RunEventsExample` | stub | A listener on the task receives every step as it happens, including a delegated agent's, printed as a tree |
| `scheduler/AgentSchedulerExample` | none | Interval and cron schedules, with pause / resume / trigger |
| `planner/PlannerExample` | stub / **live** | `plan_execute` on a small coding task: a JSON plan with dependencies, notes that carry a choice from one step to the next, and a failed step that replans — in an in-memory workspace |
| `web/StreamingChatWebExample` | stub / **live** | A chat page on a JDK `HttpServer`, streaming over SSE |

---

## Documentation — read it in this order

The guides form a path, not a reference dump. Start at the top and stop when your use
case is covered.

| Step | Guide | What you'll be able to do |
|---|---|---|
| 1 | [Quick start](#quick-start--60-seconds-no-api-key) + [Runnable examples](#runnable-examples) | Run your first agent offline, then read the example closest to your goal |
| 2 | [Tool calling](docs/TOOLS.md) | Give an agent a tool, with parallel dispatch on virtual threads |
| 3 | [Contracts & processors](docs/CONTRACTS.md) | Validate, sanitise and transform I/O without spending tokens; `PromptShaper`, multimodal input |
| 4 | [Configuration reference](docs/CONFIGURATION.md) | Every `AgentConfig` and `LlmProfile` field: sessions, concurrency, cancellation, instance context, scheduling, and defining an agent in a file |
| 5 | [RAG & human-in-the-loop](docs/HITL-AND-RAG.md) | Knowledge bases (in-memory or Qdrant), retrieval as a strategy vs. a tool, approval gates and notifiers |
| 6 | [Providers & resilience](docs/PROVIDERS.md) | Real endpoints (OpenAI / Anthropic / Ollama / Mistral / compatible), failover, circuit breaker, I/O logging, OpenTelemetry |
| 7 | [Advanced usage](docs/ADVANCED.md) | Custom strategies and extension points |
| — | [Coding guidelines](docs/CODING-GUIDELINES.md) | What a PR is expected to look like |

---

## FAQ

<details>
<summary><b>Do I need Spring, or any DI container?</b></summary>

No. ARA is a plain library with a builder API. It runs in a bare `main()`, and drops into
a Spring or Quarkus application as an ordinary dependency.

</details>

<details>
<summary><b>Can I run it without any API key?</b></summary>

Yes, two ways. `ScriptedLlmClient` and `AssociativeLlmClient` replay scripted responses
for tests and demos, and the Ollama adapter talks to a local model with no key at all.

</details>

<details>
<summary><b>Which providers are supported?</b></summary>

OpenAI, Anthropic, Ollama, Mistral and ChatJimmy have first-class adapters, and the
OpenAI client points at any OpenAI-compatible endpoint — LM Studio, Groq, Together AI, a
corporate gateway. Provider coverage is inherited from LangChain4j.

</details>

<details>
<summary><b>Why Java 21 and not 17?</b></summary>

Virtual threads. Parallel tool dispatch, session concurrency and the cheap parking of a
thread waiting on a human approval are all built on them.

</details>

<details>
<summary><b>How is this different from calling a chat API in a loop?</b></summary>

The loop is the easy part. Session isolation, cooperative cancellation, output schema
validation, failover with circuit breaking, cost budgets, approval gates and a complete
execution trace are the parts you would otherwise write, and they are what ARA is.

</details>

---

## Contributing

Issues, discussions and PRs are welcome — including README and documentation fixes,
which are the easiest first contribution.

Code style is enforced through [`docs/CODING-GUIDELINES.md`](docs/CODING-GUIDELINES.md):
simplicity first, no premature abstractions, short single-responsibility functions,
honest naming. Read it before opening a PR.

ARA is developed with heavy AI assistance under human architectural review.

**If ARA is useful to you, a ⭐ helps other Java developers find it.**

---

## License

Apache 2.0 — see [LICENSE](LICENSE).

<div align="center">
<sub>

**Keywords** — Java AI agent framework · LLM orchestration on the JVM · multi-agent
systems in Java · ReAct agents · tool calling · RAG · human-in-the-loop · OpenAI ·
Anthropic Claude · Ollama · Mistral · Java 21 virtual threads

</sub>
</div>
