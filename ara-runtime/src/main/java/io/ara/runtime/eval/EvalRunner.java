package io.ara.runtime.eval;

import io.ara.core.eval.EvalResult;

/**
 * Runs an eval suite against an agent spec, N≥3 times per case, and aggregates into an
 * {@link EvalResult} with per-case {@code CaseStats} and an ADR-0059-cascade {@code Verdict}
 * (ADR-0070 D3/D4).
 *
 * <p><b>Two distinct methods, not a boolean flag</b> (ADR-0070 D3): a proposal cycle
 * (ADR-0081) can only call {@link #run}, which never touches hold-out cases; only the
 * promotion gate (ADR-0083) calls {@link #runHoldoutOnly}. A cycle trying to read the
 * hold-out during iteration is a compile error, not a silent runtime slip.
 *
 * <p><b>Deviation from ADR-0070's signature</b>: the spec is passed as a {@code specHash}
 * string, not an {@code AgentSpec} object — {@code AgentSpec} lives in a module
 * ({@code ara-meta}) that {@code ara-runtime} does not depend on, and {@link EvalResult}
 * carries the hash anyway. A concrete runner resolves the spec from the hash out of band.
 *
 * <p>No implementation ships in this increment — this is the port. ADR-019's
 * {@code EnvironmentProvisioner} and eight built-in evaluators are relocated with the
 * runner when generated code needs evaluating.
 */
public interface EvalRunner {

    /**
     * Runs the non-hold-out cases of {@code suiteId} against {@code specHash}, {@code
     * nRunsPerCase} times each. Used by ADR-0081 during proposal iteration.
     *
     * @param nRunsPerCase must be {@code >= 3} (ADR-0070 D2)
     */
    EvalResult run(String specHash, String suiteId, int nRunsPerCase);

    /**
     * Runs <em>only</em> the hold-out cases of {@code suiteId}. Used solely by ADR-0083 at
     * the promotion gate.
     *
     * @param nRunsPerCase must be {@code >= 3}
     */
    EvalResult runHoldoutOnly(String specHash, String suiteId, int nRunsPerCase);
}
