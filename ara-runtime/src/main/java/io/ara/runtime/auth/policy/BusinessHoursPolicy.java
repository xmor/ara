package io.ara.runtime.auth.policy;

import io.ara.core.auth.AbacPolicy;
import io.ara.core.auth.PolicyDecision;
import io.ara.core.auth.PolicyEvaluationContext;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Denies an action on a {@code dataClassification = "critical"} resource outside a
 * configured business-hours window (ADR-033 Fase 2b, S8 —
 * `docs/adr/ADR-033-implementation-plan.md` §2b.3, `ara-private`). Abstains for any
 * resource not classified {@code "critical"} — nothing to check.
 *
 * <p>Reads {@link io.ara.core.auth.EnvironmentAttributes#timestamp()} rather than {@code
 * Instant.now()}, so a decision is reproducible and testable without mocking the clock.
 */
public final class BusinessHoursPolicy implements AbacPolicy {

    private static final String CRITICAL = "critical";

    private final ZoneId zone;
    private final LocalTime startInclusive;
    private final LocalTime endExclusive;
    private final Set<DayOfWeek> businessDays;

    private BusinessHoursPolicy(ZoneId zone, LocalTime startInclusive, LocalTime endExclusive,
                                 Set<DayOfWeek> businessDays) {
        this.zone           = Objects.requireNonNull(zone, "zone must not be null");
        this.startInclusive = Objects.requireNonNull(startInclusive, "startInclusive must not be null");
        this.endExclusive   = Objects.requireNonNull(endExclusive, "endExclusive must not be null");
        this.businessDays   = Set.copyOf(businessDays);
        if (!startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("startInclusive must be before endExclusive");
        }
    }

    /** 09:00-18:00, Monday-Friday, in {@code zoneId} — the common default. */
    public static BusinessHoursPolicy forZone(String zoneId) {
        return new BusinessHoursPolicy(ZoneId.of(zoneId), LocalTime.of(9, 0), LocalTime.of(18, 0),
                EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
    }

    /** Fully custom window — e.g. a deployment with shorter hours or weekend coverage. */
    public static BusinessHoursPolicy of(String zoneId, LocalTime startInclusive, LocalTime endExclusive,
                                          Set<DayOfWeek> businessDays) {
        return new BusinessHoursPolicy(ZoneId.of(zoneId), startInclusive, endExclusive, businessDays);
    }

    @Override
    public PolicyDecision evaluate(PolicyEvaluationContext ctx) {
        if (!CRITICAL.equalsIgnoreCase(ctx.resource().dataClassification())) {
            return PolicyDecision.NOT_APPLICABLE;
        }
        ZonedDateTime at = ctx.environment().timestamp().atZone(zone);
        boolean withinWindow = businessDays.contains(at.getDayOfWeek())
                && !at.toLocalTime().isBefore(startInclusive)
                && at.toLocalTime().isBefore(endExclusive);
        return withinWindow ? PolicyDecision.PERMIT : PolicyDecision.DENY;
    }
}
