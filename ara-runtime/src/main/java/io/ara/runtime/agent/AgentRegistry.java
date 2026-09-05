package io.ara.runtime.agent;

import io.ara.core.agent.AgentCard;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentView;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AraAgents;
import io.ara.core.auth.ScopeSet;
import io.ara.core.common.AgentId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Thread-safe catalog of all {@link AraAgent} instances currently managed
 * by this ARA platform node.
 *
 * <p>The registry is the authoritative source of truth for which agents are alive.
 * {@link io.ara.runtime.factory.AgentFactory} registers newly created agents here automatically.
 * The Meta-Agent and the Orchestration layer query the registry to discover agents,
 * route messages, and monitor lifecycle state.
 *
 * <p>Id-keyed operations ({@link #register}, {@link #replace}, {@link #deregister},
 * {@link #findById}, {@link #isRegistered}) are O(1) average thanks to the underlying
 * {@link ConcurrentHashMap}. The scanning queries ({@link #findByState}, {@link
 * #findByType}, {@link #all()}) are O(n) in the number of registered agents and return
 * immutable snapshots, so callers are not affected by concurrent registrations or
 * removals — and, being weakly consistent, may reflect a registration that lands
 * mid-iteration.
 */
public final class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    /** The backing store: agentId.value() → AraAgent instance. */
    private final Map<String, AraAgent> agents = new ConcurrentHashMap<>();

    /**
     * Registers a newly created agent.
     *
     * <p>Called by {@link io.ara.runtime.factory.AgentFactory} immediately after construction.
     * Duplicate registrations (same id) are rejected.
     *
     * @param agent the agent to register; must not be {@code null}
     * @throws IllegalArgumentException if an agent with the same id is already registered
     */
    public void register(AraAgent agent) {
        Objects.requireNonNull(agent, "agent must not be null");
        String key = agent.agentId().value();

        AraAgent existing = agents.putIfAbsent(key, agent);
        if (existing != null) {
            throw new IllegalArgumentException(
                    "Agent with id [%s] is already registered in the registry."
                            .formatted(key));
        }
        log.debug("Registered agent [{}] type=[{}]",
                key, agent.config().agentType());
    }

    /**
     * Atomically replaces whatever agent currently holds {@code newAgent.agentId()} —
     * or registers it fresh if none exists — and returns the agent it displaced.
     *
     * <p>Unlike calling {@link #deregister} and then {@link #register} as two separate
     * steps, this is a single {@code ConcurrentHashMap.put}: a concurrent {@link
     * #findById} for this id always observes either the old agent or the new one, never
     * a momentary absence. That gap is real with the deregister-then-register sequence —
     * e.g. {@code LocalMessageBus} routing a delegated task to this id in between the
     * two calls would fail with "not registered on this node" purely due to timing.
     *
     * <p>The caller owns the displaced agent's fate: this method does not call {@link
     * AraAgent#terminate()} on it. A displaced agent keeps running any in-flight
     * sessions exactly as before — the caller can let it finish naturally and drop the
     * last reference, or terminate it immediately, depending on whether the replacement
     * is a routine config update or a forced cutover.
     *
     * @param newAgent the agent to install under its own {@code agentId()}
     * @return the previously registered agent under this id, or {@code null} if none existed
     */
    public AraAgent replace(AraAgent newAgent) {
        Objects.requireNonNull(newAgent, "newAgent must not be null");
        String key = newAgent.agentId().value();
        AraAgent previous = agents.put(key, newAgent);
        if (previous != null) {
            log.debug("Replaced agent [{}]: type=[{}] -> type=[{}]",
                    key, previous.config().agentType(), newAgent.config().agentType());
        } else {
            log.debug("Replaced agent [{}] type=[{}] (no previous agent — behaves like register)",
                    key, newAgent.config().agentType());
        }
        return previous;
    }

    /**
     * Removes an agent from the registry.
     *
     * <p>Typically called after the agent has been {@link AraAgent#terminate() terminated}.
     * If the agent id is not found, this method is a no-op.
     *
     * @param agentId the id of the agent to deregister
     */
    public void deregister(AgentId agentId) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        AraAgent removed = agents.remove(agentId.value());
        if (removed != null) {
            log.debug("Deregistered agent [{}]", agentId.value());
        }
    }

    /**
     * Looks up an agent by its id.
     *
     * @param agentId the agent's unique identifier
     * @return an {@link Optional} containing the agent, or empty if not registered
     */
    public Optional<AraAgent> findById(AgentId agentId) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        return Optional.ofNullable(agents.get(agentId.value()));
    }

    /**
     * Returns all agents currently in the given lifecycle state.
     *
     * <p>The returned list is an immutable snapshot — subsequent state changes
     * do not affect it.
     *
     * @param state the state to filter by; must not be {@code null}
     * @return an immutable list of matching agents; may be empty
     */
    public List<AraAgent> findByState(AgentState state) {
        Objects.requireNonNull(state, "state must not be null");
        return filter(a -> a.currentState() == state);
    }

    /**
     * Returns all agents of the given logical type (e.g. {@code "researcher"}).
     *
     * @param agentType the type label to filter by
     * @return an immutable list of matching agents; may be empty
     */
    public List<AraAgent> findByType(String agentType) {
        Objects.requireNonNull(agentType, "agentType must not be null");
        return filter(a -> agentType.equals(a.config().agentType()));
    }

    private List<AraAgent> filter(Predicate<AraAgent> predicate) {
        return agents.values().stream().filter(predicate).toList();
    }

    /**
     * Returns an immutable snapshot of all currently registered agents.
     *
     * @return an unmodifiable collection of all agents
     */
    public Collection<AraAgent> all() {
        return List.copyOf(agents.values());
    }

    /**
     * Returns an {@link AgentCard} for every currently registered agent — the discovery
     * catalog this class's own Javadoc refers to ("query the registry to discover
     * agents"). Each card is built fresh via {@link AraAgents#agentCard(AraAgent)} at
     * call time, so it reflects the agent's live {@link io.ara.core.agent.AgentConfig}
     * (including any {@code reconfigure} since the agent was registered), not a
     * snapshot cached at registration.
     *
     * @return an immutable list of cards, one per registered agent, in no particular
     *         order; may be empty
     */
    public List<AgentCard> cards() {
        return agents.values().stream().map(AraAgents::agentCard).toList();
    }

    /** Returns the number of agents currently registered. */
    public int count() {
        return agents.size();
    }

    /** Returns {@code true} if an agent with the given id is registered. */
    public boolean isRegistered(AgentId agentId) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        return agents.containsKey(agentId.value());
    }

    /**
     * A scope-filtered {@link AgentView} of this registry, scoped to {@code
     * effectiveScopes} (ADR-033 Fase 2) — built fresh, never cached, so it reflects
     * registrations/deregistrations made after this call.
     */
    public AgentView viewFor(ScopeSet effectiveScopes) {
        return new FilteredAgentView(this, effectiveScopes);
    }

    /**
     * A view with no scope restriction — {@link ScopeSet#EMPTY} as the effective scopes.
     * Sees every agent that has not itself declared {@code visibleToScopes}/{@code
     * requiredScopes}, i.e. every agent in the wild today: this is today's unrestricted
     * discovery behavior, expressed as an {@link AgentView} rather than direct registry
     * access, for a caller migrating onto the {@link AgentView} API before any agent it
     * talks to has opted into scopes. It is not a superuser bypass of scopes an agent
     * <em>has</em> declared — {@link ScopeSet#EMPTY} still fails a real restriction the
     * normal way, which is what "unrestricted" here actually means.
     */
    public AgentView unrestricted() {
        return new FilteredAgentView(this, ScopeSet.EMPTY);
    }
}