package io.ara.runtime.strategy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolCallParserTest {

    private static final String TOOL_ID_JSON = "{\"tool_id\":\"noop\",\"arguments\":{\"x\":1}}";
    private static final String NAME_JSON = "{\"name\":\"functions.get_current_time\",\"arguments\":{\"tz\":\"UTC\"}}";
    private static final String NO_ARGS_JSON = "{\"tool_id\":\"noop\"}";
    private static final String SCHEMA_JSON = "{\"name\":\"noop\",\"arguments\":{\"type\":\"object\",\"properties\":{\"x\":{}}}}";
    private static final String UNPARSEABLE_JSON = "{not json}";

    @Test
    void extractsToolIdAndArgumentsInOneParse() {
        ToolCallParser.ToolCallRequest parsed = ToolCallParser.extractNameAndArgs(TOOL_ID_JSON);
        assertEquals("noop", parsed.toolId());
        assertEquals("{\"x\":1}", parsed.argumentJson());
    }

    @Test
    void nameFallbackStripsProviderNamespace() {
        ToolCallParser.ToolCallRequest parsed = ToolCallParser.extractNameAndArgs(NAME_JSON);
        assertEquals("get_current_time", parsed.toolId());
        assertEquals("{\"tz\":\"UTC\"}", parsed.argumentJson());
    }

    @Test
    void missingArgumentsBecomesEmptyObject() {
        ToolCallParser.ToolCallRequest parsed = ToolCallParser.extractNameAndArgs(NO_ARGS_JSON);
        assertEquals("noop", parsed.toolId());
        assertEquals("{}", parsed.argumentJson());
    }

    @Test
    void matchesTheTwoSeparateAccessors() {
        String[] samples = {TOOL_ID_JSON, NAME_JSON, NO_ARGS_JSON};
        for (String json : samples) {
            ToolCallParser.ToolCallRequest parsed = ToolCallParser.extractNameAndArgs(json);
            assertEquals(ToolCallParser.extractToolName(json), parsed.toolId(), "name for: " + json);
            assertEquals(ToolCallParser.extractToolArgs(json), parsed.argumentJson(), "args for: " + json);
        }
    }

    @Test
    void nullAndUnparseableJsonFallBackToEmptyValues() {
        ToolCallParser.ToolCallRequest nullParsed = ToolCallParser.extractNameAndArgs(null);
        assertEquals("", nullParsed.toolId());
        assertEquals("{}", nullParsed.argumentJson());

        ToolCallParser.ToolCallRequest badParsed = ToolCallParser.extractNameAndArgs(UNPARSEABLE_JSON);
        assertEquals("", badParsed.toolId());
        assertEquals("{}", badParsed.argumentJson());
    }

    @Test
    void schemaShapedArgumentsPassThroughLikeTheSingleAccessorDoes() {
        ToolCallParser.ToolCallRequest parsed = ToolCallParser.extractNameAndArgs(SCHEMA_JSON);
        assertEquals(ToolCallParser.extractToolName(SCHEMA_JSON), parsed.toolId());
        assertEquals(ToolCallParser.extractToolArgs(SCHEMA_JSON), parsed.argumentJson());
    }
}