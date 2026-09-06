package io.ara.core.auth;

import java.util.Objects;

/**
 * Everything an {@link AbacPolicy} needs to reach a decision (ADR-033 Fase 2b, S8 —
 * `docs/adr/ADR-033-implementation-plan.md` §2b.1, `ara-private`).
 *
 * @param subject         who is calling
 * @param resource        what is being called
 * @param action          what they are trying to do
 * @param environment     when/in what context
 * @param executionContext the full {@link ExecutionContext} for this call (ADR-033 Fase 5),
 *                        when one is available — {@code null} for a caller that never
 *                        populated it (M2M without OBO, or a call outside the bus); a
 *                        policy that reads {@link ExecutionContext#subjectId()} must handle
 *                        that {@code null} case itself, same as reading it anywhere else
 */
public record PolicyEvaluationContext(
        SubjectAttributes     subject,
        ResourceAttributes    resource,
        ActionAttributes      action,
        EnvironmentAttributes environment,
        ExecutionContext      executionContext
) {

    public PolicyEvaluationContext {
        Objects.requireNonNull(subject,     "subject must not be null");
        Objects.requireNonNull(resource,    "resource must not be null");
        Objects.requireNonNull(action,      "action must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        // executionContext is intentionally nullable — see the @param note above.
    }
}
