package io.ara.runtime.workflow;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of a {@link DataflowScheduler} run: the append-only journal, plus whether
 * the run completed. ADR-052 calls the journal the trace, the checkpoint, and the
 * serialization order all at once — one artifact serving three purposes that would
 * otherwise be three separate types.
 *
 * <p>{@code ok}/{@code failureReason} is a placeholder pair, not a finished status model:
 * a node failure and a node suspension are both reported as {@code ok == false} today,
 * distinguishable only by reading {@code failureReason}. Giving suspension its own status
 * — one a caller can branch on without string-matching — is ADR-052 D6's job, once there
 * is something to resume a suspended run *into*; adding that distinction here now, with
 * nothing yet consuming it, would be exactly the premature abstraction the coding
 * guidelines ask to avoid.
 */
public record WorkflowResult(List<JournalEntry> journal, boolean ok, String failureReason) {

    public WorkflowResult {
        Objects.requireNonNull(journal, "journal must not be null");
        journal = List.copyOf(journal);
    }

    /** How many occurrences of this node reached a {@link JournalEntry.Finished} entry, of any outcome. */
    public long firedTimes(String nodeId) {
        return journal.stream()
                .filter(JournalEntry.Finished.class::isInstance)
                .filter(e -> e.nodeId().equals(nodeId))
                .count();
    }

    /** The first {@link JournalEntry.Finished} entry for this node. */
    public JournalEntry.Finished firstOf(String nodeId) {
        return journal.stream()
                .filter(JournalEntry.Finished.class::isInstance)
                .map(JournalEntry.Finished.class::cast)
                .filter(e -> e.nodeId().equals(nodeId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(nodeId + " never finished"));
    }

    /** The journal as {@code "nodeId#occurrence"} tokens, in write order — for assertions and logs. */
    public List<String> order() {
        return journal.stream().map(JournalEntry::toString).toList();
    }
}
