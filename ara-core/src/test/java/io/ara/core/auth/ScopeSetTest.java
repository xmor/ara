package io.ara.core.auth;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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

    @Test
    void scopeSet_isImmutable_andDefensivelyCopiesItsInput() {
        List<String> mutable = new ArrayList<>(List.of("ops"));
        ScopeSet set = ScopeSet.of(mutable);
        mutable.add("hr");

        assertEquals(ScopeSet.of("ops"), set, "later mutation of the source list does not leak in");
        assertThrows(UnsupportedOperationException.class, () -> set.scopes().add("x"));
    }
}
