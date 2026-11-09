package io.ara.core.llm;

/**
 * Where an adapter puts the media a tool returned when it builds the request for its provider.
 *
 * <p>The choice belongs to the adapter because it is a property of the provider's wire format,
 * not of the conversation: Anthropic accepts images inside a {@code tool_result}, while the
 * OpenAI chat-completions format (and the OpenAI-compatible servers built on it, vLLM and
 * Ollama included) accepts only text there. Sending an image in a tool message to such an
 * endpoint is not rejected — a gateway verified for this design answered HTTP 200 and the
 * model never saw the image — which is why the safe form is the default.
 *
 * <p><b>Thread-safety:</b> an immutable enum.
 */
public enum ToolMediaPlacement {

    /**
     * The results of a tool block are sent as text only, and one user message after the block
     * carries the images with their labels. Every provider accepts it, which is why it is the
     * default and the answer a composite gives when its candidates disagree.
     */
    FOLLOWING_USER_MESSAGE,

    /**
     * The images travel inside the tool result itself. Only for providers that document it
     * (Anthropic); an adapter that declares this and whose provider drops the content fails
     * silently, so it is declared per adapter, never assumed.
     */
    INLINE_IN_TOOL_RESULT
}
