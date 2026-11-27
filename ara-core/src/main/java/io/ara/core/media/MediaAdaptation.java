package io.ara.core.media;

import java.util.Objects;

/**
 * A conversion a model client does by itself so that a media type it cannot send to its provider still reaches the model:
 * for example a PDF turned into one image per page for a provider that reads images and rejects a PDF. The conversion is a
 * capability of the client, not a service of the application — declared by the client, so every host that uses it (agents,
 * workflows, a gateway) gets the same behaviour with one tested implementation.
 *
 * <p>It is a <em>declared</em> capability, not a silent fallback: a client that adapts a type lists it in {@link
 * io.ara.core.llm.LlmClient#supportedMediaTypes()} and describes the conversion in {@link
 * io.ara.core.llm.LlmClient#mediaAdaptations()}, so a caller (and a person, in a UI) can see what the model will actually be
 * shown. The same rule that governs {@code supportedMediaTypes()} stands: what cannot be
 * adapted is refused before the call, never degraded in silence.
 *
 * @param from        the MIME type the caller attaches
 * @param to          the MIME type the model receives (one attachment can become several)
 * @param description what happens, in words a person reads, including its limits ("one image per page, up to 10 pages")
 */
public record MediaAdaptation(String from, String to, String description) {

    public MediaAdaptation {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(description, "description must not be null");
    }
}
