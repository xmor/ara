package io.ara.core.agent;

import io.ara.core.common.AgentId;
import io.ara.core.common.Money;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FunctionAgent} and the {@link AraAgents#deterministic} door onto it.
 *
 * <p>Covers what the type is supposed to guarantee — the work runs, the identity and
 * config are the agent's own, and a failing function becomes a failed response rather
 * than a thrown one — plus the two properties the javadoc commits to explicitly:
 * {@code plannerStrategy} does not claim to reason, and reported usage is zero.
 */
class FunctionAgentTest {

    private static final AgentId ID = AgentId.of("renderer");

    @Test
    void the_function_runs_and_its_text_becomes_the_response_content() {
        AraAgent agent = AraAgents.deterministic(ID, task -> task.input().toUpperCase());

        AgentResponse response = agent.execute(AgentTask.of("ciao"));

        assertTrue(response.isSuccess());
        assertEquals("CIAO", response.content());
        assertEquals(ID, response.agentId());
    }

    @Test
    void the_function_receives_the_whole_task_not_just_its_text() {
        AraAgent agent = AraAgents.deterministic(ID,
                task -> task.runContext().promptVar("locale") + "/" + task.input());

        assertEquals("it-IT/ciao",
                agent.execute(AgentTask.of("ciao", java.util.Map.of("locale", "it-IT"))).content());
    }

    @Test
    void a_throwing_function_produces_a_failed_response_not_a_thrown_exception() {
        AraAgent agent = AraAgents.deterministic(ID, task -> { throw new IllegalStateException("template missing"); });

        AgentResponse response = agent.execute(AgentTask.of("x"));

        assertFalse(response.isSuccess());
        assertTrue(response.failureReason().contains("template missing"),
                "the cause must survive into the failure reason, was: " + response.failureReason());
    }

    @Test
    void an_exception_with_no_message_still_yields_a_usable_failure_reason() {
        AraAgent agent = AraAgents.deterministic(ID, task -> { throw new IllegalStateException(); });

        assertTrue(agent.execute(AgentTask.of("x")).failureReason().contains("IllegalStateException"));
    }

    @Test
    void a_function_returning_null_content_produces_a_failed_response() {
        AraAgent agent = AraAgents.deterministic(ID, task -> null);

        AgentResponse response = agent.execute(AgentTask.of("x"));

        assertFalse(response.isSuccess());
        assertTrue(response.failureReason().contains("null"));
    }

    @Test
    void a_body_returning_a_null_response_produces_a_failed_response() {
        AraAgent agent = new FunctionAgent(ID, FunctionAgent.defaultConfig(ID), task -> null);

        assertFalse(agent.execute(AgentTask.of("x")).isSuccess());
    }

    @Test
    void the_default_config_does_not_claim_to_reason() {
        AraAgent agent = AraAgents.deterministic(ID, task -> "ok");

        assertEquals("deterministic", agent.config().agentType());
        assertEquals("deterministic", agent.config().plannerStrategy(),
                "an AgentCard advertising 'react' for an agent with no LLM would be a lie");
        assertEquals(ID, agent.agentId());
    }

    @Test
    void a_caller_supplied_config_is_used_as_is_and_surfaces_in_the_agent_card() {
        AgentConfig config = AgentConfig.defaults()
                .agentId(ID).agentType("renderer").name("Report renderer").version("2.1")
                .plannerStrategy("deterministic")
                .build();

        AraAgent agent = AraAgents.deterministic(ID, config, task -> "ok");
        AgentCard card = AraAgents.agentCard(agent);

        assertEquals("renderer", agent.config().agentType());
        assertEquals("Report renderer", card.name());
        assertEquals("2.1", card.version());
    }

    @Test
    void reported_usage_is_zero_because_the_response_type_has_no_way_to_say_absent() {
        AgentResponse response = AraAgents.deterministic(ID, task -> "ok").execute(AgentTask.of("x"));

        assertEquals(0, response.inputTokens());
        assertEquals(0, response.outputTokens());
        assertEquals(Money.ZERO_EUR, response.estimatedCost());
        assertEquals(1, response.iterationsUsed(), "one pass through the function");
    }

    @Test
    void the_body_overload_lets_a_caller_report_real_figures() {
        AraAgent agent = new FunctionAgent(ID, FunctionAgent.defaultConfig(ID),
                task -> AgentResponse.success(task.taskId(), ID, "aggregated", 3, 120, 45,
                        Money.ZERO_EUR, Duration.ofMillis(7), List.of()));

        AgentResponse response = agent.execute(AgentTask.of("x"));

        assertEquals(120, response.inputTokens());
        assertEquals(45,  response.outputTokens());
        assertEquals(3,   response.iterationsUsed());
    }

    @Test
    void it_owns_no_lifecycle() {
        AraAgent agent = AraAgents.deterministic(ID, task -> "ok");

        assertEquals(AgentState.IDLE, agent.currentState());
        assertDoesNotThrow(agent::terminate);
        assertEquals(AgentState.IDLE, agent.currentState(), "terminate() changes nothing — there is nothing to end");
    }

    @Test
    void the_constructor_rejects_a_missing_collaborator() {
        assertThrows(NullPointerException.class,
                () -> new FunctionAgent(null, FunctionAgent.defaultConfig(ID), task -> null));
        assertThrows(NullPointerException.class,
                () -> new FunctionAgent(ID, null, task -> null));
        assertThrows(NullPointerException.class,
                () -> new FunctionAgent(ID, FunctionAgent.defaultConfig(ID), null));
    }
}
