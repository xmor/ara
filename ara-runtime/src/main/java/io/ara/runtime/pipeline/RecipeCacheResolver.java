package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AraAgent;
import io.ara.core.spec.SpecArchive;

import java.util.Objects;
import java.util.function.Function;

/**
 * The recipe-cache fast-path (ADR-0072) as a third {@link ClassifyAndActSpec.AgentResolver}
 * implementation — <em>not</em> a new mechanism. ADR-050's classify-and-act already
 * resolves worker labels through a pluggable {@code AgentResolver} ({@code of(Map)} /
 * {@code byId(registry)}); this one resolves a {@code task_class} label to an
 * {@link AraAgent} built from the promoted variant archived for that class.
 *
 * <ul>
 *   <li><b>Cache hit</b> — {@link SpecArchive#bestFor} returns a config: the label is
 *       {@code MATCHED} in {@code IntentRouter.Reason}'s existing vocabulary and the
 *       pipeline routes to the built worker (state isolation, original input, pipeline
 *       termination — all guaranteed by {@code worker(...)} already). ADR-0072 D2.</li>
 *   <li><b>Cache miss</b> — {@code resolve} returns {@code null}, so the label falls to
 *       the router's mandatory {@code else} arc, which the spec points at the full
 *       factory pass (ADR-0075). ADR-0072 D2.</li>
 * </ul>
 *
 * <p>Only promoted variants resolve a hit — that is {@link SpecArchive#bestFor}'s
 * contract (ADR-0072 D4), not a check here.
 *
 * <p><b>Not wired here</b>: the {@code routing.recipe_cache_hit} span attribute (ADR-0072
 * D5) lives in the pipeline's {@code pipeline.classify} emission, a follow-up; and the
 * archive is only populated once the evolution loop and ADR-0082 exist.
 */
public final class RecipeCacheResolver implements ClassifyAndActSpec.AgentResolver {

    private final SpecArchive archive;
    private final Function<AgentConfig, AraAgent> agentFactory;

    /**
     * @param archive      the promoted-variant archive (ADR-0082; {@link SpecArchive#inMemory()} for now)
     * @param agentFactory builds a runnable agent from a config — typically {@code runtime::createAgent}
     */
    public RecipeCacheResolver(SpecArchive archive, Function<AgentConfig, AraAgent> agentFactory) {
        this.archive      = Objects.requireNonNull(archive, "archive must not be null");
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory must not be null");
    }

    @Override
    public AraAgent resolve(String ref) {
        return archive.bestFor(ref).map(agentFactory).orElse(null);
    }

    /**
     * Whether {@code ref} (a {@code task_class} label) resolves to a promoted archived
     * variant — i.e. whether routing to it is a recipe-cache hit (ADR-0072 D5). Pure: no
     * agent is built. {@code ClassifyAndActSpec} calls this at bind time to tag the
     * {@code pipeline.classify} span's {@code routing.recipe_cache_hit} attribute.
     */
    public boolean isCacheHit(String ref) {
        return ref != null && archive.bestFor(ref).isPresent();
    }
}
