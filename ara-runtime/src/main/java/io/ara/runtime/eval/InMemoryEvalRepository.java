package io.ara.runtime.eval;

import io.ara.core.eval.EvalCase;
import io.ara.core.eval.EvalResult;
import io.ara.core.eval.EvalSuite;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-local {@link EvalRepository} — {@link ConcurrentHashMap}s keyed by id. A
 * reference implementation for tests and single-process use; a durable backend implements
 * the same interface.
 */
public final class InMemoryEvalRepository implements EvalRepository {

    private final ConcurrentHashMap<String, EvalSuite>  suites  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EvalCase>   cases   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, EvalResult> results = new ConcurrentHashMap<>();

    @Override
    public void saveSuite(EvalSuite suite) {
        Objects.requireNonNull(suite, "suite must not be null");
        suites.put(suite.suiteId(), suite);
    }

    @Override
    public Optional<EvalSuite> findSuite(String suiteId) {
        return Optional.ofNullable(suites.get(Objects.requireNonNull(suiteId, "suiteId must not be null")));
    }

    @Override
    public void saveCase(EvalCase evalCase) {
        Objects.requireNonNull(evalCase, "evalCase must not be null");
        cases.put(evalCase.caseId(), evalCase);
    }

    @Override
    public List<EvalCase> findCases(String suiteId) {
        Objects.requireNonNull(suiteId, "suiteId must not be null");
        return cases.values().stream()
                .filter(c -> c.suiteId().equals(suiteId))
                .sorted(Comparator.comparingInt(EvalCase::seqNo))
                .toList();
    }

    @Override
    public List<EvalCase> findCases(String suiteId, boolean holdout) {
        return findCases(suiteId).stream().filter(c -> c.holdout() == holdout).toList();
    }

    @Override
    public void saveResult(EvalResult result) {
        Objects.requireNonNull(result, "result must not be null");
        results.put(result.evalId(), result);
    }

    @Override
    public Optional<EvalResult> findResult(String evalId) {
        return Optional.ofNullable(results.get(Objects.requireNonNull(evalId, "evalId must not be null")));
    }

    @Override
    public List<EvalResult> findResultsForSpec(String specHash) {
        Objects.requireNonNull(specHash, "specHash must not be null");
        return results.values().stream().filter(r -> r.specHash().equals(specHash)).toList();
    }
}
