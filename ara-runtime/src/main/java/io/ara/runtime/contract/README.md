# io.ara.runtime.contract

Deterministic, LLM-free building blocks for `AgentContract` (ADR-014): concrete
`InputProcessor`, `OutputProcessor`, and `PromptShaper` implementations that
`ContractEnforcingAgent` (`io.ara.runtime.agent`) applies around every
`agent.execute()` call. Nothing in this package ever calls an LLM — every class here
is a pure, synchronous string transformation with no I/O beyond in-memory parsing.

## How this fits together

`ContractEnforcingAgent` delegates to `ContractEnforcer`, which applies the pieces
declared on an `AgentContract` in a fixed order:

```
1. InputProcessor chain   — contract.inputProcessors(), applied in declaration order
2. PromptShaper chain     — contract.promptShapers(), applied in declaration order
3. outputSchema           — contract.outputSchema() (a SchemaProvider): always put on the
                            task's LlmExecutionHints, and additionally appended to the
                            system prompt via OutputFormatEnforcer unless the LLM profile
                            sets nativeJsonSchema(true) — in which case the adapter sends
                            it as a provider-native response_format instead
4. inner.execute(task)    — the actual agent/strategy/LLM loop
5. OutputProcessor chain  — contract.outputProcessors(), applied in declaration order
```

An input `Reject` short-circuits to `AgentResponse.failure(...)` before `execute()` is
ever called; an output `Reject` does the same after `execute()` succeeds. `PromptShaper`
cannot reject — it only transforms the system prompt string.

## Core interfaces (defined in `ara-core`, `io.ara.core.agent.processor`)

- **`Processor`** — the shared functional shape: `ProcessingResult process(String content)`.
  `InputProcessor` and `OutputProcessor` both extend it with no extra methods, purely so
  the two roles stay distinct at the `AgentContract` declaration site (a class can freely
  implement both, as most classes in this package do).
- **`ProcessingResult`** — sealed: `Pass(String value)` or `Reject(String reason)`.
  Callers `switch` over it exhaustively; there is no accessor that throws on the wrong
  branch.
- **`PromptShaper`** — `String shape(String systemPrompt, AgentTask task)`. Cannot reject;
  each shaper sees the prompt as already modified by the previous one in the chain.
- **`SchemaProvider`** — `String jsonSchema()`. Lets a validator double as the schema
  source for `AgentContract.Builder.outputSchema(...)` so the schema is declared once,
  not duplicated between the schema itself and the validator that checks it.

## Input processors

| Class | Rejects when | Notes |
|---|---|---|
| `InputSanitizer` | input matches a prompt-injection/jailbreak pattern | Built-in EN + IT regex set (`ignore previous instructions`, ChatML/Llama2 injection markers, `ignora le istruzioni precedenti`, …). `instance()` = built-ins only, `withExtra(...)` = built-ins + extra, `custom(...)` = only the given patterns. |
| `ContentTruncator` | never — truncates instead | `to(maxChars)`. Silently cuts to `maxChars`; use `MaxLengthValidator` instead if you want a hard rejection rather than silent truncation. |

## Output-only processors

