package io.ara.runtime.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.llm.ToolMediaPlacement;
import io.ara.runtime.factory.FailoverLlmClient;
import io.ara.runtime.stubs.RoutingLlmClient;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A pool promises the inline placement only when every candidate does [V14].
 *
 * <p>The candidate is chosen per call, so the weakest one decides: an image put inside a tool
 * result and handed to an OpenAI-format candidate is lost without an error, and the answer
 * that follows is fluent and about nothing.
 */
class ToolMediaPlacementCompositionTest {

    private record Leaf(ToolMediaPlacement toolMediaPlacement) implements LlmClient {
        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            return new LlmCompletion("ok", 1, 1, "stop", null);
        }

        @Override
        public String providerId() {
            return "leaf-" + toolMediaPlacement;
        }
    }

    private static final LlmClient INLINE = new Leaf(ToolMediaPlacement.INLINE_IN_TOOL_RESULT);
    private static final LlmClient FOLLOWING = new Leaf(ToolMediaPlacement.FOLLOWING_USER_MESSAGE);

    @Test
    void failoverIsInlineOnlyWhenEveryCandidateIs() {
        assertEquals(ToolMediaPlacement.INLINE_IN_TOOL_RESULT,
                new FailoverLlmClient(List.of(INLINE, INLINE)).toolMediaPlacement());
        assertEquals(ToolMediaPlacement.FOLLOWING_USER_MESSAGE,
                new FailoverLlmClient(List.of(INLINE, FOLLOWING)).toolMediaPlacement());
        assertEquals(ToolMediaPlacement.FOLLOWING_USER_MESSAGE,
                new FailoverLlmClient(List.of(FOLLOWING, INLINE)).toolMediaPlacement(), "order must not matter");
    }

    @Test
    void roundRobinIsInlineOnlyWhenEveryCandidateIs() {
        assertEquals(ToolMediaPlacement.INLINE_IN_TOOL_RESULT,
                new RoundRobinLlmClient(List.of(INLINE, INLINE)).toolMediaPlacement());
        assertEquals(ToolMediaPlacement.FOLLOWING_USER_MESSAGE,
                new RoundRobinLlmClient(List.of(INLINE, FOLLOWING)).toolMediaPlacement());
    }

    @Test
    void routingConsidersTheDefaultAndEveryRoute() {
        assertEquals(ToolMediaPlacement.INLINE_IN_TOOL_RESULT, RoutingLlmClient.builder()
                .defaultClient(INLINE).route("a", INLINE).build().toolMediaPlacement());
        assertEquals(ToolMediaPlacement.FOLLOWING_USER_MESSAGE, RoutingLlmClient.builder()
                .defaultClient(INLINE).route("a", FOLLOWING).build().toolMediaPlacement(),
                "one route that is not inline is enough");
        assertEquals(ToolMediaPlacement.FOLLOWING_USER_MESSAGE, RoutingLlmClient.builder()
                .defaultClient(FOLLOWING).route("a", INLINE).build().toolMediaPlacement(),
                "the default counts too");
    }
}
