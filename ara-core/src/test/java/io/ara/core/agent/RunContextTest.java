package io.ara.core.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies {@link RunContext}'s {@code state} channel: defaults, and that every existing wither propagates it unchanged. */
class RunContextTest {

    @Test
    void empty_hasNoopState() {
        assertSame(RunState.noop(), RunContext.empty().state());
    }

    @Test
    void nullState_defaultsToNoop() {
        RunContext ctx = new RunContext(Map.of(), Map.of(), null);
        assertSame(RunState.noop(), ctx.state());
    }

    @Test
    void withState_replacesState() {
        RunState custom = RunState.inMemory();
        RunContext ctx = RunContext.empty().withState(custom);
        assertSame(custom, ctx.state());
    }

    @Test
    void withState_null_throws() {
        assertThrows(NullPointerException.class, () -> RunContext.empty().withState(null));
    }

    @Test
    void withPromptVar_preservesState() {
        RunState custom = RunState.inMemory();
        RunContext ctx = RunContext.empty().withState(custom).withPromptVar("k", "v");
        assertSame(custom, ctx.state());
    }

    @Test
    void withPromptVars_preservesState() {
        RunState custom = RunState.inMemory();
        RunContext ctx = RunContext.empty().withState(custom).withPromptVars(Map.of("k", "v"));
        assertSame(custom, ctx.state());
    }

    @Test
    void withOpaque_preservesState() {
        RunState custom = RunState.inMemory();
        RunContext ctx = RunContext.empty().withState(custom).withOpaque("k", new Object());
        assertSame(custom, ctx.state());
    }

    // ── userMemory channel (ADR-043 rev. 3) ────────────────────────────────────

    @Test
    void empty_hasNoopUserMemory() {
        assertSame(RunState.noop(), RunContext.empty().userMemory());
    }

    @Test
    void threeArgConstructor_defaultsUserMemoryToNoop() {
        RunContext ctx = new RunContext(Map.of(), Map.of(), RunState.inMemory());
        assertSame(RunState.noop(), ctx.userMemory());
    }

    @Test
    void nullUserMemory_defaultsToNoop() {
        RunContext ctx = new RunContext(Map.of(), Map.of(), null, null);
        assertSame(RunState.noop(), ctx.userMemory());
    }

    @Test
    void withUserMemory_replacesUserMemory() {
        RunState custom = RunState.inMemory();
        RunContext ctx = RunContext.empty().withUserMemory(custom);
        assertSame(custom, ctx.userMemory());
    }

    @Test
    void withUserMemory_null_throws() {
        assertThrows(NullPointerException.class, () -> RunContext.empty().withUserMemory(null));
    }

    @Test
    void withUserMemory_preservesState_andViceVersa() {
        RunState state  = RunState.inMemory();
        RunState memory = RunState.inMemory();
        RunContext ctx = RunContext.empty().withState(state).withUserMemory(memory);

        assertSame(state, ctx.state());
        assertSame(memory, ctx.userMemory());
        assertNotSame(ctx.state(), ctx.userMemory(), "state and userMemory are distinct RunState instances");
    }

    @Test
    void withPromptVar_preservesUserMemory() {
        RunState custom = RunState.inMemory();
        RunContext ctx = RunContext.empty().withUserMemory(custom).withPromptVar("k", "v");
        assertSame(custom, ctx.userMemory());
    }

    @Test
    void withOpaque_preservesUserMemory() {
        RunState custom = RunState.inMemory();
        RunContext ctx = RunContext.empty().withUserMemory(custom).withOpaque("k", new Object());
        assertSame(custom, ctx.userMemory());
    }

    @Test
    void withState_preservesUserMemory() {
        RunState memory = RunState.inMemory();
        RunContext ctx = RunContext.empty().withUserMemory(memory).withState(RunState.inMemory());
        assertSame(memory, ctx.userMemory());
    }

    @Test
    void mediaAccessIsEmptyWhenTheRuntimeOfferedNone() {
        assertTrue(RunContext.empty().mediaAccess().isEmpty());
    }

    @Test
    void mediaAccessIsWhatWasPlacedUnderTheMediaAccessKey() {
        io.ara.core.media.MediaAccess access = new io.ara.core.media.MediaAccess() {
            @Override public java.util.Optional<io.ara.core.media.MediaRef> resolve(String idOrPrefix) { return java.util.Optional.empty(); }
            @Override public byte[] read(io.ara.core.media.MediaRef reference) { return new byte[0]; }
            @Override public io.ara.core.media.MediaRef put(String name, String mimeType, byte[] bytes) { throw new UnsupportedOperationException(); }
        };

        RunContext context = RunContext.empty().withOpaque(RunContext.MEDIA_ACCESS_KEY, access);

        assertSame(access, context.mediaAccess().orElseThrow());
    }
}
