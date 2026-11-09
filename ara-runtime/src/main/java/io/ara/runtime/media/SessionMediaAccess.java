package io.ara.runtime.media;

import io.ara.core.media.MediaAccess;
import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaResolver;
import io.ara.core.media.MediaStore;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The {@link MediaAccess} of one run: the {@link MediaStore} seen through the set of media the session has
 * already had in front of it, plus whatever the run stores from now on.
 *
 * <h2>The visible set is derived, not kept</h2>
 * <p>It is built when the run starts, from what the session already records — the attachments of past turns,
 * the media earlier tool calls returned, the attachments of this task, and, when the run is resumed from a
 * checkpoint, the media in the memory the checkpoint saved. Nothing new is persisted for it. Keeping the set
 * as state of its own, in the session's run state for instance, would hold a second copy of facts the history
 * already has and give two places to keep in step; a derived set cannot disagree with the history it comes from.
 * The cost is that visibility lasts as long as the history does: media of a turn the history no longer holds are
 * no longer visible.
 *
 * <h2>Why the run's own writes are held here</h2>
 * <p>An image a tool stores during the run is visible to the next tool call of the same run, before any turn
 * has been recorded. The set of those writes is the only mutable part of this class and is a concurrent map,
 * because the tools of one block run in parallel on virtual threads and share the instance.
 *
 * <p><b>Thread-safe</b>; confined to one run, which a session executes one at a time.
 */
public final class SessionMediaAccess implements MediaAccess {

    /** Visible media by id. Starts as the media the session has seen and grows with this run's {@link #put}s. */
    private final Map<String, MediaRef> visible = new ConcurrentHashMap<>();
    private final MediaStore store;
    private final MediaResolver resolver;

    /**
     * @param store       where the bytes live; never the no-op store, which is the runtime's way of saying there is
     *                    no media and for which no access is created
     * @param alreadySeen the media this session has already had in front of it, in any order; duplicates are fine
     */
    public SessionMediaAccess(MediaStore store, Collection<MediaRef> alreadySeen) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.resolver = MediaResolver.backedBy(store);
        for (MediaRef reference : Objects.requireNonNull(alreadySeen, "alreadySeen must not be null")) {
            visible.putIfAbsent(reference.mediaId(), reference);
        }
    }

    @Override
    public Optional<MediaRef> resolve(String idOrPrefix) {
        if (idOrPrefix == null || idOrPrefix.isBlank()) {
            return Optional.empty();
        }
        String wanted = idOrPrefix.strip().toLowerCase(Locale.ROOT);
        // An exact id wins even when it is also the prefix of a longer one: a model that copied a whole id
        // meant that media, and calling it ambiguous would be wrong.
        for (MediaRef reference : visible.values()) {
            if (reference.mediaId().toLowerCase(Locale.ROOT).equals(wanted)) {
                return Optional.of(reference);
            }
        }
        List<MediaRef> candidates = visible.values().stream()
                .filter(reference -> reference.mediaId().toLowerCase(Locale.ROOT).startsWith(wanted))
                .sorted(Comparator.comparing(MediaRef::name).thenComparing(MediaRef::mediaId))
                .toList();
        if (candidates.size() > 1) {
            throw new IllegalArgumentException("'" + idOrPrefix.strip() + "' matches more than one media: "
                    + candidates.stream().map(MediaRef::describe).collect(Collectors.joining(", "))
                    + ". Use a longer part of the id.");
        }
        return candidates.stream().findFirst();
    }

    @Override
    public byte[] read(MediaRef reference) {
        Objects.requireNonNull(reference, "reference must not be null");
        MediaRef known = visible.get(reference.mediaId());
        // The same message whether the id is unknown or merely outside this session: telling the two apart
        // would confirm to a caller that an id it should not know exists.
        if (known == null) {
            throw new IllegalArgumentException("Media '" + reference.name() + "' is not available to this session");
        }
        if (known.isExternal()) {
            throw new IllegalArgumentException("Media '" + known.name() + "' is held at " + known.uri()
                    + ", not by ARA, so its bytes cannot be read here");
        }
        return resolver.bytesOf(known);
    }

    @Override
    public MediaRef put(String name, String mimeType, byte[] bytes) {
        MediaRef stored = store.put(name, mimeType, bytes);
        visible.putIfAbsent(stored.mediaId(), stored);
        return stored;
    }
}
