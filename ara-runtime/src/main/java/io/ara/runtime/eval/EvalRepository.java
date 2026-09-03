package io.ara.runtime.eval;

import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvalResult;
import io.ara.core.eval.EvalSuite;

import java.util.List;
import java.util.Optional;

/**
 * Store for eval suites, cases and results (ADR-0070) — the relocation of ADR-019's
 * {@code BenchmarkRepository}, trimmed to what this backlog needs. Same in-memory-default
 * idiom as {@code TraceStore} / {@code PromptCatalogRepository}; durable persistence is an
 * implementation concern, not decided by the ADR.
 */
public interface EvalRepository {

    void saveSuite(EvalSuite suite);

    Optional<EvalSuite> findSuite(String suiteId);

    void saveCase(EvalCase evalCase);

    /** Cases of {@code suiteId}, ordered by {@link EvalCase#seqNo()}. */
    List<EvalCase> findCases(String suiteId);

    /** Cases of {@code suiteId} filtered by hold-out flag — how {@code EvalRunner}'s two methods partition the suite. */
    List<EvalCase> findCases(String suiteId, boolean holdout);

    void saveResult(EvalResult result);

    Optional<EvalResult> findResult(String evalId);

    /** Every result recorded for {@code specHash}, newest first not guaranteed. */
    List<EvalResult> findResultsForSpec(String specHash);

    static EvalRepository inMemory() {
        return new InMemoryEvalRepository();
    }
}
