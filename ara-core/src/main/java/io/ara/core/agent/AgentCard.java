package io.ara.core.agent;

import io.ara.core.common.AgentId;

import java.util.List;
import java.util.Objects;

/**
 * Machine-readable description of an agent's identity and capabilities.
 *
 * <p>Generated on demand via {@link AraAgents#agentCard(AraAgent)} — not a method on
 * {@code AraAgent} itself, which stays a minimal contract (see {@link AraAgents}). Used by
 * {@code AgentRegistry} to build the discovery context injected into Supervisor agent
 * prompts, and in future by ara-cluster for cross-node discovery.
 *
 * <p>{@code requiredScopes} / {@code authenticationSchemes} align with the A2A agent-card
 * shape (ADR-033 Fase 1). Both default to "open": no scopes required,
 * {@code authenticationSchemes = ["none"]}.
 */
public record AgentCard(
        AgentId           agentId,
        String            name,
        String            description,
        String            version,
        AgentCapabilities capabilities,
        List<String>      requiredScopes,
        List<String>      authenticationSchemes
) {
    public AgentCard {
        Objects.requireNonNull(agentId, "agentId must not be null");
        name        = Objects.requireNonNullElse(name, "");
        description = Objects.requireNonNullElse(description, "");
        version     = Objects.requireNonNullElse(version, "1.0.0");
        Objects.requireNonNull(capabilities, "capabilities must not be null");
        requiredScopes        = List.copyOf(Objects.requireNonNullElse(requiredScopes, List.of()));
        authenticationSchemes = List.copyOf(Objects.requireNonNullElse(authenticationSchemes, List.of("none")));
    }

    /**
     * Backward-compatible constructor — {@code requiredScopes} defaults to empty and
     * {@code authenticationSchemes} to {@code ["none"]} (ADR-033 Fase 1).
     */
    public AgentCard(AgentId agentId, String name, String description, String version,
                     AgentCapabilities capabilities) {
        this(agentId, name, description, version, capabilities, List.of(), List.of("none"));
    }
}
