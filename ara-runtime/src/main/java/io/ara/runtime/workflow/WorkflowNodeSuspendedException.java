package io.ara.runtime.workflow;

/**
 * Thrown by a {@link WorkflowNode#body()} to signal that this occurrence did not
 * complete and did not fail either — it is waiting on something outside the graph (a
 * human approval, an external event) before it can proceed. {@link DataflowScheduler}
 * catches it and records a {@link NodeOutcome.Suspended} instead of a {@link
 * NodeOutcome.Failed}.
 *
 * <p>D1 does not yet make a suspended run resumable in the way ADR-052 D6 eventually
 * will (waiting for a decision and continuing from exactly this point); today it stops
 * the run, the same as a failure would. What this exception buys, ahead of D6, is an
 * honest journal: a future reader of a suspended run's trace can tell "waiting on a
 * decision" apart from "broke", even though neither one resumes automatically yet.
 */
public final class WorkflowNodeSuspendedException extends RuntimeException {

    public WorkflowNodeSuspendedException(String reason) {
        super(reason);
    }
}
