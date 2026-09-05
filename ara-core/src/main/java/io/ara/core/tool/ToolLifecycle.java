package io.ara.core.tool;

/**
 * The lifecycle phase of a {@link ToolSpec} (ADR-0084 D5). Mirrors the {@code SpecStatus}
 * vocabulary of ADR-0065 — {@code draft}/{@code shadow}/{@code canary}/{@code default}/
 * {@code deprecated} — rather than a tool-specific taxonomy (ADR-0063 D4). Defined as a
 * plain enum here because {@code SpecStatus} lives in the meta-agent module, which
 * {@code ara-core} cannot depend on.
 *
 * <p>For a synthesized tool (ADR-0063 D4): {@link #DRAFT} is "generated, run in the
 * sandbox, tests passed — not yet human-reviewed". Advancement past {@code DRAFT} is the
 * ADR-0083 pipeline applied to a {@code ToolSpec}, where "sign-off" means the number of
 * independent approvals ADR-0084 D3 requires.
 */
public enum ToolLifecycle {
    DRAFT,
    SHADOW,
    CANARY,
    DEFAULT,
    DEPRECATED
}
