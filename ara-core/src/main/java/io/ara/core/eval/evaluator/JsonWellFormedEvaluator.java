package io.ara.core.eval.evaluator;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import io.ara.core.agent.AgentResponse;
import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvaluationResult;
import io.ara.core.eval.EvaluationStrategy;

/**
 * Passes when the agent's output is one well-formed JSON value. Registered under
 * {@code "schema"} in the default registry as an L0 stand-in: a full JSON-Schema
 * conformance check ({@code io.ara.runtime.contract.JsonSchemaValidator}) lives in
 * {@code ara-runtime} and can be registered over this id by a runtime-side caller when a
 * schema is actually available (ADR-019's schema evaluator, deferred by ADR-0070).
 */
public final class JsonWellFormedEvaluator implements EvaluationStrategy {

    private static final JsonFactory JSON = new JsonFactory();

    @Override
    public String strategyId() {
        return "json_well_formed";
    }

    @Override
    public EvaluationResult evaluate(AgentResponse response, EvalCase evalCase) {
        String content = response.content() == null ? "" : response.content().strip();
        if (content.isEmpty()) {
            return EvaluationResult.fail(0.0, "output is empty — not valid JSON");
        }
        try (JsonParser p = JSON.createParser(content)) {
            while (p.nextToken() != null) {
                // walk every token — a trailing garbage token makes this throw
            }
            return EvaluationResult.pass(1.0, "output is well-formed JSON");
        } catch (Exception notJson) {
            return EvaluationResult.fail(0.0, "output is not well-formed JSON: " + notJson.getMessage());
        }
    }
}
