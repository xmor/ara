package io.ara.core.tool;

import io.ara.core.agent.AgentTask;

import java.util.List;

/**
 * Contract that every tool registered in ARA must implement.
 *
 * <p>A tool is any capability the agent can invoke during its {@code Act} step:
 * a web search, a code executor, a database query, an external API call, etc.
 *
 * <p>Implementations must be stateless and thread-safe. State between invocations
 * must never be stored inside the tool — it belongs in the agent's memory layer.
 */
public interface AraTool {

    /**
     * Returns the unique identifier of this tool (e.g. {@code "search_web"}).
     *
     * @return a non-null, non-blank tool identifier
     */
    String toolId();

    /**
     * Returns a human-readable description of what this tool does.
     * The LLM uses this description to decide when to call the tool.
     *
     * @return a non-null description string
     */
    String description();

    /**
     * Returns the JSON Schema of the arguments this tool accepts.
     * Used by the LLM for structured function calling.
     *
     * @return a valid JSON Schema string; never {@code null}
     */
    String argumentSchema();

    /**
     * Executes the tool with the provided JSON arguments.
     *
     * @param argumentJson JSON matching this tool's {@link #argumentSchema()}
     * @return the execution result; never {@code null}
     */
    ToolResult execute(String argumentJson);

    /**
     * Executes the tool with access to the current {@link AgentTask}, including its
     * opaque values in {@link AgentTask#runContext()} (ADR-037, ADR-041 rev. 2).
     *
     * <p>Default: ignores the task and delegates to {@link #execute(String)}. Tools
     * that depend on an attachment for their correctness or safety (e.g. a
     * {@code SecurityContext} used for authorization) MUST override
     * {@link #execute(String)} to fail rather than execute with degraded or
     * implicitly permissive behavior — the task may legitimately be absent when a
     * {@link ToolRegistry} in the chain does not forward it.
     *
     * @param argumentJson JSON matching this tool's {@link #argumentSchema()}
     * @param task         the current task; may carry attachments unavailable via
     *                     {@link #execute(String)} alone
     * @return the execution result; never {@code null}
     */
    default ToolResult execute(String argumentJson, AgentTask task) {
        return execute(argumentJson);
    }

    /**
     * The authorization scopes a caller must hold to have this tool resolved into its
     * catalog (ADR-033 Fase 1). Default: none — the tool is available to every agent that
     * enables it, exactly as before.
     *
     * <p>Fase 1 is declaration only; {@code DelegatingToolRegistry} does not filter on
     * this yet (Fase 3).
     *
     * @return an unmodifiable list of required scope strings; never {@code null}
     */
    default List<String> requiredScopes() {
        return List.of();
    }
}
