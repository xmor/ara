package io.ara.core.llm;

import io.ara.core.media.MediaRef;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.ToolCallMetadata;

import java.util.List;
import java.util.Objects;

/**
 * A single message in the conversation history sent to an {@link LlmClient}.
 *
 * <h2>Media travels beside the text, not inside it</h2>
 * <p>{@link #media} is a peer of {@link #content}, and holds references rather than bytes.
 * The alternative — adopting a provider SDK's list-of-content-parts shape as the domain
 * type — would tie {@code ara-core} to one SDK and force every {@code LlmClient}, including
 * stubs, decorators and non-SDK clients, to reason in parts even when the provider
 * underneath has none. Flattening into parts is the adapter's job.
 *
 * <p>Keeping media as a peer list does lose positional information: "this text, then
 * <em>this</em> image, then that text" cannot be expressed inside one message. That is
 * acceptable because the real shape of these requests is a short prompt plus one or more
 * attachments, not prose interleaving ten images — and a caller that genuinely needs
 * fine-grained interleaving can split the content across consecutive {@code user} messages,
 * whose order the message list already preserves. So {@code MediaRef} carries no position
 * field. What the peer list <em>does</em> require is one fixed flattening convention, or
 * each adapter would invent its own: the text comes first, then the media in list order.
 * {@code ToolConversionUtils} in {@code ara-adapters} applies it in a single place.
 *
 * @param role        the speaker role: {@code "system"}, {@code "user"}, {@code "assistant"},
 *                    {@code "assistant_tool_call"}, or {@code "tool"}
 * @param content     the text content (or tool arguments JSON for {@code "assistant_tool_call"})
 * @param toolCallId  OpenAI tool-call id — non-null for {@code "assistant_tool_call"} and
 *                    {@code "tool"} roles; {@code null} for all other roles
 * @param toolName    the tool name — non-null for {@code "assistant_tool_call"} and {@code "tool"}
 *                    roles; {@code null} for all other roles
 * @param media       images and documents accompanying this message, in presentation order;
 *                    never {@code null}, and meaningful for the {@code "user"} and {@code "tool"}
 *                    roles. No provider accepts attachments on a system message. A tool result
 *                    is different: OpenAI-compatible endpoints take only text in a tool message,
 *                    so what an adapter does with the media of a {@code "tool"} message — send
 *                    them in a following user message, or inside the result where the provider
 *                    allows it — is its decision, and the message just says which media belong to
 *                    which result
 * @param reasoning   what the model reasoned before making this tool call, as its provider
 *                    returned it; {@code null} when there was none, and only meaningful for the
 *                    {@code "assistant_tool_call"} role. See {@link #assistantToolCall(String,
 *                    String, String, String)} for why it travels back
 */
