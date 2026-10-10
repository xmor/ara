package io.ara.runtime.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.DelegateStateAccess;
import io.ara.core.agent.SessionBusyPolicy;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.common.AgentId;
import io.ara.core.common.Budget;
import io.ara.core.common.Money;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.LlmSelectionPolicy;
import io.ara.core.spec.AgentSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent document's contract: a spec survives a round trip with its whole config intact,
 * a short hand-written document gets the library defaults, and every way a document can be
 * wrong is reported with the path of the field that is wrong.
 */
class AgentSpecDocumentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Every field of the document set to a value that differs from its default. */
    static AgentSpec fullSpec() {
        LlmProfile primary = LlmProfile.builder()
                .transportId("main").pinnedTransportVersion(3L)
                .temperature(0.2).topP(0.9).maxTokens(512)
                .streamingEnabled(true)
                .costCurrency("USD")
                .costInputPer1kTokens(Money.of("0.0015", "USD"))
                .costOutputPer1kTokens(Money.of("0.002", "USD"))
                .costBudget(Budget.limited(Money.of("5", "USD")))
                .reasoningEffort(io.ara.core.llm.ReasoningEffort.MEDIUM).thinkingBudgetTokens(4096)
                .returnReasoning(true)
                .build();
        AgentConfig config = AgentConfig.defaults()
                .agentId(AgentId.of("triage-1")).agentType("support-triage").name("Triage")
                .description("Sorts requests").version("2.1.0").tags(List.of("support", "l1"))
                .systemPrompt("Classify the request.").promptCatalogId("catalog-1")
                .primaryLlm(primary).fallbackLlms(List.of(LlmProfile.of("backup")))
                .llmSelectionPolicy(LlmSelectionPolicy.FAILOVER).logLlmIo(true).logLlmIoMaxChars(99)
                .strategyConfig(new StrategyConfig.PlanExecute("on_failure", 6, 2, 3))
                .enabledTools(List.of("search", "calc")).mcpServerIds(List.of("files"))
                .maxIterations(7).executionTimeout(Duration.ofSeconds(90)).maxTokensPerStep(2048)
                .humanApprovalRequired(true).knowledgeBaseId("kb-1")
                .sessionBusyPolicy(SessionBusyPolicy.ENQUEUE).delegateStateAccess(DelegateStateAccess.SHARED)
                .sessionTtl(Duration.ofMinutes(10))
                .grantedScopes(List.of("tools:read")).visibleToScopes(List.of("team"))
                .requiredScopes(List.of("tenant")).requiresApproval(true)
                .workingMemoryTokenBudget(1000).workingMemoryEviction("drop_oldest")
                .maxConversationTurns(5).maxReflections(4).reflectionPrompt("Reflect.")
                .contextSummarizerAgentId("summariser")
                .build();
        return AgentSpec.root(config)
                .withFewShotRefs(List.of("shot-1", "shot-2")).withSchemaRef("in-v1")
                .withOutputSchemaRef("out-v1").withOutputRepairAttempts(2);
    }

    private static ObjectNode minimalDocument() {
        ObjectNode document = MAPPER.createObjectNode();
        document.put("schemaVersion", 1);
        document.putObject("agent").put("type", "analyst");
        return document;
    }

    private static AgentSpecDocumentException rejected(JsonNode document) {
        return assertThrows(AgentSpecDocumentException.class, () -> AgentSpecDocument.decode(document));
    }

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    // ── round trip ─────────────────────────────────────────────────────────────────────

    @Test
    void roundTrip_keepsTheWholeConfig_notJustTheHash() {
        AgentSpec original = fullSpec();

        AgentSpec back = AgentSpecDocument.decode(AgentSpecDocument.encode(original));

        // Config equality is the real test: the hash ignores id, name, description and version.
        assertEquals(original.config(), back.config());
        assertEquals(original.specHash(), back.specHash());
        assertEquals(original.effectiveHash(), back.effectiveHash());
        assertEquals(original.fewShotRefs(), back.fewShotRefs());
        assertEquals("in-v1", back.schemaRef());
        assertEquals("out-v1", back.outputSchemaRef());
        assertEquals(2, back.outputRepairAttempts());
    }

    @Test
    void roundTrip_throughText_isByteStable() throws Exception {
        JsonNode first = AgentSpecDocument.encode(fullSpec());
        JsonNode second = AgentSpecDocument.encode(AgentSpecDocument.decode(first));

        assertEquals(first, second);
        assertEquals(first.toString(), second.toString(), "key order must be identical, not just the content");
    }

    @Test
    void encode_writesIdentityFirst_soAFileReadsTopDown() {
        List<String> keys = AgentSpecDocument.encode(fullSpec()).properties().stream().map(Map.Entry::getKey).toList();

        assertEquals(List.of("schemaVersion", "agent", "llm", "execution", "memory", "contract", "fewShotRefs"), keys);
    }

    @Test
    void decode_neverCarriesALineage() {
        AgentSpec derived = fullSpec().derive(fullSpec().config().toBuilder().maxIterations(9).build());

        AgentSpec back = AgentSpecDocument.decode(AgentSpecDocument.encode(derived));

        assertEquals(1, back.lineage().specVersion(), "an import is always a fresh root");
        assertNull(back.lineage().derivedFrom());
    }

    @Test
    void roundTrip_stringStrategyAndTypedStrategy_stayDistinct() {
        AgentConfig named = AgentConfig.defaults().agentType("a").plannerStrategy("rag+react")
                .retrieverId("docs").build();
        AgentConfig typed = AgentConfig.defaults().agentType("a").strategyConfig(new StrategyConfig.React()).build();

        AgentSpec backNamed = AgentSpecDocument.decode(AgentSpecDocument.encode(AgentSpec.root(named)));
        AgentSpec backTyped = AgentSpecDocument.decode(AgentSpecDocument.encode(AgentSpec.root(typed)));

        assertEquals(named, backNamed.config());
        assertNull(backNamed.config().execution().strategyConfig());
        assertEquals(typed, backTyped.config());
        assertInstanceOf(StrategyConfig.React.class, backTyped.config().execution().strategyConfig());
    }

    @Test
    void roundTrip_everyBuiltInStrategyArm() {
        List<StrategyConfig> arms = List.of(
                new StrategyConfig.React(),
                new StrategyConfig.PlanExecute("never", 4, 2),
                new StrategyConfig.PlanExecute("on_failure", 4, 2, 3),
                new StrategyConfig.Reflexion(3, "think again", "judge"),
                new StrategyConfig.ReflAct(5, 3, false, "judge"));
        for (StrategyConfig arm : arms) {
            AgentSpec spec = AgentSpec.root(AgentConfig.defaults().agentType("a").strategyConfig(arm).build());

            assertEquals(spec.config(), AgentSpecDocument.decode(AgentSpecDocument.encode(spec)).config(), arm.toString());
        }
    }

    @Test
    void planExecute_withoutParallelism_isEncodedWithoutTheField_andKeepsItsHash() {
        AgentSpec spec = AgentSpec.root(AgentConfig.defaults().agentType("a")
                .strategyConfig(new StrategyConfig.PlanExecute("never", 4, 2)).build());

        assertFalse(AgentSpecDocument.encode(spec).toString().contains("maxParallelSteps"));
        AgentSpec back = AgentSpecDocument.decode(AgentSpecDocument.encode(spec));
        assertNull(((StrategyConfig.PlanExecute) back.config().execution().strategyConfig()).maxParallelSteps());
        assertEquals(spec.specHash(), back.specHash());
    }

    @Test
    void planExecute_parallelism_changesTheHash_sinceItChangesWhatRuns() {
        AgentSpec sequential = AgentSpec.root(AgentConfig.defaults().agentType("a")
                .strategyConfig(new StrategyConfig.PlanExecute("never", 4, 2)).build());
        AgentSpec parallel = AgentSpec.root(AgentConfig.defaults().agentType("a")
                .strategyConfig(new StrategyConfig.PlanExecute("never", 4, 2, 3)).build());

        assertNotEquals(sequential.specHash(), parallel.specHash());
    }

    @Test
    void roundTrip_customStrategy_keepsParamTypes() {
        Map<String, Object> params = Map.of("depth", 3, "ratio", 3.0, "big", 5_000_000_000L, "name", "x",
                "on", true, "tags", List.of("a", 1), "nested", Map.of("k", "v"));
        AgentSpec spec = AgentSpec.root(AgentConfig.defaults().agentType("a")
                .strategyConfig(new StrategyConfig.Custom("tournament", params)).build());

        AgentSpec back = AgentSpecDocument.decode(AgentSpecDocument.encode(spec));

        // 3 stays an Integer and 3.0 a Double: they differ in the hash.
        assertEquals(spec.config(), back.config());
        assertEquals(spec.specHash(), back.specHash());
    }

    // ── reasoning options ────────────────────────────────────────────────────────────────

    @Test
    void aDocumentWithoutAReasoningObject_hasNoReasoningOptions() throws Exception {
        AgentSpec spec = AgentSpecDocument.decode(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"llm":{"primary":{"model":"m"}}}"""));

        assertNull(spec.config().reasoningEffort());
        assertNull(spec.config().thinkingBudgetTokens());
        assertNull(spec.config().returnReasoning());
    }

    @Test
    void theExport_writesAReasoningObjectOnlyWhenOptionsAreSet() {
        JsonNode plain = AgentSpecDocument.encode(AgentSpec.root(AgentConfig.defaults().agentType("a")
                .primaryLlm(LlmProfile.of("m")).build()));
        JsonNode withOptions = AgentSpecDocument.encode(AgentSpec.root(AgentConfig.defaults().agentType("a")
                .primaryLlm(LlmProfile.builder().transportId("m").reasoningEffort(
                        io.ara.core.llm.ReasoningEffort.HIGH).build()).build()));

        assertEquals(1, plain.path("schemaVersion").asInt());
        assertTrue(plain.path("llm").path("primary").path("reasoning").isMissingNode(),
                "an agent that never mentioned reasoning exports exactly what it did before");
        assertEquals("HIGH", withOptions.path("llm").path("primary").path("reasoning").path("effort").asText());
    }

    @Test
    void decode_aBadReasoningValue_isReportedWithItsPath() throws Exception {
        assertEquals("llm.primary.reasoning.effort", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"llm":{"primary":{"reasoning":{"effort":"EXTREME"}}}}""")).path());
        assertEquals("llm.primary.reasoning.thinkingBudgetTokens", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"llm":{"primary":{"reasoning":{"thinkingBudgetTokens":"lots"}}}}""")).path());
        assertEquals("llm.primary.reasoning.depth", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"llm":{"primary":{"reasoning":{"depth":3}}}}""")).path());
    }

    // ── defaults ───────────────────────────────────────────────────────────────────────

    @Test
    void decode_minimalDocument_takesTheBuilderDefaults() {
        AgentSpec spec = AgentSpecDocument.decode(minimalDocument());

        AgentConfig defaults = AgentConfig.defaults().agentType("analyst").build();
        assertEquals("analyst", spec.config().agentType());
        assertEquals(defaults.execution().maxIterations(), spec.config().execution().maxIterations());
        assertEquals(defaults.execution().executionTimeout(), spec.config().execution().executionTimeout());
        assertEquals(defaults.systemPrompt(), spec.config().systemPrompt());
        assertEquals(defaults.memory(), spec.config().memory());
    }

    @Test
    void decode_withoutAnId_generatesADifferentOneEachTime() {
        AgentId first = AgentSpecDocument.decode(minimalDocument()).config().agentId();
        AgentId second = AgentSpecDocument.decode(minimalDocument()).config().agentId();

        assertTrue(!first.equals(second));
    }

    @Test
    void decode_aCurrencyWithoutPrices_defaultsThePricesToZeroInThatCurrency() throws Exception {
        AgentSpec spec = AgentSpecDocument.decode(json("""
                {"schemaVersion":1,"agent":{"type":"a"},
                 "llm":{"primary":{"model":"m","costCurrency":"USD"}}}"""));

        assertEquals(Money.zero("USD"), spec.config().costInputPer1kTokens());
    }

    @Test
    void decode_aJsonNull_countsAsAbsent() throws Exception {
        AgentSpec spec = AgentSpecDocument.decode(json("""
                {"schemaVersion":1,"agent":{"type":"a","name":null},"execution":null}"""));

        assertEquals("", spec.config().name());
    }

    // ── errors, with paths ─────────────────────────────────────────────────────────────

    @Test
    void decode_withoutASchemaVersion_isRejected() {
        ObjectNode document = minimalDocument();
        document.remove("schemaVersion");

        assertEquals("schemaVersion", rejected(document).path());
    }

    @Test
    void decode_aNewerSchemaVersion_isRejectedAsNewer() {
        ObjectNode document = minimalDocument();
        document.put("schemaVersion", AgentSpecDocument.SCHEMA_VERSION + 1);

        AgentSpecDocumentException error = rejected(document);

        assertEquals("schemaVersion", error.path());
        assertTrue(error.getMessage().contains("newer version"), error.getMessage());
    }

    @Test
    void decode_versionZero_isRejected() {
        ObjectNode document = minimalDocument();
        document.put("schemaVersion", 0);

        assertEquals("schemaVersion", rejected(document).path());
    }

    @Test
    void decode_anUnknownField_isRejectedWithItsPath() throws Exception {
        AgentSpecDocumentException error = rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"execution":{"maxIteration":5}}"""));

        assertEquals("execution.maxIteration", error.path());
        assertTrue(error.getMessage().contains("unknown field"), error.getMessage());
    }

    @Test
    void decode_anUnknownTopLevelField_isRejected() throws Exception {
        assertEquals("extra", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"extra":1}""")).path());
    }

    @Test
    void decode_typesAreStrict_noCoercion() throws Exception {
        assertEquals("execution.maxIterations", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"execution":{"maxIterations":"5"}}""")).path());
        assertEquals("execution.maxIterations", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"execution":{"maxIterations":5.0}}""")).path());
        assertEquals("execution.humanApprovalRequired", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"execution":{"humanApprovalRequired":1}}""")).path());
        assertEquals("agent.name", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a","name":5}}""")).path());
        assertEquals("execution.tools", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"execution":{"tools":"search"}}""")).path());
        assertEquals("execution.tools[1]", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"execution":{"tools":["a",2]}}""")).path());
    }

    @Test
    void decode_aSectionThatIsNotAnObject_isRejected() throws Exception {
        assertEquals("llm", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"llm":[]}""")).path());
        assertEquals("", rejected(json("[]")).path());
    }

    @Test
    void decode_agentTypeIsRequired_andMustBeReal() throws Exception {
        assertEquals("agent", rejected(json("""
                {"schemaVersion":1}""")).path());
        assertEquals("agent.type", rejected(json("""
                {"schemaVersion":1,"agent":{}}""")).path());
        assertEquals("agent.type", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"generic"}}""")).path());
        assertEquals("agent.type", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"  "}}""")).path());
    }

    @Test
    void decode_aBadValueFromADomainRecord_carriesThePathOfItsSection() throws Exception {
        AgentSpecDocumentException error = rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"llm":{"primary":{"model":"m","temperature":5.0}}}"""));

        assertEquals("llm.primary", error.path());
        assertTrue(error.getMessage().contains("temperature"), error.getMessage());
    }

    @Test
    void decode_aBadEnumOrDuration_isRejectedWithTheAllowedValues() throws Exception {
        AgentSpecDocumentException enumError = rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"llm":{"selectionPolicy":"RANDOM"}}"""));
        AgentSpecDocumentException durationError = rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"execution":{"timeout":"5 minutes"}}"""));

        assertEquals("llm.selectionPolicy", enumError.path());
        assertTrue(enumError.getMessage().contains("FAILOVER"), enumError.getMessage());
        assertEquals("execution.timeout", durationError.path());
    }

    @Test
    void decode_aMisspeltEvictionPolicy_isCaughtHere() throws Exception {
        assertEquals("memory.workingMemoryEviction", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"memory":{"workingMemoryEviction":"drop_midle"}}""")).path());
    }

    @Test
    void decode_aRetrieverWithoutARagStrategy_isRejectedByTheDomain() throws Exception {
        assertEquals("", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"execution":{"retrieverId":"docs"}}""")).path());
    }

    @Test
    void decode_aStrategy_hasTwoShapesAndNoThird() throws Exception {
        assertEquals("execution.strategy", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"execution":{"strategy":5}}""")).path());
        assertEquals("execution.strategy.type", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"execution":{"strategy":{}}}""")).path());
        assertEquals("execution.strategy.params", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"execution":{"strategy":{"type":"mine","params":[]}}}""")).path());
        assertEquals("execution.strategy.paramz", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"react"},"execution":{"strategy":{"type":"mine","paramz":{}}}}""")).path());
        assertEquals("execution.strategy.maxPlanSteps", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"execution":{"strategy":{"type":"plan_execute","maxPlanSteps":"x"}}}""")).path());
        assertEquals("execution.strategy", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"execution":{"strategy":{"type":"plan_execute","maxPlanSteps":0}}}""")).path());
    }

    @Test
    void decode_paramsOfACustomStrategy_cannotHoldANullInAList() throws Exception {
        assertEquals("execution.strategy.params.items[0]", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},
                 "execution":{"strategy":{"type":"mine","params":{"items":[null]}}}}""")).path());
    }

    @Test
    void decode_moneyAndBudget_haveStrictShapes() throws Exception {
        assertEquals("llm.primary.budget", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"llm":{"primary":{"model":"m","budget":"limitless"}}}""")).path());
        assertEquals("llm.primary.budget.cap", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"llm":{"primary":{"model":"m","budget":{}}}}""")).path());
        assertEquals("llm.primary.costInputPer1k", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},
                 "llm":{"primary":{"model":"m","costInputPer1k":{"amount":"-1","currency":"EUR"}}}}""")).path());
        assertEquals("llm.primary.costInputPer1k.amount", rejected(json("""
                {"schemaVersion":1,"agent":{"type":"a"},
                 "llm":{"primary":{"model":"m","costInputPer1k":{"amount":0.5,"currency":"EUR"}}}}""")).path());
    }

    @Test
    void decode_unlimitedBudget_isAString() throws Exception {
        AgentSpec spec = AgentSpecDocument.decode(json("""
                {"schemaVersion":1,"agent":{"type":"a"},"llm":{"primary":{"model":"m","budget":"unlimited"}}}"""));

        assertEquals(Budget.unlimited(), spec.config().costBudget());
    }

    @Test
    void decode_anOutputSchemaWithNativeJsonSchema_isAcceptedByTheSpec() throws Exception {
        // Legal since adapters send a provider-native response_format: the document round-trips
        // instead of being refused for a restriction that no longer holds.
        AgentSpec spec = AgentSpecDocument.decode(json("""
                {"schemaVersion":1,"agent":{"type":"a"},
                 "llm":{"primary":{"model":"m","nativeJsonSchema":true}},
                 "contract":{"outputSchemaRef":"out-v1"}}"""));

        assertEquals("out-v1", spec.outputSchemaRef());
        assertTrue(spec.config().nativeJsonSchema());
    }

    // ── what a document cannot hold ────────────────────────────────────────────────────

    @Test
    void encode_anInlineTransport_isRefused_withOrWithoutAKey() {
        for (LlmProfile inline : List.of(
                LlmProfile.builder().baseUrl("http://x").modelName("gpt").apiKey("secret").build(),
                LlmProfile.builder().baseUrl("http://x").modelName("gpt").build())) {
            AgentSpec spec = AgentSpec.root(AgentConfig.defaults().agentType("a").primaryLlm(inline).build());

            AgentSpecDocumentException error = assertThrows(AgentSpecDocumentException.class,
                    () -> AgentSpecDocument.encode(spec));

            assertEquals("llm.primary", error.path());
            assertTrue(!error.getMessage().contains("secret"), "the key must never reach an error message");
        }
    }

    @Test
    void encode_anInlineTransportOnAFallback_namesTheFallbackIndex() {
        LlmProfile inline = LlmProfile.builder().baseUrl("http://x").modelName("gpt").build();
        AgentSpec spec = AgentSpec.root(AgentConfig.defaults().agentType("a")
                .primaryLlm(LlmProfile.of("m")).fallbackLlms(List.of(LlmProfile.of("ok"), inline)).build());

        assertEquals("llm.fallbacks[1]", assertThrows(AgentSpecDocumentException.class,
                () -> AgentSpecDocument.encode(spec)).path());
    }

    @Test
    void encode_aCustomParamThatIsNotJsonNative_isRefused() {
        AgentSpec spec = AgentSpec.root(AgentConfig.defaults().agentType("a")
                .strategyConfig(new StrategyConfig.Custom("mine",
                        Map.of("price", new java.math.BigDecimal("1.5")))).build());

        AgentSpecDocumentException error = assertThrows(AgentSpecDocumentException.class,
                () -> AgentSpecDocument.encode(spec));

        assertEquals("execution.strategy.params.price", error.path());
    }

    @Test
    void encode_aCustomStrategyBorrowingABuiltInName_isRefused() {
        AgentSpec spec = AgentSpec.root(AgentConfig.defaults().agentType("a")
                .strategyConfig(StrategyConfig.Custom.of("react")).build());

        assertEquals("execution.strategy", assertThrows(AgentSpecDocumentException.class,
                () -> AgentSpecDocument.encode(spec)).path());
    }

    @Test
    void encode_aCustomParamMapWithNonStringKeys_isRefused() {
        AgentSpec spec = AgentSpec.root(AgentConfig.defaults().agentType("a")
                .strategyConfig(new StrategyConfig.Custom("mine", Map.of("m", Map.of(1, "x")))).build());

        assertEquals("execution.strategy.params.m", assertThrows(AgentSpecDocumentException.class,
                () -> AgentSpecDocument.encode(spec)).path());
    }

    @Test
    void anExceptionPath_ofTheRoot_readsAsDocument() {
        assertTrue(new AgentSpecDocumentException("", "bad").getMessage().startsWith("document: "));
    }
}
