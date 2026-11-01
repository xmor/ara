package io.ara.core.agent;

import io.ara.core.memory.MemoryConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTurnToolCallsTest {

    @Test
    void aTurnBuiltTheOldWayHasNoToolCalls() {
        assertTrue(new ConversationTurn("hi", "hello").toolCalls().isEmpty());
        assertTrue(new ConversationTurn("hi", "hello", List.of()).toolCalls().isEmpty());
    }

    @Test
    void aTurnStoredBeforeToolCallsWereKeptReadsAsHavingNone() {
        // what a JSON deserialiser hands the canonical constructor for a turn persisted without the field
        ConversationTurn old = new ConversationTurn("hi", "hello", null, null);

        assertEquals(List.of(), old.toolCalls());
        assertEquals(List.of(), old.media());
    }

    @Test
    void theToolCallsOfATurnAreKeptInOrderAndCannotBeChangedAfterwards() {
        List<ToolExchange> calls = new java.util.ArrayList<>(List.of(
                new ToolExchange("lights", "{\"on\":false}", "done"), new ToolExchange("lights", "{\"on\":true}", "done")));
        ConversationTurn turn = new ConversationTurn("toggle", "ok", List.of(), calls);
        calls.clear();

        assertEquals(2, turn.toolCalls().size());
        assertEquals("{\"on\":false}", turn.toolCalls().get(0).arguments());
        assertThrows(UnsupportedOperationException.class, () -> turn.toolCalls().add(null));
    }

    @Test
    void anExchangeHasATool_andMissingArgumentsOrResultAreNormalised() {
        ToolExchange bare = new ToolExchange("lookup", null, null);

        assertEquals("{}", bare.arguments());
        assertEquals("", bare.result());
        assertThrows(IllegalArgumentException.class, () -> new ToolExchange(" ", "{}", "x"));
        assertThrows(NullPointerException.class, () -> new ToolExchange(null, "{}", "x"));
    }

    @Test
    void anAgentThatDoesNotKeepToolCallsHashesAsBeforeTheOptionExisted() {
        AgentConfig untouched = AgentConfig.defaults().agentType("a").maxConversationTurns(4).build();
        AgentConfig saidOff = AgentConfig.defaults().agentType("a").maxConversationTurns(4).replayToolCalls(false).build();
        AgentConfig on = AgentConfig.defaults().agentType("a").maxConversationTurns(4).replayToolCalls(true).build();

        assertEquals(io.ara.core.spec.SpecLineage.hash(untouched), io.ara.core.spec.SpecLineage.hash(saidOff));
        assertEquals(null, untouched.memory().replayToolCalls(), "off is null, so the hash leaves it out");
        assertFalse(io.ara.core.spec.SpecLineage.hash(untouched).equals(io.ara.core.spec.SpecLineage.hash(on)),
                "keeping tool calls changes the behaviour, so it changes the hash");
        assertEquals(null, new MemoryConfig(0, "drop_middle", 4, 2, null, null, Boolean.FALSE).replayToolCalls());
    }

    @Test
    void keepingToolCallsIsOffByDefaultAndSurvivesToBuilder() {
        assertFalse(MemoryConfig.defaults().keepsToolCalls());
        assertFalse(AgentConfig.defaults().build().replayToolCalls());
        assertFalse(new MemoryConfig(0, "drop_middle", 4, 2, null, null).keepsToolCalls(), "the 6-arg shape");

        AgentConfig on = AgentConfig.defaults().maxConversationTurns(4).replayToolCalls(true).build();
        assertTrue(on.replayToolCalls());
        assertTrue(on.toBuilder().build().replayToolCalls());
    }
}
