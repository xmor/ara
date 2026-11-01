package io.ara.runtime.agent;

import io.ara.core.agent.ConversationTurn;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ToolExchange;
import io.ara.core.memory.MemoryManager;
import io.ara.core.memory.ToolCallMetadata;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolHistoryPolicy;
import io.ara.core.tool.ToolRegistry;
import io.ara.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallHistoryTest {

    private static AraTool tool(String id, ToolHistoryPolicy policy) {
        return new AraTool() {
            @Override public String toolId() { return id; }
            @Override public String description() { return id; }
            @Override public String argumentSchema() { return "{\"type\":\"object\"}"; }
            @Override public ToolResult execute(String argumentJson) { return ToolResult.success(id, "ok"); }
            @Override public ToolHistoryPolicy historyPolicy() { return policy; }
        };
    }

    private static ToolRegistry registry(AraTool... tools) {
        Map<String, AraTool> byId = new java.util.HashMap<>();
        for (AraTool t : tools) {
            byId.put(t.toolId(), t);
        }
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.copyOf(byId.values()); }
            @Override public Optional<AraTool> findById(String id) { return Optional.ofNullable(byId.get(id)); }
            @Override public ToolResult execute(String id, String json) { return byId.get(id).execute(json); }
        };
    }

    private static ExecutionStep observed(String toolId, String content) {
        return ExecutionStep.observation(ToolResult.success(toolId, content), 1);
    }

    @Test
    void eachCallIsPairedWithItsResultInTheOrderTheCallsWereMade() {
        List<ToolExchange> calls = ToolCallHistory.record(List.of(
                ExecutionStep.thought("thinking", 1),
                ExecutionStep.toolCall("lights", "{\"on\":false}", 1),
                observed("lights", "switched off"),
                ExecutionStep.toolCall("lights", "{\"on\":true}", 2),
                observed("lights", "switched on"),
                ExecutionStep.finalAnswer("done", 3)), registry(tool("lights", ToolHistoryPolicy.CALL_AND_RESULT)));

        assertEquals(List.of(new ToolExchange("lights", "{\"on\":false}", "switched off"),
                new ToolExchange("lights", "{\"on\":true}", "switched on")), calls);
    }

    @Test
    void parallelCallsArePairedByToolWhateverOrderTheirResultsCameIn() {
        List<ToolExchange> calls = ToolCallHistory.record(List.of(
                ExecutionStep.toolCall("weather", "{}", 1),
                ExecutionStep.toolCall("lights", "{}", 1),
                observed("lights", "off"),
                observed("weather", "sunny")),
                registry(tool("weather", ToolHistoryPolicy.CALL_AND_RESULT), tool("lights", ToolHistoryPolicy.CALL_AND_RESULT)));

        assertEquals("sunny", calls.get(0).result());
        assertEquals("off", calls.get(1).result());
    }

    @Test
    void aPseudoCallOfAStrategyIsNotATool() {
        // PlanApproval records the plan put up for approval as a tool call; no registry knows that "tool"
        List<ToolExchange> calls = ToolCallHistory.record(List.of(
                ExecutionStep.toolCall("approve_plan", "{\"steps\":[]}", 1),
                ExecutionStep.observation("Plan approved.", 1)), registry());

        assertTrue(calls.isEmpty());
    }

    @Test
    void theToolDecidesWhatOfACallRemains() {
        List<ToolExchange> calls = ToolCallHistory.record(List.of(
                ExecutionStep.toolCall("fetch", "{\"url\":\"x\"}", 1), observed("fetch", "<a whole page>"),
                ExecutionStep.toolCall("vault", "{\"secret\":\"s3cr3t\"}", 1), observed("vault", "opened")),
                registry(tool("fetch", ToolHistoryPolicy.CALL_ONLY), tool("vault", ToolHistoryPolicy.NONE)));

        assertEquals(List.of(new ToolExchange("fetch", "{\"url\":\"x\"}", "")), calls, "no page, no secret");
    }

    @Test
    void aCallThatNeverGotAResultKeepsTheCall() {
        List<ToolExchange> calls = ToolCallHistory.record(List.of(ExecutionStep.toolCall("lights", "{}", 1)),
                registry(tool("lights", ToolHistoryPolicy.CALL_AND_RESULT)));

        assertEquals(List.of(new ToolExchange("lights", "{}", "")), calls);
    }

    /** Records what is written into working memory. */
    private static final class Written implements MemoryManager {
        final List<String> entries = new ArrayList<>();

        @Override public void appendToWorkingMemory(String role, String content) { entries.add(role + "|" + content); }
        @Override public void appendToWorkingMemory(String role, String content, ToolCallMetadata metadata) {
            entries.add(role + "|" + content + "|" + metadata.callId() + "|" + metadata.toolName());
        }
        @Override public List<io.ara.core.memory.MemoryEntry> workingMemory() { return List.of(); }
        @Override public void clearWorkingMemory() { }
    }

    private static final ConversationTurn TURN = new ConversationTurn("switch it off", "it is off", List.of(),
            List.of(new ToolExchange("lights", "{\"on\":false}", "switched off")));

    @Test
    void forANativeClientTheCallIsReplayedAsACallAndAResultWithTheSameId() {
        Written memory = new Written();

        ToolCallHistory.replay(memory, TURN, 3, true, true);

        assertEquals(List.of("assistant_tool_call|{\"on\":false}|h3-0|lights", "tool|switched off|h3-0|lights"),
                memory.entries);
    }

    @Test
    void forATextClientTheCallIsReplayedInTheTextProtocol() {
        Written memory = new Written();

        ToolCallHistory.replay(memory, TURN, 0, true, false);

        assertEquals(List.of("assistant|{\"tool_id\":\"lights\",\"arguments\":{\"on\":false}}",
                "user|Observation: switched off"), memory.entries);
    }

    @Test
    void onlyTheLatestTurnKeepsItsResultAndALongResultIsCut() {
        assertEquals(ToolCallHistory.OMITTED, ToolCallHistory.shown("switched off", false));
        assertEquals(ToolCallHistory.OMITTED, ToolCallHistory.shown("", true), "a result the tool did not keep");
        String cut = ToolCallHistory.shown("x".repeat(ToolCallHistory.MAX_RESULT_CHARS + 50), true);
        assertEquals(ToolCallHistory.MAX_RESULT_CHARS + 1, cut.length());
        assertTrue(cut.endsWith("…"));
    }

    @Test
    void argumentsThatAreNotJsonAreReplayedAsText() {
        Written memory = new Written();
        ConversationTurn turn = new ConversationTurn("q", "a", List.of(), List.of(new ToolExchange("t", "not json", "r")));

        ToolCallHistory.replay(memory, turn, 0, true, false);

        assertEquals("assistant|{\"tool_id\":\"t\",\"arguments\":\"not json\"}", memory.entries.get(0));
    }
}
