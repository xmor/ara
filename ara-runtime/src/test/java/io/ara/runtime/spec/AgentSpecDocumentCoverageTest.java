package io.ara.runtime.spec;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentIdentity;
import io.ara.core.agent.ExecutionConfig;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.common.Money;
import io.ara.core.llm.LlmConfig;
import io.ara.core.llm.LlmProfile;
import io.ara.core.memory.MemoryConfig;
import io.ara.core.spec.AgentSpec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Makes "a field was added to {@code AgentConfig} and the document forgot it" a build
 * failure instead of a silent loss on export.
 *
 * <p>Checklist: when one of these tests fails because a record gained or lost a component,
 * (1) read or write the field in the matching section of {@code AgentSpecDocument}, (2) set
 * it to a non-default value in {@link AgentSpecDocumentTest#fullSpec()}, (3) update the
 * expected list below, and (4) if the field is a new <em>document</em> field, increment
 * {@code AgentSpecDocument.SCHEMA_VERSION}.
 *
 * <p>This test reads record components by reflection. That is deliberate and confined to
 * test code: the production codec stays free of it, and there is no other way to ask a
 * record what it contains.
 */
class AgentSpecDocumentCoverageTest {

    /**
     * The components each record has today. A component deliberately left out of the document
     * is named in {@link #NOT_IN_DOCUMENT} with its reason.
     */
    private static final Map<Class<?>, List<String>> COMPONENTS = Map.ofEntries(
            Map.entry(AgentConfig.class, List.of("identity", "llm", "execution", "memory")),
            Map.entry(AgentIdentity.class, List.of("agentId", "agentType", "name", "description", "version",
                    "tags", "systemPrompt", "promptCatalogId")),
            Map.entry(LlmConfig.class, List.of("primary", "fallbacks", "policy", "logIo", "logIoMaxChars")),
            Map.entry(LlmProfile.class, List.of("transportId", "inlineTransport", "temperature", "topP",
                    "maxTokens", "costBudget", "costCurrency", "streamingEnabled", "nativeJsonSchema",
                    "costInputPer1kTokens", "costOutputPer1kTokens", "pinnedTransportVersion",
                    "reasoningEffort", "thinkingBudgetTokens", "returnReasoning")),
            Map.entry(ExecutionConfig.class, List.of("plannerStrategy", "strategyConfig", "enabledTools",
                    "mcpServerIds", "maxIterations", "executionTimeout", "maxTokensPerStep",
                    "humanApprovalRequired", "knowledgeBaseId", "sessionBusyPolicy", "retrieverId",
                    "delegateStateAccess", "sessionTtl", "grantedScopes", "visibleToScopes",
                    "requiredScopes", "requiresApproval")),
            Map.entry(MemoryConfig.class, List.of("workingMemoryTokenBudget", "workingMemoryEviction",
                    "maxConversationTurns", "maxReflections", "reflectionPrompt", "contextSummarizerAgentId")),
            Map.entry(StrategyConfig.PlanExecute.class, List.of("replanPolicy", "maxPlanSteps", "maxStepRoundsPerStep",
                    "maxParallelSteps")),
            Map.entry(StrategyConfig.Reflexion.class, List.of("maxReflections", "reflectionPrompt", "reflectionProvider")),
            Map.entry(StrategyConfig.ReflAct.class, List.of("maxReflections", "unproductiveStreak",
                    "reflectOnToolFailure", "reflectionProvider")),
            Map.entry(StrategyConfig.Custom.class, List.of("strategyName", "params")),
            Map.entry(Money.class, List.of("amount", "currency")),
            Map.entry(AgentSpec.class, List.of("config", "lineage", "fewShotRefs", "schemaRef", "outputSchemaRef",
                    "outputRepairAttempts")));

    /** Components the document intentionally does not carry, and why. */
    private static final Map<String, String> NOT_IN_DOCUMENT = Map.of(
            "LlmProfile.inlineTransport", "an inline endpoint would put a key in a file; encoding it fails instead",
            "AgentSpec.lineage", "evolution state, not part of a definition; an import is always a fresh root");

    /**
     * Components {@code fullSpec()} cannot set to a non-default value, with the test that
     * covers them instead.
     */
    private static final Map<String, String> COVERED_ELSEWHERE = Map.of(
            "ExecutionConfig.retrieverId", "needs a rag+ strategy; see roundTrip_theFieldsFullSpecCannotSet",
            "LlmProfile.nativeJsonSchema", "excludes outputSchemaRef; see roundTrip_theFieldsFullSpecCannotSet",
            "LlmProfile.inlineTransport", "not in the document",
            "AgentSpec.lineage", "not in the document");

    private static List<String> namesOf(Class<?> record) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : record.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    @Test
    void everyRecordTheDocumentTouches_hasExactlyTheComponentsTheCodecKnows() {
        COMPONENTS.forEach((record, expected) -> assertEquals(expected, namesOf(record),
                record.getSimpleName() + " changed: update the codec, fullSpec() and this list"));
    }

    @Test
    void everyComponentOfTheConfig_isSetToANonDefaultValueByFullSpec_soADroppedFieldFailsTheRoundTrip() throws Exception {
        AgentConfig full = AgentSpecDocumentTest.fullSpec().config();
        AgentConfig defaults = AgentConfig.defaults().agentType("baseline").build();

        assertAllDiffer(full.identity(), defaults.identity());
        assertAllDiffer(full.llm(), defaults.llm());
        assertAllDiffer(full.llm().primary(), LlmProfile.builder().build());
        assertAllDiffer(full.execution(), defaults.execution());
        assertAllDiffer(full.memory(), defaults.memory());
    }

    private static void assertAllDiffer(Record full, Record defaults) throws Exception {
        List<String> same = new ArrayList<>();
        for (RecordComponent component : full.getClass().getRecordComponents()) {
            String key = full.getClass().getSimpleName() + "." + component.getName();
            Object fullValue = component.getAccessor().invoke(full);
            Object defaultValue = component.getAccessor().invoke(defaults);
            if (java.util.Objects.equals(fullValue, defaultValue) && !COVERED_ELSEWHERE.containsKey(key)) {
                same.add(key);
            }
        }
        assertTrue(same.isEmpty(), "fullSpec() leaves these at their default, so a codec that dropped them "
                + "would still pass the round trip: " + same);
    }

    @Test
    void theDocumentExclusions_areTheOnesDeclared() {
        Set<String> declared = NOT_IN_DOCUMENT.keySet();
        assertEquals(Set.of("LlmProfile.inlineTransport", "AgentSpec.lineage"), declared);
        assertTrue(Arrays.stream(LlmProfile.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("inlineTransport")));
    }

    @Test
    void roundTrip_theFieldsFullSpecCannotSet() {
        AgentConfig config = AgentConfig.defaults().agentType("a").plannerStrategy("rag+react")
                .retrieverId("docs")
                .primaryLlm(LlmProfile.builder().transportId("m").nativeJsonSchema(true).build())
                .build();
        AgentSpec spec = AgentSpec.root(config);

        AgentSpec back = AgentSpecDocument.decode(AgentSpecDocument.encode(spec));

        assertEquals(config, back.config());
        assertEquals("docs", back.config().execution().retrieverId());
        assertTrue(back.config().nativeJsonSchema());
    }
}
