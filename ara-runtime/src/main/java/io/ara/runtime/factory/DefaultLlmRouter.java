package io.ara.runtime.factory;

import io.ara.core.llm.LlmConfig;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.LlmSelectionPolicy;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmClientFactory;
import io.ara.core.llm.LlmRouter;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.runtime.llm.LoggingLlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default implementation of {@link LlmRouter} (ADR-030).
 *
 * <p>Resolves the {@link LlmClient} to use for a given call by applying the
 * agent's {@link LlmSelectionPolicy}:
 * <ul>
 *   <li>{@code PRIMARY_ONLY}       — always use the primary client; no fallback</li>
 *   <li>{@code FAILOVER}           — try primary; on exception try each fallback in order</li>
 *   <li>{@code ROUND_ROBIN}        — distribute calls sequentially across all profiles</li>
 * </ul>
 *
 * <p>Client resolution order for each profile:
 * <ol>
 *   <li>Inline transport override: {@code profile.inlineTransport() != null} → {@link LlmClientFactory}</li>
 *   <li>Registry lookup by {@code transportId}</li>
 *   <li>Default registered client</li>
 * </ol>
 */
public final class DefaultLlmRouter implements LlmRouter {

    private static final Logger log = LoggerFactory.getLogger(DefaultLlmRouter.class);

    private final Map<String, LlmClient> registry;
    private final String                 defaultClientId;
    private final LlmClientFactory       clientFactory;
    private final AraTelemetry           telemetry;
    private final AtomicInteger          roundRobinIndex = new AtomicInteger(0);

    public DefaultLlmRouter(Map<String, LlmClient> registry,
                            String defaultClientId,
                            LlmClientFactory clientFactory) {
        this(registry, defaultClientId, clientFactory, AraTelemetry.noop());
    }

    /**
     * @param telemetry records the failover chain this router builds for reflection calls
     *                  ({@link FailoverLlmClient}); {@link AraTelemetry#noop()} when the
     *                  deployment does not trace.
     */
    public DefaultLlmRouter(Map<String, LlmClient> registry,
                            String defaultClientId,
                            LlmClientFactory clientFactory,
                            AraTelemetry telemetry) {
        this(registry, defaultClientId, clientFactory, telemetry, true);
    }

    /**
     * A router that reads {@code liveRegistry} on every call instead of a snapshot, so a client
     * added to the map later is found under its id. The caller owns the map and must make it
     * safe for concurrent reads and writes.
     */
    public static DefaultLlmRouter over(Map<String, LlmClient> liveRegistry,
                                        String defaultClientId,
                                        LlmClientFactory clientFactory,
                                        AraTelemetry telemetry) {
        return new DefaultLlmRouter(liveRegistry, defaultClientId, clientFactory, telemetry, false);
    }

    private DefaultLlmRouter(Map<String, LlmClient> registry,
                             String defaultClientId,
                             LlmClientFactory clientFactory,
                             AraTelemetry telemetry,
                             boolean snapshot) {
        Objects.requireNonNull(registry);
        this.registry        = snapshot ? Map.copyOf(registry) : registry;
        this.defaultClientId = Objects.requireNonNull(defaultClientId);
        this.clientFactory   = clientFactory;
        this.telemetry       = Objects.requireNonNull(telemetry, "telemetry must not be null");
    }

    @Override
    public LlmClient select(LlmConfig config, LlmCallContext callContext) {
        Objects.requireNonNull(config, "LlmConfig must not be null");

        LlmSelectionPolicy policy   = config.policy();
        List<LlmProfile>   all      = config.allProfiles();

        LlmClient client = switch (policy) {

            case FAILOVER -> {
                List<LlmClient> ordered = all.stream()
                        .map(p -> resolve(p, config))
                        .toList();
                yield ordered.size() == 1
                        ? ordered.get(0)
                        : new FailoverLlmClient(ordered, telemetry);
            }

            case ROUND_ROBIN -> {
                // floorMod keeps idx non-negative even after the AtomicInteger wraps at 2^31
                int idx = Math.floorMod(roundRobinIndex.getAndIncrement(), all.size());
                yield resolve(all.get(idx), config);
            }

            default -> resolve(config.primary(), config);
        };

        return config.logIo() ? new LoggingLlmClient(client, config.logIoMaxChars()) : client;
    }

    private LlmClient resolve(LlmProfile profile, LlmConfig config) {
        // 1. Inline transport override (ADR-039 §3 asse A)
        if (clientFactory != null && profile.inlineTransport() != null) {
            log.debug("LlmRouter: using inline override model={}", profile.inlineTransport().modelName());
            return clientFactory.create(profile.inlineTransport());
        }

        // 2. Registry lookup by transportId
        String transportId = profile.transportId();
        if (transportId != null && !transportId.isBlank()) {
            LlmClient named = registry.get(transportId);
            if (named != null) {
                log.debug("LlmRouter: resolved '{}' from registry", transportId);
                return named;
            }
        }

        // 3. Default client
        LlmClient def = registry.get(defaultClientId);
        if (def != null) {
            log.debug("LlmRouter: using default client '{}'", defaultClientId);
            return def;
        }

        throw new IllegalStateException(
                "No LlmClient found for transportId='" + transportId
                + "' and no default registered (defaultClientId='" + defaultClientId + "')");
    }
}
