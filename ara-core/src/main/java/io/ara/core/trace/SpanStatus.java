package io.ara.core.trace;

import java.util.Objects;

/**
 * How a {@link TraceSpan} ended — the same three outcomes as an ADR-052 D1 journal entry
 * ({@code Completed} / {@code Failed} / {@code Suspended}), so a span derived from the
 * journal maps its outcome across without a translation table, and a span derived from an
 * {@code ExecutionStep} uses the same vocabulary.
 */
public sealed interface SpanStatus
        permits SpanStatus.Completed, SpanStatus.Failed, SpanStatus.Suspended {

    /** The unit of work returned normally. */
    record Completed() implements SpanStatus {}

    /** It threw / errored. {@code reason} is the failure message, never {@code null}. */
    record Failed(String reason) implements SpanStatus {
        public Failed {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }

    /** It is waiting on something outside the run (an approval, an external event). */
    record Suspended(String reason) implements SpanStatus {
        public Suspended {
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
