package io.ara.core.spec;

import io.ara.core.agent.AgentConfig;

import java.util.List;
import java.util.Objects;

/**
 * A behaviourally content-addressed agent definition: an {@link AgentConfig} paired with
 * the evolutionary {@link SpecLineage} that tracks its hash, its depth in a derivation
 * chain, and its lifecycle status, plus the few-shot examples it pulls from a prompt
 * catalog.
 *
 * <p><b>A wrapper, not an extension</b> (ADR-0065 D1). {@code AgentConfig} is a public
 * record with a fixed constructor arity, published as {@code io.github.xmor:ara-core}.
 * Adding a component to carry hash/version/status/few-shot refs would break every caller
 * that constructs it directly. Wrapping costs those callers nothing: {@code AgentConfig}
 * is untouched, and code that doesn't use versioned specs never sees this type.
 *
 * <p>Instances are created through {@link #root(AgentConfig)} (a new lineage) or
 * {@link #derive(AgentConfig)} (a child of an existing spec); the canonical constructor
 * is for deserialisation and copying. {@link #withStatus(SpecStatus)} moves a spec
 * through its lifecycle, and {@link #withFewShotRefs(List)} / {@link #withSchemaRef(String)} /
 * {@link #withOutputSchemaRef(String)} / {@link #withOutputRepairAttempts(int)} change the overlay
 * axes — none of them touches the hash or the version (ADR-0065 D5, ADR-0066 D6, ADR-0077 D1).
 *
 * <h2>The three overlay axes</h2>
 * {@code fewShotRefs} holds {@code entryId}s of few-shot example rows in a prompt
 * catalog (ADR-0066 D6); {@code schemaRef} holds the {@code entryId} of a JSON-Schema
 * catalog entry whose schema becomes the agent's {@code AgentContract.inputSchema}
 * (ADR-0077 D1, axis 5); {@code outputSchemaRef} is its mirror for the answer: the
 * {@code entryId} of the catalog entry whose JSON Schema becomes
 * {@code AgentContract.outputSchema}, is appended to the system prompt, and is enforced
 * on what the agent answers. The catalogs behind these ids live outside this module —
 * the spec carries only the references, so what an agent depends on stays decidable
 * from its id. None of them is part
 * of the behavioural hash — {@link SpecLineage#specHash()} stays a pure function of
 * {@code config} — and all are resolved separately, at {@code createAgent()}, not when the
 * {@code AgentSpec} is built (which stays an I/O-free, cheap operation).
 *
 * <p>{@code outputRepairAttempts} belongs to the output side with {@code outputSchemaRef}: how many
 * times an answer the schema rejects is sent back to the agent for correction before the rejection
 * fails the task ({@code AgentContract.outputRepairAttempts()}, {@code 0} = off). It is a number,
 * not a reference, so it has no catalog behind it — but it is just as much a part of <em>what runs</em>
 * (it changes the cost and the success rate), which is why it moves {@link #effectiveHash()} too.
 *
 * <p><b>{@code outputSchemaRef} and {@code nativeJsonSchema} exclude each other.</b> The runtime
 * delivers an output schema to the model by appending it to the system prompt, and only when the
 * agent's LLM profile does not ask for the provider-native path; no adapter implements that path,
 * so an agent with both would fail at {@code createAgent()} with a message about a flag. The
 * constructor refuses the combination instead — at the moment a spec is built, not at the first
 * task of a candidate that a derivation pipeline has already paid to propose.
 *
 * <p><b>Why {@link #effectiveHash()} then exists.</b> "Not part of the behavioural
 * identity" and "a mutation axis a derivation pipeline proposes on" (ADR-0055 D2, axes 1 and
 * 5) pull in opposite directions: a candidate that changes only an overlay ref has the
 * same {@code specHash} as its own baseline, so any pipeline keyed by {@code specHash} alone
 * — candidate materialisation, agent caches, archived variants —
 * would resolve the <em>baseline's</em> cached agent and silently measure it as if it were
 * the candidate. {@link #effectiveHash()} is the identity of <em>what actually runs</em>:
 * equal to {@link #specHash()} whenever no overlay is set (so nothing changes for the five
 * config-shaped axes), and a distinct, deterministic id as soon as one is. Derivation
 * pipelines key on it; lineage, promotion and publication keep keying on {@link #specHash()}.
 */
