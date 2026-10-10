# Providers & resilience

[← back to README](../README.md)

- [Connecting a real LLM](#connecting-a-real-llm)
- [Reusing an opencode server](#reusing-an-opencode-server)
- [Multi-provider runtimes](#multi-provider)
- [LLM I/O logging](#llm-io-logging)
- [OpenTelemetry tracing](#opentelemetry-tracing)
- [LlmException — typed error handling](#llmexception--typed-error-handling)
- [LLM failover & circuit breaker](#llm-failover--circuit-breaker)

---

## Connecting a real LLM

Add `ara-adapters` to your `../pom.xml`:

```xml
<dependency>
    <groupId>io.github.xmor</groupId>
    <artifactId>ara-adapters</artifactId>
    <version>1.0.2</version>
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

// Mistral (native PDF documents — see the contracts guide)
LlmClient mistral = AraLlmClientFactory.mistral()
        .apiKey(System.getenv("MISTRAL_API_KEY"))
        .model(MistralLlmClient.Models.MISTRAL_MEDIUM_LATEST)
        .build();

// opencode — reuse the providers and subscriptions you already configured there
// (no API key of your own; read the limits before enabling its free-tier models)
LlmClient opencode = AraLlmClientFactory.openCode()
        .model("opencode/big-pickle")
        .build();

// OpenAI-compatible endpoint (LM Studio, Groq, Together AI, …)
LlmClient local = AraLlmClientFactory.openAi()
        .baseUrl("http://localhost:1234/v1")
        .apiKey("lm-studio")
        .modelName("llama-3.1-8b-instruct")
        .build();
```

## Reusing an opencode server

`openCode()` talks to an [opencode](https://opencode.ai) headless server instead of a
provider API, so ARA inherits whatever providers, credentials and subscriptions you have
already configured there — no second API key to manage. It is not LangChain4j-backed: it
speaks opencode's session API directly.

There are two ways to get a server, and they differ in more than convenience:

```java
// 1. Launch one. Needs the `opencode` binary on your PATH (override with
//    executable(...)). Runs `opencode serve --port 0 --hostname 127.0.0.1` as a
//    child process, reads the ephemeral URL it prints on stdout, and kills the
//    process on close() — and at JVM exit, so an unclosed adapter cannot orphan a
//    server. Loopback-bound, so it is reachable only from this machine.
LlmClient launched = AraLlmClientFactory.openCode()
        .model("opencode/big-pickle")
        .build();

// 2. Connect to one that is already running — localhost, a LAN box, a shared team
//    server. Any reachable URL: nothing here is restricted to loopback. close()
//    leaves it alone, because this adapter did not start it.
LlmClient connected = AraLlmClientFactory.openCode()
        .baseUrl("https://opencode.internal.example.com")
        .password(System.getenv("OPENCODE_SERVER_PASSWORD"))
        .model("anthropic/claude-sonnet-4-6")
        .build();
```

Other builder options: `executable(...)` for the binary in launch mode, `timeout(...)`
per request (default 5 min — a whole agent turn happens inside one request),
`startupTimeout(...)` for how long to wait for the URL announcement (default 30s), and
`model(...)` as `providerID/modelID`, split on the first slash only, since model ids
themselves contain slashes. Unset means "whatever the server is configured to use".
`providerId()` reports `opencode-<model>`, which is what shows up in logs and in
`LlmException.provider()`.

### ⚠️ Read this before pointing it at a shared server

`zenFreeTierTools(false)` is the default and should stay that way. opencode's Zen free tier
only answers calls made from inside opencode, and it decides that by looking at the tool
list: deny it opencode's own `bash` or `read` and the gateway refuses with `FreeTierError`.
So the zero-cost models — `opencode/big-pickle` and friends — need
`zenFreeTierTools(true)`, and the price is real: opencode executes those tools **inside its
own loop**, on the machine where the opencode server runs, and only the final text ever
reaches ARA. ARA's HITL gate never sees those calls. Turn it on for a box you would let a
model loose in — a scratch machine, a container — or keep it off and use a paid model or
one you host. This matters most in mode 2: with `baseUrl` pointing at a shared or remote
server you are granting `bash` and `read` on **that host**, not on yours.

### Known limits

Deliberate, and stated rather than discovered:

- **opencode's own tools are off** for every call, so `supportsNativeTools()` is `false` and
  ARA's text-based tool catalog is used instead. ARA runs its own tool loop; leaving
  opencode's `bash`/`edit` enabled would let the model act behind ARA's back.
- **Sampling parameters are ignored** — temperature, top-p, max tokens, stop sequences, seed
  and the output JSON schema have no counterpart on opencode's message endpoint, so the
  model's own defaults apply.
- **Media is rejected** with a non-failover `LlmException` (`supportedMediaTypes()` is empty)
  rather than silently dropped.
- **No native streaming**: `stream` falls back to the interface default and emits the whole
  reply as one item.
- **Token counts include opencode's own system prompt** (thousands of tokens), so they
  overstate what your ARA prompt actually cost. Budgets set from them will be pessimistic.
- **Each call opens and deletes its own opencode session.** Reusing one across calls was
  discarded: opencode would append ARA's resent history to its own copy, duplicating every
  turn and growing the context quadratically. On a shared server, a session whose cleanup
  fails is left behind rather than failing the call.

## Multi-provider

Each agent references its provider by name:

```java
AraRuntime runtime = AraRuntime.builder()
        .llmClient("fast",  AraLlmClientFactory.openAi().apiKey(KEY).modelName("gpt-4o-mini").build())
        .llmClient("smart", AraLlmClientFactory.openAi().apiKey(KEY).modelName("gpt-4o").build())
        .llmClient("local", AraLlmClientFactory.ollama().modelName("qwen3-coder:30b").build())
        .build();

AgentConfig config = AgentConfig.defaults()
        .agentType("analyst")
        .primaryLlm(LlmProfile.of("smart"))   // zero credentials in AgentConfig
        .build();
```

## LLM I/O logging

Turn on `logLlmIo` to trace every LLM request and response — plus each tool call and its
result — at `INFO`, truncated to `logLlmIoMaxChars` (`0` = no truncation):

```java
AgentConfig config = AgentConfig.defaults()
        .agentType("debug")
        .logLlmIo(true)
        .logLlmIoMaxChars(1000)
        .build();
```

Make sure `INFO` is enabled for `io.ara.runtime.llm.LoggingLlmClient` (LLM I/O) and
`io.ara.runtime.strategy.ReactStrategy` (tool calls) to see the full trace.

## OpenTelemetry tracing

Pass an `AraTelemetry` to `AraRuntime.Builder.telemetry(...)` to get a full trace tree —
one `agent.execute` span per task, with `llm.complete` (one per LLM call) and
`tool.execute` (one per tool dispatch) nested as children in call order. A `FAILOVER` chain
adds `llm.failover` and `llm.circuit` spans on top — see
[LLM failover & circuit breaker](#observing-failover-and-breaker-state):

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
        // rate limit, transient 5xx → worth a local retry on the SAME client
    } else {
        // auth error, invalid request, connect error → no local retry;
        // the strategy's retry loop (ReactExecutionSupport) acts on this flag
    }
    System.out.println(ex.errorType());   // RATE_LIMIT, AUTHENTICATION, NETWORK, …
    System.out.println(ex.provider());    // "OpenAI", "Anthropic", "Ollama"
    System.out.println(ex.statusCode());  // 429, 401, 500, …
}
```

`isRetryable()` is the *"try the same client again"* signal, and it is distinct from
`shouldFailover()` — *"could a different provider plausibly succeed?"* The two decisions
are independent. A `connectionError` is deliberately non-retryable locally (the endpoint
is unreachable, retrying hits the same wall) yet worth failing over to another model that
may well be reachable. An `AUTHENTICATION` / `INVALID_REQUEST` failure, by contrast, is
neither: it would recur on every candidate in the pool, so there is nothing to gain by
switching.

`FailoverLlmClient` in `ara-runtime` acts on `shouldFailover()` to decide whether to try
the next provider in the chain or abort immediately.

---

## LLM failover & circuit breaker

`FAILOVER` gives the agent an ordered chain of models: try the primary, and on a
`shouldFailover()` failure (network error, 5xx, rate limit) advance to the next fallback
in declaration order. A deterministic error (401, invalid request, content filter) aborts
the whole chain instead — it would recur on every candidate, so switching models would
change nothing but the log noise.

```java
AgentConfig config = AgentConfig.defaults()
        .agentType("resilient")
        .primaryLlm(LlmProfile.of("smart"))
        .fallbackLlms(List.of(LlmProfile.of("local"), LlmProfile.of("cheap")))
        .llmSelectionPolicy(LlmSelectionPolicy.FAILOVER)
        .build();
```

Every candidate hides behind a small passive circuit breaker (`CircuitBreakerLlmClient`).
After the first few consecutive failures (3 by default) the endpoint is *open*: later
calls skip it entirely — an outage stops being charged a connect/read timeout *per
request*, and the fallback serves the pool straight away. Once the 30-second cooldown
elapses, a single trial call re-probes the endpoint; success closes the circuit, another
failure reopens it for a fresh cooldown. Health always comes from real traffic, never
from background probe calls: probes bill like a call, can trip the very rate limit they
are meant to absorb, and judge an endpoint against a probe-specific timeout that real
calls would have survived.

Circuit state lives on the session's wiring (ADR-039), so a conversation that keeps its
session alive accumulates the diagnosis across calls, while a fresh ephemeral session
starts a clean breaker.

### Observing failover and breaker state

With `telemetry(...)` configured, the resilience layer adds its own spans to the trace. The
per-candidate `llm.complete` spans are already there (each candidate is instrumented), but
they don't say *why* a fallback answered — so the pool records a chain-level span:

| span | when | notable attributes |
| --- | --- | --- |
| `llm.failover` | every blocking chain | `outcome` = `served_by_primary` / `served_by_fallback` / `aborted_non_failover` / `exhausted` / `interrupted`, `attempts`, `served_by`, `chain` |
| `llm.failover` | each streaming decision | same, plus `streaming: true` and `provider`; `outcome` = `served` / `switching` / `aborted_non_failover` / `failed_after_first_token` / `exhausted` |
| `llm.circuit` | breaker transitions only | `outcome` = `opened` / `half_open` / `closed`, `from`, `failure_threshold`, `cooldown_ms`, `provider` |

Blocking calls wrap the whole candidate walk in a single `llm.failover` span, so the
candidates' `llm.complete` spans appear as its children. Streaming cannot: tokens arrive over
time on the provider's thread and a tracing scope is thread-bound, so each streaming decision
is recorded as its own short span instead.

Skips caused by an open circuit are deliberately *not* spans — an open circuit is hit once per
request for the whole outage, and one span per skip would bury the transitions that actually
explain the behaviour. Read the `opened` transition plus the pool's `served_by_fallback`
instead: they already say the candidate was skipped and who answered.

Runnable: `io.ara.examples.failover.FailoverExample` — the same 503 against `FAILOVER`
(survives via the fallback), against `PRIMARY_ONLY` (dies), a 401 (aborts without
touching the fallback), and a fourth agent driven repeatedly to watch the circuit open
and skip the dead primary. The same pattern for embedding calls is
`EmbeddingEndpointPool`, covered in the [RAG guide](HITL-AND-RAG.md).