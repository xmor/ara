package io.ara.core.spec;

import io.ara.core.agent.AgentConfig;
import io.ara.core.llm.LlmProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code outputSchemaRef}: the answer-side twin of {@code schemaRef} — an overlay that moves
 * {@link AgentSpec#effectiveHash()} and nothing else, carried through every copy, and legal
 * together with {@code nativeJsonSchema(true)} now that adapters send a native
 * {@code response_format}.
 */
class AgentSpecOutputSchemaRefTest {

    private static AgentConfig.Builder builder() {
        return AgentConfig.defaults()
                .agentType("analyst")
                .systemPrompt("You analyse quarterly filings.")
                .primaryLlm(LlmProfile.of("test-model"));
    }

    private static AgentSpec base() {
        return AgentSpec.root(builder().build());
    }

    @Test
    void aRootSpecDeclaresNoOutputSchema() {
        assertNull(base().outputSchemaRef());
    }

    @Test
    void withOutputSchemaRef_setsIt_andTouchesNothingElse() {
        AgentSpec base = base().withStatus(new SpecStatus.Canary())
                .withFewShotRefs(List.of("fs-1")).withSchemaRef("in-1");

        AgentSpec with = base.withOutputSchemaRef("out-1");

        assertEquals("out-1", with.outputSchemaRef());
        assertEquals(base.config(), with.config());
        assertEquals(base.lineage(), with.lineage(), "hash, version and status are untouched");
        assertEquals(base.specHash(), with.specHash());
        assertEquals(List.of("fs-1"), with.fewShotRefs());
        assertEquals("in-1", with.schemaRef());
    }

    @Test
    void withOutputSchemaRef_null_removesIt() {
        AgentSpec with = base().withOutputSchemaRef("out-1");

        assertNull(with.withOutputSchemaRef(null).outputSchemaRef());
        assertEquals(with.specHash(), with.withOutputSchemaRef(null).effectiveHash());
    }

    // ── effectiveHash ─────────────────────────────────────────────────────────

    @Test
    void effectiveHash_isTheSpecHash_whenNoOverlayIsSet() {
        AgentSpec spec = base();

        assertEquals(spec.specHash(), spec.effectiveHash());
    }

    @Test
    void effectiveHash_distinguishesAnOutputSchemaCandidateFromItsOwnBaseline() {
        AgentSpec base = base();
        AgentSpec candidate = base.withOutputSchemaRef("out-1");

        assertEquals(base.specHash(), candidate.specHash(), "the behavioural hash does not move");
        assertNotEquals(base.effectiveHash(), candidate.effectiveHash());
        assertTrue(candidate.effectiveHash().startsWith(base.specHash() + "+"),
                "the id stays readable back to the config it derives from");
    }

    @Test
    void effectiveHash_differsBetweenTwoOutputSchemas_andIsDeterministic() {
        AgentSpec base = base();

        assertNotEquals(base.withOutputSchemaRef("out-1").effectiveHash(),
                base.withOutputSchemaRef("out-2").effectiveHash());
        assertEquals(base.withOutputSchemaRef("out-1").effectiveHash(),
                base.withOutputSchemaRef("out-1").effectiveHash());
    }

    @Test
    void theSameEntryIdAsInputAndAsOutput_isNotTheSameCandidate() {
        AgentSpec base = base();

        assertNotEquals(base.withSchemaRef("shared").effectiveHash(),
                base.withOutputSchemaRef("shared").effectiveHash(),
                "the two axes must not collide on a shared entry id");
    }

    @Test
    void anInputOnlySpec_hasTheSameEffectiveHashItAlwaysHad() {
        AgentSpec base = base();   // once: every base() mints a fresh random agentId
        AgentSpec viaFourArg = new AgentSpec(base.config(), base.lineage(), List.of("fs-1"), "in-1");
        AgentSpec viaFiveArg = new AgentSpec(base.config(), base.lineage(), List.of("fs-1"), "in-1", null);

        assertEquals(viaFourArg.effectiveHash(), viaFiveArg.effectiveHash());
        assertEquals(viaFourArg, viaFiveArg);
    }

    // ── carried through every copy ────────────────────────────────────────────

    @Test
    void everyCopyCarriesTheOutputSchemaRef() {
        AgentSpec spec = base().withOutputSchemaRef("out-1");

        assertEquals("out-1", spec.withStatus(new SpecStatus.Shadow()).outputSchemaRef());
        assertEquals("out-1", spec.withFewShotRefs(List.of("fs-9")).outputSchemaRef());
        assertEquals("out-1", spec.withSchemaRef("in-9").outputSchemaRef());
    }

    @Test
    void derive_carriesTheOutputSchemaRef_likeTheOtherOverlays() {
        AgentSpec spec = base().withOutputSchemaRef("out-1");

        AgentSpec child = spec.derive(builder().systemPrompt("A different instruction.").build());

        assertEquals("out-1", child.outputSchemaRef());
        assertNotEquals(spec.specHash(), child.specHash());
    }

    // ── nativeJsonSchema ──────────────────────────────────────────────────────

    private static AgentConfig nativeConfig() {
        return builder().primaryLlm(LlmProfile.builder().nativeJsonSchema(true).build()).build();
    }

    @Test
    void anOutputSchemaWithNativeJsonSchema_isAllowed() {
        // Adapters now send a provider-native response_format, so the combination is legal:
        // the schema reaches the model through the request instead of the system prompt. A
        // client that cannot do it fails the call naming the capability (see the adapters'
        // supportsNativeStructuredOutput), rather than the spec forbidding it up front.
        AgentSpec nativeSpec = AgentSpec.root(nativeConfig());

        AgentSpec withSchema = assertDoesNotThrow(() -> nativeSpec.withOutputSchemaRef("out-1"));
        assertEquals("out-1", withSchema.outputSchemaRef());
        assertTrue(withSchema.config().nativeJsonSchema());
    }

    @Test
    void derivingIntoNativeJsonSchema_isAllowedWhenTheSpecCarriesAnOutputSchema() {
        AgentSpec spec = base().withOutputSchemaRef("out-1");

        AgentSpec derived = assertDoesNotThrow(() -> spec.derive(nativeConfig()));
        assertEquals("out-1", derived.outputSchemaRef());
        assertTrue(derived.config().nativeJsonSchema());
    }

    @Test
    void nativeJsonSchemaWithoutAnOutputSchema_isFine() {
        assertNull(AgentSpec.root(nativeConfig()).outputSchemaRef());
        assertDoesNotThrow(() -> AgentSpec.root(nativeConfig()).withSchemaRef("in-1"));
    }
}
