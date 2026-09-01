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
- [Multimodal input — images and documents](#multimodal-input--images-and-documents)
- [AgentContract — deterministic I/O](#agentcontract--deterministic-io)
- [PromptShaper — dynamic system prompt](#promptshaper--dynamic-system-prompt)
- [Multi-agent pipeline](#multi-agent-pipeline) <!-- - [Agent graph](#agent-graph--parallel-branches-and-feedback-loops) -->
- [Classify-and-act](#classify-and-act)
- [Execution strategies](#execution-strategies)
- [AgentConfig — configurable agent values](#agentconfig--configurable-agent-values)
- [Sessions & concurrency](#sessions--concurrency)
- [Agent Instance Context](#agent-instance-context--private-per-agent-data)
- [Runnable examples](#runnable-examples)
- [Human-in-the-loop (HITL)](#human-in-the-loop-hitl--approval-gate)
- [Agent scheduling](#agent-scheduling)
- [Advanced usage](docs/ADVANCED_README.md)
- [Contributing](#contributing)
- [License](#license)

---

## Modules

| Module          | Description                                                                                                                                                               |
|-----------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ara-core`      | Pure interfaces and domain model: `AraAgent`, `LlmClient`, `LlmException`, `MemoryManager`, `ToolRegistry`, `AgentContract`, `ExecutionStrategy`, …                     |
| `ara-runtime`   | Implementations: `AraRuntime`, the execution strategies (`ReactStrategy`, `ReSpActStrategy`, `ReflActStrategy`, `PlanExecuteStrategy`, `ReflexionStrategy`), `ContractEnforcer`, `AgentPipeline` and the classify-and-act building blocks, `ScriptedLlmClient`/`AssociativeLlmClient` stubs, built-in processors |
| `ara-adapters`  | LangChain4j-backed `LlmClient` adapters for OpenAI, Anthropic, Ollama, Mistral and ChatJimmy. No Kotlin, no OkHttp, no Spring. Declares its own LangChain4j BOM.        |
| `ara-examples`  | Runnable examples for offline (stub) and live (real LLM) scenarios                                                                                                       |

Those four are what this repository builds — see `<modules>` in the root `pom.xml`.
`ara-gateway`, referenced further down for HTTP-side HITL approvals, is an optional HTTP
layer (Javalin/Jetty) that ships **separately** and is not part of this build; nothing in
the four modules above depends on it.

---

## What you can build

- Single agents with any LLM (OpenAI, Anthropic, Ollama, Mistral, LM Studio, Groq, …)
- Deterministic I/O contracts: sanitize input, validate output, strip markdown fences — zero tokens consumed
- Multi-agent pipelines with conditional routing and FSM-style state machines
- Classify-and-act triage: one classification decides the single worker that handles the
  task, escalating from keyword rules to a model to a human as confidence drops — the
  whole dispatch table loadable as a JSON document
<!-- - Agent graphs with parallel branches and feedback loops -->
- Tool calling from LLM responses, including parallel dispatch on virtual threads (Java 21)
- Conversational agents that ask clarifying questions mid-task (`"respact"`) and self-correcting ones that recover from failed tool calls without restarting (`"reflact"`)
- Multimodal input: attach images and PDFs to a task and have the model read them natively — layout, tables and scans included, no text extraction upstream
- RAG as a strategy decorator — retrieval before every LLM call, no tool configuration needed
- Fully offline testing with `ScriptedLlmClient` (one script, popped per call) and
  `AssociativeLlmClient` (one script per agent id, so several agents in flight stay
  deterministic)

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
    <version>1.0.1</version>
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
    <version>1.0.1</version>
</dependency>
```

Then use `AraLlmClientFactory` or the individual client builders:

```java
import io.ara.adapters.llm.AraLlmClientFactory;
import io.ara.adapters.llm.openai.OpenAiLlmClient;
import io.ara.adapters.llm.anthropic.AnthropicLlmClient;
import io.ara.adapters.llm.ollama.OllamaLlmClient;
import io.ara.adapters.llm.mistral.MistralLlmClient;

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

// Mistral (native PDF documents — see Multimodal input below)
LlmClient mistral = AraLlmClientFactory.mistral()
        .apiKey(System.getenv("MISTRAL_API_KEY"))
        .model(MistralLlmClient.Models.MISTRAL_MEDIUM_LATEST)
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
    @Override
    public String toolId()      { return "get_weather"; }

    @Override
    public String description() { return "Returns current weather for a city."; }

    @Override
    public String argumentSchema() {
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

Tools are always supplied by the caller: ARA ships the `ToolRegistry` port and the
decorators around it (approval gating, telemetry, delegation), not a catalogue of
ready-made tools. The one tool the runtime registers on its own is `delegate_task`
(`AgentDelegationTool`), which lets a supervisor agent hand work to another registered
agent.

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

## Multimodal input — images and documents

Attach an image or a PDF to a task and the model reads it natively — layout, tables,
stamps and scanned pages included. This is the path for what text extraction cannot give
you; for PDFs that are *already* text, `KnowledgeBase` + `RetrievalAugmentedStrategy`
remains the cheaper answer, and the two coexist without talking to each other.

The bytes live in a `MediaStore`, wired once on the runtime. Everything above the adapter
carries a `MediaRef` — a name, a MIME type, a size and the SHA-256 of the content — never
the payload:

```java
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaStore;

MediaStore media = MediaStore.inMemory();          // or your own backend

MediaRef contract = media.put("contract.pdf", "application/pdf",
        Files.readAllBytes(Path.of("contract.pdf")));

AraRuntime runtime = AraRuntime.builder()
        .llmClient("mistral", mistral)
        .mediaStore(media)                          // defaults to MediaStore.noop()
        .build();

// Blank input is legal when media is present: the document *is* the request.
AgentResponse response = agent.execute(AgentTask.of("", List.of(contract)));
```

`MediaRef.remote(uri, mimeType, name)` covers a document already reachable at a URL — no
store involved, and those bytes are never ARA's to delete.

**Why the bytes stay out of the domain.** Inline, a 2 MB PDF becomes ~2.7 million base64
characters. It would be written into every persisted session turn, printed into the
request log, and counted by the working-memory token estimate as ~680k tokens — enough to
evict the entire window, system prompt included, leaving the document as the sole
survivor. Holding a reference removes all of that at once, with no per-agent flag to turn
any of it off. Deduplication comes free and by *content*: `put` derives the id from a
SHA-256 of the bytes, so the same document submitted by two unrelated tasks costs one
entry.

**Provider support is per type, and a mismatch is a hard failure.**

| Provider  | Images | PDF as document | Text files |
|-----------|--------|-----------------|------------|
| Mistral   | yes    | yes             | yes        |
| OpenAI    | yes    | yes — hosted only, see below | yes |
| Anthropic | yes    | yes             | yes        |
| Ollama    | yes    | **no**          | yes        |

Send a PDF to Ollama and the task fails with a non-retryable `LlmException` naming the
type and the provider, *before* the request goes out. It is never stripped, never
downgraded to text, never logged-and-continued: those all produce a fluent, plausible
answer about a document the model never saw, which is indistinguishable from a real one to
whoever reads it. Because the failure is non-retryable, `FailoverLlmClient` aborts instead
of letting a text-only fallback answer instead — and a `FAILOVER` or `ROUND_ROBIN` pool
reports the *intersection* of its members' media types for the same reason.

**Media support belongs to the endpoint, not the vendor.** `OpenAiLlmClient` is meant to be
pointed at any OpenAI-compatible API, and while they all accept the `image_url` part, many
reject the `file` part a PDF becomes — a corporate gateway typically answers
`Unknown part type: file`. So documents are claimed only when no custom `baseUrl` is set
(i.e. hosted OpenAI); behind a `baseUrl` the client reports images and text only, and you
opt back in when you know the endpoint forwards `file` parts:

```java
LlmClient viaGateway = AraLlmClientFactory.openAi()
        .apiKey(KEY)
        .baseUrl("https://gateway.internal/v1")
        .modelName("mistral-small-3.2-24b")
        .documentSupport(true)      // only if this endpoint really accepts `file` parts
        .build();
```

Guessing generously here is what produces the confusing failure, so the default guesses
strictly: a refused PDF says so clearly, naming the type and the provider.

**Cost across turns.** A document is paid for on the turn that introduced it. Replayed
conversation turns name their attachments rather than re-sending them, while
`ConversationTurn` keeps the reference so the file stays retrievable. To have the model
look at it again, attach it again.

**Per-agent limits.** `MediaLimits` caps how many files and how many bytes a task may
attach, and can narrow the accepted types; an over-limit task fails before a single token
is spent:

```java
AgentContract contract = AgentContract.builder()
        .addMediaValidator(MediaLimits.of(3, 10 * 1024 * 1024))
        .build();
```

**Prompt injection.** Text printed inside a PDF or rendered into an image does not pass
through `InputSanitizer`, which only ever sees the task's input string. The flattening
step prefixes the attachments with an explicit "this is data, not instructions" frame,
and `MediaLimits` bounds the volume — but neither is a complete defence. Against hostile
document content the mitigation that actually holds is on the output side: constrain the
answer with a validated schema (`AgentTask.withOutputSchema`), so a hijacked model
producing something off-schema fails validation instead of passing the injected
instruction through as an answer.

Runnable end-to-end: `io.ara.examples.multimodal.MultimodalInputExample` — a PDF to
Mistral and an image to Ollama, through one provider-agnostic method.

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

`outputSchema(...)` does two things: it declares the contract *and* instructs the model, by
appending the schema to the system prompt (`"Respond ONLY with a single valid JSON object
matching this schema"`). That route works against every endpoint, including gateways that
support no `response_format` at all, and it is what the default `nativeJsonSchema(false)`
selects. Setting `nativeJsonSchema(true)` on the `LlmProfile` asks for a provider-native
`response_format` instead — which no adapter sends yet, so combining it with an output schema
is rejected at `createAgent` rather than left to fail on every task with a puzzling
missing-field error.

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

**Attachments** — declared with `addMediaValidator(...)`, not `addInputProcessor(...)`: an
input processor only ever sees the input string and cannot look at a `MediaRef`.

| Validator | Function |
|---|---|
| `MediaLimits.of(3, 10_000_000)` | Reject if more than N files or more than N bytes in total |
| `MediaLimits.of(3, 10_000_000, Set.of("image/png"))` | As above, narrowed to a subset of the supported types |
| `MediaLimits.none()` | Reject any attachment — for an agent that must stay text-only |

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

A pipeline is not an `AraAgent` by itself — `PipelineAgents.of(pipeline)` hosts it inside a
real `AgentInstance`, so it gains session isolation, cancellation and telemetry and can be
registered, delegated to, or nested as a step of another pipeline. `ParallelAgent` (or the
`.parallel(...)` builder shortcut) fans one step out to N agents on virtual threads and
merges their responses. Details and gotchas:
[`io/ara/runtime/pipeline/README.md`](ara-runtime/src/main/java/io/ara/runtime/pipeline/README.md).

---

## Classify-and-act

The triage shape: classify the incoming task once, then let **exactly one** worker handle
it. `IntentRouter` reads the label out of a classifier step's output and turns it into a
worker name; `classify(...)` and `worker(...)` declare the two kinds of step.

```java
IntentRouter router = IntentRouter.onField("intent")
        .route("BILLING", "billing")
        .route("TECH",    "tech")
        .writeLabelTo("intent")            // → RunState, where a worker can read it
        .confidenceField("confidence")
        .escalateBelow(0.7, "human")       // low confidence → escalate, not guess
        .orElse("fallback");               // terminal builder method: the else-arc is mandatory

AgentPipeline triage = AgentPipeline.builder()
        .classify("classify", classifierAgent, router)
        .worker("billing",  billingAgent)
        .worker("tech",     techAgent)
        .worker("human",    humanReviewAgent)
        .worker("fallback", fallbackAgent)
        .build();
```

`worker(...)` feeds the step the **original** input rather than the classifier's label, and
ends the run — the two defaults that a hand-written `route(...)` lambda gets wrong. Every
routing decision emits a `pipeline.classify` span carrying the label, the target, the
confidence and *why* that target was chosen (`MATCHED`, `UNKNOWN_LABEL`, `LOW_CONFIDENCE`,
`UNPARSEABLE_OUTPUT`, …), and `build()` rejects a route naming a step that was never
declared.

**Three interchangeable classifiers** fill the same slot, emitting the same
`{"intent": …, "confidence": …}` shape:

| Classifier | What decides the label | Cost |
|---|---|---|
| `RuleClassifier` | keyword/regex rules over the task text, first match wins in declaration order | zero tokens, no round trip |
| any `AraAgent` | a prompted model | one LLM call |
| `ApprovalClassifier` | a human, through an `ApprovalGate` | blocks until a decision or the timeout |

Chaining them with `escalateBelow(...)` gives the cascade: rules answer what they know at
no cost, the model sees the original ticket when no rule fired, and a human decides what
the model was unsure of. `RuleClassifier` is built on `AraAgents.deterministic(...)`
(`FunctionAgent`) — an agent whose signature has nowhere to put an `LlmClient`, so it
provably never reasons and reports zero tokens.

The whole arrangement is also loadable as a document — tiers, rules, label→worker table
and thresholds are data; the agents are named and resolved at bind time:

```java
AgentPipeline triage = ClassifyAndActSpec.fromJson(document)
        .build(ClassifyAndActSpec.Bindings
                .of(ClassifyAndActSpec.AgentResolver.byId(runtime.registry()))
                .withApprovalGate(gate)
                .withTelemetry(telemetry));
```

Adding a category becomes a document edit, and everything checkable — a route to an
undeclared worker, an `escalateBelow` with no `escalateTo`, a regex that does not compile —
is refused when the spec is loaded rather than on the first ticket that exercises it.

Runnable: `pipeline/ClassifyAndActExample` (the minimal form, offline) and
`pipeline/TicketTriageCascadeExample` (the three-tier cascade). The JSON schema, the
`RunState` caveats and the rest live in
[`io/ara/runtime/pipeline/README.md`](ara-runtime/src/main/java/io/ara/runtime/pipeline/README.md).

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
| `costInputPer1kTokens` / `costOutputPer1kTokens` (`Money`) | `Money.zero("EUR")` | Unit prices used for cost accounting |
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
| `humanApprovalRequired(boolean)` | `false` | When `true` and an `ApprovalGate` is configured on the runtime, every tool call is routed through the gate before dispatch — the virtual thread parks until a human decision arrives or the request times out |
| `retrieverId(String)` | `null` | Which registered `Retriever` a `"rag+…"` strategy uses; `null` = the runtime's default retriever. Setting it with a non-`rag+` strategy is rejected at construction |
| `knowledgeBaseId(String)` | `null` | Knowledge base the agent searches *as a tool*: combined with `search_documents` in `enabledTools`, it attaches a `KnowledgeBasePromptShaper` (see [Knowledge base / RAG retrieval](#knowledge-base--rag-retrieval-in-memory-or-qdrant)) |
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
| `basics/InterceptorEventsExample` | stub | Every `AgentInterceptor` event in order, around one run |
| `basics/SimpleStreamingExample` | stub | The smallest streaming agent: `streamingEnabled(true)` + `AgentTask.ofStreaming`, one turn, tokens printed as they arrive |
| `basics/StreamingWithToolExample` | stub / **live** | Token streaming through a ReAct loop that calls a tool: `streamingEnabled` + `AgentTask.ofStreaming`, Think turns streamed, blocking path for contrast. Pass `live` (or `-Dara.example.live=true`) to stream from a real OpenAI-compatible endpoint |
| `pipeline/ClassifyAndActExample` | none | Classify-and-act at its smallest: a `RuleClassifier`, an `IntentRouter`, four workers — no model, no API key |
| `pipeline/TicketTriageCascadeExample` | stub | The three-tier cascade: rules → model → human, with confidence-driven escalation and an `ApprovalGate` |
| `hitl/HumanInTheLoopExample` | stub | A tool call parked on an `ApprovalGate` until an operator approves, rejects or modifies it |
| `rag/RagAgentExample` | stub | `rag+react` over an `InMemoryDocumentStore`, plus an orchestrator delegating to it via `delegate_task` |
| `multimodal/MultimodalInputExample` | **live** | A PDF to Mistral and an image to Ollama, through one provider-agnostic method |
| `scheduler/AgentSchedulerExample` | none | Recurring execution: an interval schedule firing every second, plus `list` / `triggerNow` / `pause` / `resume` / `cancel` and a cron registration — no model, no API key |
| `web/StreamingChatWebExample` | stub / **live** | An ARA-styled chat page served by a JDK `HttpServer`: each request runs a one-turn streaming agent and pushes every `tokenCallback` token to the browser over SSE. `main()` → open `http://localhost:8080`; pass `live` for a real model |

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
  `ara-core`, `ara-runtime` and `ara-adapters` — every module in this build. The separately
  shipped `ara-gateway` (Javalin, for its native path-param routing and first-class SSE) is
  the one exception — it pulls in Jetty and a Kotlin runtime, but only for whoever
  explicitly adds it.
- **Deterministic I/O contracts.** `AgentContract` validates, sanitises and transforms in
  plain Java before and after every call, spending **zero tokens**.
- **Java 21 by design.** Virtual threads are the concurrency model, not an option: when the
  LLM asks for several tools at once they are dispatched in parallel automatically, with no
  executor to wire up or thread pool to tune.
- **A runtime, not a toolkit.** Execution strategies, FSM pipelines,<!-- agent graphs, --> session
  isolation, human-in-the-loop and cost budgets come in the box rather than assembled from
  parts.
- **Built on LangChain4j, not against it.** Provider integration is inherited through
  `ara-adapters`, so you get LangChain4j's provider coverage *plus* the runtime on top.

### Capabilities that rarely come as one piece
- **Human-in-the-loop as a runtime primitive** — an `ApprovalGate` wired into the tool
  dispatch chain via `AraRuntime.Builder.approvalGate(gate)`, with pluggable notifiers
  (`LoggingApprovalNotifier`, `WebhookApprovalNotifier`). `ApprovalDecision` is a sealed
  interface, so handling approve / reject / modify exhaustively is enforced by the compiler,
  not by convention. `gate.getPendingRequests()` / `gate.submit(...)` are the API any
  operator surface builds on; the separately shipped `ara-gateway` puts them behind HTTP
  (`GET /approvals`, `POST /approvals/{requestId}/decision`).
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
  
### Knowledge base / RAG retrieval (in-memory or Qdrant)

Retrieval enters the runtime through one port, `Retriever`. `InMemoryDocumentStore` needs
no external infrastructure (brute-force cosine over the indexed chunks — fine up to ~10k
chunks); `DocumentStore` is the Qdrant-backed equivalent, behind the same contract. Both
also implement `KbStore`, which adds indexing and document management on top of `retrieve`.

```java
EmbeddingClient embeddings = /* your embedding model */;

InMemoryDocumentStore kb = new InMemoryDocumentStore("ara-docs", embeddings);
kb.ensureCollection();
kb.indexDocument("adr-016", "Session isolation", "…");

AraRuntime runtime = AraRuntime.builder()
        .llmClient("live", gpt4o)
        .retriever(kb)                 // or .retriever("docs", kb) to name it
        .build();
```

For persistent vector search, swap the store — nothing else changes:

```java
QdrantConfig qdrant = QdrantConfig.builder()
        .host("localhost").port(6334)
        .collectionName("my-docs")
        .build();

DocumentStore kb = new DocumentStore(qdrant, embeddings);
```

(`QdrantSemanticStore` is a different thing — agent *episodic memory*, a separate
collection — and is not a `Retriever`.)

Registering at least one retriever makes `AraRuntime` auto-register the RAG-wrapped
strategies — `"rag+react"`, `"rag+respact"`, `"rag+plan_execute"` and `"rag+reflact"` —
each backed by a `RetrieverRouter` over everything registered. An agent opts in by naming
one:

```java
AgentConfig config = AgentConfig.defaults()
        .agentType("kb-agent")
        .plannerStrategy("rag+react")   // retrieval before every LLM call
        .retrieverId("docs")            // optional; the default retriever otherwise
        .build();
```

`retrieverId` without a `rag+…` strategy is rejected at construction rather than silently
ignored.

**RAG-as-a-strategy vs. a search tool.** The above retrieves *before every LLM call*, with
no tool involved and nothing for the model to decide. The alternative is letting the agent
search on its own: set `knowledgeBaseId(...)` and enable a `search_documents` tool, and the
runtime attaches a `KnowledgeBasePromptShaper` that tells the model how to call it. The
tool implementation itself is yours — ARA ships the prompt shaping and the stores, not the
tool.

### Human-in-the-loop (HITL) — approval gate

Agents with `humanApprovalRequired(true)` route every tool call through an `ApprovalGate`
before dispatch. The calling virtual thread parks cheaply (Project Loom) until a human
decision arrives or the request times out.

**Setup:**

```java
ApprovalGate gate = new InMemoryApprovalGate();

AraRuntime runtime = AraRuntime.builder()
        .llmClient(llmClient)
        .approvalGate(gate)
        .build();

AraAgent agent = runtime.createAgent(AgentConfig.builder()
        .agentId("payment-agent")
        .agentType("finance")
        .humanApprovalRequired(true)
        .enabledTools(List.of("process_payment", "refund"))
        .build());
```

When the agent calls a tool, `ApprovalToolRegistry` intercepts and:
1. Creates an `ApprovalRequest` (UUID, agentId, toolId, arguments, expiry).
2. Calls `gate.requestApproval(request)` — returns a `CompletableFuture<ApprovalDecision>`.
3. The virtual thread parks on `.join()` until the future resolves.
4. On `Approved` → dispatches the tool normally.
5. On `Rejected` → returns a failed `ToolResult` (the agent sees "Human rejected action: …").
6. On `Modified` → dispatches with the revised payload.
7. On timeout → returns a failed `ToolResult`.

**Resolving approvals programmatically:**

```java
// List pending requests
List<ApprovalRequest> pending = gate.getPendingRequests();

// Submit a decision
gate.submit(requestId, new ApprovalDecision.Approved());
gate.submit(requestId, new ApprovalDecision.Rejected("Too expensive"));
gate.submit(requestId, new ApprovalDecision.Modified(newArgumentJson));
```

**Resolving approvals via HTTP** — with `ara-gateway`, the optional HTTP layer that ships
separately from this build:

```bash
# List pending approvals
curl http://localhost:8080/approvals

# Approve
curl -X POST http://localhost:8080/approvals/{requestId}/decision \
  -H "Content-Type: application/json" \
  -d '{"decision": "approved"}'

# Reject with reason
curl -X POST http://localhost:8080/approvals/{requestId}/decision \
  -H "Content-Type: application/json" \
  -d '{"decision": "rejected", "reason": "Amount exceeds limit"}'

# Modify payload
curl -X POST http://localhost:8080/approvals/{requestId}/decision \
  -H "Content-Type: application/json" \
  -d '{"decision": "modified", "newPayload": {"amount": 50.00}}'
```

Enable the `APPROVALS` surface in the gateway config (`ara.yml`):
```yaml
ara:
  gateway:
    enabled: true
    surfaces: [system, discovery, runs, control, sessions, approvals]
```

**Notifications:** Wire a notifier to alert operators when a request is pending:

```java
WebhookApprovalNotifier notifier = WebhookApprovalNotifier.builder()
        .url("https://slack-hook.example.com/hitl")
        .header("Authorization", "Bearer " + slackToken)
        .build();
```

> **Design note:** The approval gate is opt-in at two levels — `AraRuntime.Builder.approvalGate(gate)`
> enables the feature globally, and `AgentConfig.humanApprovalRequired(true)` enables it per
> agent. If no gate is configured, the flag is inert (no error, no approval). If the gate is
> configured but `humanApprovalRequired` is `false`, tool calls bypass the gate entirely.

## Agent scheduling
`LocalAgentScheduler` is created by the runtime but agent schedules must be registered explicitly
via `AraRuntime.scheduler()`. A schedule fires either on a fixed interval or on a cron expression:

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
supports the standard syntax — `*`, single values, ranges (`1-5`, `MON-FRI`, wrapping
for day-of-week), steps (`*/15`, `0-30/10`) and comma-separated lists (`1,15,30`,
`MON,WED,FRI`). Day-of-week accepts symbolic names and both `0` and `7` for Sunday.
When day-of-month and day-of-week are both restricted, standard cron OR semantics apply
(fires when either matches). `LocalAgentScheduler` holds schedules in memory — they do
not survive a process restart.

## Contributing

Code style is enforced through [`docs/CODING-GUIDELINES.md`](docs/CODING-GUIDELINES.md) —
simplicity first, no premature abstractions, short single-responsibility functions,
honest naming, and a taxonomy of what to keep vs. avoid in comments. Read it before
opening a PR.

ARA is developed with heavy AI assistance under human architectural review.
Every change is expected to pass the checklist in [`docs/CODING-GUIDELINES.md`](docs/CODING-GUIDELINES.md).

## License

Apache 2.0 — see [LICENSE](LICENSE).
