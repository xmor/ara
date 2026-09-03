package io.ara.core.auth;

import io.ara.core.exceptions.AraException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-033 Fase 1 — {@link AuthorizationException} exposes the typed reason, target and
 * scope sets, and is an {@link AraException} so existing framework error handling catches
 * it. Nothing throws it in Fase 1.
 */
class AuthorizationExceptionTest {

    @Test
    void carriesReasonTargetAndScopeSets() {
        ScopeSet required = ScopeSet.of("finance:read");
        ScopeSet effective = ScopeSet.of("ops");
        AuthorizationException ex = new AuthorizationException(
                AuthorizationException.Reason.AGENT_NOT_AUTHORIZED, "finance-agent", required, effective);

        assertSame(AuthorizationException.Reason.AGENT_NOT_AUTHORIZED, ex.reason());
        assertEquals("finance-agent", ex.targetId());
        assertEquals(required, ex.required());
        assertEquals(effective, ex.effective());
    }

    @Test
    void isAnAraException_soExistingHandlingCatchesIt() {
        AuthorizationException ex = new AuthorizationException(
                AuthorizationException.Reason.TOOL_NOT_AUTHORIZED, "shell_exec", ScopeSet.EMPTY, ScopeSet.EMPTY);
        assertInstanceOf(AraException.class, ex);
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    void message_namesTheTargetAndReason() {
        AuthorizationException ex = new AuthorizationException(
                AuthorizationException.Reason.APPROVAL_REQUIRED, "delete-agent",
                ScopeSet.of("data:delete"), ScopeSet.of("data:delete"));
        assertTrue(ex.getMessage().contains("delete-agent"));
        assertTrue(ex.getMessage().contains("APPROVAL_REQUIRED"));
    }

    @Test
    void nullScopeSetsDegradeToEmpty() {
        AuthorizationException ex = new AuthorizationException(
                AuthorizationException.Reason.AGENT_NOT_VISIBLE, "x", null, null);
        assertEquals(ScopeSet.EMPTY, ex.required());
        assertEquals(ScopeSet.EMPTY, ex.effective());
    }
}
