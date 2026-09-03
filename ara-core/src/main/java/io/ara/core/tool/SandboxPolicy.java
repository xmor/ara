package io.ara.core.tool;

import java.util.List;
import java.util.Objects;

/**
 * The isolation a tool must run under (ADR-0067 D3). Mandatory on a
 * {@link ToolOrigin#SYNTHESIZED} tool's {@link ToolSpec}, absent ({@code null}) on a
 * {@link ToolOrigin#BUILTIN} one.
 *
 * <p>This ADR declares the <em>shape</em>; the actual enforcement (denying the network,
 * capping CPU/memory) is ADR-0084's job.
 *
 * @param network        network access; default {@link Network#DENY} is the non-negotiable
 *                       "network denied by default" of source §3.2.6
 * @param allowedDomains when {@code network == ALLOW}, the domains the tool may reach; ignored otherwise
 * @param fsScope        a filesystem path the tool is confined to, or {@code null} for no filesystem access
 * @param timeoutSeconds hard wall-clock cap; {@code > 0}
 * @param memMb          memory cap in MiB; {@code > 0}
 */
public record SandboxPolicy(
        Network      network,
        List<String> allowedDomains,
        String       fsScope,
        int          timeoutSeconds,
        int          memMb
) {

    public enum Network { DENY, ALLOW }

    public SandboxPolicy {
        network = Objects.requireNonNullElse(network, Network.DENY);
        allowedDomains = List.copyOf(Objects.requireNonNullElse(allowedDomains, List.of()));
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("timeoutSeconds must be > 0, got: " + timeoutSeconds);
        if (memMb <= 0) throw new IllegalArgumentException("memMb must be > 0, got: " + memMb);
    }

    /** Network denied, no filesystem, with the given resource caps — the default posture for a synthesized tool. */
    public static SandboxPolicy denied(int timeoutSeconds, int memMb) {
        return new SandboxPolicy(Network.DENY, List.of(), null, timeoutSeconds, memMb);
    }
}
