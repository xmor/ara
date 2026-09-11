package io.ara.runtime.interceptor;

import io.ara.core.agent.AgentExecutionContext;
import io.ara.core.agent.AgentInterceptor;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ordered pipeline of {@link AgentInterceptor} instances applied around each
 * step of the ReAct loop.
 *
 * <p>Interceptors are invoked in registration order on {@link #before} and in
 * <em>reverse</em> registration order on {@link #after} and {@link #onError}.
 * This mirrors the "onion" / middleware pattern used in HTTP filter chains.
 *
 * <p>Exceptions thrown by any individual interceptor are caught, logged, and
 * swallowed so that a misbehaving interceptor never disrupts the chain or the
 * agent's execution loop.
 */
public final class AgentInterceptorChain {

    private static final Logger log = LoggerFactory.getLogger(AgentInterceptorChain.class);

    private final List<AgentInterceptor> interceptors;
    private final List<AgentInterceptor> reverse;

    /**
     * Creates a chain from the given list of interceptors.
     *
     * @param interceptors the interceptors to compose; defensive copy is taken
     */
    public AgentInterceptorChain(List<AgentInterceptor> interceptors) {
        this.interceptors = List.copyOf(Objects.requireNonNull(interceptors, "interceptors must not be null"));
        // Reverse registration order is the "outgoing" half of the onion, invoked
        // after every step. Computed once here because the chain is immutable — the
        // after-path runs on every LLM call and tool dispatch, so rebuilding an
        // ArrayList + reverse on each of those (even with an empty chain) would be
        // pure churn that a copy in the constructor makes go away entirely.
        List<AgentInterceptor> rev = new ArrayList<>(this.interceptors);
        java.util.Collections.reverse(rev);
        this.reverse = rev;
    }

    /**
     * Returns an empty chain with no interceptors.
     *
     * @return an empty {@code AgentInterceptorChain}
     */
    public static AgentInterceptorChain empty() {
        return new AgentInterceptorChain(List.of());
    }

    /**
     * Invokes {@link AgentInterceptor#before} on all interceptors in order.
     *
     * @param context  the current execution context snapshot
     * @param stepName the name of the step about to execute (e.g. "Planning", "Executing")
     */
    public void before(AgentExecutionContext context, String stepName) {
        for (AgentInterceptor interceptor : interceptors) {
            try {
                interceptor.before(context, stepName);
            } catch (Exception e) {
                log.warn("Interceptor [{}] threw on before({}): {}",
                        interceptor.getClass().getSimpleName(), stepName, e.getMessage(), e);
            }
        }
    }

    /**
     * Invokes {@link AgentInterceptor#after} on all interceptors in reverse order.
     *
     * @param context  the updated execution context after the step completed
     * @param stepName the name of the step that just completed
     * @param result   a string representation of the step's output
     */
    public void after(AgentExecutionContext context, String stepName, String result) {
        for (AgentInterceptor interceptor : reversed()) {
            try {
                interceptor.after(context, stepName, result);
            } catch (Exception e) {
                log.warn("Interceptor [{}] threw on after({}): {}",
                        interceptor.getClass().getSimpleName(), stepName, e.getMessage(), e);
            }
        }
    }

    /**
     * Invokes {@link AgentInterceptor#onError} on all interceptors in reverse order.
     *
     * @param context   the execution context at the time of failure
     * @param stepName  the name of the step that failed
     * @param throwable the exception that caused the failure
     */
    public void onError(AgentExecutionContext context, String stepName, Throwable throwable) {
        for (AgentInterceptor interceptor : reversed()) {
            try {
                interceptor.onError(context, stepName, throwable);
            } catch (Exception e) {
                log.warn("Interceptor [{}] threw on onError({}): {}",
                        interceptor.getClass().getSimpleName(), stepName, e.getMessage(), e);
            }
        }
    }

    /**
     * Invokes {@link AgentInterceptor#beforeThink} on all interceptors in order.
     *
     * @param context the current execution context snapshot
     */
    public void beforeThink(AgentExecutionContext context) {
        for (AgentInterceptor interceptor : interceptors) {
            try {
                interceptor.beforeThink(context);
            } catch (Exception e) {
                log.warn("Interceptor [{}] threw on beforeThink(): {}",
                        interceptor.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * Invokes {@link AgentInterceptor#afterThink} on all interceptors in reverse order.
     *
     * @param context    the current execution context snapshot
     * @param completion the LLM's completion for the call that just finished
     */
    public void afterThink(AgentExecutionContext context, LlmCompletion completion) {
        for (AgentInterceptor interceptor : reversed()) {
            try {
                interceptor.afterThink(context, completion);
            } catch (Exception e) {
                log.warn("Interceptor [{}] threw on afterThink(): {}",
                        interceptor.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    /**
     * Invokes {@link AgentInterceptor#beforeToolCall} on all interceptors in order.
     *
     * @param context      the current execution context snapshot
     * @param toolId       the identifier of the tool about to be invoked
     * @param argumentJson the JSON-serialised arguments the LLM supplied
     */
    public void beforeToolCall(AgentExecutionContext context, String toolId, String argumentJson) {
        for (AgentInterceptor interceptor : interceptors) {
            try {
                interceptor.beforeToolCall(context, toolId, argumentJson);
            } catch (Exception e) {
                log.warn("Interceptor [{}] threw on beforeToolCall({}): {}",
                        interceptor.getClass().getSimpleName(), toolId, e.getMessage(), e);
            }
        }
    }

    /**
     * Invokes {@link AgentInterceptor#afterToolCall} on all interceptors in reverse order.
     *
     * @param context      the current execution context snapshot
     * @param toolId       the identifier of the tool that was invoked
     * @param argumentJson the JSON-serialised arguments the LLM supplied
     * @param result       the tool's result
     */
    public void afterToolCall(AgentExecutionContext context, String toolId, String argumentJson, ToolResult result) {
        for (AgentInterceptor interceptor : reversed()) {
            try {
                interceptor.afterToolCall(context, toolId, argumentJson, result);
            } catch (Exception e) {
                log.warn("Interceptor [{}] threw on afterToolCall({}): {}",
                        interceptor.getClass().getSimpleName(), toolId, e.getMessage(), e);
            }
        }
    }

    /**
     * Invokes {@link AgentInterceptor#onBudgetExceeded} on all interceptors in reverse order.
     *
     * @param context  the execution context at the time of failure
     * @param stepName the name of the step that was in progress
     * @param reason   a human-readable description of the budget that was exceeded
     */
    public void onBudgetExceeded(AgentExecutionContext context, String stepName, String reason) {
        for (AgentInterceptor interceptor : reversed()) {
            try {
                interceptor.onBudgetExceeded(context, stepName, reason);
            } catch (Exception e) {
                log.warn("Interceptor [{}] threw on onBudgetExceeded({}): {}",
                        interceptor.getClass().getSimpleName(), stepName, e.getMessage(), e);
            }
        }
    }

    /**
     * Invokes {@link AgentInterceptor#onTimeout} on all interceptors in reverse order.
     *
     * @param context  the execution context at the time of failure
     * @param stepName the name of the step that was in progress
     * @param timeout  the configured timeout duration that was exceeded
     */
    public void onTimeout(AgentExecutionContext context, String stepName, Duration timeout) {
        for (AgentInterceptor interceptor : reversed()) {
            try {
                interceptor.onTimeout(context, stepName, timeout);
            } catch (Exception e) {
                log.warn("Interceptor [{}] threw on onTimeout({}): {}",
                        interceptor.getClass().getSimpleName(), stepName, e.getMessage(), e);
            }
        }
    }

    /**
     * Invokes {@link AgentInterceptor#onCancelled} on all interceptors in reverse order.
     *
     * @param context  the execution context at the time of cancellation
     * @param stepName the name of the step that was in progress
     */
    public void onCancelled(AgentExecutionContext context, String stepName) {
        for (AgentInterceptor interceptor : reversed()) {
            try {
                interceptor.onCancelled(context, stepName);
            } catch (Exception e) {
                log.warn("Interceptor [{}] threw on onCancelled({}): {}",
                        interceptor.getClass().getSimpleName(), stepName, e.getMessage(), e);
            }
        }
    }

    /**
     * Invokes {@link AgentInterceptor#onDelegate} on all interceptors in order.
     *
     * @param context          the current execution context snapshot
     * @param recipientAgentId the id of the peer agent the task is delegated to
     * @param delegatedTask    the sub-task text handed to the recipient
     */
    public void onDelegate(AgentExecutionContext context, String recipientAgentId, String delegatedTask) {
        for (AgentInterceptor interceptor : interceptors) {
            try {
                interceptor.onDelegate(context, recipientAgentId, delegatedTask);
            } catch (Exception e) {
                log.warn("Interceptor [{}] threw on onDelegate({}): {}",
                        interceptor.getClass().getSimpleName(), recipientAgentId, e.getMessage(), e);
            }
        }
    }

    /**
     * Invokes {@link AgentInterceptor#onDelegateReturn} on all interceptors in reverse order.
     *
     * @param context          the current execution context snapshot
     * @param recipientAgentId the id of the peer agent that was delegated to
     * @param result           the delegated call's result
     */
    public void onDelegateReturn(AgentExecutionContext context, String recipientAgentId, ToolResult result) {
        for (AgentInterceptor interceptor : reversed()) {
            try {
                interceptor.onDelegateReturn(context, recipientAgentId, result);
            } catch (Exception e) {
                log.warn("Interceptor [{}] threw on onDelegateReturn({}): {}",
                        interceptor.getClass().getSimpleName(), recipientAgentId, e.getMessage(), e);
            }
        }
    }

    /**
     * Returns the number of interceptors in this chain.
     *
     * @return the interceptor count
     */
    public int size() {
        return interceptors.size();
    }

    private List<AgentInterceptor> reversed() {
        return reverse;
    }
}