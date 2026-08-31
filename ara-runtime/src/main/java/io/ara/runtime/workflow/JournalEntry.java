package io.ara.runtime.workflow;

import java.util.List;
import java.util.Objects;

/**
 * One append-only journal entry, written when a node occurrence finishes. The pair
 * {@code (nodeId, occurrence)} is its key.
 *
 * <p>This is deliberately narrower than the entry shape ADR-052 describes for the
 * finished system — no {@code Failed}/{@code Suspended} outcome, no state writes, no
 * elapsed/tokens/cost. Those belong to the increment that introduces the {@code
 * onUncertainResume} policy and the {@code RunState} reducers that need them; adding
 * unused fields here now, before anything populates them, would be exactly the kind of
 * abstraction the coding guidelines ask to avoid until there's a real consumer.
 *
 * @param selectedTargets the outgoing edges this occurrence activated — used to deposit
 *                         tokens on those edges and mark the rest dead, see
 *                         {@link DataflowScheduler}
 */
public record JournalEntry(String nodeId, int occurrence, String input, String output, List<String> selectedTargets) {

    public JournalEntry {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(selectedTargets, "selectedTargets must not be null");
        selectedTargets = List.copyOf(selectedTargets);
    }

    @Override
    public String toString() {
        return nodeId + "#" + occurrence;
    }
}
