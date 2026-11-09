# io.ara.core.llm

Provider-agnostic abstraction over LLM completions: the interfaces, data model, and
configuration types that let `ara-runtime` drive any model (OpenAI, Anthropic, Ollama, …)
without depending on a specific SDK. Concrete adapters live in `ara-adapters`.

## Core interfaces

- **`LlmClient`** — the single entry point to a model. Primary method is
  `complete(List<LlmMessage>, LlmCallContext)`. The `complete(List, AgentConfig)` /
  `stream(List, AgentConfig)` overloads are deprecated bridges (ADR-017) kept for
  backward compatibility; new code should use the `LlmCallContext` overloads.
- **`LlmRouter`** — selects which `LlmClient` to use for a call, applying
  `LlmSelectionPolicy` (`PRIMARY_ONLY`, `FAILOVER`, `ROUND_ROBIN`). Default
  implementation is `DefaultLlmRouter` in `ara-runtime`.
- **`LlmClientFactory`** — functional interface for building an `LlmClient` from an
  `LlmTransport` (ADR-039 §3 "asse A" — `baseUrl`/`apiKey`/`modelName` only), wired by
  `AraPlatformFactory`. Deliberately *not* given the full `LlmProfile`: parameters like
  `temperature`, `streamingEnabled()`, `nativeJsonSchema()` are asse B and flow per-call
  via `LlmCallContext` instead, since the same transport is shared across every profile
  that references it regardless of their parameters.

## Data model

- **`LlmMessage`** — one turn in the conversation. `role` is a plain `String`
  (`"system"`, `"user"`, `"assistant"`, `"assistant_tool_call"`, `"tool"`) rather than
  an enum; use the static factories (`system`, `user`, `assistant`, `assistantToolCall`,
  `tool`) instead of constructing roles by hand. `media` (a `List<MediaRef>`) carries
  images and documents accompanying a `"user"` turn — references, never bytes; use
  `user(String, List<MediaRef>)`.
- **`LlmCompletion`** — the result of a call. Supports both a single legacy tool call
  (`toolCallJson` / `toolCallId`) and multiple parallel tool calls (`toolCalls`, a list
  of `ToolCallEntry`). When `toolCalls` is non-empty it takes precedence; use
  `hasToolCall()` rather than checking either field directly.
- **`ToolCallEntry`** — one normalised tool invocation inside a multi-call completion.
- **`LlmException`** — typed failure with `ErrorType` and `isRetryable()`. Adapters map
  provider errors to it via factory methods (`rateLimit`, `authenticationError`,
  `networkError`, `serverError`, …); `FailoverLlmClient` uses `isRetryable()` to decide
  whether to try the next profile or propagate.

## Configuration hierarchy

Three layers, increasing in specificity — later layers win:

1. **`LlmProfile`** — static per-model config, itself split by concern (ADR-039 §3): which
   `LlmTransport` to call (`transportId`/`inlineTransport` — base URL, API key, model
   name; shared, ref-counted, resolved once per session), per-call parameters
   (temperature, topP, maxTokens, streaming/JSON-schema support), and per-agent
   governance (cost budget, currency, tariffs). One profile per model.
2. **`LlmConfig`** — an agent's `primary` profile plus `fallbacks` and the
   `LlmSelectionPolicy` used to choose between them. Part of `AgentConfig`.
3. **`LlmCallContext`** — per-call overrides (output JSON schema, temperature, stop
   sequences, seed, provider override) layered on top of an `AgentConfig`/`LlmProfile`
   via `LlmCallContext.of(config, task)`. Built fresh by the strategy for every
   invocation; never reused across tasks.

`LlmExecutionHints` is the channel (`AgentTask.hints()`) a caller uses to request
per-call overrides without touching the data contract (`AgentContract`) — it describes
*how* the model is called, not the shape of the output.

## Notes for implementers

- `temperature`/`topP` can be set on `LlmProfile`, overridden per-call on
  `LlmCallContext`, and are also exposed via `AgentConfig` delegate methods — when
  reading effective values, always go through `LlmCallContext.temperature()` (applies
  the override) rather than reaching into `LlmProfile` directly.
