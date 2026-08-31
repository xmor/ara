package io.ara.runtime.workflow;

import java.util.List;
import java.util.Objects;

/**
 * The journal, in two phases per ADR-052 D1: a {@link Started} entry written the moment
 * a node occurrence is submitted, a {@link Finished} entry written when it completes.
 *
 * <p>One phase would be simpler, and Step 1 of this port had exactly that: an entry
 * written only on completion. It has one hole — a node in flight when the process
 * crashes leaves no trace, so resume cannot tell "this never started" apart from
 * "this started and we don't know how it ended". For an idempotent node that's harmless;
 * for one with an external effect (an email sent, a charge made), silently re-running it
 * is a bug. Splitting the write in two closes the hole: a {@code Started} entry with no
 * matching {@code Finished} on resume is exactly that node's declared {@link
 * WorkflowNode#onUncertainResume()} to react to, instead of the scheduler guessing.
 */
public sealed interface JournalEntry permits JournalEntry.Started, JournalEntry.Finished {

    String nodeId();

    int occurrence();

    String input();

    record Started(String nodeId, int occurrence, String input) implements JournalEntry {
        public Started {
            Objects.requireNonNull(nodeId, "nodeId must not be null");
            Objects.requireNonNull(input, "input must not be null");
        }

        @Override
        public String toString() {
            return nodeId + "#" + occurrence + " (started)";
        }
    }

    record Finished(String nodeId, int occurrence, String input, NodeOutcome outcome) implements JournalEntry {
        public Finished {
            Objects.requireNonNull(nodeId, "nodeId must not be null");
            Objects.requireNonNull(input, "input must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
        }

        @Override
        public String toString() {
            return nodeId + "#" + occurrence;
        }
    }
}
