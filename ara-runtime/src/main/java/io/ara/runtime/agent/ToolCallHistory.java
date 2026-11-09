package io.ara.runtime.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ara.core.agent.ConversationTurn;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.StepType;
import io.ara.core.agent.ToolExchange;
import io.ara.core.media.MediaRef;
import io.ara.core.memory.MemoryManager;
import io.ara.core.memory.ToolCallMetadata;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolHistoryPolicy;
import io.ara.core.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The tool calls of a turn, on their way into the session history and back into working memory, for an agent whose
 * memory configuration keeps them ({@code replayToolCalls}).
 *
 * <p><b>Why.</b> The session history used to keep a question and an answer per turn, and nothing of what the agent did
 * in between. A model rereading such a history sees past actions only as sentences ("the light is on"), and answers the
 * next request the same way, with a sentence and no call. Measured with a small model on a device command repeated
 * after a first one: 3 times right out of 10 with questions and answers alone, 10 out of 10 with the calls replayed.
 *
 * <p><b>How.</b> Recording reads the run's own steps ({@link ExecutionStep} {@code TOOL_CALL} and {@code OBSERVATION}),
 * so no strategy has to change and every strategy that records tool steps is covered. Replaying writes the same roles a
 * run writes for its own calls: {@code assistant_tool_call} and {@code tool} for a client with native tool calling,
 * which the adapters already turn into the provider's tool messages, or the text protocol's tool-call line and
 * {@code Observation:} for one without. The model thus sees past calls exactly as it sees the ones of the run in
 * progress.
 *
 * <p><b>What is shortened.</b> The call (tool and arguments) is always replayed; the result whole only on the latest
 * replayed turn, cut at {@value #MAX_RESULT_CHARS} characters, and replaced by {@value #OMITTED} on older turns. The
 * arguments are what tells the model what was done, and in the measurement a placeholder instead of the result did as
 * well as the whole result; keeping the calls and dropping old observations is also what "observation masking" (Lindenbauer
 * et al., 2025, arXiv:2508.21433) found to match LLM summarisation at half the cost. A tool can keep less still through
 * {@link AraTool#historyPolicy()}.
 *
 * <p><b>Discarded.</b> A text note on the answer ("[tools used: …]"), the way attachments are named in a replayed turn:
 * measured at 0 out of 10, worse than nothing, because the model imitates the note instead of calling. Keeping the whole
 * working memory across turns: it would also carry thoughts, retries after a format error and the run's automated
 * instructions, and it conflicts with resetting working memory per task. Reading the run's checkpoint: it is deleted when
 * the run ends, and the default store keeps none.
 *
 * <p>Stateless and thread-safe.
 */
final class ToolCallHistory {

    /** How much of a result the latest replayed turn keeps: enough for an answer from a device or a short lookup. */
    static final int MAX_RESULT_CHARS = 500;

    /** What an older turn shows instead of a result, and what a result the tool chose not to keep shows. */
    static final String OMITTED = "[result omitted]";

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolCallHistory() {
    }

    /**
     * The tool calls of a run, in the order they were made, each with its result. A call whose tool the agent's registry
     * does not know is left out: that is a strategy's own pseudo-call (a plan put up for approval), not a tool, and a
     * tool removed since has no policy to ask. The tool's {@link AraTool#historyPolicy()} decides what remains.
     *
     * <p>The media a call returned follow its result: kept with a result that is kept, dropped with one that is
     * not. They are kept as references only, to say what the session has seen; replaying a turn never sends them
     * again (see {@link #replay}).
     */
    static List<ToolExchange> record(List<ExecutionStep> steps, ToolRegistry tools) {
        List<ExecutionStep> calls = new ArrayList<>();
        List<String> results = new ArrayList<>();          // parallel to calls; null until the call is observed
        List<List<MediaRef>> returned = new ArrayList<>(); // parallel to calls; what the observed call returned
        for (ExecutionStep step : steps) {
            if (step.type() == StepType.TOOL_CALL) {
                calls.add(step);
                results.add(null);
                returned.add(List.of());
            }
            else if (step.type() == StepType.OBSERVATION) {
                int answered = firstUnanswered(calls, results, step.toolId());
                if (answered >= 0) {
                    results.set(answered, Objects.requireNonNullElse(step.content(), ""));
                    returned.set(answered, step.media());
                }
            }
        }
        List<ToolExchange> exchanges = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ExecutionStep call = calls.get(i);
            Optional<AraTool> tool = tools.findById(call.toolId());
            ToolHistoryPolicy policy = tool.map(AraTool::historyPolicy).orElse(ToolHistoryPolicy.NONE);
            if (policy == ToolHistoryPolicy.NONE) {
                continue;
            }
            String result = policy == ToolHistoryPolicy.CALL_ONLY ? "" : Objects.requireNonNullElse(results.get(i), "");
            List<MediaRef> media = policy == ToolHistoryPolicy.CALL_ONLY ? List.of() : returned.get(i);
            exchanges.add(new ToolExchange(call.toolId(), call.arguments(), result, media));
        }
        return exchanges;
    }

    /**
     * The earliest call not yet answered that {@code observedToolId} can answer: the same tool, or any tool when the
     * observation does not say which (a text-only observation). Matching by tool rather than by position keeps two
     * parallel calls to different tools paired right whatever order their results came back in.
     */
    private static int firstUnanswered(List<ExecutionStep> calls, List<String> results, String observedToolId) {
        for (int i = 0; i < calls.size(); i++) {
            if (results.get(i) == null && (observedToolId == null || observedToolId.equals(calls.get(i).toolId()))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Writes the calls of a replayed turn into working memory, between its question and its answer.
     *
     * @param position    the turn's place among the replayed turns, which makes the synthetic call ids unique: the
     *                    original ids are not part of the run's steps
     * @param latest      whether this is the most recent replayed turn, the only one that keeps its results
     * @param nativeTools whether the client speaks native tool calling; otherwise the text protocol's form is used
     */
    static void replay(MemoryManager memory, ConversationTurn turn, int position, boolean latest, boolean nativeTools) {
        List<ToolExchange> calls = turn.toolCalls();
        for (int k = 0; k < calls.size(); k++) {
            ToolExchange call = calls.get(k);
            String result = shown(call.result(), latest);
            if (nativeTools) {
                ToolCallMetadata metadata = new ToolCallMetadata("h" + position + "-" + k, call.toolId());
                memory.appendToWorkingMemory("assistant_tool_call", call.arguments(), metadata);
                memory.appendToWorkingMemory("tool", result, metadata);
            }
            else {
                memory.appendToWorkingMemory("assistant", textCall(call));
                memory.appendToWorkingMemory("user", "Observation: " + result);
            }
        }
    }

    static String shown(String result, boolean latest) {
        if (!latest || result.isEmpty()) {
            return OMITTED;
        }
        return result.length() <= MAX_RESULT_CHARS ? result : result.substring(0, MAX_RESULT_CHARS) + "…";
    }

    /** The text protocol's tool-call line, {@code {"tool_id":…,"arguments":{…}}}, as the model writes it in a run. */
    private static String textCall(ToolExchange call) {
        ObjectNode line = JSON.createObjectNode();
        line.put("tool_id", call.toolId());
        try {
            JsonNode arguments = JSON.readTree(call.arguments());
            line.set("arguments", arguments == null ? JSON.createObjectNode() : arguments);
        }
        catch (Exception notJson) {
            // the model sent arguments that are not JSON; they are replayed as they were, as text
            line.put("arguments", call.arguments());
        }
        return line.toString();
    }
}
