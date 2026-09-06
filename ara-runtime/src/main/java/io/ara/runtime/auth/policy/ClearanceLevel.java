package io.ara.runtime.auth.policy;

import java.util.Locale;

/**
 * The clearance hierarchy {@link ClearancePolicy} enforces (ADR-033 Fase 2b, S8 —
 * `docs/adr/ADR-033-implementation-plan.md` §2b.3, `ara-private`): {@code STANDARD} <
 * {@code SENSITIVE} < {@code CONFIDENTIAL} < {@code SECRET}, in declaration order so
 * {@link Enum#compareTo} is the comparison — no separate ordinal table to keep in sync.
 */
public enum ClearanceLevel {
    STANDARD,
    SENSITIVE,
    CONFIDENTIAL,
    SECRET;

    /**
     * Defensive parse of a free-form clearance string (see {@code SubjectAttributes
     * .clearanceLevel()}/{@code ResourceAttributes.requiredClearance()}, both plain
     * {@code String} so a deployment can use its own vocabulary): {@code null}, blank, or
     * unrecognized text all parse to {@link #STANDARD} — the lowest tier, never the
     * highest, so a caller with malformed or missing clearance data is never accidentally
     * treated as more trusted than it declared itself to be.
     */
    public static ClearanceLevel parse(String value) {
        if (value == null || value.isBlank()) {
            return STANDARD;
        }
        try {
            return ClearanceLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return STANDARD;
        }
    }
}
