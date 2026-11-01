package io.ara.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A persisted turn is read back by a plain {@code ObjectMapper} (the cluster's checkpoint store does exactly that, and
 * skips with a warning any turn it cannot read). So a turn written before tool calls were kept must still load, or a
 * session's whole history would disappear the day the field is added.
 */
class ConversationTurnJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aTurnStoredBeforeToolCallsWereKeptStillLoads() throws Exception {
        String stored = "{\"userInput\":\"hi\",\"assistantOutput\":\"hello\",\"media\":[]}";

        ConversationTurn turn = MAPPER.readValue(stored, ConversationTurn.class);

        assertEquals("hi", turn.userInput());
        assertEquals(List.of(), turn.toolCalls());
    }

    @Test
    void aTurnStoredBeforeMediaExistedEitherStillLoads() throws Exception {
        ConversationTurn turn = MAPPER.readValue("{\"userInput\":\"hi\",\"assistantOutput\":\"hello\"}",
                ConversationTurn.class);

        assertEquals(List.of(), turn.media());
        assertEquals(List.of(), turn.toolCalls());
    }

    @Test
    void aTurnWithToolCallsSurvivesAWriteAndARead() throws Exception {
        ConversationTurn turn = new ConversationTurn("switch it off", "it is off", List.of(),
                List.of(new ToolExchange("lights", "{\"on\":false}", "switched off")));

        ConversationTurn read = MAPPER.readValue(MAPPER.writeValueAsString(turn), ConversationTurn.class);

        assertEquals(turn, read);
    }
}
