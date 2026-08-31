package io.ara.runtime.workflow;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of a {@link DataflowScheduler} run: the append-only journal, plus whether
 * the run completed. ADR-052 calls the journal the trace, the checkpoint, and the
 * serialization order all at once — one artifact serving three purposes that would
 * otherwise be three separate types.
 */
public record WorkflowResult(List<JournalEntry> journal, boolean ok, String failureReason) {

    public WorkflowResult {
        Objects.requireNonNull(journal, "journal must not be null");
        journal = List.copyOf(journal);
    }

    public long firedTimes(String nodeId) {
        return journal.stream().filter(e -> e.nodeId().equals(nodeId)).count();
    }

    public JournalEntry firstOf(String nodeId) {
        return journal.stream().filter(e -> e.nodeId().equals(nodeId)).findFirst()
                .orElseThrow(() -> new IllegalStateException(nodeId + " never fired"));
    }

    /** The journal as {@code "nodeId#occurrence"} tokens, in firing order — for assertions and logs. */
    public List<String> order() {
        return journal.stream().map(JournalEntry::toString).toList();
    }
}
