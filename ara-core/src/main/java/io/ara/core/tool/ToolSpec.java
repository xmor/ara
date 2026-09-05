package io.ara.core.tool;

import java.util.List;
import java.util.Objects;

/**
 * The risk classification of a tool — a property of the tool declared in the registry,
 * never decided by the calling agent at runtime (ADR-0067, source §3.8.3). A wrapper over
 * {@link AraTool#toolId()}, the same pattern as {@code AgentSpec} over {@code AgentConfig}
 * (ADR-0065): {@code AraTool} is a widely-implemented interface and is not touched.
 *
 * <p>{@link #approvalRequired()} is <b>derived</b> from {@link #reversibility()}, not a
 * stored field: a separate boolean could be set inconsistently with the classification,
 * and a tool marked {@code IrreversibleHighImpact} with {@code approvalRequired = false}
 * would silently bypass the gate. The guarantee lives in the type (ADR-0067 D2).
 *
 * <p>{@code success_rate_30d} from the source schema is deliberately <em>not</em> a field
 * (ADR-0067 D4): a moving-window rate computed once would be stale immediately — it will
 * be a query against the trace store (ADR-0068), keyed by {@code toolId}, not built here.
 *
 * @param toolId       references {@link AraTool#toolId()}
 * @param sideEffects  independent axis (ADR-0067 D1)
 * @param reversibility the fused four-level classification
 * @param sandbox      {@code null} for {@link ToolOrigin#BUILTIN}; mandatory for {@link ToolOrigin#SYNTHESIZED}
 * @param origin       {@link ToolOrigin#BUILTIN} or {@link ToolOrigin#SYNTHESIZED}
 * @param tests        must be non-empty for a synthesized tool (§3.2.6)
 * @param status       {@link ToolLifecycle} phase (ADR-0084 D5); defaults to {@link ToolLifecycle#DRAFT}
 */
public record ToolSpec(
        String        toolId,
        SideEffects   sideEffects,
        Reversibility reversibility,
        SandboxPolicy sandbox,
        ToolOrigin    origin,
        List<String>  tests,
        ToolLifecycle status
) {

    public ToolSpec {
        Objects.requireNonNull(toolId, "toolId must not be null");
        if (toolId.isBlank()) throw new IllegalArgumentException("toolId must not be blank");
        Objects.requireNonNull(sideEffects, "sideEffects must not be null");
        Objects.requireNonNull(reversibility, "reversibility must not be null");
        origin = Objects.requireNonNullElse(origin, ToolOrigin.BUILTIN);
        tests = List.copyOf(Objects.requireNonNullElse(tests, List.of()));
        status = Objects.requireNonNullElse(status, ToolLifecycle.DRAFT);
        if (origin == ToolOrigin.SYNTHESIZED && tests.isEmpty()) {
            throw new IllegalArgumentException(
                    "a synthesized tool must declare at least one test (§3.2.6, non-negotiable)");
        }
        if (origin == ToolOrigin.SYNTHESIZED && sandbox == null) {
            throw new IllegalArgumentException("a synthesized tool must declare a sandbox policy");
        }
    }

    /**
     * 6-arg constructor (the shape before {@code status}, ADR-0084 D5) — kept so every
     * existing {@code new ToolSpec(...)} call from ADR-0067 keeps compiling with the phase
     * defaulted to {@link ToolLifecycle#DRAFT}.
     */
    public ToolSpec(String toolId, SideEffects sideEffects, Reversibility reversibility,
                    SandboxPolicy sandbox, ToolOrigin origin, List<String> tests) {
        this(toolId, sideEffects, reversibility, sandbox, origin, tests, ToolLifecycle.DRAFT);
    }

    /** A copy in a different lifecycle phase (ADR-0084 D4). */
    public ToolSpec withStatus(ToolLifecycle newStatus) {
        return new ToolSpec(toolId, sideEffects, reversibility, sandbox, origin, tests,
                Objects.requireNonNull(newStatus, "newStatus must not be null"));
    }

    /**
     * Whether a call to this tool must pass a human approval gate, whatever the calling
     * agent's own flag says. Derived, never set independently (ADR-0067 D2).
     */
    public boolean approvalRequired() {
        return reversibility instanceof Reversibility.IrreversibleHighImpact;
    }

    /** A built-in tool at the given classification, no sandbox, no declared tests. */
    public static ToolSpec builtin(String toolId, SideEffects sideEffects, Reversibility reversibility) {
        return new ToolSpec(toolId, sideEffects, reversibility, null, ToolOrigin.BUILTIN, List.of());
    }
}
