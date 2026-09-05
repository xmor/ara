package io.ara.runtime.agent;

import io.ara.core.agent.AgentCard;
import io.ara.core.agent.AgentView;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.AraAgents;
import io.ara.core.auth.AuthorizationException;
import io.ara.core.auth.ScopeSet;
import io.ara.core.common.AgentId;
import io.ara.runtime.auth.ScopeVerifier;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@link AgentView} implementation ADR-033 Fase 2 adds: a scope-filtered projection
 * of an {@link AgentRegistry}, built fresh per caller via {@link
 * AgentRegistry#viewFor(ScopeSet)}.
 *
 * <p><b>Visible vs. authorized — two different gates.</b> {@link #all()} and {@link
 * #catalog(String)} filter by visibility alone ({@link ScopeVerifier#checkVisible}): an
 * agent a caller cannot invoke may still be worth knowing exists, e.g. to explain a
 * later {@code AGENT_NOT_AUTHORIZED} denial rather than making it look like the agent
 * never existed. {@link #findById} — the gate before an actual invocation — checks both:
 * visibility first, then authorization, so a caller gets {@code Optional.empty()} for
 * either reason without needing to distinguish them (the caller wanting the distinction
 * can call {@link ScopeVerifier} directly instead of going through this view).
 */
public final class FilteredAgentView implements AgentView {

    private final AgentRegistry registry;
    private final ScopeSet      effectiveScopes;

    public FilteredAgentView(AgentRegistry registry, ScopeSet effectiveScopes) {
        this.registry        = Objects.requireNonNull(registry, "registry must not be null");
        this.effectiveScopes = Objects.requireNonNull(effectiveScopes, "effectiveScopes must not be null");
    }

    @Override
    public Optional<AraAgent> findById(AgentId id) {
        Objects.requireNonNull(id, "id must not be null");
        return registry.findById(id).filter(this::isVisibleAndAuthorized);
    }

    @Override
    public Collection<AraAgent> all() {
        return registry.all().stream().filter(this::isVisible).toList();
    }

    /**
     * Discovery text for a supervisor prompt, one line per visible agent. {@code
     * contextInput} is accepted for a future relevance-based ordering or filtering — today
     * every visible agent is listed, in registry order, and the parameter is otherwise
     * unused: the only filter this phase applies is scope-based visibility.
     */
    @Override
    public String catalog(String contextInput) {
        StringBuilder sb = new StringBuilder();
        for (AraAgent agent : all()) {
            AgentCard card = AraAgents.agentCard(agent);
            sb.append("- ").append(card.agentId().value());
            if (!card.description().isBlank()) {
                sb.append(": ").append(card.description());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Override
    public ScopeSet effectiveScopes() {
        return effectiveScopes;
    }

    private boolean isVisible(AraAgent agent) {
        try {
            ScopeVerifier.checkVisible(agent, effectiveScopes);
            return true;
        } catch (AuthorizationException e) {
            return false;
        }
    }

    private boolean isVisibleAndAuthorized(AraAgent agent) {
        try {
            ScopeVerifier.checkVisible(agent, effectiveScopes);
            ScopeVerifier.checkAuthorized(agent, effectiveScopes);
            return true;
        } catch (AuthorizationException e) {
            return false;
        }
    }
}