public record AgentSpec(
        AgentConfig  config,
        SpecLineage  lineage,
        List<String> fewShotRefs,
        String       schemaRef,
        String       outputSchemaRef,
        int          outputRepairAttempts
) {

    public AgentSpec {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(lineage, "lineage must not be null");
        fewShotRefs = List.copyOf(Objects.requireNonNullElse(fewShotRefs, List.of()));
        // schemaRef / outputSchemaRef stay nullable: declaring no schema is the common case.
        if (outputRepairAttempts < 0) {
            throw new IllegalArgumentException("outputRepairAttempts must be >= 0, was " + outputRepairAttempts);
        }
    }

    /**
     * The shape before {@code outputSchemaRef}, kept so every existing direct
     * {@code new AgentSpec(...)} call keeps compiling, with no output schema.
     */
    public AgentSpec(AgentConfig config, SpecLineage lineage, List<String> fewShotRefs, String schemaRef) {
        this(config, lineage, fewShotRefs, schemaRef, null, 0);
    }

    /**
     * The shape before {@code outputRepairAttempts}, kept so every existing direct
     * {@code new AgentSpec(...)} call keeps compiling, with repair off.
     */
    public AgentSpec(AgentConfig config, SpecLineage lineage, List<String> fewShotRefs, String schemaRef,
                     String outputSchemaRef) {
        this(config, lineage, fewShotRefs, schemaRef, outputSchemaRef, 0);
    }

    /**
     * A new spec with no parent — the root of a lineage. Its {@code specVersion} is 1,
     * its {@code derivedFrom} is {@code null}, its status is {@link SpecStatus.Draft}, and
     * it references no few-shot examples.
     *
     * @throws IllegalArgumentException if {@code config.agentType()} is blank or
     *                                  {@code "generic"} (ADR-0065 D4)
     */
    public static AgentSpec root(AgentConfig config) {
        SpecLineage.validate(config);
        return new AgentSpec(config, SpecLineage.root(config), List.of(), null, null, 0);
    }

    /**
     * A spec derived from this one by swapping in {@code newConfig}: a fresh hash,
     * {@code this.specVersion + 1}, {@code derivedFrom} pointing at this spec's hash,
     * status reset to {@link SpecStatus.Draft}. The few-shot references carry over
     * unchanged — {@code derive} moves the config axis, not the few-shot axis
     * (ADR-0055 D2); use {@link #withFewShotRefs(List)} for the latter.
     *
     * @throws IllegalArgumentException if {@code newConfig.agentType()} is blank or
     *                                  {@code "generic"} (ADR-0065 D4)
     */
    public AgentSpec derive(AgentConfig newConfig) {
        SpecLineage.validate(newConfig);
        return new AgentSpec(newConfig, lineage.deriveFrom(this, newConfig), fewShotRefs, schemaRef, outputSchemaRef,
                outputRepairAttempts);
    }

    /**
     * The same spec in a different lifecycle phase: identical {@code config},
     * {@code fewShotRefs}, {@code specHash} and {@code specVersion}, only
     * {@link SpecLineage#status()} changed (ADR-0065 D5). A promotion from {@code draft}
     * to {@code shadow}/{@code canary}/{@code default} is not a new version.
     */
    public AgentSpec withStatus(SpecStatus newStatus) {
        return new AgentSpec(config, lineage.withStatus(newStatus), fewShotRefs, schemaRef, outputSchemaRef,
                outputRepairAttempts);
    }

    /**
     * The same spec with a different set of few-shot references — the one unconditionally
     * AUTONOMOUS axis (ADR-0055 D2, ADR-0066 D6). {@code config}, {@code specHash},
     * {@code specVersion} and {@code status} are all unchanged: few-shot refs are not part
     * of the behavioural identity.
     */
    public AgentSpec withFewShotRefs(List<String> newFewShotRefs) {
        return new AgentSpec(config, lineage, newFewShotRefs, schemaRef, outputSchemaRef, outputRepairAttempts);
    }

    /**
     * The same spec pointing at a different input-schema entry — axis 5 (ADR-0077 D1,
     * {@code REVISIONE} per ADR-0055 D2). Like {@link #withFewShotRefs(List)} this is an
     * overlay change: {@code config}, {@code specHash}, {@code specVersion} and
     * {@code status} are all unchanged, and only {@link #effectiveHash()} moves.
     *
     * @param newSchemaRef {@code entryId} of a {@code SchemaCatalogEntry}, or {@code null}
     *                     to declare no input schema
     */
    public AgentSpec withSchemaRef(String newSchemaRef) {
        return new AgentSpec(config, lineage, fewShotRefs, newSchemaRef, outputSchemaRef, outputRepairAttempts);
    }

    /**
     * The same spec pointing at a different output-schema entry — the answer-side twin of
     * {@link #withSchemaRef(String)}, and like it an overlay change: {@code config},
     * {@code specHash}, {@code specVersion} and {@code status} are all unchanged, and only
     * {@link #effectiveHash()} moves.
     *
     * @param newOutputSchemaRef {@code entryId} of a {@code SchemaCatalogEntry}, or {@code null}
     *                           to declare no output schema
     * @throws IllegalArgumentException if the config asks for {@code nativeJsonSchema(true)} —
     *                                  see the class javadoc
     */
    public AgentSpec withOutputSchemaRef(String newOutputSchemaRef) {
        return new AgentSpec(config, lineage, fewShotRefs, schemaRef, newOutputSchemaRef, outputRepairAttempts);
    }

    /**
     * The same spec with a different number of repair attempts for a rejected answer — an overlay
     * change like the others: {@code config}, {@code specHash}, {@code specVersion} and
     * {@code status} are unchanged, and only {@link #effectiveHash()} moves.
     *
     * @param attempts how many times a rejected answer is sent back for correction; {@code 0} = off
     * @throws IllegalArgumentException if {@code attempts} is negative
     */
    public AgentSpec withOutputRepairAttempts(int attempts) {
        return new AgentSpec(config, lineage, fewShotRefs, schemaRef, outputSchemaRef, attempts);
    }

    /** Shorthand for {@code lineage().specHash()} — the behavioural identity of this spec. */
    public String specHash() {
        return lineage.specHash();
    }

    /**
     * The identity of <em>what actually runs</em>: {@link #specHash()} when no overlay ref
     * is set, and {@code specHash + "+" + digest} as soon as {@link #fewShotRefs()},
     * {@link #schemaRef()} or {@link #outputSchemaRef()} is — see the class javadoc for why the
     * evolution cycle needs an identifier the behavioural hash cannot provide.
     *
     * <p>Deterministic and order-sensitive on {@code fewShotRefs} (the order examples are
     * shown in is itself part of what runs), and it keeps the {@code specHash} prefix so a
     * candidate id remains readable back to the config it derives from.
     */
    public String effectiveHash() {
        if (fewShotRefs.isEmpty() && schemaRef == null && outputSchemaRef == null && outputRepairAttempts == 0) {
            return lineage.specHash();
        }
        return lineage.specHash() + "+"
                + SpecLineage.overlayDigest(fewShotRefs, schemaRef, outputSchemaRef, outputRepairAttempts);
    }
}
