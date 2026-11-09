package io.ara.runtime.memory;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaTypes.MediaKind;
import io.ara.core.memory.EmbeddingClient;
import io.ara.core.memory.EpisodeLabel;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.SemanticEntry;
import io.ara.core.memory.SemanticStore;
import io.ara.core.memory.TokenCounter;
import io.ara.core.telemetry.AraTelemetry;
import io.ara.core.telemetry.Span;
import io.ara.core.telemetry.SpanStatus;
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
 * Defaults to a char-based approximation for text: {@code tokens ≈ chars / 4}. This matches
 * GPT-family tokenisers within ~15 % for Latin-script text — accurate enough for budget
 * enforcement, far worse for CJK and dense JSON. A real tokenizer can be supplied instead via
 * the {@link TokenCounter} constructor parameter (ADR-0087, e.g. {@code JtokkitTokenCounter}
 * in {@code ara-adapters}); a manager built without one keeps exactly the historic
 * {@code chars / 4} behaviour.
 *
 * <p>The estimate is maintained as a running counter (text tokens/chars, depending on which
 * mode is active, plus flat media constants) charged on every mutation and discharged on
 * eviction — {@code O(1)} per append instead of a full-window recount per append, which made
 * a long conversation quadratic in the number of entries. {@link #estimatedTokens()} is
 * package-private so tests can verify the counter against a fresh full recount.
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
 *
 * <h2>Telemetry (ADR-0078 D5)</h2>
 * Emits {@code memory.evict} (once per eviction pass — {@code agent_id}, {@code policy},
 * {@code entries_evicted}, {@code offloaded}, {@code summarized}) and {@code memory.recall}
 * (once per {@link #recallRelevant} call that actually reaches the store — {@code agent_id},
 * {@code recalled_count}). {@code agent_id} is what lets a consumer attribute the span to an
 * agent: the span itself is not tied to a run or an agent otherwise. It is empty for a manager
 * built without an agent id (the short constructors), never absent, so a consumer can bucket it
 * as "unknown" instead of handling a missing key. No preexisting span on either operation to extend, unlike most of this
 * backlog's other D5/D6 decisions — both are new.
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

    /**
     * Running {@code (role+content)} char count across the window, for
     * {@link #estimatedTokens()}. Only maintained — and only meaningful — when
     * {@link #tokenCounter} is {@code null}; see {@link #tokenCount}.
     */
    private int charCount;
    /**
     * Running token count across the window as reported by {@link #tokenCounter}
     * (ADR-0087), for {@link #estimatedTokens()}. Only maintained — and only meaningful —
     * when {@link #tokenCounter} is non-null; mutually exclusive with {@link #charCount},
     * never both at once, so switching which one backs {@link #estimatedTokens()} needs no
     * reconciliation between them.
     */
    private int tokenCount;
    /** Running sum of the flat per-media constants across the window, for {@link #estimatedTokens()}. */
    private int mediaTokenCount;

    // ADR-0078 — all nullable; a manager built without them behaves exactly as before:
    // SUMMARIZE degrades to DROP_MIDDLE (D2), no offload (D3), no recall (D4).
    private final AraAgent        summarizerAgent;
    private final SemanticStore   offloadStore;
    private final EmbeddingClient embeddingClient;
    private final String          agentId;
    private final AraTelemetry    telemetry;
    /** ADR-0087 — nullable; {@code null} keeps the historic {@code chars / 4} estimate. */
    private final TokenCounter    tokenCounter;

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
        this(maxTokens, policy, summarizerAgent, offloadStore, embeddingClient, agentId, AraTelemetry.noop());
    }

    /** Full form plus {@link AraTelemetry} for the {@code memory.evict}/{@code memory.recall} spans (ADR-0078 D5). */
    public SlidingWindowMemoryManager(int maxTokens, EvictionPolicy policy, AraAgent summarizerAgent,
                                      SemanticStore offloadStore, EmbeddingClient embeddingClient, String agentId,
                                      AraTelemetry telemetry) {
        this(maxTokens, policy, summarizerAgent, offloadStore, embeddingClient, agentId, telemetry, null);
    }

    /**
     * Full form plus a {@link TokenCounter} (ADR-0087). {@code null} keeps the historic
     * {@code chars / 4} estimate this class always used before this parameter existed — see
     * {@link TokenCounter#approximate()} and the class javadoc's "Token estimation" section.
     */
    public SlidingWindowMemoryManager(int maxTokens, EvictionPolicy policy, AraAgent summarizerAgent,
                                      SemanticStore offloadStore, EmbeddingClient embeddingClient, String agentId,
                                      AraTelemetry telemetry, TokenCounter tokenCounter) {
        if (maxTokens < 0) throw new IllegalArgumentException("maxTokens must be >= 0");
        this.maxTokens       = maxTokens;
        this.policy          = policy != null ? policy : EvictionPolicy.DROP_MIDDLE;
        this.summarizerAgent = summarizerAgent;
        this.offloadStore    = offloadStore;
        this.embeddingClient = embeddingClient;
        this.agentId         = agentId;
        this.telemetry       = telemetry != null ? telemetry : AraTelemetry.noop();
        this.tokenCounter    = tokenCounter;
    }

    private boolean offloadEnabled() {
        return offloadStore != null && embeddingClient != null && agentId != null;
    }

    @Override
    public void appendToWorkingMemory(MemoryEntry entry) {
        add(entry);
    }

    @Override
    public void clearWorkingMemory() {
        working.clear();
        charCount       = 0;
        tokenCount      = 0;
        mediaTokenCount = 0;
    }

    /** Appends {@code e}, charging it against the running token estimate, then checks the budget. */
    private void add(MemoryEntry e) {
        working.add(e);
        charge(e);
        // A tool result is never a safe eviction point: the tool-call group it belongs to may
        // still be receiving results — recordAssistantOutput writes one header per parallel
        // batch and ReactExecutionSupport.recordObservation appends each result separately,
        // with eviction firing on every append. Evicting here would drop the header together
        // with the first result while later results of the same call are still on their way,
        // stranding them headerless. Defer until the next non-tool append closes the group.
        if (maxTokens > 0 && !"tool".equals(e.role())) {
            evictIfNeeded();
        }
    }

    /**
     * Adds {@code e}'s contribution to the running estimate. Charging here and discharging
     * in {@link #removeRange} keeps {@link #estimatedTokens()} {@code O(1)} per append
     * rather than a full-window recount — see class javadoc.
     */
    private void charge(MemoryEntry e) {
        if (tokenCounter != null) {
            tokenCount += tokenCounter.count(entryText(e));
        } else {
            charCount += (e.role()    != null ? e.role().length()    : 0)
                       + (e.content() != null ? e.content().length() : 0);
        }
        for (MediaRef ref : e.media()) {
            mediaTokenCount += tokensFor(ref);
        }
    }

    private void discharge(MemoryEntry e) {
        if (tokenCounter != null) {
            tokenCount -= tokenCounter.count(entryText(e));
        } else {
            charCount -= (e.role()    != null ? e.role().length()    : 0)
                       + (e.content() != null ? e.content().length() : 0);
        }
        for (MediaRef ref : e.media()) {
            mediaTokenCount -= tokensFor(ref);
        }
    }

    /** {@code role + " " + content}, the text handed to {@link #tokenCounter} (ADR-0087). */
    private static String entryText(MemoryEntry e) {
        String role    = e.role()    != null ? e.role()    : "";
        String content = e.content() != null ? e.content() : "";
        return role + " " + content;
    }

    // ── Eviction ──────────────────────────────────────────────────────────────

    /** One eviction pass' outcome, for the {@code memory.evict} span (ADR-0078 D5). */
    private record EvictionEvent(int entriesEvicted, boolean summarized) {}

    private void evictIfNeeded() {
        while (estimatedTokens() > maxTokens && working.size() > 1) {
            EvictionEvent event = switch (policy) {
                case DROP_OLDEST -> evictOldest();
                case DROP_MIDDLE -> evictMiddle();
                case SUMMARIZE   -> evictSummarize();
            };
            telemetry.spanBuilder("memory.evict")
                    .setAttribute("agent_id", agentId != null ? agentId : "")
                    .setAttribute("policy", policy.name())
                    .setAttribute("entries_evicted", (long) event.entriesEvicted())
                    .setAttribute("offloaded", offloadEnabled())
                    .setAttribute("summarized", event.summarized())
                    .startSpan()
                    .setStatus(SpanStatus.OK)
                    .end();
        }
    }

    private EvictionEvent evictOldest() {
        return evictRange(firstEvictableIndex());
    }

    /**
     * The opening system prompt is never an eviction candidate: the strategies hang the tool
     * catalog and the response format on it (see {@link #recallInsertIndex()}), so evicting it
     * leaves the agent without instructions rather than merely with less history. That holds for
     * {@code DROP_OLDEST} and for the small-window fallback of {@code DROP_MIDDLE} alike — the
     * latter promises to preserve its anchors and used to break that promise below five entries.
     * {@link #evictIfNeeded()} stops at one entry, so a lone system prompt is left in place and
     * the window may stay over budget rather than lose it.
     */
    private int firstEvictableIndex() {
        return "system".equals(working.get(0).role()) ? 1 : 0;
    }

    private EvictionEvent evictMiddle() {
        int size = working.size();
        if (size <= ANCHOR_COUNT * 2) {
            return evictOldest();
        }
        return evictRange(ANCHOR_COUNT);
    }

    /** Evicts the tool-call-group safe range starting at {@code index}: offload, discharge, remove. */
    private EvictionEvent evictRange(int index) {
        int[] bounds = toolCallGroupBounds(index);
        offloadBeforeDiscard(bounds[0], bounds[1]);
        int evicted = bounds[1] - bounds[0];
        removeRange(bounds[0], bounds[1]);
        return new EvictionEvent(evicted, false);
    }

    /**
     * ADR-0078 D2 — replace an evictable middle range with an agent-produced summary.
     * Degrades to {@link #evictMiddle()} (same log message, now literally true) when no
     * summariser is configured, and on any failure/non-success from the summariser, so the
     * worst case is wasted latency, never a stuck turn.
     */
    private EvictionEvent evictSummarize() {
        if (summarizerAgent == null) {
            log.warn("SlidingWindowMemoryManager: SUMMARIZE policy configured with no summarizer agent "
                    + "— degrading to DROP_MIDDLE");
            return evictMiddle();
        }
        // Collapse the whole middle block (between the anchors) into one summary entry, so
        // the window always shrinks by at least one entry — a single-entry summarise could
        // replace an entry with a same-size summary and never make progress.
        int size = working.size();
        // toolCallGroupBounds() reads working.get(ANCHOR_COUNT) on entry, so a window with no
        // room past the two leading anchors cannot even be measured — the "not enough middle to
        // collapse" check below would then come too late, after the read that throws. This is
        // the same shape as evictMiddle's own `size <= ANCHOR_COUNT * 2` guard, and it has to
        // hold for a configuration an operator can reach without thinking about it: any budget
        // below what a single attachment costs (a PDF is a flat 6,000, an image 1,500 —
        // see TOKENS_PER_MEDIA) puts the freshly seeded [system, user] window over budget with
        // only these two entries in it, on the very first append. Degrade, as every other
        // SUMMARIZE edge case here does, instead of throwing out of appendToWorkingMemory.
        if (size <= ANCHOR_COUNT) {
            log.warn("SlidingWindowMemoryManager: SUMMARIZE policy reached with a {}-entry window "
                    + "— too small to have a collapsible middle, degrading to DROP_MIDDLE", size);
            return evictMiddle();
        }
        int start = toolCallGroupBounds(ANCHOR_COUNT)[0];
        int end = size - ANCHOR_COUNT;
        // The first KEPT entry is working.get(end): while it is a tool result, its header sits
        // inside the about-to-be-evicted range and cutting here would orphan it. Walk the
        // boundary down to that group's header so the group stays whole — either fully evicted
        // or fully kept. Checking get(end - 1) instead would be backwards: it pulls trailing
        // tools out of the eviction range while leaving their header in it.
        while (end > start && "tool".equals(working.get(end).role())) {
            end--;
        }
        if (end - start < 2) {
            return evictMiddle();   // not enough middle to collapse without risking an orphaned tool result
        }
        String toSummarize = concatEntries(start, end);
        try {
            AgentResponse summary = summarizerAgent.execute(AgentTask.of(toSummarize));
            offloadBeforeDiscard(start, end);
            int evicted = end - start;
            removeRange(start, end);
            boolean summarized = summary.isSuccess() && summary.content() != null && !summary.content().isBlank();
            if (summarized) {
                MemoryEntry summaryEntry =
                        MemoryEntry.of("system", summary.content(), new EpisodeLabel("context_summary"));
                // The removed range was discharged by removeRange; the replacement entry
                // enters the budget like any other append.
                working.add(start, summaryEntry);
                charge(summaryEntry);
            } else {
                log.warn("SlidingWindowMemoryManager: summarizer agent produced no usable summary "
                        + "— dropped the range instead");
            }
            return new EvictionEvent(evicted, summarized);
        } catch (RuntimeException e) {
            log.warn("SlidingWindowMemoryManager: summarizer agent failed ({}) — degrading to DROP_MIDDLE",
                    e.getMessage());
            offloadBeforeDiscard(start, end);
            int evicted = end - start;
            removeRange(start, end);
            return new EvictionEvent(evicted, false);
        }
    }

    /**
     * ADR-0078 D3 — before a range is discarded, upsert each entry into the episodic
     * {@link SemanticStore} so it can be recalled later ({@link #recallRelevant}). A no-op
     * unless {@code offloadStore}/{@code embeddingClient}/{@code agentId} are all set —
     * every ARA system today.
     *
     * <p>The whole range goes out as a single {@code upsertAll} call: a per-entry upsert
     * meant one HTTP round-trip to Qdrant per evicted entry, synchronously on the strategy
     * thread — a 10-entry eviction cost 10 RTTs before the agent could continue. Per-entry
     * embedding failures are still swallowed individually (embedding can be a remote call
     * too); a failed batch write is logged and swallowed — offload is best-effort, it must
     * never break eviction.
     *
     * <p><b>P5/U18, 2026-09-23 — interrupt hygiene, not a new budget parameter:</b> neither
     * {@code embed} nor {@code appendToWorkingMemory} (hence this whole eviction path) takes
     * a deadline — {@link io.ara.core.memory.MemoryManager} has no such parameter today, and
     * adding one is a breaking interface change across every implementation and call site,
     * out of this unit's scope. What <em>is</em> in scope and was a real bug: when the
     * strategy thread this runs on <em>is</em> externally interrupted (the deadline watchdog
     * that already wraps this same thread's LLM calls elsewhere in {@code
     * ReactExecutionSupport} et al.), {@code embed}'s {@code RuntimeException} wrapper — like
     * most — does not restore the interrupt flag itself, and the catch below used to just
     * log and move on to the <em>next</em> entry regardless, one multi-second remote RTT at a
     * time, instead of noticing the deadline had already fired and stopping. Checked once per
     * iteration (cheap — this method has no other cheap cancellation point) and once more
     * before the batch write, since {@code upsertAll} is itself another unbounded remote
     * call.
     */
    private void offloadBeforeDiscard(int fromInclusive, int toExclusive) {
        if (!offloadEnabled()) {
            return;
        }
        List<SemanticEntry> batch = new ArrayList<>();
        for (int i = fromInclusive; i < toExclusive && i < working.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                log.warn("SlidingWindowMemoryManager: offload interrupted — stopping after {} of {} entries",
                        i - fromInclusive, toExclusive - fromInclusive);
                return;
            }
            MemoryEntry e = working.get(i);
            if (e.content() == null || e.content().isBlank()) {
                continue;
            }
            try {
                batch.add(new SemanticEntry(e.role(), "evicted_context",
                        e.content(), embeddingClient.embed(e.content())));
            } catch (RuntimeException ex) {
                log.warn("SlidingWindowMemoryManager: offload of an evicted entry failed ({})", ex.getMessage());
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        if (Thread.currentThread().isInterrupted()) {
            log.warn("SlidingWindowMemoryManager: offload interrupted — skipping upsertAll for {} collected entries",
                    batch.size());
            return;
        }
        try {
            offloadStore.upsertAll(agentId, batch);
        } catch (RuntimeException ex) {
            log.warn("SlidingWindowMemoryManager: offload of an evicted range failed ({})", ex.getMessage());
        }
    }

    @Override
    public void recallRelevant(String queryText, int maxResults) {
        if (!offloadEnabled() || queryText == null || queryText.isBlank() || maxResults <= 0) {
            return;
        }
        Span span = telemetry.spanBuilder("memory.recall")
                .setAttribute("agent_id", agentId)
                .startSpan();
        try (var scope = span.makeCurrent()) {
            List<MemoryEntry> hits;
            try {
                hits = offloadStore.search(agentId, embeddingClient.embed(queryText), maxResults);
            } catch (RuntimeException e) {
                log.warn("SlidingWindowMemoryManager: recall search failed ({})", e.getMessage());
                span.setAttribute("recalled_count", 0L).setStatus(SpanStatus.ERROR);
                return;
            }
            if (hits == null || hits.isEmpty()) {
                span.setAttribute("recalled_count", 0L).setStatus(SpanStatus.OK);
                return;
            }
            List<MemoryEntry> recalled = hits.stream()
                    .filter(h -> h.content() != null && !h.content().isBlank())
                    .map(h -> MemoryEntry.of(h.role() != null ? h.role() : "system",
                            h.content(), new EpisodeLabel("recalled")))
                    .collect(Collectors.toCollection(ArrayList::new));
            working.addAll(recallInsertIndex(), recalled);
            for (MemoryEntry e : recalled) {
                charge(e);
            }
            span.setAttribute("recalled_count", (long) recalled.size()).setStatus(SpanStatus.OK);
        } finally {
            span.end();
        }
    }

    /**
     * Where recalled episodes land in the window. Index 0 holds the agent's system prompt,
     * so prepending at 0 would push the system entry below the recalled ones and — via the
     * strategies' first-system-message enhancement — silently drop the tool catalog and
     * format instructions from every LLM call of the turn. Recall therefore slots its hits
     * just after the opening system entry, keeping the system prompt first and the recalled
     * context ahead of the replayed/new conversation.
     */
    private int recallInsertIndex() {
        for (int i = 0; i < working.size(); i++) {
            if ("system".equals(working.get(i).role())) {
                return i + 1;
            }
        }
        return 0;
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
        if (fromInclusive >= toExclusive) {
            return;
        }
        // Discharge first: the sublist view is tied to the list contents, so it must be
        // read before the single range-removal below invalidates it.
        for (MemoryEntry e : working.subList(fromInclusive, toExclusive)) {
            discharge(e);
        }
        working.subList(fromInclusive, toExclusive).clear();
    }

    /**
     * Returns the running token estimate for the whole window — {@link #tokenCounter}'s
     * running total when one is configured, otherwise {@code chars / 4} — plus the flat
     * media constants. Package-private so tests can assert it equals a fresh full recount.
     */
    int estimatedTokens() {
        int textTokens = tokenCounter != null ? tokenCount : charCount / CHARS_PER_TOKEN;
        return textTokens + mediaTokenCount;
    }

    private static int tokensFor(MediaRef ref) {
        return TOKENS_PER_MEDIA.get(ref.kind());
    }
}
