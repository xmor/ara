package io.ara.core.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0067 — {@link ToolSpec} classification invariants (D1), the derived
 * {@link ToolSpec#approvalRequired()} (D2), the synthesized-tool obligations (D3), and the
 * additive {@link ToolRegistry#specFor(String)} default (D5).
 */
class ToolSpecTest {

    // D2 — approvalRequired is true only for the top level, never a stored flag
    @Test
    void approvalRequired_isDerivedFromReversibilityOnly() {
        assertFalse(spec(new Reversibility.Reversible()).approvalRequired());
        assertFalse(spec(new Reversibility.CostlyButReversible()).approvalRequired());
        assertFalse(spec(new Reversibility.IrreversibleLowImpact()).approvalRequired());
        assertTrue(spec(new Reversibility.IrreversibleHighImpact()).approvalRequired());
    }

    // D1 — construction guards
    @Test
    void rejectsBlankToolIdAndNullClassificationAxes() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolSpec.builtin("  ", SideEffects.NONE, new Reversibility.Reversible()));
        assertThrows(NullPointerException.class,
                () -> ToolSpec.builtin("t", null, new Reversibility.Reversible()));
        assertThrows(NullPointerException.class,
                () -> ToolSpec.builtin("t", SideEffects.NONE, null));
    }

    // D3 — a synthesized tool must declare tests AND a sandbox policy
    @Test
    void synthesizedTool_requiresTestsAndSandbox() {
        SandboxPolicy sandbox = SandboxPolicy.denied(30, 256);

        assertThrows(IllegalArgumentException.class, () -> new ToolSpec(
                "gen_tool", SideEffects.EXTERNAL_READ, new Reversibility.Reversible(),
                sandbox, ToolOrigin.SYNTHESIZED, List.of()), "no tests");
        assertThrows(IllegalArgumentException.class, () -> new ToolSpec(
                "gen_tool", SideEffects.EXTERNAL_READ, new Reversibility.Reversible(),
                null, ToolOrigin.SYNTHESIZED, List.of("a test")), "no sandbox");

        ToolSpec ok = new ToolSpec("gen_tool", SideEffects.EXTERNAL_READ,
                new Reversibility.Reversible(), sandbox, ToolOrigin.SYNTHESIZED, List.of("t1"));
        assertEquals(ToolOrigin.SYNTHESIZED, ok.origin());
        assertEquals(List.of("t1"), ok.tests());
    }

    @Test
    void builtinTool_hasNoSandboxAndNoTests_andThatIsFine() {
        ToolSpec t = ToolSpec.builtin("shell_exec", SideEffects.EXTERNAL_WRITE,
                new Reversibility.IrreversibleHighImpact());
        assertEquals(ToolOrigin.BUILTIN, t.origin());
        assertTrue(t.tests().isEmpty());
        assertTrue(t.approvalRequired());
    }

    @Test
    void sandboxPolicy_defaultsNetworkDeniedAndValidatesCaps() {
        SandboxPolicy p = new SandboxPolicy(null, null, "/tmp/x", 10, 64);
        assertEquals(SandboxPolicy.Network.DENY, p.network());
        assertTrue(p.allowedDomains().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SandboxPolicy.denied(0, 64));
        assertThrows(IllegalArgumentException.class, () -> SandboxPolicy.denied(10, 0));
    }

    // D5 — specFor default is empty, breaks no implementor
    @Test
    void toolRegistry_specFor_defaultsToEmpty() {
        ToolRegistry bare = new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> ids) { return List.of(); }
            @Override public Optional<AraTool> findById(String toolId) { return Optional.empty(); }
            @Override public ToolResult execute(String toolId, String argumentJson) {
                return ToolResult.failure(toolId, "x");
            }
        };
        assertTrue(bare.specFor("anything").isEmpty());
    }

    private static ToolSpec spec(Reversibility reversibility) {
        return ToolSpec.builtin("t", SideEffects.NONE, reversibility);
    }
}
