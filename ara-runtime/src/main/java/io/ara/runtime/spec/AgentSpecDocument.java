package io.ara.runtime.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentIdentity;
import io.ara.core.common.AgentId;
import io.ara.core.memory.MemoryConfig;
import io.ara.core.spec.AgentSpec;
import io.ara.runtime.memory.EvictionPolicy;

import java.util.List;
import java.util.Objects;

import static io.ara.runtime.spec.DocumentFields.guard;
import static io.ara.runtime.spec.DocumentFields.put;
import static io.ara.runtime.spec.DocumentFields.set;
import static io.ara.runtime.spec.DocumentFields.stringArray;

/**
 * The agent document: a format-independent {@link JsonNode} tree that defines one agent,
 * and the only place that maps it to and from an {@link AgentSpec}.
 *
 * <pre>{@code
 * { "schemaVersion": 1,
 *   "agent":     { "id", "type", "name", "description", "version", "tags",
 *                  "systemPrompt", "promptCatalogId", "presentationMessage" },
 *   "llm":       { "primary", "fallbacks", "selectionPolicy", "logIo", "logIoMaxChars" },
 *   "execution": { "strategy", "tools", "mcpServers", "maxIterations", "timeout", ... },
 *   "memory":    { "workingMemoryTokenBudget", "workingMemoryEviction", ... },
 *   "contract":  { "inputSchemaRef", "outputSchemaRef", "outputRepairAttempts" },
 *   "fewShotRefs": [ ... ] }
 * }</pre>
 *
 * <p><b>An editor can check the file as it is written</b>: the format is described by the JSON Schema
 * packaged as the classpath resource {@code /io/ara/runtime/spec/agent-document.schema.json}
 * (also in the source tree under {@code ara-runtime/src/main/resources}); a document may point to
 * it with a top-level {@code "$schema"} field, which is accepted and ignored here. The schema is
 * checked against this class by a test, so the two cannot drift apart unnoticed.
 *
 * <p><b>This is also the extension point for foreign sources.</b> Someone whose agents live
 * in a database with its own schema writes the column-to-field mapping, builds this tree and
 * calls {@link #decode(JsonNode)}; they get type checking, unknown-field detection, version
 * checking and error paths for free, and never touch the {@code AgentConfig} constructors.
 * There is deliberately no {@code AgentSpecSource} interface for this: with no second
 * implementation yet it would be an abstraction nobody has asked for. Because third parties
 * depend on these field names, any incompatible change must bump {@link #SCHEMA_VERSION}.
 *
 * <p><b>What a document is, and is not.</b> It is a <em>definition</em>: {@link #decode}
 * always returns a fresh root spec (version 1, draft) and {@link #encode} drops the lineage
 * (derivation, status), which is evolution state. Anything that must keep a lineage uses the
 * meta-agent's machine codec instead. Models, tools and strategies are referred to by name;
 * an inline LLM transport (and so any API key) is not representable (see {@code LlmDocument}).
 *
 * <p><b>Defaults.</b> Decoding fills a missing field from {@code AgentConfig.Builder}, so a
 * hand-written file can be short; encoding writes every non-null field, so an export does not
 * change meaning if a library default changes later. {@code agent.type} is the one required
 * field, and it may not be blank or {@code "generic"} (a spec needs a real capability name).
 *
 * <p><b>Round-trip contract.</b> {@code decode(encode(spec)).config().equals(spec.config())},
 * and the two specs share {@code specHash()} and {@code effectiveHash()}. Equality of the
 * config, not of the hash, is the real test: the hash ignores the agent's id, name,
 * description and version, so a decoder that dropped the name would still pass a hash check.
 *
 * <p>Stateless; thread-safe.
 */
public final class AgentSpecDocument {

    /**
     * The newest document version this class reads and the one it writes. A reader rejects a
     * larger number instead of guessing; adding any field increments it (an older reader would
     * otherwise report "unknown field" rather than "newer version"). Version-to-version
     * migration code is written when a version 2 exists, not before.
     */
    public static final int SCHEMA_VERSION = 1;

    private static final String GENERIC_TYPE = "generic";

    private AgentSpecDocument() {}

    // ── decode ─────────────────────────────────────────────────────────────────────────

    /**
     * Turns a document tree into a fresh root {@link AgentSpec}.
     *
     * @throws AgentSpecDocumentException naming the offending field, for a wrong type, an
     *         unknown or missing field, an unsupported version, or a value a domain record
     *         rejects
     */
    public static AgentSpec decode(JsonNode document) {
        Objects.requireNonNull(document, "document must not be null");
        DocumentFields root = new DocumentFields(document, "");
        // "$schema" is the pointer an editor needs to validate the file; it carries no agent data.
        root.string("$schema");
        checkVersion(root);

        AgentConfig.Builder builder = AgentConfig.defaults();
        decodeAgent(root.requireObject("agent"), builder);
        LlmDocument.decode(root.objectOrEmpty("llm"), builder);
        ExecutionDocument.decode(root.objectOrEmpty("execution"), builder);
        decodeMemory(root.objectOrEmpty("memory"), builder);
        DocumentFields contract = root.objectOrEmpty("contract");
        String inputSchemaRef = contract.string("inputSchemaRef");
        String outputSchemaRef = contract.string("outputSchemaRef");
        Integer repairAttempts = contract.integer("outputRepairAttempts");
        contract.finish();
        List<String> fewShotRefs = root.stringList("fewShotRefs");
        root.finish();

        AgentConfig config = guard("", builder::build);
        return guard("contract", () -> overlays(AgentSpec.root(config), fewShotRefs, inputSchemaRef,
                outputSchemaRef, repairAttempts));
    }

