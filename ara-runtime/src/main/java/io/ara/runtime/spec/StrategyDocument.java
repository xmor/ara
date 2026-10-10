package io.ara.runtime.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ara.core.agent.StrategyConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.ara.runtime.spec.DocumentFields.guard;
import static io.ara.runtime.spec.DocumentFields.put;

/**
 * A typed {@link StrategyConfig} as a document object, discriminated by {@code "type"}.
 * Package-private; reached through {@link ExecutionDocument}. Stateless; thread-safe.
 *
 * <p>{@code StrategyConfig} is sealed, so the four built-in arms get one case each and any
 * other {@code type} is a {@link StrategyConfig.Custom} whose {@code params} are free-form.
 * Absent fields take the arm's own {@code defaults()}, so a document can say just
 * {@code {"type": "plan_execute"}}.
 *
 * <p><b>Custom params keep their JSON type.</b> Integers stay integers and decimals stay
 * decimals, because the values reach the agent's hash, where {@code 3} and {@code 3.0}
 * differ. Only strings, booleans, integers ({@code int}/{@code long}), decimals ({@code
 * double}), lists and maps are representable; anything else (a {@code BigDecimal}, an
 * arbitrary object) makes {@link #encode} fail rather than round-trip into something else.
 * A {@code Custom} that borrows a built-in name cannot be told apart from that built-in on
 * the way back, so it is refused too.
 */
final class StrategyDocument {

    private static final String REACT = "react";
    private static final String PLAN_EXECUTE = "plan_execute";
    private static final String REFLEXION = "reflexion";
    private static final String REFLACT = "reflact";

    private StrategyDocument() {}

    // ── decode ─────────────────────────────────────────────────────────────────────────

    static StrategyConfig decode(DocumentFields fields) {
        String type = fields.requireString("type");
        StrategyConfig strategy = switch (type) {
            case REACT -> new StrategyConfig.React();
            case PLAN_EXECUTE -> decodePlanExecute(fields);
            case REFLEXION -> decodeReflexion(fields);
            case REFLACT -> decodeReflAct(fields);
            default -> new StrategyConfig.Custom(type, decodeParams(fields));
        };
        fields.finish();
        return strategy;
    }

    private static StrategyConfig decodePlanExecute(DocumentFields fields) {
        StrategyConfig.PlanExecute defaults = StrategyConfig.PlanExecute.defaults();
        return guard(fields.path(), () -> new StrategyConfig.PlanExecute(
                orElse(fields.string("replanPolicy"), defaults.replanPolicy()),
                orElse(fields.integer("maxPlanSteps"), defaults.maxPlanSteps()),
                orElse(fields.integer("maxStepRoundsPerStep"), defaults.maxStepRoundsPerStep()),
                fields.integer("maxParallelSteps")));
    }

    private static StrategyConfig decodeReflexion(DocumentFields fields) {
        StrategyConfig.Reflexion defaults = StrategyConfig.Reflexion.defaults();
        return guard(fields.path(), () -> new StrategyConfig.Reflexion(
                orElse(fields.integer("maxReflections"), defaults.maxReflections()),
                fields.string("reflectionPrompt"),
                fields.string("reflectionProvider")));
    }

    private static StrategyConfig decodeReflAct(DocumentFields fields) {
        StrategyConfig.ReflAct defaults = StrategyConfig.ReflAct.defaults();
        return guard(fields.path(), () -> new StrategyConfig.ReflAct(
                orElse(fields.integer("maxReflections"), defaults.maxReflections()),
                orElse(fields.integer("unproductiveStreak"), defaults.unproductiveStreak()),
                orElse(fields.bool("reflectOnToolFailure"), defaults.reflectOnToolFailure()),
                fields.string("reflectionProvider")));
    }

