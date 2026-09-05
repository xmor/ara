package io.ara.core.agent;

import io.ara.core.common.AgentId;
import io.ara.core.common.Budget;
import io.ara.core.common.Money;
import io.ara.core.llm.LlmConfig;
import io.ara.core.llm.LlmProfile;
import io.ara.core.llm.LlmSelectionPolicy;
import io.ara.core.memory.MemoryConfig;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable configuration record that fully describes how an {@link AraAgent}
 * instance should be built and behave at runtime.
 *
 * <p>Composed of four sub-records with single responsibility (ADR-030):
 * <ul>
 *   <li>{@link AgentIdentity} — who the agent is</li>
 *   <li>{@link LlmConfig}     — which LLM(s) to use and how to select them</li>
 *   <li>{@link ExecutionConfig} — how to execute tasks</li>
 *   <li>{@link MemoryConfig}  — working memory and conversation settings</li>
 * </ul>
 *
 * <p>Built through the flat {@link Builder} API, e.g.
 * {@code AgentConfig.defaults().agentType(...).systemPrompt(...).primaryLlm(LlmProfile.of(...)).build()}.
 * LLM settings are declared per profile via {@code primaryLlm}, {@code fallbackLlm}
 * / {@code fallbackLlms} and {@code llmSelectionPolicy}.
 *
 * <p>Read-side delegation methods (e.g. {@link #systemPrompt()}, {@link #llmProvider()})
 * preserve backward compatibility for existing call sites that read individual fields.
 */
public record AgentConfig(
        AgentIdentity   identity,
        LlmConfig       llm,
        ExecutionConfig execution,
        MemoryConfig    memory
) {
    public AgentConfig {
        Objects.requireNonNull(identity,  "identity must not be null");
        Objects.requireNonNull(llm,       "llm must not be null");
        Objects.requireNonNull(execution, "execution must not be null");
        Objects.requireNonNull(memory,    "memory must not be null");
    }

    // -------------------------------------------------------------------------
    // Backward-compat delegation methods — identity
    // -------------------------------------------------------------------------

    public AgentId      agentId()     { return identity.agentId(); }
    public String       agentType()   { return identity.agentType(); }
    public String       systemPrompt(){ return identity.systemPrompt(); }
    public String       name()        { return identity.name(); }
    public String       description() { return identity.description(); }
    public String       version()     { return identity.version(); }
    public List<String> tags()        { return identity.tags(); }
    public String       promptCatalogId() { return identity.promptCatalogId(); }

    // -------------------------------------------------------------------------
    // Backward-compat delegation methods — llm
    // -------------------------------------------------------------------------

    public String  llmProvider()     { return llm.primary().transportId(); }
    public Double  temperature()     { return llm.primary().temperature(); }
    public Double  topP()            { return llm.primary().topP(); }
    public boolean streamingEnabled(){ return llm.primary().streamingEnabled(); }
    public boolean nativeJsonSchema(){ return llm.primary().nativeJsonSchema(); }
    public boolean logLlmIo()        { return llm.logIo(); }
    public int     logLlmIoMaxChars(){ return llm.logIoMaxChars(); }
    public Money   costInputPer1kTokens()  { return llm.primary().costInputPer1kTokens(); }
    public Money   costOutputPer1kTokens() { return llm.primary().costOutputPer1kTokens(); }
    public Budget  costBudget()            { return llm.primary().costBudget(); }
    public String  costCurrency()          { return llm.primary().costCurrency(); }

    // -------------------------------------------------------------------------
    // Backward-compat delegation methods — execution
    // -------------------------------------------------------------------------

    public String                    plannerStrategy()       { return execution.plannerStrategy(); }
    public StrategyConfig            strategyConfig()        { return execution.strategyConfig(); }
    public List<String>              enabledTools()          { return execution.enabledTools(); }
    public List<String>              mcpServerIds()          { return execution.mcpServerIds(); }
    public int                       maxIterations()         { return execution.maxIterations(); }
    public Duration                  executionTimeout()      { return execution.executionTimeout(); }
    public int                       maxTokensPerStep()      { return execution.maxTokensPerStep(); }
    public boolean                   humanApprovalRequired() { return execution.humanApprovalRequired(); }
    public String                    knowledgeBaseId()       { return execution.knowledgeBaseId(); }
    public SessionBusyPolicy         sessionBusyPolicy()     { return execution.sessionBusyPolicy(); }
    public String                    retrieverId()           { return execution.retrieverId(); }
    public DelegateStateAccess       delegateStateAccess()   { return execution.delegateStateAccess(); }
    public Duration                  sessionTtl()            { return execution.sessionTtl(); }
    public List<String>              grantedScopes()         { return execution.grantedScopes(); }
    public List<String>              visibleToScopes()       { return execution.visibleToScopes(); }
    public List<String>              requiredScopes()        { return execution.requiredScopes(); }
    public boolean                   requiresApproval()      { return execution.requiresApproval(); }

    // -------------------------------------------------------------------------
    // Backward-compat delegation methods — memory
    // -------------------------------------------------------------------------

    public int    workingMemoryTokenBudget() { return memory.workingMemoryTokenBudget(); }
    public String workingMemoryEviction()    { return memory.workingMemoryEviction(); }
    public int    maxConversationTurns()     { return memory.maxConversationTurns(); }
    public int    maxReflections()           { return memory.maxReflections(); }
    public String reflectionPrompt()         { return memory.reflectionPrompt(); }
    public String contextSummarizerAgentId() { return memory.contextSummarizerAgentId(); }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static Builder defaults() { return new Builder(); }

    /**
     * Returns a {@link Builder} pre-populated with every field of this config — the
     * starting point for a hot reconfiguration (ADR-039):
     * {@code config.toBuilder().temperature(0.7).build()}.
     */
    public Builder toBuilder() {
        return new Builder()
                .agentId(identity.agentId())
                .agentType(identity.agentType())
                .name(identity.name())
                .description(identity.description())
                .version(identity.version())
                .tags(identity.tags())
                .systemPrompt(identity.systemPrompt())
                .promptCatalogId(identity.promptCatalogId())
                .logLlmIo(llm.logIo())
                .logLlmIoMaxChars(llm.logIoMaxChars())
                .primaryLlm(llm.primary())
                .fallbackLlms(llm.fallbacks())
                .llmSelectionPolicy(llm.policy())
                .plannerStrategy(execution.plannerStrategy())
                .strategyConfig(execution.strategyConfig())
                .enabledTools(execution.enabledTools())
                .mcpServerIds(execution.mcpServerIds())
                .maxIterations(execution.maxIterations())
                .executionTimeout(execution.executionTimeout())
                .maxTokensPerStep(execution.maxTokensPerStep())
                .humanApprovalRequired(execution.humanApprovalRequired())
                .knowledgeBaseId(execution.knowledgeBaseId())
                .sessionBusyPolicy(execution.sessionBusyPolicy())
                .retrieverId(execution.retrieverId())
                .delegateStateAccess(execution.delegateStateAccess())
                .sessionTtl(execution.sessionTtl())
                .grantedScopes(execution.grantedScopes())
                .visibleToScopes(execution.visibleToScopes())
                .requiredScopes(execution.requiredScopes())
                .requiresApproval(execution.requiresApproval())
                .workingMemoryTokenBudget(memory.workingMemoryTokenBudget())
                .workingMemoryEviction(memory.workingMemoryEviction())
                .maxConversationTurns(memory.maxConversationTurns())
                .maxReflections(memory.maxReflections())
                .reflectionPrompt(memory.reflectionPrompt())
                .contextSummarizerAgentId(memory.contextSummarizerAgentId());
    }

    // -------------------------------------------------------------------------
    // Builder — flat API, fully backward-compatible
    // -------------------------------------------------------------------------

    public static final class Builder {

        // identity
        private AgentId      agentId         = AgentId.generate();
        private String       agentType       = "generic";
        private String       agentName       = "";
        private String       agentDescription= "";
        private String       agentVersion    = "1.0.0";
        private List<String> tags            = List.of();
        private String       systemPrompt    = "You are a helpful AI agent.";
        private String       promptCatalogId = null;

        // llm
        private boolean    logLlmIo               = false;
        private int        logLlmIoMaxChars       = 1500;
        private LlmProfile           primaryLlm   = null;
        private List<LlmProfile>     fallbackLlms = List.of();
        private LlmSelectionPolicy   llmPolicy    = LlmSelectionPolicy.PRIMARY_ONLY;

        // execution
        private String                    plannerStrategy       = "react";
        private StrategyConfig            strategyConfig        = null;
        private List<String>              enabledTools          = List.of();
        private List<String>              mcpServerIds          = List.of();
        private int                       maxIterations         = 10;
        private Duration                  executionTimeout      = Duration.ofMinutes(5);
        private int                       maxTokensPerStep      = 4096;
        private boolean                   humanApprovalRequired = false;
        private String                    knowledgeBaseId       = null;
        private SessionBusyPolicy         sessionBusyPolicy     = SessionBusyPolicy.REJECT;
        private String                    retrieverId           = null;
        private DelegateStateAccess       delegateStateAccess   = DelegateStateAccess.OVERLAY;
        private Duration                  sessionTtl            = ExecutionConfig.DEFAULT_SESSION_TTL;
        private List<String>              grantedScopes         = List.of();
        private List<String>              visibleToScopes       = List.of();
        private List<String>              requiredScopes        = List.of();
        private boolean                   requiresApproval      = false;

        // memory
        private int    workingMemoryTokenBudget = 0;
        private String workingMemoryEviction    = "drop_middle";
        private int    maxConversationTurns     = 0;
        private int    maxReflections           = 2;
        private String reflectionPrompt         = null;
        private String contextSummarizerAgentId = null;

        private Builder() {}

        // --- identity ---
        public Builder agentId(AgentId v)     { agentId = v;          return this; }
        public Builder agentType(String v)    { agentType = v;        return this; }
        public Builder name(String v)         { agentName = v;        return this; }
        public Builder description(String v)  { agentDescription = v; return this; }
        public Builder version(String v)      { agentVersion = v;     return this; }
        public Builder tags(List<String> v)   { tags = v;             return this; }
        public Builder systemPrompt(String v) { systemPrompt = v;     return this; }
        public Builder promptCatalogId(String v) { promptCatalogId = v; return this; }

        // --- llm ---
        public Builder logLlmIo(boolean v)                 { logLlmIo = v;              return this; }
        public Builder logLlmIoMaxChars(int v)             { logLlmIoMaxChars = v;       return this; }
        public Builder primaryLlm(LlmProfile v)          { primaryLlm = v;   return this; }
        public Builder fallbackLlm(LlmProfile v)         { fallbackLlms = List.of(v); return this; }
        public Builder fallbackLlms(List<LlmProfile> v)  { fallbackLlms = v; return this; }
        public Builder llmSelectionPolicy(LlmSelectionPolicy v) { llmPolicy = v; return this; }

        // --- execution ---
        public Builder plannerStrategy(String v)                      { plannerStrategy = v;       return this; }
        public Builder strategyConfig(StrategyConfig v)               { strategyConfig = v;
                                                                        if (v != null) plannerStrategy = v.strategyName();
                                                                        return this; }
        public Builder enabledTools(List<String> v)                   { enabledTools = v;          return this; }
        public Builder mcpServerIds(List<String> v)                   { mcpServerIds = v;          return this; }
        public Builder maxIterations(int v)                           { maxIterations = v;         return this; }
        public Builder executionTimeout(Duration v)                   { executionTimeout = v;      return this; }
        public Builder maxTokensPerStep(int v)                        { maxTokensPerStep = v;      return this; }
        public Builder humanApprovalRequired(boolean v)               { humanApprovalRequired = v; return this; }
        public Builder knowledgeBaseId(String v)                      { knowledgeBaseId = v;       return this; }
        public Builder sessionBusyPolicy(SessionBusyPolicy v)         { sessionBusyPolicy = v;     return this; }
        /**
         * Selects, by id, which registered {@link io.ara.core.retriever.Retriever} a
         * {@code "rag+..."} {@link #plannerStrategy(String)} resolves via {@code
         * RetrieverRouter} — {@code null} means "use the router's default". Only valid
         * together with a {@code rag+...} strategy; {@link #build()} throws otherwise.
         */
        public Builder retrieverId(String v)                          { retrieverId = v;           return this; }
        /**
         * Governs what a sub-agent delegated to via {@code delegate_task} sees of and can
         * do to this agent's {@link RunState}. Default: {@link DelegateStateAccess#OVERLAY}
         * (the delegate reads this agent's state; its own writes stay private).
         */
        public Builder delegateStateAccess(DelegateStateAccess v)      { delegateStateAccess = v;   return this; }
        /**
         * How long a session may stay <em>idle</em> before the runtime reclaims it and
         * releases its wiring leases. The clock resets on every task run on that session,
         * so this bounds inactivity, not total conversation length. Default:
         * {@link ExecutionConfig#DEFAULT_SESSION_TTL} (30 minutes).
         *
         * <p>Read once, when the agent is built: unlike the parameter axes, this is not
         * hot-swappable — a {@code reconfigure()} that changes it does not retune the
         * already-running session sweeper.
         */
        public Builder sessionTtl(Duration v)                          { sessionTtl = v;            return this; }

        // --- authorization scopes (ADR-033 Fase 1; default empty = no restriction, no enforcement yet) ---
        /** Scopes this agent holds as a caller. */
        public Builder grantedScopes(List<String> v)                   { grantedScopes = v;         return this; }
        /** Scopes a caller must share for this agent to be discoverable; empty = visible to all. */
        public Builder visibleToScopes(List<String> v)                 { visibleToScopes = v;       return this; }
        /** Scopes a caller must hold to invoke this agent; empty = no scopes required. */
        public Builder requiredScopes(List<String> v)                  { requiredScopes = v;        return this; }
        /**
         * Whether an attempt to invoke this agent via delegation must pause for human
         * approval (ADR-033) — checked in addition to, not instead of, {@code
         * requiredScopes}; scopes must still be satisfied first. Distinct from {@link
         * #humanApprovalRequired(boolean)}, which instead gates this agent's own outgoing
         * tool calls (ADR-0067 D6). Default {@code false} — no gate, matching the behavior
         * before this per-invocation approval flag existed.
         */
        public Builder requiresApproval(boolean v)                     { requiresApproval = v;      return this; }

        // --- memory ---
        public Builder workingMemoryTokenBudget(int v)    { workingMemoryTokenBudget = v; return this; }
        public Builder workingMemoryEviction(String v)    { workingMemoryEviction = v;    return this; }
        public Builder maxConversationTurns(int v)        { maxConversationTurns = v;     return this; }
        public Builder maxReflections(int v)              { maxReflections = v;           return this; }
        public Builder reflectionPrompt(String v)         { reflectionPrompt = v;         return this; }
        public Builder contextSummarizerAgentId(String v) { contextSummarizerAgentId = v; return this; }

        public AgentConfig build() {
            LlmProfile primary = primaryLlm != null ? primaryLlm : LlmProfile.builder().build();

            AgentIdentity id = new AgentIdentity(
                    agentId, agentType, agentName, agentDescription,
                    agentVersion, tags, systemPrompt, promptCatalogId);

            LlmConfig llmCfg = new LlmConfig(primary, fallbackLlms, llmPolicy, logLlmIo, logLlmIoMaxChars);

            ExecutionConfig exec = new ExecutionConfig(
                    plannerStrategy, strategyConfig, enabledTools, mcpServerIds,
                    maxIterations, executionTimeout, maxTokensPerStep,
                    humanApprovalRequired, knowledgeBaseId, sessionBusyPolicy, retrieverId,
                    delegateStateAccess, sessionTtl,
                    grantedScopes, visibleToScopes, requiredScopes, requiresApproval);

            MemoryConfig mem = new MemoryConfig(
                    workingMemoryTokenBudget, workingMemoryEviction,
                    maxConversationTurns, maxReflections, reflectionPrompt, contextSummarizerAgentId);

            return new AgentConfig(id, llmCfg, exec, mem);
        }
    }
}
