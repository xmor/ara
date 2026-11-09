package io.ara.runtime.strategy;

import io.ara.core.llm.LlmMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Which tool-returned media a model call carries: only those of the last block of tool results.
 *
 * <h2>Why a window, and why here</h2>
 * <p>An image costs tokens on every call that includes it, and a ReAct run makes a call per
 * iteration: an image returned at iteration 2 would be paid for again at iterations 3 to 15,
 * though the model has by then said what it saw. So the images of earlier blocks are left out of
 * the outgoing messages. The text of the tool message stays, including the {@code Attached:} line
 * that names the media, so the model still knows the image exists and can ask for it again with
 * {@code view_media}.
 *
 * <p>This is a pure function over the list about to be sent, applied after the list is rebuilt
 * from memory and before the adapter converts it. It is not in the adapter, which must not decide
 * what the history is, and not in memory, which keeps every media so a later turn, a checkpoint
 * and {@code view_media} can still reach them. Alternatives discarded: dropping media from memory
 * (irreversible, and breaks the session's visible set), and a per-adapter cutoff (each provider
 * would then show a different history for the same run).
 *
 * <h2>What is a block, and what is not touched</h2>
 * <p>A block is a maximal run of consecutive {@code "tool"} messages — the results of one assistant
 * turn's calls. The window keeps the media of the block that contains the <em>last</em> tool message
 * and strips those of every {@code "tool"} message before it. Messages of any other role are
 * returned as they are: a user's attachments stay visible for the whole turn, as before this
 * existed. A text-protocol observation (a {@code "user"} turn written when the call had no id to pair
 * with) is a user message to this function and is therefore not windowed; native tool calling, the
 * path media in tool results is designed for, is.
 *
 * <p><b>Thread-safety:</b> stateless; never mutates its input.
 */
final class ToolMediaWindow {

    private static final String TOOL_ROLE = "tool";

    private ToolMediaWindow() {}

    /**
     * @param messages the messages about to be sent, oldest first
     * @return {@code messages} itself when there is nothing to strip, otherwise a copy in which the
     *         tool messages before the last block carry no media
     */
    static List<LlmMessage> apply(List<LlmMessage> messages) {
        int last = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (TOOL_ROLE.equals(messages.get(i).role())) {
                last = i;
                break;
            }
        }
        if (last < 0) {
            return messages;
        }
        int blockStart = last;
        while (blockStart > 0 && TOOL_ROLE.equals(messages.get(blockStart - 1).role())) {
            blockStart--;
        }
        List<LlmMessage> windowed = null;
        for (int i = 0; i < blockStart; i++) {
            LlmMessage m = messages.get(i);
            if (TOOL_ROLE.equals(m.role()) && m.hasMedia()) {
                if (windowed == null) {
                    windowed = new ArrayList<>(messages);
                }
                windowed.set(i, LlmMessage.tool(m.toolCallId(), m.toolName(), m.content()));
            }
        }
        return windowed != null ? windowed : messages;
    }
}
