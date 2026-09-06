package io.ara.runtime.workflow;

/**
 * A workflow pattern, in the one shape ADR-054 decides on (option O3): a declarative spec
 * that <em>compiles onto</em> a {@link WorkflowGraph} through {@link Workflow.Builder},
 * never a special node type inside {@link DataflowScheduler}.
 *
 * <p>That distinction is the whole point of the decision, and it is worth restating where
 * an implementor will read it: a pattern implemented as a scheduler case turns the engine
 * into a collection of special cases, and every special case is a build-time control that
 * quietly stops holding (ADR-054 DR-3, and the way LangGraph arrived at {@code
 * defer=True}). A pattern implemented as a spec adds nodes, edges and declared per-node
 * properties — nothing the scheduler has to know about — so the structural controls keep
 * applying to it for free.
 *
 * <p>Each implementation carries its own build-time checks, and raises them as early as it
 * can: a check that can run when the spec itself is constructed (a missing reject target,
 * a non-positive round cap) belongs there rather than in {@link #compileInto}, so the
 * caller learns about it at the call site that got it wrong.
 *
 * @see io.ara.runtime.workflow.patterns.Critic
 * @see io.ara.runtime.workflow.patterns.Filter
 * @see io.ara.runtime.workflow.patterns.Supervisor
 * @see io.ara.runtime.workflow.patterns.Tournament
 */
@FunctionalInterface
public interface WorkflowPattern {

    /** Adds this pattern's nodes, edges and declared properties to {@code builder}. */
    void compileInto(Workflow.Builder builder);
}
