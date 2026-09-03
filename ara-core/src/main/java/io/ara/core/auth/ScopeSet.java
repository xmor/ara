package io.ara.core.auth;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable set of authorization scopes, with the containment/visibility/intersection
 * logic the authorization model is built on (ADR-033, Fase 1).
 *
 * <p>Scopes are OAuth 2.0-style opaque strings (e.g. {@code "finance:read"},
 * {@code "acme:ops"}). ARA does not interpret their structure — a tenant prefix
 * (ADR-033 S7) is just part of the string.
 *
 * <p><b>Fase 1 is types only.</b> Nothing enforces scopes yet: the fields on
 * {@code ExecutionConfig}/{@code AgentCard}/{@code AraTool} default to empty, and no code
 * path calls {@link #grants}/{@link #visibleTo} at dispatch time. Enforcement
 * ({@code ScopeVerifier}, {@code FilteredAgentView}) arrives in Fase 2 ({@code ara-runtime}).
 */
public record ScopeSet(Set<String> scopes) {

    /** The empty scope set — grants nothing, denies nothing (see {@link #grants}/{@link #visibleTo}). */
    public static final ScopeSet EMPTY = new ScopeSet(Set.of());

    public ScopeSet {
        scopes = Set.copyOf(Objects.requireNonNullElse(scopes, Set.of()));
    }

    public static ScopeSet of(String... scopes) {
        return new ScopeSet(Set.of(scopes));
    }

    public static ScopeSet of(Collection<String> scopes) {
        return new ScopeSet(scopes == null ? Set.of() : new LinkedHashSet<>(scopes));
    }

    /**
     * Whether this set satisfies every scope in {@code required}. An empty {@code required}
     * is always satisfied — a resource that requires no scopes is open to any caller.
     */
    public boolean grants(ScopeSet required) {
        return required.isEmpty() || scopes.containsAll(required.scopes());
    }

    /**
     * Whether a caller holding {@code caller} may discover this resource: true if this set
     * is empty (no visibility restriction) or shares at least one scope with the caller.
     */
    public boolean visibleTo(ScopeSet caller) {
        return isEmpty() || scopes.stream().anyMatch(caller.scopes()::contains);
    }

    /** The scopes present in both sets. */
    public ScopeSet intersect(ScopeSet other) {
        LinkedHashSet<String> common = new LinkedHashSet<>(scopes);
        common.retainAll(other.scopes());
        return new ScopeSet(common);
    }

    public boolean isEmpty() {
        return scopes.isEmpty();
    }
}
