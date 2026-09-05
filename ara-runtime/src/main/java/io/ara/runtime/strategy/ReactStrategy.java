package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStep;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.agent.ExecutionTimeoutException;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Implementation of the ReAct (Reason + Act) execution strategy
 * (Yao et al., 2022 — arXiv:2210.03629).
 *
 * <p>Each iteration of the loop follows the cycle:
 * <ol>
 *   <li><strong>Think</strong> — assemble the conversation window from working memory
 *       and call the LLM to reason about the next action.</li>
 *   <li><strong>Act</strong> — if the LLM requested a tool call, dispatch it to the
 *       {@link ToolRegistry} in the appropriate sandbox tier.</li>
 *   <li><strong>Observe</strong> — append the tool result as a new "user" message
 *       so the LLM can incorporate the observation in the next Think step.</li>
 *   <li><strong>Evaluate</strong> — check whether the LLM emitted a {@code FINAL_ANSWER}
 *       token or whether {@code maxIterations} has been reached.</li>
 * </ol>
 *
 * <p>Working memory is expected to have already been seeded with the system prompt
 * and the user's task input by {@code AgentInstance} before this method is called.
 *
 * <p>Tool call JSON is expected in the normalised ARA format:
 * <pre>{@code
 * {"tool_id": "search_web", "arguments": {"query": "capital of France"}}
 * }</pre>
 * LLM provider adapters in {@code ara-llm-providers} are responsible for
 * translating provider-specific formats (OpenAI function calls, Anthropic tool use, …)
 * into this normalised form before populating {@link LlmCompletion#toolCallJson()}.
 *
 * <p>Every piece of this loop besides its top-level shape — the decision logic (final
 * answer vs. tool call), message building, the synthesis nudge, tool dispatch, and
 * streaming — is shared with {@link ReflActStrategy} (identical decision logic, plus
 * in-loop self-correction) via {@link ReactExecutionSupport}, and with {@link
 * ReSpActStrategy} (a genuinely different three-branch decision, but the same dispatch/
 * streaming machinery). This class itself holds only the loop control flow.
 */
public final class ReactStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReactStrategy.class);

    @Override
    public String strategyName() {
        return "react";
    }

    @Override
    public ExecutionResult execute(
            AgentTask task,
            LlmClient llm,
            MemoryManager memory,
            ToolRegistry tools,
            AgentConfig config
    ) {
        Objects.requireNonNull(task,   "task must not be null");
        Objects.requireNonNull(llm,    "llm must not be null");
        Objects.requireNonNull(memory, "memory must not be null");
        Objects.requireNonNull(tools,  "tools must not be null");
        Objects.requireNonNull(config, "config must not be null");

        int     iterations          = 0;
        int     totalPromptTokens   = 0;
        int     totalOutputTokens   = 0;
        boolean synthesisModeActive = false;
        final boolean logIo         = config.logLlmIo();
        final int     logIoMaxChars = config.logLlmIoMaxChars();
        // When the client speaks native provider function-calling, the text-based tool
        // catalog + ReAct instructions in buildMessages() are redundant with (and can
        // compete against) the structured tool channel — omit them in that case.
        final boolean nativeTools   = llm.supportsNativeTools();
        List<ExecutionStep> steps = new ArrayList<>();
        Instant deadline = Instant.now().plus(config.executionTimeout());

        LlmCallContext ctx = LlmCallContext.of(config, task);

        // Resolve tools once — the list is stable for the lifetime of this execution
        List<AraTool> resolvedTools = tools.resolveEnabled(config.enabledTools());

        log.debug("ReactStrategy starting for task [{}] maxIterations={} tools={}",
                task.taskId(), config.maxIterations(),
                resolvedTools.stream().map(AraTool::toolId).toList());

        while (iterations < config.maxIterations()) {
            // Cooperative cancellation: AgentInstance.terminate(session) interrupts this thread.
            if (Thread.currentThread().isInterrupted()) {
                log.debug("Task [{}] cancelled at iteration {} (thread interrupted)", task.taskId(), iterations);
                return ExecutionResult.failure("Cancelled", iterations, totalPromptTokens, totalOutputTokens, steps);
            }
            if (Instant.now().isAfter(deadline)) {
                throw new ExecutionTimeoutException(config.executionTimeout());
            }

            ExecutionResult budgetExceeded = ReactExecutionSupport.checkBudget(
                    config, task.taskId(), totalPromptTokens, totalOutputTokens, iterations, steps);
            if (budgetExceeded != null) {
                return budgetExceeded;
            }

            iterations++;
            synthesisModeActive = ReactExecutionSupport.maybeInjectSynthesis(
                    memory, config, task, iterations, synthesisModeActive, resolvedTools, nativeTools);

            // ── Think ──────────────────────────────────────────────────────────
            // Hard stop: on the final iteration(s) withhold tools so the model cannot
            // emit a structured tool call and MUST produce plain text, which
            // isFinalAnswer() catches. This guarantees termination even for models
            // that never self-terminate — without contradicting the persist step above.
            boolean forceFinal = iterations >= config.maxIterations() - 1;
            List<AraTool> activeTools = forceFinal ? List.of() : resolvedTools;
            List<LlmMessage> messages = ReactExecutionSupport.buildMessages(memory, activeTools, nativeTools);
            // Inject resolved tools into the context so the LLM client can build
            // native ToolSpecification objects from the agent's own registry.
            LlmCallContext stepCtx = ctx.withResolvedTools(activeTools);
            LlmCompletion completion;
            try {
                completion = ReactExecutionSupport.callLlm(llm, messages, stepCtx, task, deadline, config);
            } catch (ExecutionTimeoutException te) {
                throw te;   // preserve timeout semantics for AgentInstance
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();   // restore the flag for our caller
                log.debug("Task [{}] cancelled during the LLM call at iteration {}", task.taskId(), iterations);
                return ExecutionResult.failure("Cancelled", iterations, totalPromptTokens, totalOutputTokens, steps);
            } catch (Exception e) {
                log.warn("LLM call failed on iteration {} for task [{}]: {}",
                        iterations, task.taskId(), e.getMessage());
                log.debug("LLM exception detail", e);
                return ExecutionResult.failure(ReactExecutionSupport.describeLlmFailure(e),
                        iterations, totalPromptTokens, totalOutputTokens, steps);
            }

            if (Instant.now().isAfter(deadline)) {
                throw new ExecutionTimeoutException(config.executionTimeout());
            }

            totalPromptTokens += completion.promptTokens();
            totalOutputTokens += completion.outputTokens();
            String output = completion.text();

            ExecutionResult runBudgetExceeded = ReactExecutionSupport.chargeRunBudget(
                    config, task, completion.promptTokens(), completion.outputTokens(),
                    iterations, totalPromptTokens, totalOutputTokens, steps);
            if (runBudgetExceeded != null) {
                return runBudgetExceeded;
            }

            ReactExecutionSupport.recordAssistantOutput(memory, steps, completion, output, iterations, task.taskId());
            ReactExecutionSupport.logIterationResult(completion, iterations, config.maxIterations(), task.taskId());

            // ── Evaluate / Act ─────────────────────────────────────────────────
            // forceFinal picks which sealed decision type governs this iteration — on the
            // forced branch DispatchTools does not exist as a case, so a tool dispatch here
            // is a compile error rather than a rule enforced by remembering to check a flag.
            if (forceFinal) {
                ReactExecutionSupport.ForcedFinalDecision decision =
                        ReactExecutionSupport.decideForcedFinal(output, completion);
                switch (decision) {
                    case ReactExecutionSupport.ForcedFinalDecision.FinalAnswer(String answer) -> {
                        log.debug("Task [{}] reached FINAL_ANSWER in {} iteration(s)", task.taskId(), iterations);
                        steps.add(ExecutionStep.finalAnswer(answer, iterations));
                        return ExecutionResult.success(answer, iterations, totalPromptTokens, totalOutputTokens, steps);
                    }
                    case ReactExecutionSupport.ForcedFinalDecision.Continue ignored ->
                        log.debug("Forced-final iteration: skipping tool dispatch for task [{}]", task.taskId());
                }
            } else {
                ReactExecutionSupport.StepDecision decision = ReactExecutionSupport.decideNormal(
                        output, completion, task, resolvedTools.isEmpty());
                switch (decision) {
                    case ReactExecutionSupport.StepDecision.FinalAnswer(String answer) -> {
                        log.debug("Task [{}] reached FINAL_ANSWER in {} iteration(s)", task.taskId(), iterations);
                        steps.add(ExecutionStep.finalAnswer(answer, iterations));
                        return ExecutionResult.success(answer, iterations, totalPromptTokens, totalOutputTokens, steps);
                    }
                    case ReactExecutionSupport.StepDecision.DispatchTools(List<ToolCallParser.ToolCallRequest> calls) -> {
                        ReactExecutionSupport.DispatchContext dispatchCtx = new ReactExecutionSupport.DispatchContext(
                                tools, memory, steps, task, iterations, deadline, logIo, logIoMaxChars);
                        if (calls.size() == 1) {
                            // Single tool call — same deadline-bounded dispatch, plus the
                            // legacy top-level toolCallId fallback batches never need.
                            ReactExecutionSupport.dispatchSingle(calls.get(0), completion.toolCallId(), dispatchCtx);
                        } else {
                            // Multiple tool calls — dispatch in parallel via virtual threads
                            log.debug("Parallel dispatch: {} tool calls for task [{}]", calls.size(), task.taskId());
                            ReactExecutionSupport.dispatchParallel(calls, dispatchCtx);
                        }
                    }
                    case ReactExecutionSupport.StepDecision.Continue ignored -> {
                        // No tool call and no final answer this iteration — an intermediate
                        // reasoning step. Loop again.
                    }
                }
            }
        }

        log.warn("Task [{}] hit maxIterations={} without a final answer",
                task.taskId(), config.maxIterations());
        return ExecutionResult.failure(
                "Max iterations (%d) reached without a final answer".formatted(config.maxIterations()),
                iterations, totalPromptTokens, totalOutputTokens, steps);
    }
}
