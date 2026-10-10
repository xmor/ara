package io.ara.runtime.strategy;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Test double for {@code plan_execute}: tells the four phases of the strategy apart by the
 * instruction in the system message and answers each from its own queue of replies, so a test
 * can say "the first plan is unreadable, the second is fine, the step succeeds" without caring
 * about call order. It records every call, with its messages and context, for assertions.
 *
 * <p>The marker phrases below are the strategy's own prompt wording; if a prompt changes, this is
 * the one place to follow it. A queue's last reply repeats when the queue runs dry, so a test
 * only scripts what differs from "keep answering the same thing".
 *
 * <p>Not thread-safe; for single-threaded strategy tests.
 */
final class PlanPhaseLlmClient implements LlmClient {

    record Call(Phase phase, List<LlmMessage> messages, LlmCallContext context) { }

    enum Phase { PLAN, STEP, REPLAN, SYNTHESIS }

    private final List<Call> calls = new ArrayList<>();
    private final Deque<String> planReplies = new ArrayDeque<>();
    private final Deque<String> stepReplies = new ArrayDeque<>();
    private final Deque<String> replanReplies = new ArrayDeque<>();
    private final Deque<LlmCompletion> stepCompletions = new ArrayDeque<>();
    private String synthesisReply = "Final answer.";
    private boolean nativeStructuredOutput;

    PlanPhaseLlmClient planReplies(String... replies) {
        planReplies.addAll(List.of(replies));
        return this;
    }

    PlanPhaseLlmClient stepReplies(String... replies) {
        stepReplies.addAll(List.of(replies));
        return this;
    }

    /**
     * Step replies given as whole completions (tool calls, native call ids, several calls in one
     * turn), used before any {@link #stepReplies} text. Like the text queues, the last one repeats.
     */
    PlanPhaseLlmClient stepCompletions(LlmCompletion... completions) {
        stepCompletions.addAll(List.of(completions));
        return this;
    }

    PlanPhaseLlmClient replanReplies(String... replies) {
        replanReplies.addAll(List.of(replies));
        return this;
    }

    PlanPhaseLlmClient nativeStructuredOutput(boolean supported) {
        this.nativeStructuredOutput = supported;
        return this;
    }

    List<Call> calls() {
        return calls;
    }

    List<Call> callsIn(Phase phase) {
        return calls.stream().filter(call -> call.phase() == phase).toList();
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
        Phase phase = phaseOf(messages.get(0).content());
        calls.add(new Call(phase, List.copyOf(messages), context));
        if (phase == Phase.STEP && !stepCompletions.isEmpty()) {
            return stepCompletions.size() > 1 ? stepCompletions.poll() : stepCompletions.peek();
        }
        String text = switch (phase) {
            case PLAN -> next(planReplies, "1. Only step");
            case STEP -> next(stepReplies, "STEP_DONE");
            case REPLAN -> next(replanReplies, "1. Only step");
            case SYNTHESIS -> synthesisReply;
        };
        return new LlmCompletion(text, 5, 5, "stop", null);
    }

    private static Phase phaseOf(String systemPrompt) {
        if (systemPrompt.contains("Produce a short numbered execution plan")) return Phase.PLAN;
        if (systemPrompt.contains("Produce a revised plan")) return Phase.REPLAN;
        if (systemPrompt.contains("You are executing one step of a plan")) return Phase.STEP;
        return Phase.SYNTHESIS;
    }

    private static String next(Deque<String> queue, String whenEmpty) {
        if (queue.isEmpty()) return whenEmpty;
        return queue.size() > 1 ? queue.poll() : queue.peek();
    }

    @Override
    public String providerId() {
        return "plan-phase-test";
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return nativeStructuredOutput;
    }
}
