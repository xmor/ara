package io.ara.runtime.strategy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The protocol suffixes appended to every system prompt must not name a model's control tokens.
 *
 * <p>They used to forbid {@code <|channel|>} and {@code <|constrain|>}. gpt-oss needs those tokens to
 * open its final answer, and with the prohibition in the prompt it produced output LM Studio's parser
 * rejects: 0 replies out of 5 with the line, 5 out of 5 without it (gpt-oss-20b, a tool-less agent
 * asked for JSON). See the javadoc of {@code ReactExecutionSupport.REACT_SYSTEM_SUFFIX}.
 */
class ReactSuffixTest {

    @ParameterizedTest
    @ValueSource(strings = {"<|channel|>", "<|constrain|>", "<|message|>", "<|start|>", "<|end|>", "special tokens"})
    void noSuffixNamesControlTokens(String token) {
        assertFalse(ReactExecutionSupport.REACT_SYSTEM_SUFFIX.contains(token), "ReAct suffix names " + token);
        assertFalse(ReSpActStrategy.RESPACT_SYSTEM_SUFFIX.contains(token), "ReSpAct suffix names " + token);
    }
}
