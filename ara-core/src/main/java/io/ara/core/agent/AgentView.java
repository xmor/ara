package io.ara.core.agent;

import io.ara.core.auth.ScopeSet;
import io.ara.core.common.AgentId;

import java.util.Collection;
import java.util.Optional;

/**
 * A scope-filtered projection of {@code AgentRegistry}: the same discovery surface, but
 * limited to the agents a given caller may see and invoke (ADR-033 Livello 1).
 *
 * <p>The holder's {@link #effectiveScopes()} decides visibility (an agent's
 * {@code visibleToScopes}) and authorization (its {@code requiredScopes}).
 *
 * <p><b>No implementation ships in Fase 1.</b> {@code FilteredAgentView} (ara-runtime)
 * and {@code AgentRegistry.viewFor(ScopeSet)} arrive in Fase 2; until then agent
 * discovery is unrestricted, exactly as before.
 */
public interface AgentView {

    /** The agent with this id, or empty if it is not visible to — or not authorized for — this view's scopes. */
    Optional<AraAgent> findById(AgentId id);

    /** Every agent visible to this view's scopes. */
    Collection<AraAgent> all();

    /** Discovery text for a supervisor prompt, listing only the agents visible to this view. */
    String catalog(String contextInput);

    /** The effective scopes of this view's holder — what visibility and authorization are decided against. */
    ScopeSet effectiveScopes();
}
