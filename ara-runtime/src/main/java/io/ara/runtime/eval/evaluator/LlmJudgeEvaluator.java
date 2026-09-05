package io.ara.runtime.eval.evaluator;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvaluationResult;
import io.ara.core.eval.EvaluationStrategy;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The real {@code "judge"} strategy (ADR-0059 D2 / ADR-0070 / ADR-0080 D4) that {@code
 * PlaceholderJudgeEvaluator}'s own javadoc calls for: "a runtime-side caller registers a
 * real judge over the {@code judge} id to replace this". Wraps a judge {@link AraAgent} —
 * any agent, real transport or test fake, this class does not care which — and asks it to
 * score the case's output against a rubric.
 *
 * <p><b>Still advisory, never a veto</b> (ADR-0059 D2): {@code DefaultEvalRunner.isBlocking}
 * already treats every {@code "judge"}-registered strategy as non-blocking regardless of
 * which implementation answers — nothing here needs to re-assert that. A response the
 * parser cannot read scores {@link #UNPARSEABLE_SCORE} rather than throwing, so a judge
 * hiccup degrades a verdict to {@code NeedsReview} (ADR-0059 D2's own escalation path),
 * never crashes the run.
 *
 * <p><b>Rubric</b>: {@link EvalCase#evaluationConfig()}'s {@code "rubric"} entry, or a
 * generic "does the output correctly and completely answer the input" instruction when
 * absent. The judge is asked to answer in exactly the shape {@link #SCORE_LINE} parses.
 */
public final class LlmJudgeEvaluator implements EvaluationStrategy {

    /** Neutral-low score for a judge reply this evaluator could not parse — advisory, not a veto. */
    public static final double UNPARSEABLE_SCORE = 0.5;

    private static final String DEFAULT_RUBRIC =
            "Does the output correctly and completely answer the input? Consider correctness first, "
                    + "then completeness and clarity.";

    private static final Pattern SCORE_LINE =
            Pattern.compile("(?im)^\\s*SCORE\\s*:\\s*(\\d*\\.?\\d+)\\s*$");

    private final AraAgent judge;

    public LlmJudgeEvaluator(AraAgent judge) {
        this.judge = Objects.requireNonNull(judge, "judge must not be null");
    }

    @Override
    public String strategyId() {
        return "judge";
    }

    @Override
    public EvaluationResult evaluate(AgentResponse response, EvalCase evalCase) {
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(evalCase, "evalCase must not be null");

        String rubric = evalCase.evaluationConfig().getOrDefault("rubric", DEFAULT_RUBRIC);
        AgentResponse verdict = judge.execute(AgentTask.of(prompt(rubric, evalCase.input(), response.content())));

        if (!verdict.isSuccess()) {
            return EvaluationResult.error("judge agent did not complete: " + verdict.failureReason());
        }
        Double score = parseScore(verdict.content());
        if (score == null) {
            return new EvaluationResult(true, UNPARSEABLE_SCORE,
                    "judge reply did not contain a parseable 'SCORE: <0..1>' line — advisory neutral score",
                    java.util.Map.of("judge_raw_reply", verdict.content()));
        }
        String rationale = stripScoreLine(verdict.content());
        return new EvaluationResult(score >= 0.5, score, rationale, java.util.Map.of());
    }

    private static String prompt(String rubric, String taskInput, String candidateOutput) {
        return """
                You are an evaluation judge. Score the CANDIDATE OUTPUT for the TASK against \
                the RUBRIC on a continuous scale from 0.0 (fails completely) to 1.0 (fully \
                satisfies the rubric).

                TASK:
                %s

                CANDIDATE OUTPUT:
                %s

                RUBRIC:
                %s

                Reply with a brief rationale, then a final line in exactly this form:
                SCORE: <a number between 0.0 and 1.0>
                """.formatted(taskInput, candidateOutput, rubric);
    }

    private static Double parseScore(String judgeReply) {
        if (judgeReply == null) {
            return null;
        }
        Matcher m = SCORE_LINE.matcher(judgeReply);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        if (last == null) {
            return null;
        }
        try {
            double v = Double.parseDouble(last);
            return Math.max(0.0, Math.min(1.0, v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stripScoreLine(String judgeReply) {
        return SCORE_LINE.matcher(judgeReply).replaceAll("").strip();
    }
}
