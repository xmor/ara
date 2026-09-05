package io.ara.core.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 5 (`docs/adr/ADR-033-implementation-plan.md` §5.1, `ara-private`) —
 * {@link ExecutionContext}: pure M2M vs. on-behalf-of, and attenuation across a
 * multi-hop delegation chain.
 */
class ExecutionContextTest {

    @Test
    void ofAgent_pureM2m_effectiveScopesIsJustActorScopes() {
        ExecutionContext ctx = ExecutionContext.ofAgent("A", ScopeSet.of("ops", "finance"));

        assertNull(ctx.subjectId());
        assertTrue(ctx.subjectScopes().isEmpty());
        assertEquals(ScopeSet.of("ops", "finance"), ctx.effectiveScopes());
    }

    @Test
    void ofUserDelegation_effectiveScopesIsIntersectionOfActorAndSubject() {
        ExecutionContext ctx = ExecutionContext.ofUserDelegation(
                "agent-1", ScopeSet.of("finance:read", "finance:write"),
                "user-1", ScopeSet.of("finance:read", "hr:read"));

        assertEquals("user-1", ctx.subjectId());
        assertEquals(ScopeSet.of("finance:read"), ctx.effectiveScopes(),
                "delegation, not impersonation: bounded by both the actor and the user");
    }

    @Test
    void ofUserDelegation_requiresANonNullSubjectId() {
        assertThrows(NullPointerException.class,
                () -> ExecutionContext.ofUserDelegation("agent-1", ScopeSet.of("ops"), null, ScopeSet.EMPTY));
    }

    @Test
    void delegate_neverExceedsEitherSide_evenWhenSubjectPresent() {
        ExecutionContext atUser = ExecutionContext.ofUserDelegation(
                "agent-1", ScopeSet.of("finance:read", "finance:write", "hr:read"),
                "user-1", ScopeSet.of("finance:read", "finance:write"));

        // agent-2 is only trusted with finance:read, even though both actor and subject
        // above it in the chain could have gone further.
        ExecutionContext atAgent2 = atUser.delegate("agent-2", ScopeSet.of("finance:read"));

        assertEquals("agent-2", atAgent2.actorId());
        assertEquals("user-1", atAgent2.subjectId(), "subject identity survives the hop unchanged");
        assertEquals(ScopeSet.of("finance:read"), atAgent2.effectiveScopes());
    }

    @Test
    void delegate_composesAcrossAThreeHopChain_matchingTheAdrExample() {
        // A[ops,hr] → B[finance,ops] → C[ops]: C must see effectiveScopes=[ops] only.
        ExecutionContext atA = ExecutionContext.ofAgent("A", ScopeSet.of("ops", "hr"));
        ExecutionContext atB = atA.delegate("B", ScopeSet.of("finance", "ops"));
        ExecutionContext atC = atB.delegate("C", ScopeSet.of("ops"));

        assertEquals(ScopeSet.of("ops"), atB.effectiveScopes());
        assertEquals(ScopeSet.of("ops"), atC.effectiveScopes());
        assertFalse(atC.effectiveScopes().scopes().contains("finance"),
                "C cannot access finance-scoped resources even though B could");
    }

    @Test
    void delegate_withNullNextActorGranted_treatedAsEmpty() {
        ExecutionContext atA = ExecutionContext.ofAgent("A", ScopeSet.of("ops"));
        ExecutionContext atB = atA.delegate("B", null);

        assertTrue(atB.effectiveScopes().isEmpty());
    }

    @Test
    void constructor_rejectsNullActorIdAndActorScopes() {
        assertThrows(NullPointerException.class, () -> new ExecutionContext(null, ScopeSet.EMPTY, null, null));
        assertThrows(NullPointerException.class, () -> new ExecutionContext("A", null, null, null));
    }

    @Test
    void constructor_nullSubjectScopesDefaultsToEmpty() {
        ExecutionContext ctx = new ExecutionContext("A", ScopeSet.of("ops"), "user-1", null);
        assertEquals(ScopeSet.EMPTY, ctx.subjectScopes());
    }
}
