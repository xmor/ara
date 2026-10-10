package io.ara.runtime.strategy;

import io.ara.core.tool.AraTool;

import java.util.List;

/**
 * Formats the tool catalogue for inclusion in a system prompt.
 *
 * <p>Centralising this logic eliminates the inconsistency where
 * {@code ReactStrategy} returned an empty string on an empty list
 * while {@code PlanExecuteStrategy} returned an explicit sentence.
 * The canonical behaviour is an empty string (the LLM infers "no tools"
 * from the absence of the section).
 */
public final class ToolCatalogFormatter {

    private ToolCatalogFormatter() {}

    /**
     * Returns a human-readable tool catalogue to append to the system prompt,
     * or an empty string when {@code tools} is empty.
     */
    public static String format(List<AraTool> tools) {
        if (tools.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nAvailable tools:\n");
        for (AraTool tool : tools) {
            sb.append("- ").append(tool.toolId())
              .append(": ").append(tool.description()).append("\n")
              .append("  Arguments: ").append(tool.argumentSchema()).append("\n");
        }
        return sb.toString();
    }

    /**
     * The catalogue as a planner needs it: what each tool is called and what it does, without the
     * argument schemas. A plan names the tools it will use; the arguments are the step's concern.
     *
     * <p>Leaving the schemas out is not only shorter. Measured against a local gpt-oss-20b behind
     * LM Studio, a system prompt that carries tool schemas as JSON text <i>and</i> asks for a JSON
     * plan makes the server fail the request — the model's JSON plan is taken for a tool call it
     * cannot parse — on every attempt (5 of 5 on one task), while the same request without the
     * schemas worked every time (6 of 6) and so did the schemas with a list-shaped plan. Returns an
     * empty string when {@code tools} is empty.
     */
    public static String formatForPlanning(List<AraTool> tools) {
        if (tools.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nAvailable tools:\n");
        for (AraTool tool : tools) {
            sb.append("- ").append(tool.toolId()).append(": ").append(tool.description()).append("\n");
        }
        return sb.toString();
    }
}
