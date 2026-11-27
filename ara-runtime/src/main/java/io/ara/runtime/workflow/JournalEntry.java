package io.ara.runtime.workflow;

import java.util.List;
import java.util.Objects;

/**
 * The journal, in two phases: a {@link Started} entry written the moment
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

    /**
     * @param mediaCount how many attachments of the run's request this occurrence was handed: 0 for a node that
     *                   does not read them. A count, never the references: the journal is a record of what happened — so a
     *                   host can say "no step of the run read the document" without reading logs — and the request is
     *                   presented again, whole, by whoever resumes the run.
     */
    record Started(String nodeId, int occurrence, String input, int mediaCount) implements JournalEntry {
        public Started {
            Objects.requireNonNull(nodeId, "nodeId must not be null");
            Objects.requireNonNull(input, "input must not be null");
            if (mediaCount < 0) {
                throw new IllegalArgumentException("mediaCount must not be negative: " + mediaCount);
            }
        }

        /** A node that was handed no attachments — every entry written before attachments existed. */
        public Started(String nodeId, int occurrence, String input) {
            this(nodeId, occurrence, input, 0);
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
