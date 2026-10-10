package io.ara.runtime.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.StrategyConfig;
import io.ara.core.agent.processor.ProcessingResult;
import io.ara.core.common.Budget;
import io.ara.core.common.Money;
import io.ara.core.llm.LlmProfile;
import io.ara.core.spec.AgentSpec;
import io.ara.runtime.contract.JsonSchemaValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the published JSON Schema and the decoder from drifting apart. They describe the same
 * format by two hands, so three things are checked: what the encoder writes, the schema
 * accepts; what the schema rejects, the decoder rejects too (on a corpus of broken documents);
 * and the two know exactly the same field names.
 *
 * <p>Checklist: a field added to the document goes in the decoder, the encoder, {@code
 * agent-document.schema.json}, and the corpus below. The schema is stricter than the decoder
 * in a few places by design (it lists the allowed eviction names in lower case, the decoder
 * lowercases first); it is never looser.
 */
class AgentSpecSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SCHEMA_RESOURCE = "/io/ara/runtime/spec/agent-document.schema.json";

    private static final JsonNode SCHEMA = readSchema();
    private static final JsonSchemaValidator VALIDATOR = JsonSchemaValidator.forOutput(SCHEMA.toString());

    private static JsonNode readSchema() {
        try (InputStream in = AgentSpecSchemaTest.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            return MAPPER.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean schemaAccepts(JsonNode document) {
        return VALIDATOR.process(document.toString()) instanceof ProcessingResult.Pass;
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── what the encoder writes, the schema accepts ────────────────────────────────────

    @Test
    void aDocumentTheEncoderWrites_isAcceptedByTheSchema_andByTheDecoder() {
        for (AgentSpec spec : encodedCorpus()) {
            JsonNode document = AgentSpecDocument.encode(spec);

            assertTrue(schemaAccepts(document), "schema rejected: " + document);
            AgentSpecDocument.decode(document);
        }
    }

    @Test
    void aMinimalHandWrittenDocument_isAcceptedByBoth() {
        JsonNode document = json("{\"schemaVersion\":1,\"agent\":{\"type\":\"analyst\"}}");

        assertTrue(schemaAccepts(document));
        AgentSpecDocument.decode(document);
    }

    @Test
    void theSchemaPointer_isAcceptedAndIgnored() {
        JsonNode document = json("{\"$schema\":\"./agent-document.schema.json\","
                + "\"schemaVersion\":1,\"agent\":{\"type\":\"analyst\"}}");

        assertTrue(schemaAccepts(document));
        assertEquals("analyst", AgentSpecDocument.decode(document).config().agentType());
    }

    @Test
    void theSchemaPointer_mustBeAString() {
        JsonNode document = json("{\"$schema\":5,\"schemaVersion\":1,\"agent\":{\"type\":\"a\"}}");

        assertTrue(!schemaAccepts(document));
        assertThrows(AgentSpecDocumentException.class, () -> AgentSpecDocument.decode(document));
    }

    // ── what the schema rejects, the decoder rejects ───────────────────────────────────

    private static final Map<String, String> BROKEN = Map.ofEntries(
            Map.entry("unknown top-level field", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"extra\":1}"),
            Map.entry("unknown execution field", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"execution\":{\"maxIteration\":5}}"),
            Map.entry("unknown profile field", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"llm\":{\"primary\":{\"baseUrl\":\"http://x\"}}}"),
            Map.entry("string for an integer", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"execution\":{\"maxIterations\":\"5\"}}"),
            Map.entry("zero iterations", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"execution\":{\"maxIterations\":0}}"),
            Map.entry("a future schema version", "{\"schemaVersion\":2,\"agent\":{\"type\":\"a\"}}"),
            Map.entry("a bad reasoning effort", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"llm\":{\"primary\":{\"reasoning\":{\"effort\":\"EXTREME\"}}}}"),
            Map.entry("a zero thinking budget", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"llm\":{\"primary\":{\"reasoning\":{\"thinkingBudgetTokens\":0}}}}"),
            Map.entry("an unknown reasoning field", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"llm\":{\"primary\":{\"reasoning\":{\"depth\":3}}}}"),
            Map.entry("no schema version", "{\"agent\":{\"type\":\"a\"}}"),
            Map.entry("no agent", "{\"schemaVersion\":1}"),
            Map.entry("no agent type", "{\"schemaVersion\":1,\"agent\":{}}"),
            Map.entry("generic agent type", "{\"schemaVersion\":1,\"agent\":{\"type\":\"generic\"}}"),
            Map.entry("blank agent type", "{\"schemaVersion\":1,\"agent\":{\"type\":\"  \"}}"),
            Map.entry("bad selection policy", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"llm\":{\"selectionPolicy\":\"RANDOM\"}}"),
            Map.entry("temperature out of range", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"llm\":{\"primary\":{\"temperature\":5.0}}}"),
            Map.entry("strategy that is a number", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"execution\":{\"strategy\":5}}"),
            Map.entry("strategy object without type", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"execution\":{\"strategy\":{}}}"),
            Map.entry("plan_execute with zero steps", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"execution\":{\"strategy\":{\"type\":\"plan_execute\",\"maxPlanSteps\":0}}}"),
            Map.entry("plan_execute with zero parallel steps", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"execution\":{\"strategy\":{\"type\":\"plan_execute\",\"maxParallelSteps\":0}}}"),
            Map.entry("plan_execute with a bad policy", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"execution\":{\"strategy\":{\"type\":\"plan_execute\",\"replanPolicy\":\"sometimes\"}}}"),
            Map.entry("react with an extra field", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"execution\":{\"strategy\":{\"type\":\"react\",\"depth\":2}}}"),
            Map.entry("custom params that are a list", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"execution\":{\"strategy\":{\"type\":\"mine\",\"params\":[]}}}"),
            Map.entry("money as a number", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"llm\":{\"primary\":{\"costInputPer1k\":{\"amount\":0.5,\"currency\":\"EUR\"}}}}"),
            Map.entry("negative money", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"llm\":{\"primary\":{\"costInputPer1k\":{\"amount\":\"-1\",\"currency\":\"EUR\"}}}}"),
            Map.entry("a budget that is neither", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"llm\":{\"primary\":{\"budget\":\"limitless\"}}}"),
            Map.entry("a budget without a cap", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"llm\":{\"primary\":{\"budget\":{}}}}"),
            Map.entry("a duration in words", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"execution\":{\"timeout\":\"5 minutes\"}}"),
            Map.entry("an empty duration", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"execution\":{\"timeout\":\"PT\"}}"),
            Map.entry("tags that are a string", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\",\"tags\":\"x\"}}"),
            Map.entry("a tool that is a number", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"execution\":{\"tools\":[\"a\",2]}}"),
            Map.entry("a misspelt eviction policy", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"memory\":{\"workingMemoryEviction\":\"drop_midle\"}}"),
            Map.entry("a section that is a list", "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"llm\":[]}"));

    @Test
    void everyBrokenDocument_isRejectedByTheSchema_andByTheDecoder() {
        List<String> schemaTooLoose = new ArrayList<>();
        List<String> decoderTooLoose = new ArrayList<>();
        BROKEN.forEach((name, text) -> {
            JsonNode document = json(text);
            if (schemaAccepts(document)) {
                schemaTooLoose.add(name);
            }
            try {
                AgentSpecDocument.decode(document);
                decoderTooLoose.add(name);
            } catch (IllegalArgumentException expected) {
                // rejected, as it should be
            }
        });

        assertEquals(List.of(), schemaTooLoose, "the schema accepts documents that are wrong");
        assertEquals(List.of(), decoderTooLoose, "the decoder accepts documents that are wrong");
    }

    @Test
    void someRulesBelongToTheDomain_notTheSchema() {
        // The schema cannot know this: it depends on two fields together. The decoder catches it.
        JsonNode retrieverWithoutRag = json("{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"execution\":{\"retrieverId\":\"docs\"}}");

        assertTrue(schemaAccepts(retrieverWithoutRag));
        assertInstanceOf(AgentSpecDocumentException.class,
                assertThrows(IllegalArgumentException.class, () -> AgentSpecDocument.decode(retrieverWithoutRag)));
    }

    // ── the two know the same field names ──────────────────────────────────────────────

    @Test
    void theSchemaAndTheEncoder_knowExactlyTheSameFields() {
        Set<String> encoder = new TreeSet<>();
        for (AgentSpec spec : encodedCorpus()) {
            collectDocumentPaths(AgentSpecDocument.encode(spec), "", encoder);
        }
        Set<String> schema = new TreeSet<>();
        collectSchemaPaths(SCHEMA, "", schema);
        schema.remove("$schema");   // the editor pointer: accepted on input, never written

        Set<String> onlyInSchema = new TreeSet<>(schema);
        onlyInSchema.removeAll(encoder);
        Set<String> onlyInEncoder = new TreeSet<>(encoder);
        onlyInEncoder.removeAll(schema);
        assertEquals(Set.of(), onlyInSchema, "fields the schema allows but no export ever writes");
        assertEquals(Set.of(), onlyInEncoder, "fields the encoder writes that the schema does not allow");
    }

    /** Documents that between them write every field the format has. */
    private static List<AgentSpec> encodedCorpus() {
        List<AgentSpec> corpus = new ArrayList<>();
        corpus.add(AgentSpecDocumentTest.fullSpec());
        for (StrategyConfig arm : List.of(new StrategyConfig.React(),
                new StrategyConfig.Reflexion(3, "think again", "judge"),
                new StrategyConfig.ReflAct(5, 3, false, "judge"),
                new StrategyConfig.Custom("tournament", Map.of("rounds", 3)))) {
            corpus.add(AgentSpec.root(AgentConfig.defaults().agentType("a").strategyConfig(arm).build()));
        }
        corpus.add(AgentSpec.root(AgentConfig.defaults().agentType("a")
                .plannerStrategy("rag+react").retrieverId("docs").build()));
        // A fallback is a whole profile too: give it every optional field the primary has.
        LlmProfile richFallback = LlmProfile.builder().transportId("backup").pinnedTransportVersion(1L)
                .temperature(0.5).topP(0.7).maxTokens(256).costCurrency("USD")
                .costBudget(Budget.limited(Money.of("1", "USD")))
                .costInputPer1kTokens(Money.of("0.001", "USD")).costOutputPer1kTokens(Money.of("0.002", "USD"))
                .reasoningEffort(io.ara.core.llm.ReasoningEffort.LOW).thinkingBudgetTokens(256).returnReasoning(false)
                .build();
        corpus.add(AgentSpec.root(AgentConfig.defaults().agentType("a").fallbackLlms(List.of(richFallback)).build()));
        return corpus;
    }

    private static void collectDocumentPaths(JsonNode node, String path, Set<String> out) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String child = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                out.add(child);
                // The keys under a custom strategy's params are the user's, not the format's.
                if (!child.equals("execution.strategy.params")) {
                    collectDocumentPaths(entry.getValue(), child, out);
                }
            });
        } else if (node.isArray()) {
            node.forEach(element -> collectDocumentPaths(element, path + "[]", out));
        }
    }

    /** Walks the schema the way an editor would: through properties, items, refs and alternatives. */
    private static void collectSchemaPaths(JsonNode schema, String path, Set<String> out) {
        JsonNode resolved = resolve(schema);
        resolved.path("properties").properties().forEach(entry -> {
            String child = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            out.add(child);
            collectSchemaPaths(entry.getValue(), child, out);
        });
        if (resolved.has("items")) {
            collectSchemaPaths(resolved.get("items"), path + "[]", out);
        }
        for (String keyword : List.of("oneOf", "anyOf", "allOf")) {
            resolved.path(keyword).forEach(alternative -> collectSchemaPaths(alternative, path, out));
        }
    }

    private static JsonNode resolve(JsonNode schema) {
        if (!schema.has("$ref")) {
            return schema;
        }
        String ref = schema.get("$ref").asText();
        assertTrue(ref.startsWith("#/"), "only local references are expected: " + ref);
        JsonNode target = SCHEMA;
        for (String part : ref.substring(2).split("/")) {
            target = target.get(part);
        }
        return target;
    }
}
