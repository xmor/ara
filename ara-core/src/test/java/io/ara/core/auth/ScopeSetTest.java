package io.ara.core.auth;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 1 — {@link ScopeSet} containment, visibility and intersection at the
 * boundary cases: empty sets, partial overlap, superset, disjoint.
 */
class ScopeSetTest {

    @Test
    void grants_emptyRequiredIsAlwaysSatisfied() {
        assertTrue(ScopeSet.EMPTY.grants(ScopeSet.EMPTY));
        assertTrue(ScopeSet.of("ops").grants(ScopeSet.EMPTY));
        assertTrue(ScopeSet.EMPTY.grants(ScopeSet.of()), "of() with no args is empty");
    }

    @Test
    void grants_supersetYesSubsetMissingNoDisjointNo() {
        ScopeSet held = ScopeSet.of("ops", "hr", "finance");
        assertTrue(held.grants(ScopeSet.of("ops")));
        assertTrue(held.grants(ScopeSet.of("ops", "hr")));
        assertFalse(held.grants(ScopeSet.of("ops", "secret")), "one missing scope denies");
        assertFalse(ScopeSet.of("ops").grants(ScopeSet.of("finance")), "disjoint denies");
    }

    @Test
    void visibleTo_emptyResourceIsVisibleToAnyone() {
        assertTrue(ScopeSet.EMPTY.visibleTo(ScopeSet.EMPTY));
        assertTrue(ScopeSet.EMPTY.visibleTo(ScopeSet.of("anything")));
    }

    @Test
    void visibleTo_requiresAtLeastOneSharedScope() {
        ScopeSet resource = ScopeSet.of("finance", "audit");
        assertTrue(resource.visibleTo(ScopeSet.of("ops", "finance")), "partial overlap → visible");
        assertFalse(resource.visibleTo(ScopeSet.of("ops", "hr")), "disjoint → not visible");
        assertFalse(resource.visibleTo(ScopeSet.EMPTY), "caller with no scopes sees no restricted resource");
    }

    @Test
    void intersect_partialEmptyAndWithEMPTY() {
        assertEquals(ScopeSet.of("ops"),
                ScopeSet.of("ops", "hr").intersect(ScopeSet.of("ops", "finance")));
        assertTrue(ScopeSet.of("ops").intersect(ScopeSet.of("finance")).isEmpty());
        assertTrue(ScopeSet.of("ops").intersect(ScopeSet.EMPTY).isEmpty());
        assertTrue(ScopeSet.EMPTY.intersect(ScopeSet.of("ops")).isEmpty());
    }

    // ADR-033 Fase 8 — union backs a temporary grant layered on top of static scopes,
    // the opposite direction from intersect: it can only ever grow, never narrow.
    @Test
    void union_combinesBothSets_neverNarrows() {
        assertEquals(ScopeSet.of("ops", "hr", "finance"),
                ScopeSet.of("ops", "hr").union(ScopeSet.of("hr", "finance")));
        assertEquals(ScopeSet.of("ops"), ScopeSet.of("ops").union(ScopeSet.EMPTY));
        assertEquals(ScopeSet.of("ops"), ScopeSet.EMPTY.union(ScopeSet.of("ops")));
        assertTrue(ScopeSet.EMPTY.union(ScopeSet.EMPTY).isEmpty());
    }

    @Test
    void isEmpty_andEMPTY() {
        assertTrue(ScopeSet.EMPTY.isEmpty());
        assertTrue(new ScopeSet(null).isEmpty());
        assertFalse(ScopeSet.of("x").isEmpty());
    }

    @Test
    void of_variantsAreEquivalent() {
        assertEquals(ScopeSet.of("a", "b"), ScopeSet.of(List.of("a", "b")));
        assertEquals(ScopeSet.EMPTY, ScopeSet.of(new ArrayList<>()));
    }

    // ADR-0077 D3 — the non-escalation invariant is a property of the intersection itself,
    // not a runtime check: intersect can never yield a scope absent from either operand.
    @Test
    void intersect_neverYieldsAScopeAbsentFromEitherOperand_property() {
        String[] pool = {"a", "b", "c", "d", "e", "f", "g"};
        Random rnd = new Random(20260904L);
        for (int i = 0; i < 1000; i++) {
            ScopeSet incoming = randomSubset(pool, rnd);
            ScopeSet granted  = randomSubset(pool, rnd);
            ScopeSet effective = incoming.intersect(granted);

            assertTrue(incoming.scopes().containsAll(effective.scopes()),
                    "effective must never exceed the caller's scopes");
            assertTrue(granted.scopes().containsAll(effective.scopes()),
                    "effective must never exceed the delegating agent's granted scopes");
            // and it is exactly the intersection, order of operands irrelevant
            assertEquals(effective, granted.intersect(incoming));
        }
    }

    @Test
    void intersect_composesAcrossADelegationChain() {
        ScopeSet atA = ScopeSet.of("a", "b", "c", "d");
        ScopeSet grantedB = ScopeSet.of("b", "c", "d", "e");
        ScopeSet grantedC = ScopeSet.of("c", "d", "f");

        ScopeSet atB = atA.intersect(grantedB);          // A → B
        ScopeSet atC = atB.intersect(grantedC);          // B → C

        assertEquals(ScopeSet.of("c", "d"), atC);
        assertEquals(atA.intersect(grantedB).intersect(grantedC), atC,
                "no hop needs to know the whole chain");
    }

    private static ScopeSet randomSubset(String[] pool, Random rnd) {
        Set<String> picked = new LinkedHashSet<>();
        for (String s : pool) {
            if (rnd.nextBoolean()) {
                picked.add(s);
            }
        }
        return ScopeSet.of(picked);
    }

    @Test
    void scopeSet_isImmutable_andDefensivelyCopiesItsInput() {
        List<String> mutable = new ArrayList<>(List.of("ops"));
        ScopeSet set = ScopeSet.of(mutable);
        mutable.add("hr");

        assertEquals(ScopeSet.of("ops"), set, "later mutation of the source list does not leak in");
        assertThrows(UnsupportedOperationException.class, () -> set.scopes().add("x"));
    }
}
