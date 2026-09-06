package io.ara.runtime.auth.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClearanceLevelTest {

    @Test
    void hierarchy_isInDeclarationOrder() {
        assertTrue(ClearanceLevel.STANDARD.compareTo(ClearanceLevel.SENSITIVE) < 0);
        assertTrue(ClearanceLevel.SENSITIVE.compareTo(ClearanceLevel.CONFIDENTIAL) < 0);
        assertTrue(ClearanceLevel.CONFIDENTIAL.compareTo(ClearanceLevel.SECRET) < 0);
    }

    @Test
    void parse_isCaseInsensitiveAndTrims() {
        assertEquals(ClearanceLevel.SECRET, ClearanceLevel.parse("secret"));
        assertEquals(ClearanceLevel.SECRET, ClearanceLevel.parse("  SECRET  "));
    }

    @Test
    void parse_nullBlankOrUnrecognized_defaultsToStandard_neverTheHighestTier() {
        assertEquals(ClearanceLevel.STANDARD, ClearanceLevel.parse(null));
        assertEquals(ClearanceLevel.STANDARD, ClearanceLevel.parse(""));
        assertEquals(ClearanceLevel.STANDARD, ClearanceLevel.parse("not-a-real-level"));
    }
}
