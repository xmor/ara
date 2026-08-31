package io.ara.runtime.workflow;

/**
 * What to do, on {@link DataflowScheduler#run(String, java.util.concurrent.ExecutorService,
 * java.util.List) resume}, with a node occurrence that has a {@link JournalEntry.Started}
 * entry but no matching {@link JournalEntry.Finished} — i.e. one the prior run committed to
 * firing but never recorded an outcome for. The journal alone cannot tell "crashed mid-way"
 * apart from "never actually started"; ADR-052 calls this D1's one uncomfortable point and
 * pushes the decision onto whoever declares the node, instead of guessing.
 */
public enum UncertainResumePolicy {

    /**
     * Fire the node again with the same input it had before. Correct whenever the body is
     * idempotent or side-effect-free; the default, because most nodes at this stage are.
     */
    RETRY,

    /** Fail the resume outright rather than risk running a non-idempotent node twice. */
    FAIL,

    /**
     * Stop the resume the same way {@link WorkflowNodeSuspendedException} does, rather than
     * guess. Distinct from {@link #FAIL} only in the reason recorded on the {@link
     * WorkflowResult} — there is no automatic un-suspend here any more than there is for a
     * node that suspends itself; that is ADR-052 D6's job.
     */
    SUSPEND
}
