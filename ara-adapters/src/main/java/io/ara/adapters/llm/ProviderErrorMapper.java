package io.ara.adapters.llm;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.exception.TimeoutException;
import io.ara.core.llm.LlmException;

import java.time.Duration;

/**
 * Classifies a provider failure using langchain4j's typed exception hierarchy, so that
 * {@link LlmException#isRetryable()} reflects what the provider actually said rather than what
 * a substring match guessed.
 *
 * <h2>Why this exists</h2>
 * <p>Retryability is the one field of {@link LlmException} the runtime acts on:
 * {@code ReactExecutionSupport} retries a retryable failure and {@code FailoverLlmClient}
 * walks its fallback list for one. Each adapter used to decide it by matching status codes in
 * the exception <em>message</em>, and anything unmatched fell through to
 * {@code networkError(...)} — which is retryable. So a malformed request (HTTP 400: a media
 * part the endpoint does not accept, a bad schema, an unsupported parameter) was retried by
 * the strategy and then tried again against every fallback in the pool, for a request that
 * could not succeed on any of them.
 *
 * <p>langchain4j already answers the question properly. It maps HTTP failures onto
 * {@link RetriableException} / {@link NonRetriableException} subclasses — verified against
 * langchain4j 1.17: a 400 arrives as {@link InvalidRequestException} (non-retriable) wrapping
 * a {@code HttpException}. Reading that classification is both more accurate than a substring
 * match and immune to a provider rewording its error bodies.
 *
 * <p>Connection problems ({@link RetriableException} and its subclasses) are non-retryable:
 * a connection failure means the endpoint is unreachable and retrying immediately would hit
 * the same failure. Server errors ({@link InternalServerException}) and rate limits
 * ({@link RateLimitException}) remain retryable as transient server-side states.
 *
 * <h2>How adapters use it</h2>
 * <p>Adapters call this <em>after</em> their own provider-specific checks (which can be more
 * specific than the type — a context-length overflow arrives as a plain
 * {@link InvalidRequestException} but deserves its own {@code errorType}) and <em>before</em>
 * their {@code networkError} fallback:
 *
 * <pre>{@code
 * private LlmException mapException(Throwable ex) {
 *     // ... provider-specific checks that are more specific than the type ...
 *     LlmException typed = ProviderErrorMapper.fromTypedException(PROVIDER, ex);
 *     if (typed != null) return typed;
 *     return LlmException.networkError(PROVIDER, msg, ex);
 * }
 * }</pre>
 *
     * <p>With this in place the retryable fallback is reached only when langchain4j itself could
     * not classify the failure and the cause is not an {@link java.io.IOException} — that is,
     * for a genuine unknown transport error, not a connection problem.
 */
public final class ProviderErrorMapper {

    private ProviderErrorMapper() {}

    /**
     * Maps {@code ex}, or any exception in its cause chain, to an {@link LlmException} with the
     * retryability langchain4j assigned it.
     *
     * <p>The chain is walked because adapters and HTTP clients wrap: the typed exception is
     * often the cause rather than the throwable handed to the {@code catch}.
     *
     * <p>The two base classes are matched last, as a deliberate catch-all: a langchain4j
     * exception this method has never heard of still gets the correct retryability, so a
     * library upgrade that adds a subclass cannot silently make a non-retriable failure
     * retryable.
     *
     * @param provider the adapter's provider id, for the message and {@link LlmException#provider()}
     * @param ex       the failure caught by the adapter
     * @return the classified exception, or {@code null} if nothing in the chain is a typed
     *         langchain4j exception — leaving the caller's own fallback in charge
     */
    public static LlmException fromTypedException(String provider, Throwable ex) {
        return fromTypedException(provider, ex, null);
    }

    /**
     * Same as {@link #fromTypedException(String, Throwable)}, with the client's configured
     * request timeout appended to a {@link TimeoutException}'s message — e.g. "timed out
     * (configured timeout: PT30S)" — so a reader does not have to go find the adapter's
     * configuration to know whether 30 seconds was too tight or the provider was simply stuck.
     * Every other classification is unaffected; {@code timeout} may be {@code null} when the
     * caller has none to report.
     */
    public static LlmException fromTypedException(String provider, Throwable ex, Duration timeout) {
        Throwable c = ex;
        for (int depth = 0; c != null && depth < MAX_CAUSE_DEPTH; c = c.getCause(), depth++) {
            LlmException mapped = classify(provider, c, timeout);
            if (mapped != null) return mapped;
        }
        return null;
    }

    /**
     * How far down the cause chain to look.
     *
     * <p>A bound rather than cycle detection, because a cycle is reachable: {@code
     * Throwable.initCause} rejects only <em>self</em>-causation, so two exceptions can be made
     * to cause each other and an unbounded walk would never terminate. A depth limit rules out
     * every cycle length at once, needs no bookkeeping, and costs nothing in the real case —
     * an adapter's chain is a handful of frames deep, never sixteen.
     */
    private static final int MAX_CAUSE_DEPTH = 16;

    private static LlmException classify(String provider, Throwable c, Duration timeout) {
        String msg = c.getMessage() != null ? c.getMessage() : c.getClass().getSimpleName();
        return switch (c) {
            case AuthenticationException e   -> LlmException.authenticationError(provider, msg);
            case ModelNotFoundException e    -> LlmException.modelNotFound(provider, msg);
            case RateLimitException e        -> LlmException.rateLimit(provider, msg);
            case InternalServerException e   -> LlmException.serverError(provider, msg, 500);
            // The connection was established and the request accepted; the provider simply
            // did not answer in time. Distinct from a genuine connection failure (below) —
            // see ErrorCategory.TIMEOUT.
            case TimeoutException e          -> LlmException.timeout(provider, withTimeout(msg, timeout), c);
            // Before InvalidRequestException, which it extends: a filtered response is a
            // refusal to answer, not a malformed request, and reporting it as the latter would
            // send whoever reads the error looking for a bug in their own payload.
            case ContentFilteredException e  -> LlmException.contentFiltered(provider, msg);
            // A 400 whose body says the server failed. LM Studio answers this way when the model's
            // output breaks its parser ("does not match the expected peg-native format"): HTTP 400
            // around {"code":500,...,"type":"server_error"}. The request was fine and a new attempt
            // can succeed, since the generation is not deterministic; reading the status alone
            // would make it a non-retryable INVALID_REQUEST.
            case InvalidRequestException e when declaresServerError(msg)
                                             -> LlmException.serverError(provider, msg, 500);
            case InvalidRequestException e   -> LlmException.invalidRequest(provider, msg);
            case NonRetriableException e     -> LlmException.invalidRequest(provider, msg);
            case RetriableException e        -> LlmException.connectionError(provider, msg, c);
            default -> null;
        };
    }

    /**
     * Whether an error body declares a server-side failure, in the OpenAI error shape
     * ({@code "type":"server_error"}), with or without the escaping of a nested JSON string.
     */
    static boolean declaresServerError(String msg) {
        return msg != null && msg.replace("\\\"", "\"").contains("\"type\":\"server_error\"");
    }

    /** Appends the configured timeout to a message, or returns it unchanged when none is known. */
    private static String withTimeout(String msg, Duration timeout) {
        return timeout != null ? msg + " (configured timeout: " + timeout + ")" : msg;
    }
}
