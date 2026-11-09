package io.ara.core.media;

import java.net.URI;
import java.util.Objects;

/**
 * A reference to one piece of media attached to a task — an image, a PDF, a text file —
 * carried through the whole domain without its bytes.
 *
 * <h2>Why a reference and not the bytes</h2>
 * <p>The bytes of a document break four things at once if they travel inside the domain
 * objects: they get base64-inflated into every persisted session turn, printed into the
 * request/response logs, counted as characters by the working-memory token estimate (a 2 MB
 * PDF reads as ~680k estimated tokens, which empties the whole window — system prompt
 * included — before the budget is met), and duplicated once per task that carries the same
 * file. Holding a reference makes all four disappear at once, with no per-agent flag to
 * turn any of them off: there is simply nothing large to store, log, count, or duplicate.
 * The bytes live in a {@link MediaStore} and are fetched exactly once, inside the adapter,
 * at the moment the provider request is built.
 *
 * <h2>Why one type and not four</h2>
 * <p>A single {@code MediaRef} list, rather than separate {@code images}/{@code audio}/
 * {@code videos}/{@code files} channels: the category is derivable from {@link #mimeType()}
 * via {@link MediaTypes#kindOf}, so typed channels would add no information while
 * multiplying every line of plumbing along the chain by four. Code that must branch by
 * category does so where it matters — in the adapter — via {@link #kind()}.
 *
 * <h2>Two ways to reach the bytes</h2>
 * <ul>
 *   <li>{@code uri == null} — the bytes live in the {@link MediaStore} under
 *       {@link #mediaId()}, which is their SHA-256 digest.</li>
 *   <li>{@code uri != null} — the bytes are reachable at that URI and ARA does not own
 *       them; {@link #mediaId()} is then an opaque identifier, and no {@code MediaStore}
 *       is consulted (nor may the store delete anything on their behalf).</li>
 * </ul>
 *
 * @param mediaId   SHA-256 digest of the bytes when they live in a {@link MediaStore};
 *                  an opaque identifier when {@code uri} is set
 * @param mimeType  the media type, validated against {@link MediaTypes#allowed()} and
 *                  stored in canonical (trimmed, lowercase) form
 * @param name      the original file name — shown to the model and used in logs, so it
 *                  should be the name a human would recognise, not a generated id
 * @param sizeBytes size of the payload in bytes; {@code 0} for a {@code uri}-backed
 *                  reference whose size the caller does not know
 * @param uri       where the bytes live when ARA does not hold them; {@code null} means
 *                  they are in the {@link MediaStore} under {@code mediaId}
 */
public record MediaRef(
        String mediaId,
        String mimeType,
        String name,
        long sizeBytes,
        URI uri
) {

    public MediaRef {
        mediaId  = requireText(mediaId, "mediaId");
        name     = requireText(name, "name");
        // Rejected here, at construction, rather than filtered further down: an
        // unsupported type is a caller mistake and should surface next to the caller.
        mimeType = MediaTypes.normalized(mimeType);
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("MediaRef sizeBytes must not be negative: " + sizeBytes);
        }
    }

    /**
     * Creates a reference to media ARA does not hold, reachable at {@code uri} — a document
     * behind a URL the provider can fetch itself. No {@link MediaStore} is involved, so
     * {@code mediaId} is just the URI string and {@link #sizeBytes()} is {@code 0}, meaning
     * "unknown": a caller that knows the size should use the canonical constructor instead,
     * since size caps cannot count what they are not told.
     */
    public static MediaRef remote(URI uri, String mimeType, String name) {
        Objects.requireNonNull(uri, "uri must not be null");
        return new MediaRef(uri.toString(), mimeType, name, 0L, uri);
    }

    /** The coarse category of {@link #mimeType()} — see {@link MediaTypes#kindOf}. */
    public MediaTypes.MediaKind kind() {
        return MediaTypes.kindOf(mimeType);
    }

    /** {@code true} when the bytes live outside ARA, at {@link #uri()}. */
    public boolean isExternal() {
        return uri != null;
    }

    /**
     * How many characters of a {@link MediaStore} id a model is shown. See {@link #shortId()}.
     */
    public static final int SHORT_ID_LENGTH = 12;

    /**
     * The identifier a model is shown and is expected to copy back into a tool argument.
     *
     * <p>A store id is a 64-character SHA-256 digest, and a small model copies 64 hex characters
     * wrongly often enough to matter. The first {@value #SHORT_ID_LENGTH} are 48 bits: among the few
     * media one session holds a collision is not a practical concern, and the code that resolves the
     * prefix does so over that small set and reports an ambiguity as an error instead of guessing.
     * That resolution is the caller's: this only says what is shown.
     *
     * <p>A {@link #isExternal() uri-backed} reference has no digest, only a URI as its id, and
     * the first characters of a URI ({@code https://…}) identify nothing, so it is shown whole.
     */
    public String shortId() {
        if (isExternal() || mediaId.length() <= SHORT_ID_LENGTH) {
            return mediaId;
        }
        return mediaId.substring(0, SHORT_ID_LENGTH);
    }

    /**
     * The one-line description of this media that every text shown to a model uses:
     * {@code crop-1.png (id 3f9a1c2e7b4d)}. Kept here so the line a tool result carries, the label
     * placed before an attachment and the note for an image a model cannot receive all say the same
     * thing — a model that reads the id in one and has to find it in another must find it spelled
     * identically.
     */
    public String describe() {
        return name + " (id " + shortId() + ")";
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("MediaRef " + field + " must not be blank");
        }
        return value;
    }
}