    private static <T> T orElse(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static Map<String, Object> decodeParams(DocumentFields fields) {
        JsonNode raw = fields.raw("params");
        if (raw == null) {
            return Map.of();
        }
        if (!raw.isObject()) {
            throw new AgentSpecDocumentException(fields.at("params"), "expected an object");
        }
        return toMap(raw, fields.at("params"));
    }

    private static Map<String, Object> toMap(JsonNode object, String path) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
            // A JSON null carries no value, and Map.copyOf (used by Custom) rejects nulls.
            if (!entry.getValue().isNull()) {
                out.put(entry.getKey(), toValue(entry.getValue(), path + "." + entry.getKey()));
            }
        }
        return out;
    }

    private static Object toValue(JsonNode node, String path) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToInt() ? (Object) node.intValue() : (Object) node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.doubleValue();
        }
        if (node.isArray()) {
            List<Object> out = new ArrayList<>(node.size());
            for (int index = 0; index < node.size(); index++) {
                out.add(toValue(node.get(index), path + "[" + index + "]"));
            }
            return out;
        }
        if (node.isObject()) {
            return toMap(node, path);
        }
        throw new AgentSpecDocumentException(path, "null is not allowed inside a list");
    }

    // ── encode ─────────────────────────────────────────────────────────────────────────

    static ObjectNode encode(StrategyConfig strategy) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        switch (strategy) {
            case StrategyConfig.React ignored -> out.put("type", REACT);
            case StrategyConfig.PlanExecute planExecute -> {
                out.put("type", PLAN_EXECUTE);
                out.put("replanPolicy", planExecute.replanPolicy());
                out.put("maxPlanSteps", planExecute.maxPlanSteps());
                out.put("maxStepRoundsPerStep", planExecute.maxStepRoundsPerStep());
                if (planExecute.maxParallelSteps() != null) {
                    out.put("maxParallelSteps", planExecute.maxParallelSteps());
                }
            }
            case StrategyConfig.Reflexion reflexion -> {
                out.put("type", REFLEXION);
                out.put("maxReflections", reflexion.maxReflections());
                put(out, "reflectionPrompt", reflexion.reflectionPrompt());
                put(out, "reflectionProvider", reflexion.reflectionProvider());
            }
            case StrategyConfig.ReflAct reflAct -> {
                out.put("type", REFLACT);
                out.put("maxReflections", reflAct.maxReflections());
                out.put("unproductiveStreak", reflAct.unproductiveStreak());
                out.put("reflectOnToolFailure", reflAct.reflectOnToolFailure());
                put(out, "reflectionProvider", reflAct.reflectionProvider());
            }
            case StrategyConfig.Custom custom -> encodeCustom(custom, out);
        }
        return out;
    }

    private static void encodeCustom(StrategyConfig.Custom custom, ObjectNode out) {
        String name = custom.strategyName();
        if (name.equals(REACT) || name.equals(PLAN_EXECUTE) || name.equals(REFLEXION) || name.equals(REFLACT)) {
            throw new AgentSpecDocumentException("execution.strategy", "a custom strategy named '" + name
                    + "' would read back as the built-in of that name");
        }
        out.put("type", name);
        out.set("params", fromMap(new TreeMap<>(custom.params()), "execution.strategy.params"));
    }

    private static ObjectNode fromMap(Map<String, Object> map, String path) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            out.set(entry.getKey(), fromValue(entry.getValue(), path + "." + entry.getKey()));
        }
        return out;
    }

    private static JsonNode fromValue(Object value, String path) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        return switch (value) {
            case String text -> factory.textNode(text);
            case Boolean flag -> factory.booleanNode(flag);
            case Integer number -> factory.numberNode(number);
            case Long number -> factory.numberNode(number);
            case Double number -> factory.numberNode(number);
            case List<?> list -> {
                ArrayNode array = factory.arrayNode();
                for (int index = 0; index < list.size(); index++) {
                    array.add(fromValue(list.get(index), path + "[" + index + "]"));
                }
                yield array;
            }
            case Map<?, ?> map -> fromMap(sortedStringKeys(map, path), path);
            default -> throw new AgentSpecDocumentException(path, "a value of type "
                    + value.getClass().getSimpleName() + " cannot be written to a document; use a string, "
                    + "boolean, int, long, double, list or map");
        };
    }

    private static Map<String, Object> sortedStringKeys(Map<?, ?> map, String path) {
        Map<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new AgentSpecDocumentException(path, "map keys must be strings");
            }
            sorted.put(key, entry.getValue());
        }
        return sorted;
    }
}
