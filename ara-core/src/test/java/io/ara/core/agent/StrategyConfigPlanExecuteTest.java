package io.ara.core.agent;

import io.ara.core.spec.SpecLineage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StrategyConfigPlanExecuteTest {

    @Test
    void defaults_leaveRoomForCloseStepAfterTheStepsOwnToolCalls() {
        // Measured on 60 runs: with 3 rounds the closing call starved steps that needed tool calls
        StrategyConfig.PlanExecute defaults = StrategyConfig.PlanExecute.defaults();

        assertEquals("never", defaults.replanPolicy());
        assertEquals(8, defaults.maxPlanSteps());
        assertEquals(6, defaults.maxStepRoundsPerStep());
    }

    @Test
    void parallelism_isOffUnlessGiven() {
        StrategyConfig.PlanExecute threeArguments = new StrategyConfig.PlanExecute("never", 8, 6);

        assertNull(threeArguments.maxParallelSteps());
        assertEquals(1, threeArguments.parallelSteps());
        assertEquals(1, StrategyConfig.PlanExecute.defaults().parallelSteps());
        assertEquals(3, new StrategyConfig.PlanExecute("never", 8, 6, 3).parallelSteps());
    }

    @Test
    void parallelism_belowOne_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> new StrategyConfig.PlanExecute("never", 8, 6, 0));
    }

    @Test
    void aConfigWithoutParallelism_keepsTheBehaviouralHashItHadBeforeTheFieldExisted() {
        AgentConfig config = AgentConfig.defaults().agentType("a")
                .strategyConfig(new StrategyConfig.PlanExecute("on_failure", 6, 2)).build();

        // Computed with the 1.0.3 jar, before maxParallelSteps existed: a stored spec must not move
        assertEquals("fc3b356d9feadb4c849341a636f25a6b83e60211c96267f3240d490b52ae30ca",
                SpecLineage.hash(config));
    }
}
