package io.ara.core.tool;

/**
 * What a tool leaves in the session history when an agent keeps tool calls across turns ({@link AraTool#historyPolicy()}).
 *
 * <p>The history persists with the session, so this is where a tool decides what of a call survives the run: a command
 * to a device is a fact worth keeping whole ("the corridor light was switched off"), a web search is worth keeping as a
 * call but not as the pages it fetched, and a tool that receives a secret should leave nothing.
 */
public enum ToolHistoryPolicy {

    /** The call and its result. The result is still shortened, or replaced by a placeholder on older turns. */
    CALL_AND_RESULT,

    /** The call only: the result is never stored. */
    CALL_ONLY,

    /** Nothing: the call does not enter the history. */
    NONE
}
