# Contracts, processors & multimodal input

[← back to README](../README.md)

- [AgentContract — deterministic I/O](#agentcontract--deterministic-io)
- [Built-in processors](#built-in-processors)
- [PromptShaper — dynamic system prompt](#promptshaper--dynamic-system-prompt)
- [Multimodal input — images and documents](#multimodal-input--images-and-documents)

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

`outputSchema(...)` does two things: it declares the contract *and* makes the schema reach
the model. By default (`nativeJsonSchema(false)`) that happens by appending the schema to the
system prompt (`"Respond ONLY with a single valid JSON object matching this schema"`) — a route
that works against every endpoint, including gateways that support no `response_format` at all.

Setting `nativeJsonSchema(true)` on the `LlmProfile` sends the schema as a provider-native
`response_format: json_schema` instead, on the request rather than in the prompt. Support is
per-**endpoint**, not per-provider: `OpenAiLlmClient` claims it for hosted OpenAI and, behind a
custom `baseUrl`, only when told to — the same treatment media types get:

```java
LlmClient viaGateway = AraLlmClientFactory.openAi()
        .apiKey(KEY).baseUrl("https://gateway.internal/v1").modelName("m")
        .structuredOutputSupport(true)   // this endpoint really accepts response_format
        .strictJsonSchema(true)          // optional: guarantee, not just guidance — see below
        .build();
```

Asking for the native path on a client that does not support it fails the call with a
non-retryable error naming the capability, instead of quietly dropping the schema and answering
in prose.

**Guidance vs guarantee.** By default the native schema travels with `strict: false`: the
provider is told the shape but does not constrain decoding, so a wrong answer is still possible
and it is the contract's own validator (plus `outputRepairAttempts`) that catches it.
`strictJsonSchema(true)` sends `strict: true`, which makes OpenAI constrain decoding so the
answer cannot violate the schema — at the cost of what the schema may contain (every property
must be in `required`, `additionalProperties` must be `false`, and keywords like `pattern`,
`minimum` and `format` are unsupported). A legal draft-07 schema using one of those is answered
with a 400, which is why it is opt-in rather than derived.

## Built-in processors

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
| `MarkdownFenceStripper` | Remove ```` ```json ```` / ```` ``` ```` wrappers |
| `JsonFieldExtractor.field("path.sub")` | Extract a dot-path field from JSON |
| `CodeFenceExtractor.java()` | Extract content of a ```` ```java ```` fence |
| `WhitespaceNormalizer` | Collapse multiple spaces/newlines |
| `ContentTruncator.to(4000)` | Hard truncate input to N characters |

**Security**

| Processor | Function |
|---|---|
| `InputSanitizer.instance()` | Block prompt-injection patterns (EN + IT) |
| `PiiRedactor.instance()` | Redact email, phone, tax codes, credit cards, IPv4 |

**Attachments** — declared with `addMediaValidator(...)`, not `addInputProcessor(...)`:
an input processor only ever sees the input string and cannot look at a `MediaRef`.

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

## Multimodal input — images and documents

Attach an image or a PDF to a task and the model reads it natively — layout, tables,
stamps and scanned pages included. This is the path for what text extraction cannot give
you; for PDFs that are *already* text, indexing them into a `DocumentStore` and letting
`RetrievalAugmentedStrategy` retrieve the relevant chunks remains the cheaper answer, and
the two coexist without talking to each other.

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

| Provider | Images | PDF as document | Text files |
|---|---|---|---|
| Mistral | yes | yes | yes |
| OpenAI | yes | yes — hosted only, see below | yes |
| Anthropic | yes | yes | yes |
| Ollama | yes | **no** | yes |

Send a PDF to Ollama and the task fails with a non-retryable `LlmException` naming the
type and the provider, *before* the request goes out. It is never stripped, never
downgraded to text, never logged-and-continued: those all produce a fluent, plausible
answer about a document the model never saw, which is indistinguishable from a real one
to whoever reads it. Because the failure has `shouldFailover() == false` (the mismatch
would recur on every candidate), `FailoverLlmClient` aborts instead of letting a
text-only fallback answer instead — and a `FAILOVER` or `ROUND_ROBIN` pool reports the
*intersection* of its members' media types for the same reason.

**Media support belongs to the endpoint, not the vendor.** `OpenAiLlmClient` is meant to
be pointed at any OpenAI-compatible API, and while they all accept the `image_url` part,
many reject the `file` part a PDF becomes — a corporate gateway typically answers
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

## Artifacts in the response — code blocks and documents

An answer is a conversation turn; a block of code or a document inside it is *output*. When
a consumer wants those parts — to run a check on a code block, to hand a document to another
agent — re-parsing the text each time means every consumer finds them by its own rule. The
runtime can do it once, store each part in the same `MediaStore` that holds task media, and
return the references on the response:

```java
AraRuntime runtime = AraRuntime.builder()
        .llmClient("mistral", mistral)
        .mediaStore(MediaStore.inMemory())                         // artifacts need a store that accepts writes
        .artifactExtractor(new FencedBlockArtifactExtractor())     // defaults to ArtifactExtractor.none()
        .build();

AgentResponse response = agent.execute(AgentTask.of("write a function and its query"));

for (MediaRef artifact : response.artifacts()) {                   // block-1.py, block-2.sql, ... in answer order
    byte[] bytes = media.get(artifact.mediaId()).orElseThrow();
}
```

The text of the answer is unchanged — artifacts are in addition to it. Each is a `MediaRef`
like an input attachment: the SHA-256 of the content, a name, a size, no payload. So the
same block twice is one entry in the store, and a reference can be passed on as the
attachment of another task.

**It is off unless you turn it on.** Without an extractor, or with `MediaStore.noop()`
(which cannot hold anything), `artifacts()` is empty and the runtime behaves exactly as it
did before this existed. Only a completed task has artifacts; a failed one has none.

**The model never produces an artifact.** The system derives the parts from the answer the
model already wrote. Asking the model to create one would put a long text in the arguments
of a tool call, which are JSON, and the escaping of that text would move there — the very
problem an artifact avoids.

**What `FencedBlockArtifactExtractor` does.** Every fenced code block becomes
`block-N.<extension>` (`python` → `.py`, `sql` → `.sql`; no extension when the fence declares
no language or one that is not a plain word, like `c++`), stored as `text/plain`. The media
vocabulary is closed on purpose — it also decides what a task may carry *in* — so the
language rides in the name rather than in a new MIME type. A fence that is never closed is
not a block, and a block of only whitespace is skipped. A block that itself contains a line
of three backticks ends there, as in any reader that does not use longer fences. For answers
with another structure — a table, a patch — implement `ArtifactExtractor` (a pure function
of the text, thread-safe) and pass it to the builder.

**A failure to extract never fails the agent.** The task was already completed, so an
extractor that throws or a store that refuses a part costs the artifacts and nothing else:
the response keeps its text and carries none, never a partial list, and the failure is
logged at `warn`.

**Known limit.** Artifacts are derived from the answer as the agent produced it. If an
agent's contract has an output processor that rewrites the text, the artifacts correspond to
the text before the rewrite.
