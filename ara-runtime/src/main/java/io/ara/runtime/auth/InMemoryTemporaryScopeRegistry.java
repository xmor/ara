package io.ara.runtime.auth;

import io.ara.core.auth.ScopeGrant;
import io.ara.core.auth.ScopeSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link TemporaryScopeRegistry}. Grants are lost on process restart —
 * acceptable for a task-scoped, short-lived grant (ADR-033 Fase 8); a durable deployment
 * that needs grants to survive a restart supplies its own implementation.
 *
 * <p>Method-level {@code synchronized}, not per-agent locking: grant/revoke are rare,
 * human-timescale operations, so simplicity and correctness win over throughput here —
 * the same trade-off {@code InMemoryApprovalGate} makes for the same reason.
 */
public final class InMemoryTemporaryScopeRegistry implements TemporaryScopeRegistry {

    private final Map<String, List<ScopeGrant>> grantsByAgent = new ConcurrentHashMap<>();

    @Override
    public synchronized void grant(String agentId, ScopeGrant grant) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        Objects.requireNonNull(grant, "grant must not be null");
        grantsByAgent.computeIfAbsent(agentId, k -> new ArrayList<>()).add(grant);
    }

    @Override
    public synchronized ScopeSet effectiveTemporaryScopes(String agentId) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        List<ScopeGrant> grants = grantsByAgent.get(agentId);
        if (grants == null || grants.isEmpty()) {
            return ScopeSet.EMPTY;
        }

        ScopeSet combined = ScopeSet.EMPTY;
        List<ScopeGrant> stillActive = new ArrayList<>();
        for (ScopeGrant grant : grants) {
            if (!grant.isValid()) {
                continue;   // expired or exhausted before this call — drop
            }
            combined = combined.union(grant.scopes());
            ScopeGrant afterUse = grant.consume();
            if (afterUse.isValid()) {
                stillActive.add(afterUse);
            }
            // else: this very call exhausted it — drop
        }
        grantsByAgent.put(agentId, stillActive);
        return combined;
    }

    @Override
    public synchronized void revokeAll(String agentId) {
        Objects.requireNonNull(agentId, "agentId must not be null");
        grantsByAgent.remove(agentId);
    }
}
