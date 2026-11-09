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

    private static final io.ara.core.media.MediaRef CROP =
            new io.ara.core.media.MediaRef("3f9a1c2e7b4d5a6c8e0f1b2d3c4a5b6c", "image/png", "crop-1.png", 12, null);
    private static final io.ara.core.media.MediaRef PAGE =
            new io.ara.core.media.MediaRef("aa11bb22cc33dd44ee55ff6677889900", "image/png", "page-2.png", 20, null);

    private static ExecutionStep observedWithMedia(String toolId, String text, io.ara.core.media.MediaRef... media) {
        return ExecutionStep.observation(ToolResult.success(toolId, text, List.of(media)), 1);
    }

    @Test
    void theMediaACallReturnedAreKeptWithItsResult() {
        List<ToolExchange> calls = ToolCallHistory.record(List.of(
                ExecutionStep.toolCall("crop_region", "{}", 1),
                observedWithMedia("crop_region", "Crop saved.", CROP)),
                registry(tool("crop_region", ToolHistoryPolicy.CALL_AND_RESULT)));

        assertEquals(List.of(CROP), calls.get(0).media());
        assertEquals("Crop saved.\nAttached: crop-1.png (id 3f9a1c2e7b4d)", calls.get(0).result(),
                "the text names the media, so a later turn that is not shown them still knows they exist");
    }

    @Test
    void eachOfTwoParallelCallsKeepsItsOwnMedia() {
        List<ToolExchange> calls = ToolCallHistory.record(List.of(
                ExecutionStep.toolCall("crop_region", "{\"n\":1}", 1),
                ExecutionStep.toolCall("render_page", "{\"n\":2}", 1),
                observedWithMedia("render_page", "Rendered.", PAGE),
                observedWithMedia("crop_region", "Crop saved.", CROP)),
                registry(tool("crop_region", ToolHistoryPolicy.CALL_AND_RESULT),
                        tool("render_page", ToolHistoryPolicy.CALL_AND_RESULT)));

        assertEquals(List.of(CROP), calls.get(0).media());
        assertEquals(List.of(PAGE), calls.get(1).media());
    }

    @Test
    void aToolThatKeepsOnlyItsCallKeepsNoMediaEither() {
        // The media are part of the result: a tool that chose to leave its result out of the history
        // leaves them out with it.
        List<ToolExchange> calls = ToolCallHistory.record(List.of(
                ExecutionStep.toolCall("crop_region", "{}", 1),
                observedWithMedia("crop_region", "Crop saved.", CROP)),
                registry(tool("crop_region", ToolHistoryPolicy.CALL_ONLY)));

        assertEquals(1, calls.size());
        assertEquals("", calls.get(0).result());
        assertEquals(List.of(), calls.get(0).media());
    }

    @Test
    void aToolThatLeavesItsCallOutLeavesTheWholeExchangeOut() {
        List<ToolExchange> calls = ToolCallHistory.record(List.of(
                ExecutionStep.toolCall("crop_region", "{}", 1),
                observedWithMedia("crop_region", "Crop saved.", CROP)),
                registry(tool("crop_region", ToolHistoryPolicy.NONE)));

        assertEquals(List.of(), calls);
    }

    @Test
    void aCallWithoutMediaHasNone() {
        List<ToolExchange> calls = ToolCallHistory.record(List.of(
                ExecutionStep.toolCall("lights", "{}", 1), observed("lights", "switched off")),
                registry(tool("lights", ToolHistoryPolicy.CALL_AND_RESULT)));

        assertEquals(List.of(), calls.get(0).media());
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

        @Override public void appendToWorkingMemory(io.ara.core.memory.MemoryEntry entry) {
            if (entry.metadata() instanceof ToolCallMetadata metadata) {
                entries.add(entry.role() + "|" + entry.content() + "|" + metadata.callId() + "|" + metadata.toolName());
            } else {
                entries.add(entry.role() + "|" + entry.content());
            }
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
