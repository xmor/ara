package io.ara.runtime.memory;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaTypes.MediaKind;
import io.ara.core.memory.EmbeddingClient;
import io.ara.core.memory.EpisodeLabel;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.SemanticStore;
import io.ara.core.memory.ToolCallMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Token-budget-aware {@link io.ara.core.memory.MemoryManager} that evicts working-memory
 * entries when the estimated token count exceeds {@code maxTokens}.
 *
 * <h2>Token estimation</h2>
 * Uses a char-based approximation for text: {@code tokens ≈ chars / 4}. This matches
 * GPT-family tokenisers within ~15 % for Latin-script text — accurate enough for budget
 * enforcement.
 *
 * <p>Media on an entry adds a <em>flat constant per category</em>, not a function of the
 * payload size. That is a deliberately coarse choice, and it is safe because the collapse it
 * might otherwise have to defend against cannot happen: an entry holds a {@code MediaRef},
 * not bytes, so its character count is negligible whatever a document actually costs, and the
 * window can no longer empty itself trying to fit one. The constants therefore only need to
 * keep the estimate in the right order of magnitude, and a constant is far easier to tune and
 * to test than a size curve fitted to no measurement.
 *
 * <h2>Eviction</h2>
 * Controlled by {@link EvictionPolicy}:
 * <ul>
 *   <li>{@code DROP_OLDEST} — remove from head until budget is met</li>
 *   <li>{@code DROP_MIDDLE} — preserve first N and last N entries, drop the middle</li>
 *   <li>{@code SUMMARIZE} — replace an evictable range with an agent-produced summary
 *       (ADR-0078 D2); degrades to {@code DROP_MIDDLE} with a warning when no summariser
 *       agent is configured or the summariser fails</li>
 * </ul>
 *
 * <p>ADR-0078 also adds optional episodic offload: an evicted range can be upserted into a
 * {@link SemanticStore} before it is discarded (D3) and pulled back via
 * {@link #recallRelevant} (D4). Both are inert unless a store, an {@link EmbeddingClient}
 * and an {@code agentId} are all supplied.
 */
public final class SlidingWindowMemoryManager extends AbstractMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowMemoryManager.class);

    private static final int CHARS_PER_TOKEN = 4;
    private static final int ANCHOR_COUNT    = 2;

    /**
     * What one attachment is charged against the budget, by category. Chosen on the high side
     * of what providers actually bill so the estimate errs toward evicting early rather than
     * overflowing the model's context.
     *
     * <p><b>Known limitation, stated rather than hidden:</b> a flat constant cannot be an
     * over-estimate for every payload. A very large text file inlined into the prompt will
     * cost more than {@code TEXT} says, because that is the one category whose real token
     * count scales directly with its bytes. A size-derived figure was rejected on purpose —
     * see the class javadoc — so the guard against that case is a quantitative cap on
     * attachment size before the task runs ({@code MediaValidator}), not a cleverer estimate
     * here. If a deployment routinely attaches multi-megabyte text, raise {@code TEXT}.
     */
    private static final Map<MediaKind, Integer> TOKENS_PER_MEDIA = Map.of(
            MediaKind.IMAGE,    1_500,
            MediaKind.DOCUMENT, 6_000,
            MediaKind.TEXT,     4_000
    );

    private final int            maxTokens;
    private final EvictionPolicy policy;

    // ADR-0078 — all nullable; a manager built without them behaves exactly as before:
    // SUMMARIZE degrades to DROP_MIDDLE (D2), no offload (D3), no recall (D4).
    private final AraAgent        summarizerAgent;
    private final SemanticStore   offloadStore;
    private final EmbeddingClient embeddingClient;
    private final String          agentId;

    /**
     * @param maxTokens token budget for working memory (0 = unlimited)
     * @param policy    eviction strategy applied when budget is exceeded
     */
    public SlidingWindowMemoryManager(int maxTokens, EvictionPolicy policy) {
        this(maxTokens, policy, null, null, null, null);
    }

    /**
     * With a summariser agent for {@link EvictionPolicy#SUMMARIZE} (ADR-0078 D2). A
     * {@code null} agent keeps the historic fallback: SUMMARIZE degrades to DROP_MIDDLE.
     */
    public SlidingWindowMemoryManager(int maxTokens, EvictionPolicy policy, AraAgent summarizerAgent) {
        this(maxTokens, policy, summarizerAgent, null, null, null);
    }

    /**
     * Full form (ADR-0078 D2–D4). {@code offloadStore}/{@code embeddingClient}/{@code agentId}
     * enable offloading an evicted range to episodic memory before it is discarded (D3) and
     * {@link #recallRelevant} (D4); all three must be non-null for either to activate.
     *
     * @param summarizerAgent an {@link AraAgent} that turns a text block into a summary; nullable
     * @param offloadStore    the episodic store (reuses {@link SemanticStore}, ADR-0078 D3); nullable
     * @param embeddingClient embeds text before {@code upsert}/{@code search}; nullable
     * @param agentId         partition key passed to every {@code offloadStore} call so one
     *                        agent's offloaded memories are never mixed into another's
     *                        search results in a shared store (ADR-0060); nullable
     */
    public SlidingWindowMemoryManager(int maxTokens, EvictionPolicy policy, AraAgent summarizerAgent,
                                      SemanticStore offloadStore, EmbeddingClient embeddingClient, String agentId) {
        if (maxTokens < 0) throw new IllegalArgumentException("maxTokens must be >= 0");
        this.maxTokens       = maxTokens;
        this.policy          = policy != null ? policy : EvictionPolicy.DROP_MIDDLE;
        this.summarizerAgent = summarizerAgent;
        this.offloadStore    = offloadStore;
        this.embeddingClient = embeddingClient;
        this.agentId         = agentId;
    }

    private boolean offloadEnabled() {
        return offloadStore != null && embeddingClient != null && agentId != null;
    }

    @Override
    public void appendToWorkingMemory(String role, String content) {
        working.add(MemoryEntry.of(role, content));
        if (maxTokens > 0) evictIfNeeded();
    }

    @Override
    public void appendToWorkingMemory(String role, String content, ToolCallMetadata metadata) {
        working.add(MemoryEntry.of(role, content, metadata));
        if (maxTokens > 0) evictIfNeeded();
    }

    @Override
    public void appendToWorkingMemory(String role, String content, java.util.List<MediaRef> media) {
        working.add(MemoryEntry.of(role, content, media));
        if (maxTokens > 0) evictIfNeeded();
    }

    // ── Eviction ──────────────────────────────────────────────────────────────

    private void evictIfNeeded() {
        while (estimatedTokens() > maxTokens && working.size() > 1) {
            switch (policy) {
                case DROP_OLDEST -> evictOldest();
                case DROP_MIDDLE -> evictMiddle();
                case SUMMARIZE   -> evictSummarize();
            }
        }
    }

    private void evictOldest() {
        if (working.isEmpty()) return;
        int[] bounds = toolCallGroupBounds(0);
        offloadBeforeDiscard(bounds[0], bounds[1]);
        removeRange(bounds[0], bounds[1]);
    }

    private void evictMiddle() {
        int size = working.size();
        if (size <= ANCHOR_COUNT * 2) {
            evictOldest();
            return;
        }
        int[] bounds = toolCallGroupBounds(ANCHOR_COUNT);
        offloadBeforeDiscard(bounds[0], bounds[1]);
        removeRange(bounds[0], bounds[1]);
    }

    /**
     * ADR-0078 D2 — replace an evictable middle range with an agent-produced summary.
     * Degrades to {@link #evictMiddle()} (same log message, now literally true) when no
     * summariser is configured, and on any failure/non-success from the summariser, so the
     * worst case is wasted latency, never a stuck turn.
     */
    private void evictSummarize() {
        if (summarizerAgent == null) {
            log.warn("SlidingWindowMemoryManager: SUMMARIZE policy configured with no summarizer agent "
                    + "— degrading to DROP_MIDDLE");
            evictMiddle();
            return;
        }
        // Collapse the whole middle block (between the anchors) into one summary entry, so
        // the window always shrinks by at least one entry — a single-entry summarise could
        // replace an entry with a same-size summary and never make progress.
        int size = working.size();
        int start = toolCallGroupBounds(ANCHOR_COUNT)[0];
        int end = size - ANCHOR_COUNT;
        while (end > start && "tool".equals(working.get(end - 1).role())) {
            end--;   // never end mid tool-call group
        }
        if (end - start < 2) {
            evictMiddle();   // not enough middle to collapse without risking an orphaned tool result
            return;
        }
        String toSummarize = concatEntries(start, end);
        try {
            AgentResponse summary = summarizerAgent.execute(AgentTask.of(toSummarize));
            offloadBeforeDiscard(start, end);
            removeRange(start, end);
            if (summary.isSuccess() && summary.content() != null && !summary.content().isBlank()) {
                working.add(start,
                        MemoryEntry.of("system", summary.content(), new EpisodeLabel("context_summary")));
            } else {
                log.warn("SlidingWindowMemoryManager: summarizer agent produced no usable summary "
                        + "— dropped the range instead");
            }
        } catch (RuntimeException e) {
            log.warn("SlidingWindowMemoryManager: summarizer agent failed ({}) — degrading to DROP_MIDDLE",
                    e.getMessage());
            offloadBeforeDiscard(start, end);
            removeRange(start, end);
        }
    }

    /**
     * ADR-0078 D3 — before a range is discarded, upsert each entry into the episodic
     * {@link SemanticStore} so it can be recalled later ({@link #recallRelevant}). A no-op
     * unless {@code offloadStore}/{@code embeddingClient}/{@code agentId} are all set —
     * every ARA system today. A failed upsert is logged and swallowed: offload is
     * best-effort, it must never break eviction.
     */
    private void offloadBeforeDiscard(int fromInclusive, int toExclusive) {
        if (!offloadEnabled()) {
            return;
        }
        for (int i = fromInclusive; i < toExclusive && i < working.size(); i++) {
            MemoryEntry e = working.get(i);
            if (e.content() == null || e.content().isBlank()) {
                continue;
            }
            try {
                offloadStore.upsert(agentId, e.role(), "evicted_context",
                        e.content(), embeddingClient.embed(e.content()));
            } catch (RuntimeException ex) {
                log.warn("SlidingWindowMemoryManager: offload of an evicted entry failed ({})", ex.getMessage());
            }
        }
    }

    @Override
    public void recallRelevant(String queryText, int maxResults) {
        if (!offloadEnabled() || queryText == null || queryText.isBlank() || maxResults <= 0) {
            return;
        }
        List<MemoryEntry> hits;
        try {
            hits = offloadStore.search(agentId, embeddingClient.embed(queryText), maxResults);
        } catch (RuntimeException e) {
            log.warn("SlidingWindowMemoryManager: recall search failed ({})", e.getMessage());
            return;
        }
        if (hits == null || hits.isEmpty()) {
            return;
        }
        List<MemoryEntry> recalled = hits.stream()
                .filter(h -> h.content() != null && !h.content().isBlank())
                .map(h -> MemoryEntry.of(h.role() != null ? h.role() : "system",
                        h.content(), new EpisodeLabel("recalled")))
                .collect(Collectors.toCollection(ArrayList::new));
        working.addAll(0, recalled);
    }

    private String concatEntries(int fromInclusive, int toExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = fromInclusive; i < toExclusive && i < working.size(); i++) {
            MemoryEntry e = working.get(i);
            if (i > fromInclusive) sb.append('\n');
            sb.append(e.role()).append(": ").append(e.content() == null ? "" : e.content());
        }
        return sb.toString();
    }

    /**
     * Returns the {@code [start, end)} range of the atomic "tool-call group" containing
     * {@code index}: one {@code "assistant_tool_call"}/{@code "assistant_tool_calls"} header
     * entry plus every {@code "tool"}-role result that immediately follows it.
     *
     * <p>{@code ReactStrategy}/{@code PlanExecuteStrategy} always write such a group as
     * consecutive entries with nothing interleaved. Evicting only part of one — e.g. the
     * header but not its result, or a result but not its header — leaves an orphaned {@code
     * "tool"} message with no preceding tool call once {@code ToolConversionUtils
     * .toNativeAwareChatMessage} reconstructs it natively; real providers (OpenAI) reject
     * that outright with a 400. For any entry outside such a group, the range is just
     * {@code [index, index + 1)} — unchanged single-entry eviction.
     */
    private int[] toolCallGroupBounds(int index) {
        int start = index;
        if ("tool".equals(working.get(start).role())) {
            while (start > 0 && "tool".equals(working.get(start - 1).role())) start--;
            if (start > 0 && isToolCallHeader(working.get(start - 1).role())) start--;
        }

        int end = start + 1;
        if (isToolCallHeader(working.get(start).role())) {
            while (end < working.size() && "tool".equals(working.get(end).role())) end++;
        }
        return new int[]{start, end};
    }

    private static boolean isToolCallHeader(String role) {
        return "assistant_tool_call".equals(role) || "assistant_tool_calls".equals(role);
    }

    private void removeRange(int fromInclusive, int toExclusive) {
        for (int i = toExclusive - 1; i >= fromInclusive; i--) {
            working.remove(i);
        }
    }

    private int estimatedTokens() {
        int chars = working.stream()
                .mapToInt(e -> (e.role()    != null ? e.role().length()    : 0)
                             + (e.content() != null ? e.content().length() : 0))
                .sum();
        int mediaTokens = working.stream()
                .flatMap(e -> e.media().stream())
                .mapToInt(SlidingWindowMemoryManager::tokensFor)
                .sum();
        return chars / CHARS_PER_TOKEN + mediaTokens;
    }

    private static int tokensFor(MediaRef ref) {
        return TOKENS_PER_MEDIA.get(ref.kind());
    }
}
