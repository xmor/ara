package io.ara.examples.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The files the agent of {@link PlannerExample} works on, and the tools it works with: an in-memory file
 * tree with {@code write_file}, {@code read_file} and {@code list_files}, plus a tool that fails a few times
 * before it answers.
 *
 * <p>Why in memory: nothing touches the disk, so the example is safe to run anywhere and starts clean
 * every time, and what the run left is one map that {@link LibraryChecks} can inspect afterwards instead of
 * trusting the model's account of it. Paths may be nested ({@code src/main/java/app/Person.java}).
 *
 * <p>Every tool takes at least one argument, even the ones that need none ({@code list_files} takes the
 * directory, the flaky tool a scope). Some local model servers lose a tool call that has no arguments,
 * and the model gets nothing back; an argument costs nothing and works everywhere.
 *
 * <p>Not thread-safe: one workspace per run, used by the one thread that runs the plan.
 */
final class Workspace {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> files = new TreeMap<>();
    private final List<AraTool> tools = new ArrayList<>();
    private final Map<String, Integer> callsByTool = new HashMap<>();

    Workspace() {
        tools.add(writeFile());
        tools.add(readFile());
        tools.add(listFiles());
    }

    // ── the file tools ───────────────────────────────────────────────────────

    private AraTool writeFile() {
        return new FileTool("write_file",
                "Writes (or overwrites) a text file in the workspace. Paths use '/' and may be nested; "
                        + "directories are created implicitly.",
                """
                {"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},
                 "required":["path","content"]}""",
                args -> {
                    String path = normalise(args.path("path").asText(""));
                    if (path.isEmpty()) return ToolResult.failure("write_file", "path must not be blank");
                    String content = args.path("content").asText("");
                    files.put(path, content);
                    return ToolResult.success("write_file", "Wrote " + content.length() + " chars to " + path);
                });
    }

    private AraTool readFile() {
        return new FileTool("read_file", "Reads a text file from the workspace.",
                """
                {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
                args -> {
                    String path = normalise(args.path("path").asText(""));
                    String content = files.get(path);
                    return content != null
                            ? ToolResult.success("read_file", content)
                            : ToolResult.failure("read_file", "File not found: " + path
                                    + ". Use list_files to see what exists.");
                });
    }

    private AraTool listFiles() {
        return new FileTool("list_files",
                "Lists the files under a directory, one path per line. Use '.' for the whole workspace.",
                """
                {"type":"object","properties":{"directory":{"type":"string",
                 "description":"a directory prefix such as src/main/java, or . for everything"}},
                 "required":["directory"]}""",
                args -> {
                    String directory = normalise(args.path("directory").asText("."));
                    boolean everything = directory.isEmpty() || directory.equals(".");
                    String prefix = directory.endsWith("/") ? directory : directory + "/";
                    List<String> listed = files.keySet().stream()
                            .filter(path -> everything || path.startsWith(prefix)).toList();
                    return ToolResult.success("list_files", listed.isEmpty() ? "(no files)" : String.join("\n", listed));
                });
    }

    /**
     * Adds a tool whose first {@code failuresBeforeSuccess} calls fail with {@code error}, then answers with
     * {@code answer}. It takes a {@code scope} argument that it ignores (see the class comment).
     */
    Workspace withFlakyTool(String toolId, String description, int failuresBeforeSuccess, String error, String answer) {
        tools.add(new FileTool(toolId, description,
                """
                {"type":"object","properties":{"scope":{"type":"string",
                 "description":"what to run it on; use workspace"}},"required":["scope"]}""",
                args -> {
                    int call = callsByTool.merge(toolId, 1, Integer::sum);
                    return call <= failuresBeforeSuccess
                            ? ToolResult.failure(toolId, error)
                            : ToolResult.success(toolId, answer);
                }));
        return this;
    }

    // ── what the example reads ───────────────────────────────────────────────

    /** The file at {@code path}, if there is one. */
    Optional<String> read(String path) {
        return Optional.ofNullable(files.get(normalise(path)));
    }

    /** Every file, by path, in path order. A view: it cannot be changed from outside. */
    Map<String, String> files() {
        return Collections.unmodifiableMap(files);
    }

    /** The ids of the tools, for {@code AgentConfig.enabledTools(...)}. */
    List<String> toolIds() {
        return tools.stream().map(AraTool::toolId).toList();
    }

    /**
     * The registry the runtime gets the tools from. It hands out every tool whatever ids it is asked for:
     * the one agent of the example enables them all, so there is nothing to filter.
     */
    ToolRegistry registry() {
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return tools; }
            @Override public List<AraTool> all() { return tools; }
            @Override public Optional<AraTool> findById(String id) {
                return tools.stream().filter(tool -> tool.toolId().equals(id)).findFirst();
            }
            @Override public ToolResult execute(String toolId, String argumentJson) {
                return findById(toolId).map(tool -> tool.execute(argumentJson))
                        .orElseGet(() -> ToolResult.failure(toolId, "unknown tool: " + toolId));
            }
        };
    }

    /** {@code ./a/b} and {@code /a/b} and {@code a\b} are all {@code a/b}. */
    private static String normalise(String path) {
        String p = path.strip().replace('\\', '/');
        while (p.startsWith("./") || p.startsWith("/")) {
            p = p.startsWith("./") ? p.substring(2) : p.substring(1);
        }
        return p;
    }

    /** A tool whose body is one lambda; the arguments reach it already parsed. */
    private static final class FileTool implements AraTool {
        private final String id;
        private final String description;
        private final String schema;
        private final Function<JsonNode, ToolResult> body;

        FileTool(String id, String description, String schema, Function<JsonNode, ToolResult> body) {
            this.id = id;
            this.description = description;
            this.schema = schema;
            this.body = body;
        }

        @Override public String toolId() { return id; }
        @Override public String description() { return description; }
        @Override public String argumentSchema() { return schema; }

        @Override public ToolResult execute(String argumentJson) {
            try {
                return body.apply(MAPPER.readTree(argumentJson == null || argumentJson.isBlank() ? "{}" : argumentJson));
            } catch (Exception e) {
                return ToolResult.failure(id, "invalid arguments: " + e.getMessage());
            }
        }
    }
}
