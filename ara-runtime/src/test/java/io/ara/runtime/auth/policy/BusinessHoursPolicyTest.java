package io.ara.runtime.auth.policy;

import io.ara.core.auth.ActionAttributes;
import io.ara.core.auth.EnvironmentAttributes;
import io.ara.core.auth.PolicyDecision;
import io.ara.core.auth.PolicyEvaluationContext;
import io.ara.core.auth.ResourceAttributes;
import io.ara.core.auth.ScopeSet;
import io.ara.core.auth.SubjectAttributes;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ADR-033 Fase 2b DONE-WHEN, verbatim: "BusinessHoursPolicy nega alle 03:00 su risorsa
 * dataClassification=critical".
 */
class BusinessHoursPolicyTest {

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    // A known Wednesday.
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 9, 9);
    private static final LocalDate SATURDAY  = LocalDate.of(2026, 9, 12);

    private static PolicyEvaluationContext ctx(String dataClassification, LocalDate date, LocalTime time) {
        return new PolicyEvaluationContext(
                new SubjectAttributes("caller", "t", null, null, ScopeSet.EMPTY),
                new ResourceAttributes("target", "t", dataClassification, null, false),
                ActionAttributes.INVOKE,
                new EnvironmentAttributes(LocalDateTime.of(date, time).atZone(ROME).toInstant(), 0, "req-1"),
                null);
    }

    @Test
    void nonCriticalResource_abstainsRegardlessOfTime() {
        assertEquals(PolicyDecision.NOT_APPLICABLE,
                BusinessHoursPolicy.forZone("Europe/Rome").evaluate(ctx("public", WEDNESDAY, LocalTime.of(3, 0))));
    }

    @Test
    void criticalResource_at3AM_denies() {
        assertEquals(PolicyDecision.DENY,
                BusinessHoursPolicy.forZone("Europe/Rome").evaluate(ctx("critical", WEDNESDAY, LocalTime.of(3, 0))));
    }

    @Test
    void criticalResource_atNoonOnAWeekday_permits() {
        assertEquals(PolicyDecision.PERMIT,
                BusinessHoursPolicy.forZone("Europe/Rome").evaluate(ctx("critical", WEDNESDAY, LocalTime.of(12, 0))));
    }

    @Test
    void criticalResource_onAWeekend_denies_evenAtNoon() {
        assertEquals(PolicyDecision.DENY,
                BusinessHoursPolicy.forZone("Europe/Rome").evaluate(ctx("critical", SATURDAY, LocalTime.of(12, 0))));
    }

    @Test
    void criticalResource_atExactlyTheBoundaries() {
        BusinessHoursPolicy policy = BusinessHoursPolicy.forZone("Europe/Rome");
        assertEquals(PolicyDecision.PERMIT, policy.evaluate(ctx("critical", WEDNESDAY, LocalTime.of(9, 0))),
                "start boundary is inclusive");
        assertEquals(PolicyDecision.DENY, policy.evaluate(ctx("critical", WEDNESDAY, LocalTime.of(18, 0))),
                "end boundary is exclusive");
    }

    @Test
    void constructor_rejectsAStartAfterEnd() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> BusinessHoursPolicy.of("Europe/Rome", LocalTime.of(18, 0), LocalTime.of(9, 0),
                        java.util.EnumSet.allOf(java.time.DayOfWeek.class)));
    }
}
