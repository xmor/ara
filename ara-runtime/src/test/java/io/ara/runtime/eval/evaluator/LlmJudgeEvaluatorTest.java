package io.ara.runtime.eval.evaluator;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvaluationResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real {@code "judge"} strategy (ADR-0059 D2) over a fake {@link AraAgent} — same
 * fixture idiom as {@code DefaultEvalRunnerTest}. Exercises parsing, not a live LLM.
 */
class LlmJudgeEvaluatorTest {

    private static final AgentId JUDGE_ID = AgentId.of("judge");

    private static AraAgent judgeReplying(Function<AgentTask, String> reply) {
        return new AraAgent() {
            @Override public AgentId agentId() { return JUDGE_ID; }
            @Override public AgentConfig config() { return null; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.success(task.taskId(), JUDGE_ID, reply.apply(task),
                        1, 0, 0, Duration.ofMillis(1), List.of());
            }
            @Override public void terminate() {}
        };
    }

    private static EvalCase judgeCase() {
        return new EvalCase("c1", "suite-1", false, List.of(), "curated",
                "summarize the diff", Map.of(), "judge", Map.of(), 0);
    }

    private static AgentResponse candidateResponse(String content) {
        return AgentResponse.success("t1", AgentId.of("candidate"), content, 1, 0, 0, Duration.ofMillis(1), List.of());
    }

    @Test
    void parsesTheScoreLineAndKeepsTheRestAsRationale() {
        LlmJudgeEvaluator evaluator = new LlmJudgeEvaluator(judgeReplying(task ->
                "The summary covers every change correctly.\nSCORE: 0.9\n"));

        EvaluationResult result = evaluator.evaluate(candidateResponse("a good summary"), judgeCase());

        assertEquals(0.9, result.score(), 1e-9);
        assertTrue(result.passed());
        assertTrue(result.rationale().contains("covers every change"));
        assertFalse(result.rationale().contains("SCORE"));
    }

    @Test
    void aScoreBelowHalfFailsButNeverVetoes() {
        // isBlocking() in DefaultEvalRunner treats every "judge"-strategy case as advisory
        // regardless of passed() — this evaluator does not need to know that to stay honest.
        LlmJudgeEvaluator evaluator = new LlmJudgeEvaluator(judgeReplying(task -> "Misses key points.\nSCORE: 0.2\n"));

        EvaluationResult result = evaluator.evaluate(candidateResponse("a poor summary"), judgeCase());

        assertEquals(0.2, result.score(), 1e-9);
        assertFalse(result.passed());
    }

    @Test
    void anUnparseableReplyDegradesToTheAdvisoryNeutralScoreRatherThanThrowing() {
        LlmJudgeEvaluator evaluator = new LlmJudgeEvaluator(judgeReplying(task -> "I cannot evaluate this."));

        EvaluationResult result = evaluator.evaluate(candidateResponse("anything"), judgeCase());

        assertEquals(LlmJudgeEvaluator.UNPARSEABLE_SCORE, result.score(), 1e-9);
    }

    @Test
    void aCustomRubricFromEvaluationConfigReachesTheJudgePrompt() {
        String[] promptSeen = new String[1];
        LlmJudgeEvaluator evaluator = new LlmJudgeEvaluator(judgeReplying(task -> {
            promptSeen[0] = task.input();
            return "SCORE: 1.0";
        }));
        EvalCase withRubric = new EvalCase("c2", "suite-1", false, List.of(), "curated",
                "summarize the diff", Map.of(), "judge", Map.of("rubric", "must mention the file count"), 0);

        evaluator.evaluate(candidateResponse("touches 3 files"), withRubric);

        assertTrue(promptSeen[0].contains("must mention the file count"));
    }

    @Test
    void strategyIdIsJudgeSoItDropsInOverThePlaceholder() {
        assertEquals("judge", new LlmJudgeEvaluator(judgeReplying(t -> "SCORE: 1.0")).strategyId());
    }
}
