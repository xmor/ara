package io.ara.runtime.media;

import io.ara.core.media.MediaAccess;
import io.ara.core.media.MediaRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the media a final answer cites by id, so they can be handed back to the caller beside the text.
 *
 * <p>The model chooses what to return by naming it — "here is the photo: crop-2.png (id 3f9a1c2e7b4d)" — and the
 * runtime turns that into references. Alternatives discarded: a dedicated "return this media" tool (one more call
 * the model must remember to make, and a second channel beside the answer), and returning every media a tool
 * produced (a crop the model tried and rejected would come back as if it were the result).
 *
 * <h2>What counts as a citation</h2>
 * <p>A run of at least {@value #MIN_HEX} hexadecimal characters, not part of a longer word, that the run's
 * {@link MediaAccess} resolves. Only what resolves is returned: an invented id, an id of another session and an
 * ambiguous prefix all fail to resolve and are skipped with a {@code debug} line, because the text is the answer and
 * a bad citation must never turn it into a failure. The test of visibility is the one every tool is held to, so an
 * answer cannot be used to pull out a media the session never saw.
 *
 * <p>Pure apart from the log line; stateless; thread-safe.
 */
public final class CitedMedia {

    /** Same length as {@link MediaRef#SHORT_ID_LENGTH}: the id the model is shown is the shortest it can cite. */
    static final int MIN_HEX = MediaRef.SHORT_ID_LENGTH;

    private static final Pattern HEX_RUN = Pattern.compile("(?<![0-9A-Za-z])[0-9a-fA-F]{" + MIN_HEX + ",}(?![0-9A-Za-z])");

    private static final Logger log = LoggerFactory.getLogger(CitedMedia.class);

    private CitedMedia() {}

    /**
     * @param extracted the artifacts already produced for this answer, kept first and in order
     * @param text      the final answer
     * @param access    the run's view of its session's media
     * @return {@code extracted} followed by the media {@code text} cites, in order of appearance, each once;
     *         {@code extracted} itself when nothing is cited
     */
    public static List<MediaRef> appendCited(List<MediaRef> extracted, String text, MediaAccess access) {
        if (text == null || text.isEmpty() || access == null) {
            return extracted;
        }
        Map<String, MediaRef> result = new LinkedHashMap<>();
        for (MediaRef ref : extracted) {
            result.putIfAbsent(ref.mediaId(), ref);
        }
        int before = result.size();
        Matcher matcher = HEX_RUN.matcher(text);
        while (matcher.find()) {
            String candidate = matcher.group();
            try {
                access.resolve(candidate).ifPresentOrElse(
                        ref -> result.putIfAbsent(ref.mediaId(), ref),
                        () -> log.debug("The answer cites '{}', which is not a media of this session; ignored", candidate));
            } catch (IllegalArgumentException ambiguous) {
                log.debug("The answer cites '{}', which is ambiguous; ignored: {}", candidate, ambiguous.getMessage());
            }
        }
        return result.size() == before ? extracted : new ArrayList<>(result.values());
    }
}