| Class | Purpose |
|---|---|
| `MarkdownFenceStripper` | Strips the **outer** ```` ``` ```` fence LLMs commonly wrap around a whole JSON/code response. Singleton via `instance()`. Pass-through if no fence found. |
| `CodeFenceExtractor` | Extracts the **inner content of one specific fenced block** out of a larger response that mixes prose and code — `forLanguage("java")`, or the convenience factories `java()`/`json()`/`python()`/`sql()`/`anonymous()`. Different from `MarkdownFenceStripper`: that one assumes the *whole* output is one fence; this one searches for a tagged fence inside mixed content. Pass-through if no matching fence is found. |
| `MinLengthValidator` | `atLeast(minChars)` — rejects near-empty LLM responses. |

## Prompt shapers

- **`PromptTemplate`** — resolves `{key}` placeholders in the system prompt.
  Resolution order: `task.context()` first, then the shaper's own defaults, then (unless
  `strict()`) left unresolved as literal text.
  - `instance()` — `task.context()` only, no defaults.
  - `withDefaults(Map<String,String>)` — static defaults, **frozen at construction time**.
  - `withInstanceContext(AgentInstanceContext)` — defaults read **live** from the ADR-036
    per-agent instance store on every `shape()` call; use this instead of `withDefaults`
    when the fallback values can change between executions without rebuilding the
    `AgentContract`.
  - `.strict()` — returns a copy that throws `IllegalStateException` on an unresolved
    placeholder instead of leaving it as literal `{key}` text.
  - `.delimiters(open, close)` — returns a copy using a different delimiter pair (e.g.
    `{{key}}`) — needed when the prompt already contains literal single braces (a JSON
    example block) that would otherwise be misread as placeholders.
  - Must be declared **before** `AgentContract.outputSchema()` triggers
    `OutputFormatEnforcer` — the framework guarantees this ordering, not this class.
- **`KnowledgeBasePromptShaper`** — not meant to be wired manually. `AgentFactory` attaches
  it automatically when `config.knowledgeBaseId()` is set and `"search_documents"` is in
  the agent's enabled tools, appending strict instructions to call that exact tool name
  before answering.

## Dual-role processors (both `InputProcessor` and `OutputProcessor`)

These are symmetric validators/transforms with no directional bias — used on input,
output, or both, depending on the contract:

- **`JsonFieldExtractor.field("result.content")`** — extracts one field by dot-path;
  `Reject`s if the path is missing or the payload isn't JSON. Returns the field's raw text
  for strings, or the JSON-serialised form for objects/arrays/numbers.
- **`JsonSchemaValidator`** — also implements `SchemaProvider`.
  - `jsonOnly()` — well-formedness only.
  - `requiring("a", "b")` — well-formedness + presence of the named top-level fields.
  - `forOutput(schema)` — full JSON Schema validation (networknt `json-schema-validator`:
    types, nested `properties`/`items`, `required`, `enum`/`const`, bounds, `pattern`,
    `additionalProperties`, `oneOf`/`anyOf`/`allOf`, local `$ref`; dialect from `$schema`,
    draft 2020-12 by default), **and** exposes that same schema via `jsonSchema()` so the
    identical instance can be passed to both `.outputSchema(validator)` and
    `.addOutputProcessor(validator)` on the `AgentContract` builder — declared once, never
    duplicated. Calling `jsonSchema()` on an instance built via `jsonOnly()`/`requiring()`
    throws `IllegalStateException` — only `forOutput(...)` instances carry a schema.
  - A rejection lists every violation with its JSON Path (`$.items[2].price: string found,
    number expected`), up to 10. `format` is asserted, messages are always English, remote
    `$ref` is never fetched. An invalid schema throws `IllegalArgumentException` at
    `forOutput(...)`, not on the first payload.
  - Until it moved onto networknt, `forOutput` checked only the top-level `required` array —
    a payload that passed then can be rejected now.

### Repairing a rejected output

`AgentContract.builder().outputRepairAttempts(n)` (default `0`, off) makes
`ContractEnforcer` re-execute the agent up to `n` times when the output chain rejects, each
time with the original input followed by the rejected answer and the rejection reason —
which, from `JsonSchemaValidator`, names every failing path. Only output rejections are
repaired; input/media rejections and failures of the agent itself are returned as they
are. Tokens, cost, iterations and steps of every attempt add up in the final response.
- **`RegexValidator`** — `matching(regex)` requires the pattern; `notMatching(regex)`
  forbids it. Optional `description` overload for a clearer rejection message than the
  raw regex. **Does not null-guard its input** — unlike every other processor in this
  package, `process(null)` throws `NullPointerException` rather than degrading to
  `pass("")` or a `Reject`. Keep this in mind if it sits downstream of a processor that
  can legitimately produce `null`.
- **`MaxLengthValidator.atMost(maxChars)`** — hard rejection over the limit, as opposed
  to `ContentTruncator`'s silent cut.
- **`PiiRedactor`** — regex-based, best-effort redaction of email, phone (IT/intl),
  card numbers (Luhn-shaped, 13–19 digits), IPv4, and Italian *codice fiscale* into typed
  placeholders (`[EMAIL]`, `[PHONE]`, `[CARD]`, `[IPv4]`, `[CF]`). **Always `Pass`es —
  never rejects**; this is the one processor in the package designed to be silent by
  nature. Not a substitute for a dedicated PII-detection library in high-compliance
  environments. Singleton via `instance()`.
- **`WhitespaceNormalizer`** — line-ending normalisation, horizontal-whitespace collapse,
  trailing-space and excess-blank-line cleanup, then a final strip. Singleton via
  `instance()`. Useful upstream of length/regex validators so they see consistent
  formatting regardless of how the LLM or caller formatted the text.
- **`XmlFieldExtractor.xpath(expression)`** — like `JsonFieldExtractor` but for XML/XHTML
  payloads, returning the concatenated text content of every matched node. XXE-hardened
  (`disallow-doctype-decl`, external entities disabled at the `DocumentBuilderFactory`
  level). `Reject`s on unparsable XML, a blank payload, or an XPath expression that
  matches zero nodes.

## Usage example

```java
AgentContract contract = AgentContract.builder()
    .addInputProcessor(InputSanitizer.instance())
    .addInputProcessor(PiiRedactor.instance())
    .addPromptShaper(PromptTemplate.withDefaults(Map.of("lang", "italiano")))
    .outputSchema(JsonSchemaValidator.forOutput(personSchema))   // SchemaProvider
    .addOutputProcessor(MarkdownFenceStripper.instance())
    .addOutputProcessor(JsonSchemaValidator.forOutput(personSchema)) // same instance, no duplication
    .addOutputProcessor(MinLengthValidator.atLeast(10))
    .build();

AraAgent agent = agentFactory.create(config, contract);
```

## Conventions across this package

- **Immutable and stateless** — every class is safe to share as a single instance across
  concurrent agent executions; several expose a shared singleton via `instance()`
  (`InputSanitizer`, `MarkdownFenceStripper`, `PiiRedactor`, `WhitespaceNormalizer`).
- **Private constructors + static factories** are the norm (`field(...)`, `matching(...)`,
  `forLanguage(...)`, …) so call sites read as intent, not `new SomeValidator(true, false, null)`.
  The exceptions are `ContentTruncator`, `MaxLengthValidator`, and `MinLengthValidator`,
  which expose both a public constructor and an equivalent static factory
  (`to(...)`/`atMost(...)`/`atLeast(...)`) — prefer the static factory for consistency
  with the rest of the package.
- **Fail-open vs. fail-closed** is a deliberate per-class choice, not an oversight:
  extractors/strippers that find nothing (`MarkdownFenceStripper`, `CodeFenceExtractor`)
  pass the payload through unchanged, while validators (`RegexValidator`,
  `MaxLengthValidator`, `MinLengthValidator`, `JsonSchemaValidator`, `JsonFieldExtractor`,
  `XmlFieldExtractor`) reject. `PiiRedactor` is the deliberate odd one out — it never
  rejects, by design, since redaction should degrade silently rather than block a request.
- **`null` handling is inconsistent by design in one place**: most processors treat a
  `null` payload as an empty string and return `Pass("")`. `RegexValidator` is the
  exception — it null-checks eagerly and throws. If you chain processors, put anything
  that can legitimately hand back `null` *after* `RegexValidator`, not before it.
