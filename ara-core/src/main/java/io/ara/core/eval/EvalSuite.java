package io.ara.core.eval;

import java.util.Objects;

/**
 * A named collection of {@link EvalCase}s — ADR-019's {@code BenchmarkSuite}, relocated
 * (ADR-0070). Cases are looked up by {@code suiteId} through the eval repository, not
 * inlined here, so a suite grows (ADR-0071 feeds it) without rewriting this record.
 *
 * @param suiteId     stable id
 * @param name        human-readable name
 * @param description what this suite covers
 */
public record EvalSuite(String suiteId, String name, String description) {

    public EvalSuite {
        Objects.requireNonNull(suiteId, "suiteId must not be null");
        if (suiteId.isBlank()) throw new IllegalArgumentException("suiteId must not be blank");
        name        = Objects.requireNonNullElse(name, suiteId);
        description = Objects.requireNonNullElse(description, "");
    }
}