    private static void checkVersion(DocumentFields root) {
        Integer version = root.integer("schemaVersion");
        if (version == null) {
            throw new AgentSpecDocumentException("schemaVersion", "is required");
        }
        if (version < 1 || version > SCHEMA_VERSION) {
            throw new AgentSpecDocumentException("schemaVersion", "is " + version
                    + " but this reader supports 1.." + SCHEMA_VERSION
                    + (version > SCHEMA_VERSION ? "; the document was written by a newer version" : ""));
        }
    }

    private static void decodeAgent(DocumentFields agent, AgentConfig.Builder builder) {
        String type = agent.requireString("type");
        if (type.isBlank() || GENERIC_TYPE.equals(type)) {
            throw new AgentSpecDocumentException(agent.at("type"),
                    "must name a real capability, not blank or \"" + GENERIC_TYPE + "\"");
        }
        builder.agentType(type);
        String id = agent.string("id");
        if (id != null) {
            builder.agentId(guard(agent.at("id"), () -> AgentId.of(id)));
        }
        set(builder::name, agent.string("name"));
        set(builder::description, agent.string("description"));
        set(builder::version, agent.string("version"));
        set(builder::tags, agent.stringList("tags"));
        set(builder::systemPrompt, agent.string("systemPrompt"));
        set(builder::promptCatalogId, agent.string("promptCatalogId"));
        set(builder::presentationMessage, agent.string("presentationMessage"));
        agent.finish();
    }

    private static void decodeMemory(DocumentFields memory, AgentConfig.Builder builder) {
        set(builder::workingMemoryTokenBudget, memory.integer("workingMemoryTokenBudget"));
        String eviction = memory.string("workingMemoryEviction");
        if (eviction != null) {
            // ara-core cannot validate the eviction vocabulary (it lives in this module), so the
            // import is where a misspelt policy is caught, with its path.
            guard(memory.at("workingMemoryEviction"), () -> EvictionPolicy.from(eviction));
            builder.workingMemoryEviction(eviction);
        }
        set(builder::maxConversationTurns, memory.integer("maxConversationTurns"));
        set(builder::maxReflections, memory.integer("maxReflections"));
        set(builder::reflectionPrompt, memory.string("reflectionPrompt"));
        set(builder::contextSummarizerAgentId, memory.string("contextSummarizerAgentId"));
        set(builder::replayToolCalls, memory.bool("replayToolCalls"));
        memory.finish();
    }

    private static AgentSpec overlays(AgentSpec root, List<String> fewShotRefs, String inputSchemaRef,
                                      String outputSchemaRef, Integer repairAttempts) {
        AgentSpec spec = root;
        if (fewShotRefs != null) {
            spec = spec.withFewShotRefs(fewShotRefs);
        }
        if (inputSchemaRef != null) {
            spec = spec.withSchemaRef(inputSchemaRef);
        }
        if (outputSchemaRef != null) {
            spec = spec.withOutputSchemaRef(outputSchemaRef);
        }
        if (repairAttempts != null) {
            spec = spec.withOutputRepairAttempts(repairAttempts);
        }
        return spec;
    }

    // ── encode ─────────────────────────────────────────────────────────────────────────

    /**
     * Turns a spec into a document tree, keys in a fixed reading order (identity first), every
     * non-null field written.
     *
     * @throws AgentSpecDocumentException if the spec holds something a document cannot
     *         represent: an inline LLM transport, or a custom strategy parameter that is not a
     *         string, boolean, int, long, double, list or map
     */
    public static ObjectNode encode(AgentSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        AgentConfig config = spec.config();
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("schemaVersion", SCHEMA_VERSION);
        out.set("agent", encodeAgent(config.identity()));
        out.set("llm", LlmDocument.encode(config.llm()));
        out.set("execution", ExecutionDocument.encode(config.execution()));
        out.set("memory", encodeMemory(config.memory()));
        out.set("contract", encodeContract(spec));
        out.set("fewShotRefs", stringArray(spec.fewShotRefs()));
        return out;
    }

    private static ObjectNode encodeAgent(AgentIdentity identity) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("id", identity.agentId().value());
        out.put("type", identity.agentType());
        out.put("name", identity.name());
        out.put("description", identity.description());
        out.put("version", identity.version());
        out.set("tags", stringArray(identity.tags()));
        out.put("systemPrompt", identity.systemPrompt());
        put(out, "promptCatalogId", identity.promptCatalogId());
        put(out, "presentationMessage", identity.presentationMessage());
        return out;
    }

    private static ObjectNode encodeMemory(MemoryConfig memory) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("workingMemoryTokenBudget", memory.workingMemoryTokenBudget());
        out.put("workingMemoryEviction", memory.workingMemoryEviction());
        out.put("maxConversationTurns", memory.maxConversationTurns());
        out.put("maxReflections", memory.maxReflections());
        put(out, "reflectionPrompt", memory.reflectionPrompt());
        put(out, "contextSummarizerAgentId", memory.contextSummarizerAgentId());
        // written only when on: off is the absence of the field, which is also what keeps the hash of a spec that does
        // not use the option what it was before the option existed
        if (memory.keepsToolCalls()) {
            out.put("replayToolCalls", true);
        }
        return out;
    }

    private static ObjectNode encodeContract(AgentSpec spec) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        put(out, "inputSchemaRef", spec.schemaRef());
        put(out, "outputSchemaRef", spec.outputSchemaRef());
        out.put("outputRepairAttempts", spec.outputRepairAttempts());
        return out;
    }
}
