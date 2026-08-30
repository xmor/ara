package io.ara.runtime.stubs;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test/stub {@link LlmClient} that replays a script <b>per agent</b> rather than per call.
 *
 * <p>{@link ScriptedLlmClient} holds one queue and pops from it on every call. That is exact
 * for a single agent stepping through a ReAct loop, and indeterminate as soon as more than one
 * agent is in flight: with two nodes running concurrently, which of them gets the head of the
 * queue is decided by thread scheduling, so the same test passes or fails run to run.
 *
 * <p>This client keys the script on {@link LlmCallContext#agentId()} instead, so each node
 * consumes its own sequence in its own order and concurrency stops mattering:
 *
 * <pre>{@code
 * LlmClient stub = AssociativeLlmClient.script()
 *         .forAgent("planner").thenFinalAnswer("1. search  2. summarise")
 *         .forAgent("worker-a").thenToolCall("search", "{\"q\":\"ara\"}")
 *                              .thenFinalAnswer("found 3 results")
 *         .forAgent("worker-b").thenFinalAnswer("nothing to add")
 *         .build();
 * }</pre>
 *
 * <p><b>An unknown agent id fails the call</b> instead of returning a filler answer. A stub
 * whose whole purpose is to tell callers apart would defeat itself by silently answering one it
 * cannot identify — including the case where {@code agentId} is null because the context was not
 * built from an {@code AgentConfig}. Use {@link Builder#fallback(LlmCompletion)} to opt out
 * deliberately.
 *
 * <p>Thread-safe: each agent's queue is drained atomically, so N nodes may call concurrently.
 */
public final class AssociativeLlmClient implements LlmClient {

    private final Map<String, Queue<LlmCompletion>> scripts;
    private final Map<String, AtomicInteger>        calls = new ConcurrentHashMap<>();
    private final LlmCompletion                     fallback;   // nullable — null means "fail fast"

    private AssociativeLlmClient(Map<String, List<LlmCompletion>> steps, LlmCompletion fallback) {
        Map<String, Queue<LlmCompletion>> m = new ConcurrentHashMap<>();
        steps.forEach((agentId, list) -> m.put(agentId, new ConcurrentLinkedQueue<>(list)));
        this.scripts  = Map.copyOf(m);
        this.fallback = fallback;
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
        String agentId = context != null ? context.agentId() : null;

        Queue<LlmCompletion> queue = agentId != null ? scripts.get(agentId) : null;
        if (queue == null) {
            if (fallback != null) return fallback;
            throw new IllegalStateException(
                    "AssociativeLlmClient: no script for agentId=" + agentId
                            + " (known: " + scripts.keySet() + "). "
                            + "Declare it with forAgent(...), or set an explicit fallback(...).");
        }

        calls.computeIfAbsent(agentId, k -> new AtomicInteger()).incrementAndGet();

        LlmCompletion next = queue.poll();
        if (next != null) return next;
        if (fallback != null) return fallback;
        throw new IllegalStateException(
                "AssociativeLlmClient: script for agentId=" + agentId + " is exhausted after "
                        + calls.get(agentId).get() + " call(s). Add a step, or set a fallback(...).");
    }

    @Override
    public String providerId() {
        return "associative-stub";
    }

    /** How many times each agent called the client, for assertions on fan-out degree. */
    public Map<String, Integer> callsPerAgent() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        calls.forEach((agentId, n) -> snapshot.put(agentId, n.get()));
        return Map.copyOf(snapshot);
    }

    /** How many times {@code agentId} called the client; {@code 0} if it never did. */
    public int callsFor(String agentId) {
        AtomicInteger n = calls.get(agentId);
        return n != null ? n.get() : 0;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /** Returns a new builder for composing the per-agent scripts. */
    public static Builder script() {
        return new Builder();
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Fluent builder. {@link #forAgent(String)} opens a section: every {@code then*} call after
     * it appends to that agent's script, until the next {@code forAgent(...)}.
     */
    public static final class Builder {

        private final Map<String, List<LlmCompletion>> steps = new LinkedHashMap<>();
        private List<LlmCompletion>                    current;
        private LlmCompletion                          fallback;   // null = fail fast

        private Builder() {}

        /**
         * Opens (or reopens, appending) the script section for {@code agentId} — the value of
         * {@code AgentConfig.agentId()} of the node that will be executed.
         */
        public Builder forAgent(String agentId) {
            if (agentId == null || agentId.isBlank()) {
                throw new IllegalArgumentException("agentId must not be blank");
            }
            current = steps.computeIfAbsent(agentId, k -> new ArrayList<>());
            return this;
        }

        /**
         * Adds a step that instructs the ReAct loop to invoke a tool.
         *
         * @param toolId       the tool identifier (e.g. {@code "delegate_task"})
         * @param argumentJson JSON arguments in ARA format
         */
        public Builder thenToolCall(String toolId, String argumentJson) {
            String toolCallJson = """
                    {"tool_id":"%s","arguments":%s}""".formatted(toolId, argumentJson);
            return then(new LlmCompletion(
                    "I need to call tool: " + toolId, 10, 10, "tool_calls", toolCallJson));
        }

        /** Adds a step that terminates the ReAct loop with a final answer. */
        public Builder thenFinalAnswer(String answer) {
            return then(finalAnswerCompletion(answer));
        }

        /** Adds a raw {@link LlmCompletion} step for full control. */
        public Builder then(LlmCompletion completion) {
            if (current == null) {
                throw new IllegalStateException("call forAgent(...) before adding steps");
            }
            current.add(completion);
            return this;
        }

        /**
         * Sets the completion returned for an unknown agent id, or once an agent's script is
         * exhausted. Unset, both cases throw — which is the default on purpose: see the class
         * javadoc.
         */
        public Builder fallback(LlmCompletion completion) {
            this.fallback = completion;
            return this;
        }

        public AssociativeLlmClient build() {
            if (steps.isEmpty()) {
                throw new IllegalStateException("no agent scripts declared — call forAgent(...)");
            }
            return new AssociativeLlmClient(steps, fallback);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static LlmCompletion finalAnswerCompletion(String answer) {
        return new LlmCompletion("Action: FINAL_ANSWER\nAnswer: " + answer, 10, 20, "stop", null);
    }
}
