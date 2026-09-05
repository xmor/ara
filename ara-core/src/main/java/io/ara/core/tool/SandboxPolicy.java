package io.ara.core.tool;

import java.util.List;
import java.util.Objects;

/**
 * The isolation a tool must run under (ADR-0067 D3). Mandatory on a
 * {@link ToolOrigin#SYNTHESIZED} tool's {@link ToolSpec}, absent ({@code null}) on a
 * {@link ToolOrigin#BUILTIN} one.
 *
 * <p>This type only declares the desired constraints; it does not enforce them. The
 * sandbox that actually runs a synthesized tool's tests under these limits (denying the
 * network, capping CPU/memory) is a separate component (ADR-0084), not part of this module.
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

    /** ADR-0084 D2 default: network denied, filesystem scoped to a per-run temp dir, {@code 30}s / {@code 256}MiB. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** ADR-0084 D2 default memory cap in MiB. */
    public static final int DEFAULT_MEM_MB = 256;

    /**
     * The ADR-0084 D2 default a synthesized tool gets when its {@code Proposal.NewToolSynthesis}
     * declares no explicit policy — never "run with no limits because nobody set them".
     */
    public static SandboxPolicy synthesizedDefault(String runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return new SandboxPolicy(Network.DENY, List.of(),
                "/tmp/ara-forge-sandbox/" + runId, DEFAULT_TIMEOUT_SECONDS, DEFAULT_MEM_MB);
    }
}
