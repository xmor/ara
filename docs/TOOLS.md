# Tool calling

[← back to README](../README.md)

---

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

`ToolRegistry` is a small public port. For a fixed in-memory catalog you copy this helper
once — the same one the runnable examples use
([`ara-examples/.../support/Tools.java`](../ara-examples/src/main/java/io/ara/examples/support/Tools.java)) —
and every tool after that is just an `AraTool`:

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
```

Implement the port directly only when your tools come from somewhere dynamic (a database, a
remote catalog, a plugin system); `resolveEnabled`, `findById` and `execute` are the only
methods you must write. Wiring the tool into a runtime and an agent is then three lines:

```java
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
By default it waits for the answer. An agent that opts in can also start the sub-task in the
background and collect the result later — see
[Background delegation](CONFIGURATION.md#background-delegation).

## Parallel tool dispatch (Java 21 virtual threads)

When the LLM requests multiple tools in a single response, ARA dispatches them
concurrently on virtual threads automatically — no configuration needed:

```
LLM response → [get_weather(Rome), get_weather(London)]
                      ↓ virtual thread            ↓ virtual thread
                   300 ms I/O                  300 ms I/O
                      └──────── ~300 ms total ───────┘
```

## Showing each caller only the tools it may use

By default an agent's catalog is the same for everyone: whatever `enabledTools()` selects
is what every task sees, and a caller without the right authority only finds out when the
call is denied at dispatch. A registry chain can narrow that catalog per caller with the
scope-filter decorator:

```java
ToolRegistry chain = new ScopeFilteringToolRegistry(inner);
```

With this form, each task's own authority decides what the model sees: the task's
execution context (its effective scopes, actor ∩ subject) first, then the bare scope set
on the run context, then nothing. A tool that declares `requiredScopes()` and is not
covered is hidden from the catalog before the LLM ever reads it — no denied call to
explain, no retry loop on a tool that was never usable. A tool that declares no scopes is
unaffected, and a task that carries no scope channel at all is treated as authorized for
nothing: fail-closed for gated tools, invisible for everything else. When a gated tool is
hidden because the task carried no channel, the registry logs one warning per tool — the
symptom of a seeding omission, not a denial to show the model.

The runtime wires this decorator into every agent's chain automatically; declare scopes on
the tools that need them and seed the tasks, and the catalogs follow. The fixed-view
constructor (`new ScopeFilteringToolRegistry(inner, agentView)`) stays for deployments
whose scopes never vary within the agent.

## Related

- [Human approval on every tool call](HITL-AND-RAG.md#human-in-the-loop-hitl--approval-gate)
- [Private per-agent data inside a tool](CONFIGURATION.md#agent-instance-context--private-per-agent-data)
- [Media limits and contract validation](CONTRACTS.md)