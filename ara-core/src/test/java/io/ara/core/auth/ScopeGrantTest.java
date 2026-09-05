package io.ara.core.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 8 (S5, `docs/adr/ADR-033-implementation-plan.md` §8.1, `ara-private`) —
 * {@link ScopeGrant#isValid()}/{@link ScopeGrant#consume()} boundary cases.
 */
class ScopeGrantTest {

    private static ScopeGrant grant(Instant expiresAt, int remainingUses) {
        return new ScopeGrant(ScopeSet.of("tools:shell"), expiresAt, remainingUses, "operator-1", "one-off fix");
    }

    @Test
    void noExpiry_noUseLimit_alwaysValid() {
        ScopeGrant g = grant(null, -1);
        assertTrue(g.isValid());
        assertSame(g, g.consume(), "unlimited-use grant is unaffected by consume()");
    }

    @Test
    void expired_isInvalid_regardlessOfRemainingUses() {
        ScopeGrant g = grant(Instant.now().minus(Duration.ofMinutes(1)), 5);
        assertFalse(g.isValid());
    }

    @Test
    void notYetExpired_isValid() {
        ScopeGrant g = grant(Instant.now().plus(Duration.ofMinutes(10)), 1);
        assertTrue(g.isValid());
    }

    @Test
    void consume_decrementsRemainingUses_untilExhausted() {
        ScopeGrant g = grant(null, 2);
        assertTrue(g.isValid());

        ScopeGrant afterOne = g.consume();
        assertEquals(1, afterOne.remainingUses());
        assertTrue(afterOne.isValid());

        ScopeGrant afterTwo = afterOne.consume();
        assertEquals(0, afterTwo.remainingUses());
        assertFalse(afterTwo.isValid(), "zero remaining uses is exhausted, not valid");
    }

    @Test
    void constructor_rejectsRemainingUsesBelowNegativeOne() {
        assertThrows(IllegalArgumentException.class, () -> grant(null, -2));
    }
}
