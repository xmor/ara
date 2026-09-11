package io.ara.runtime.strategy;

import ch.qos.logback.classic.Level;
import io.ara.core.llm.LlmCompletion;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Exercises {@link ReactExecutionSupport#logIterationResult} with both debug enabled and
 * disabled, so the {@code isDebugEnabled} guard introduced to skip the eager JSON parse
 * covers both branches (and, with DEBUG on, proves the body — {@code extractToolName} on a
 * real tool-call payload included — is still reachable and harmless).
 */
class ReactExecutionSupportLoggingTest {

    private static final ch.qos.logback.classic.Logger TARGET =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ReactExecutionSupport.class);

    @Test
    void logsToolCallIterationWhenDebugEnabled() {
        Level previous = TARGET.getLevel();
        try {
            TARGET.setLevel(Level.DEBUG);
            LlmCompletion toolCall = new LlmCompletion("calling noop", 1, 1, "tool_calls",
                    "{\"tool_id\":\"noop\",\"arguments\":{}}");
            assertDoesNotThrow(() ->
                    ReactExecutionSupport.logIterationResult(toolCall, 1, 5, "task-1"));
        } finally {
            TARGET.setLevel(previous);
        }
    }

    @Test
    void logsTextIterationWhenDebugEnabled() {
        Level previous = TARGET.getLevel();
        try {
            TARGET.setLevel(Level.DEBUG);
            LlmCompletion text = new LlmCompletion("plain answer", 1, 1, "stop", null);
            assertDoesNotThrow(() ->
                    ReactExecutionSupport.logIterationResult(text, 2, 5, "task-1"));
        } finally {
            TARGET.setLevel(previous);
        }
    }

    @Test
    void skipsWorkWhenDebugDisabled() {
        Level previous = TARGET.getLevel();
        try {
            TARGET.setLevel(Level.INFO);
            LlmCompletion toolCall = new LlmCompletion("calling noop", 1, 1, "tool_calls",
                    "{\"tool_id\":\"noop\",\"arguments\":{}}");
            assertDoesNotThrow(() ->
                    ReactExecutionSupport.logIterationResult(toolCall, 1, 5, "task-1"));
        } finally {
            TARGET.setLevel(previous);
        }
    }
}