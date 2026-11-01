package io.ara.core.memory;

import java.util.Objects;

/**
 * Memory sub-record of {@link io.ara.core.agent.AgentConfig}: working memory and
 * conversation settings.
 *
 * <p>{@code replayToolCalls} keeps the tool calls of each turn in the session history and replays them on the turns
 * that follow, next to the question and the answer. Off by default, so an agent behaves, and costs, exactly as before:
 * switching it on changes the prompt of every turn after the first, which changes what any benchmark of the agent
 * measures. It matters for an agent that acts on the world over several turns: with questions and answers alone, a
 * small model was seen answering "done" to a repeated command without calling the tool (3 commands right out of 10),
 * and with the calls in the history it called it every time (10 out of 10). It has no effect when
 * {@code maxConversationTurns} is 0, since nothing is replayed then.
 *
 * <p>It is a {@code Boolean} and not a {@code boolean} so that "off" can be {@code null}: the behavioural hash of a config
 * ({@code SpecLineage.hash}) leaves null components out, so a config that does not use the option hashes exactly as it
 * did before the option existed, and a stored spec keeps its identity. Only {@code TRUE} means on; read it through
 * {@link #keepsToolCalls()}.
 */
public record MemoryConfig(
        int    workingMemoryTokenBudget,
        String workingMemoryEviction,
        int    maxConversationTurns,
        int    maxReflections,
        String reflectionPrompt,
        String contextSummarizerAgentId,
        Boolean replayToolCalls
) {
    public MemoryConfig {
        // FALSE and null mean the same, off; storing FALSE would move the hash of a config that only said "off"
        if (Boolean.FALSE.equals(replayToolCalls)) {
            replayToolCalls = null;
        }
        if (workingMemoryTokenBudget < 0)
            throw new IllegalArgumentException("workingMemoryTokenBudget must be >= 0");
        if (maxConversationTurns < 0)
            throw new IllegalArgumentException("maxConversationTurns must be >= 0");
        // The eviction-strategy vocabulary ("drop_oldest", "drop_middle", "summarize") is
        // owned by io.ara.runtime.memory.EvictionPolicy, which ara-core cannot depend on —
        // validating the same closed set here too would mean keeping two independently
        // -maintained copies of it in sync across modules. EvictionPolicy.from(...) is the
        // single place that rejects an unknown value; only "unspecified" is handled here.
        workingMemoryEviction = Objects.requireNonNullElse(workingMemoryEviction, "drop_middle");
        // contextSummarizerAgentId: an AraAgent id (String, not a typed reference — ara-core
        // cannot depend on runtime agent resolution, same layering reason as
        // workingMemoryEviction). Resolved at wiring time. Nullable: SUMMARIZE eviction
        // (ADR-0078 D2) degrades to DROP_MIDDLE when it is absent.
    }

    /** 6-arg constructor (the shape before {@code replayToolCalls}): tool calls are not kept. */
    public MemoryConfig(int workingMemoryTokenBudget, String workingMemoryEviction,
                        int maxConversationTurns, int maxReflections, String reflectionPrompt,
                        String contextSummarizerAgentId) {
        this(workingMemoryTokenBudget, workingMemoryEviction, maxConversationTurns, maxReflections,
                reflectionPrompt, contextSummarizerAgentId, null);
    }

    /**
     * 5-arg constructor (the shape before {@code contextSummarizerAgentId}, ADR-0078 D2) —
     * kept so every existing {@code new MemoryConfig(...)} call keeps compiling with the
     * summariser left unset.
     */
    public MemoryConfig(int workingMemoryTokenBudget, String workingMemoryEviction,
                        int maxConversationTurns, int maxReflections, String reflectionPrompt) {
        this(workingMemoryTokenBudget, workingMemoryEviction, maxConversationTurns, maxReflections,
                reflectionPrompt, null);
    }

    public static MemoryConfig defaults() {
        return new MemoryConfig(0, "drop_middle", 0, 2, null, null, null);
    }

    /** Whether the tool calls of each turn are kept and replayed. */
    public boolean keepsToolCalls() {
        return Boolean.TRUE.equals(replayToolCalls);
    }
}
