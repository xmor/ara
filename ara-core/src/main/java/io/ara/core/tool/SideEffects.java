package io.ara.core.tool;

/**
 * What kind of side effect a tool has — an axis independent of {@link Reversibility}
 * (ADR-0067 D1). No prior decision in this backlog constrains this one, so it stays a
 * plain enum.
 */
public enum SideEffects {
    /** Pure: no writes, no external calls. */
    NONE,
    /** Writes only to local state the runtime owns. */
    LOCAL_WRITE,
    /** Reads from an external system, no external writes. */
    EXTERNAL_READ,
    /** Writes to an external system. */
    EXTERNAL_WRITE
}
