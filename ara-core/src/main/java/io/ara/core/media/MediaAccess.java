package io.ara.core.media;

import java.util.Optional;

/**
 * The media a tool may read and write while it runs, limited to what its session has already seen.
 *
 * <p>A tool that crops, renders or inspects an image needs the bytes of media the model was shown,
 * and somewhere to store what it produces. This is where it gets both. It reaches the tool through
 * the task, under {@code RunContext.MEDIA_ACCESS_KEY} (see {@code RunContext#mediaAccess()}); a
 * runtime without a {@link MediaStore} offers none, and a tool that needs it answers with a failed
 * {@code ToolResult} saying media are not available.
 *
 * <h2>Why a view and not the store</h2>
 * <p>The runtime has one {@link MediaStore} for all its agents and sessions, and an id is the digest
 * of the bytes. Handing a tool the store itself would let it read the image of any session whose id
 * it can name, and an id can be named by anything the model reads: a document may carry text that
 * tells the model to ask a tool for a particular id. So a tool sees only the media its own session
 * has already had in front of it: the attachments of this and earlier turns, the media earlier tool
 * calls returned, and what this run has stored. An id outside that set simply does not resolve.
 *
 * <p>The same set is what makes short ids workable. A model is shown the first
 * {@value MediaRef#SHORT_ID_LENGTH} characters of an id ({@link MediaRef#shortId()}) and copies
 * them back; resolving a prefix over the few media one session holds is unambiguous in practice, and
 * when it is not, {@link #resolve} says so instead of picking one. Every tool author would otherwise
 * have to write that matching, and write it the same way.
 *
 * <h2>Alternatives that were rejected</h2>
 * <ul>
 *   <li><b>The {@code MediaStore} in the run context.</b> Simpler, and exactly the cross-session
 *       read described above.</li>
 *   <li><b>A {@code MediaResolver}.</b> Narrower, but read-only, and a tool that produces an image
 *       has to store it.</li>
 * </ul>
 *
 * <p><b>Thread-safety:</b> implementations must be thread-safe. The tools of one block run in parallel
 * on virtual threads and all of them hold the same instance; a {@link #put} from one must be visible
 * to a {@link #resolve} from another.
 */
public interface MediaAccess {

    /**
     * The visible media whose id is {@code idOrPrefix} or starts with it, compared without regard
     * to case: models copy hexadecimal ids in either.
     *
     * <p>An exact id always wins over longer ids it happens to prefix.
     *
     * @param idOrPrefix a full id or the shortened one a model was shown; blank is treated as absent
     * @return the media, or empty when no visible media matches
     * @throws IllegalArgumentException if more than one visible media matches, naming the candidates
     *         so the caller can report them to the model and let it ask again with a longer prefix
     */
    Optional<MediaRef> resolve(String idOrPrefix);

    /**
     * The bytes of {@code reference}.
     *
     * @throws IllegalArgumentException if the media is not visible to this session, or is
     *         uri-backed, since ARA does not hold the bytes of those
     * @throws IllegalStateException if the media is visible but its bytes are no longer in the store
     */
    byte[] read(MediaRef reference);

    /**
     * Stores {@code bytes} and makes the result visible to this session from now on, so a tool
     * that produces an image can return it and a later tool call can read it.
     *
     * @throws IllegalArgumentException if {@code mimeType} is not one {@link MediaTypes#allowed()} accepts
     */
    MediaRef put(String name, String mimeType, byte[] bytes);
}
