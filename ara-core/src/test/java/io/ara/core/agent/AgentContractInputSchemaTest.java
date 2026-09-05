package io.ara.core.agent;

import io.ara.core.agent.processor.SchemaProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0077 D1: {@code inputSchema} as the accessor symmetric to {@code outputSchema},
 * with no new enforcement and full backward compatibility for existing constructor calls.
 */
class AgentContractInputSchemaTest {

    private static final SchemaProvider IN  = () -> "{\"type\":\"object\",\"required\":[\"task\"]}";
    private static final SchemaProvider OUT = () -> "{\"type\":\"string\"}";

    @Test
    void builderExposesInputSchemaSymmetricallyToOutputSchema() {
        AgentContract contract = AgentContract.builder()
                .inputSchema(IN)
                .outputSchema(OUT)
                .build();

        assertSame(IN, contract.inputSchema());
        assertSame(OUT, contract.outputSchema());
        assertFalse(contract.isEmpty());
    }

    @Test
    void inputSchemaDefaultsToNullAndDoesNotChangeEmptiness() {
        assertNull(AgentContract.builder().build().inputSchema());
        assertNull(AgentContract.empty().inputSchema());
        assertTrue(AgentContract.empty().isEmpty());

        AgentContract onlyInputSchema = AgentContract.builder().inputSchema(IN).build();
        assertFalse(onlyInputSchema.isEmpty(), "a declared input schema makes the contract non-empty");
    }

    @Test
    void preAdr0077PositionalConstructorsStillCompileAndLeaveInputSchemaNull() {
        AgentContract fourArg = new AgentContract(List.of(), List.of(), List.of(), OUT);
        AgentContract fiveArg = new AgentContract(List.of(), List.of(), List.of(), List.of(), OUT);

        assertNull(fourArg.inputSchema());
        assertNull(fiveArg.inputSchema());
        assertSame(OUT, fiveArg.outputSchema());
    }

    @Test
    void inputSchemaAddsNoEnforcement_itIsJustAReadableDeclaration() {
        // Declaring the schema without also registering it as an InputProcessor means the
        // contract carries the shape but enforces nothing new (ADR-0077 D1).
        AgentContract declaredOnly = AgentContract.builder().inputSchema(IN).build();
        assertTrue(declaredOnly.inputProcessors().isEmpty());
    }
}