public record LlmMessage(String role, String content, String toolCallId, String toolName,
                         List<MediaRef> media, String reasoning) {

    public LlmMessage {
        Objects.requireNonNull(role,    "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        media = media != null ? List.copyOf(media) : List.of();
        // Blank is no reasoning: an empty field sent back is noise at best, and a null one is
        // worse — llama.cpp's gpt-oss template fails the whole request on it.
        reasoning = reasoning != null && !reasoning.isBlank() ? reasoning : null;
    }

    /**
     * The 5-arg shape before {@code reasoning}, kept so every existing call site compiles and
     * behaves identically: no reasoning.
     */
    public LlmMessage(String role, String content, String toolCallId, String toolName,
                      List<MediaRef> media) {
        this(role, content, toolCallId, toolName, media, null);
    }

    /**
     * Text-only 4-arg constructor.
     *
     * <p>Kept as an overload rather than left to callers of the canonical constructor
     * because adding {@code media} changed that constructor's arity: this keeps every
     * existing {@code new LlmMessage(role, content, toolCallId, toolName)} call site
     * compiling and behaving identically.
     */
    public LlmMessage(String role, String content, String toolCallId, String toolName) {
        this(role, content, toolCallId, toolName, List.of());
    }

    /** Backward-compatible 2-arg constructor — {@code toolCallId} and {@code toolName} default to {@code null}. */
    public LlmMessage(String role, String content) {
        this(role, content, null, null, List.of());
    }

    /** Creates a system-role message (the agent's system prompt). */
    public static LlmMessage system(String content) {
        return new LlmMessage("system", content);
    }

    /** Creates a user-role message (the human's input or a plain text tool observation). */
    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }

    /**
     * Creates a user-role message carrying media. {@code content} may be blank — a task
     * that is only a document has no words of its own to send.
     */
    public static LlmMessage user(String content, List<MediaRef> media) {
        return new LlmMessage("user", content, null, null, media);
    }

    /** Creates an assistant-role message (a previous LLM response in the history). */
    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content);
    }

    /**
     * Creates an assistant message that represents a native OpenAI tool call.
     * The {@code argsJson} is the raw JSON arguments; {@code toolCallId} and {@code toolName}
     * are needed to pair this message with the corresponding {@link #tool} result.
     */
    public static LlmMessage assistantToolCall(String toolCallId, String toolName, String argsJson) {
        return assistantToolCall(toolCallId, toolName, argsJson, null);
    }

    /**
     * Creates an assistant tool-call message that also carries the reasoning the model did
     * before the call.
     *
     * <p>A reasoning model that calls tools reasons, calls, reads the result and reasons again,
     * all in one turn, and expects to find its own earlier reasoning in front of each earlier
     * call when it is asked for the next one. gpt-oss is the clear case: served by llama.cpp
     * without it, it starts its reply with a malformed tool-call header often enough that the
     * server fails the call with a 500 ("does not match the expected peg-native format"). On the
     * requests that failed in a plan_execute run, 18 calls in 20 failed without the reasoning and
     * none with it. Anthropic states the same rule for its thinking blocks during tool use.
     *
     * @param reasoning the completion's reasoning, or {@code null} when the provider returned none
     */
    public static LlmMessage assistantToolCall(String toolCallId, String toolName, String argsJson,
                                               String reasoning) {
        return new LlmMessage("assistant_tool_call",
                argsJson != null ? argsJson : "{}",
                toolCallId, toolName, List.of(), reasoning);
    }

    /**
     * Creates a tool-result message for native OpenAI function calling.
     * Must be preceded in the conversation history by an {@link #assistantToolCall} with the
     * same {@code toolCallId}.
     */
    public static LlmMessage tool(String toolCallId, String toolName, String result) {
        return new LlmMessage("tool",
                result != null ? result : "",
                toolCallId, toolName);
    }

    /**
     * Creates a tool-result message whose tool also returned media. {@code result} is the text of
     * the result, which should already name each media (see {@code ToolResult#content()}): the
     * references say which media belong to this result, not how the model will see them.
     */
    public static LlmMessage tool(String toolCallId, String toolName, String result, List<MediaRef> media) {
        return new LlmMessage("tool",
                result != null ? result : "",
                toolCallId, toolName, media);
    }

    /** {@code true} when this message carries at least one media reference. */
    public boolean hasMedia() {
        return !media.isEmpty();
    }

    // ── Memory <-> message: the two conversions, and the only two ────────────────────────────

    /**
     * The message a {@link MemoryEntry} is sent as: role, content, the call id and tool name of
     * a tool-call entry, and the media. Every strategy that rebuilds a conversation from working
     * memory goes through here.
     *
     * <h2>Why one pair of conversions, and why both live in this class</h2>
     * <p>Before they existed, three places copied fields between an entry and a message by hand
     * — the strategies' {@code materialize}, the checkpoint restore in {@code AgentInstance}, and
     * the per-field overloads of {@code MemoryManager} — and each copied the fields it knew
     * about on the day it was written. The media of a tool-call entry were dropped by the first
     * two for that reason, and the next field added to {@link MemoryEntry} would have been
     * dropped the same way. One pair of conversions, covered by a round-trip test that lists the
     * record's components, turns that mistake into a failing test.
     *
     * <p>Both directions are here and not one in each type because {@code io.ara.core.llm}
     * already depends on the media types and this adds {@code memory} to its imports; the
     * reverse placement would make the two packages depend on each other.
     *
     * <h2>What does not survive, by design</h2>
     * <ul>
     *   <li>the entry's timestamp — a message has no clock; the entry made from a message is
     *       stamped when it is made;</li>
     *   <li>an {@link io.ara.core.memory.EpisodeLabel}: it classifies an entry inside the
     *       memory tiers and means nothing to a provider;</li>
     *   <li>{@link #reasoning()}, in the other direction: a memory entry has no place for it.
     *       ReAct records reasoning as an execution step, and only plan-execute's step-local
     *       history, which is a list of messages and never goes through memory, keeps it on the
     *       message.</li>
     * </ul>
     * A test pins this list: a component added to {@code MemoryEntry} that is neither carried
     * here nor named there fails it.
     */
    public static LlmMessage of(MemoryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        if (entry.metadata() instanceof ToolCallMetadata call && isToolCallRole(entry.role())) {
            return new LlmMessage(entry.role(), entry.content(), call.callId(), call.toolName(), entry.media());
        }
        return new LlmMessage(entry.role(), entry.content(), null, null, entry.media());
    }

    /**
     * The memory entry this message is stored as: the inverse of {@link #of(MemoryEntry)}, used
     * when a saved conversation is put back into working memory. See {@link #of(MemoryEntry)}
     * for what is not carried across.
     *
     * <p>A tool-call message that lacks its id or its name becomes a plain entry: the metadata
     * record refuses a null, and an entry without correlation data is what the text protocol
     * stores anyway.
     */
    public MemoryEntry toMemoryEntry() {
        if (isToolCallRole(role) && toolCallId != null && toolName != null) {
            return new MemoryEntry(role, content, java.time.Instant.now(),
                    new ToolCallMetadata(toolCallId, toolName), media);
        }
        return MemoryEntry.of(role, content, media);
    }

    private static boolean isToolCallRole(String role) {
        return "tool".equals(role) || "assistant_tool_call".equals(role);
    }
}