- Adapters must never return `null` from `aiMessage().text()`-style calls — the
  `LlmCompletion` constructor rejects a null `text`; fall back to `""`.
- `LlmCallContext` also carries `resolvedTools` (a `List<AraTool>`) when the calling
  strategy has already resolved tools for the step — clients must use these instead of
  resolving from their own registry when present (`hasResolvedTools()`).
- `LlmCallContext.mediaResolver()` is how an adapter turns a `MediaRef` on an outgoing
  message back into bytes. Never `null`: when no `MediaStore` is wired the default
  resolver answers by failing with the reference's name and id, rather than by producing
  empty bytes and letting the model answer confidently about nothing.
- `LlmClient.supportedMediaTypes()` declares what a client can really send. Provider
  media support is per-type, not a boolean: Ollama takes images but has no PDF path at
  all. A type absent from the set must produce a **non-retryable** `LlmException` naming
  the type and the provider, raised before the request goes out — never a dropped
  attachment, never a downgrade to text, never a warning and a continue. Stripping the
  attachment produces a fluent answer about a document the model never saw, with the only
  trace in a log nobody reads in production; the non-retryable classification is also
  what stops `FailoverLlmClient` from letting a text-only fallback answer instead.

- `LlmClient.toolMediaPlacement()` declares where the provider accepts the media a **tool**
  returned: `FOLLOWING_USER_MESSAGE` (the default: tool results as text, then one user message with
  the labelled images) or `INLINE_IN_TOOL_RESULT` (Anthropic). Declare the inline form only for a
  provider that documents it: OpenAI-format endpoints answer 200 to an image in a tool message and
  the model never sees it. A tool's media of a type `supportedMediaTypes()` lacks becomes a note in
  the tool text, not an exception — unlike a user's attachment, which still fails.

### Capability methods and decorators

Every capability default on `LlmClient` (`supportsNativeTools()`,
`supportedMediaTypes()`, `toolMediaPlacement()`) is the safe answer for a *leaf* client and a lie for a
decorator. Single-wrap decorators must extend `io.ara.runtime.llm.DelegatingLlmClient`,
which forwards all of them, rather than hand-copying the delegation — a decorator that
forgets one silently masks the capability of what it wraps, with no exception and no log
line. Composites over a list of clients (`Failover`, `RoundRobin`, `Routing`) report the
**intersection** of their delegates' (for `toolMediaPlacement()`, inline only if every delegate is), since they cannot promise what a candidate they
might pick lacks. Leaf stubs that wrap nothing are correct with the interface defaults.

### Architectural note: `resolvedTools` and `mediaResolver` on `LlmCallContext`

Tool availability is conceptually a different concern from the sampling parameters
(temperature, schema, stop sequences, seed) that otherwise make up `LlmCallContext`.
It lives there anyway because every adapter (OpenAI, Anthropic, Ollama, Mistral) already
reads tool specs off the call context it's given, and `AraTool` is itself a `ara-core`
domain type — so this isn't a module-boundary violation, just a widening of the class's
responsibility. The cleaner long-term shape is a dedicated parameter on
`LlmClient.complete(messages, context, tools)`, but that means changing the primary
`LlmClient` signature across every adapter and strategy call site.

This note used to say: do that split together with the next change that already has to
touch every adapter. That change has since happened — multimodal input — and the split
was **deliberately deferred again**, because that change already carried a breaking
modification to a public record (`LlmMessage` gaining `media`), and stacking a signature
change to the primary `LlmClient` method on top would have landed two independent
migrations on the same twelve call sites at once. `mediaResolver` therefore sits beside
`resolvedTools` as the second declared occurrence of the same widening.

So the trigger is now the **third** occurrence, not "the next adapter-wide change":
if another non-sampling concern needs to reach the adapter this way, split the signature
then instead of adding a third field and a third paragraph here.

Note also that `LlmCallContext` used to carry a second, redundant tool-related field —
`enabledTools` (`List<String>`, copied straight from `AgentConfig`) — that no strategy
or adapter ever read from the call context (they read `AgentConfig.enabledTools()`
directly and resolved into `resolvedTools` themselves). It was dead weight and has been
removed; `resolvedTools` is the only tool-related field on this class now.