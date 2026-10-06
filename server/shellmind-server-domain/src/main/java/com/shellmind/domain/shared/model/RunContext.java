package com.shellmind.domain.shared.model;

import java.nio.file.Path;

/**
 * Context for a single agent run (shared kernel).
 * <p>
 * Created by the use-case layer at the start of each turn and registered in the run registry.
 * Tools fetch it explicitly by session ID; it does not rely on thread-locals, so it is reliable on any thread (including ADK async dispatch and sub-agent workers).
 *
 * @param sessionId         session ID (derived child session ID for sub-agents)
 * @param userId            user ID
 * @param agentId           ID of the agent that started the run (sub-agents reuse the parent runtime)
 * @param runId             run ID (for audit; may be null)
 * @param readOnly          read-only run: forbids file writes and side-effecting commands
 * @param workspace         local workspace root (null means the default workspace)
 * @param terminalSessionId bound SSH terminal session (null means no remote server connected)
 * @param dynamicDispatch   whether this is a dynamically planned sub-task (must not plan/dispatch again)
 * @param parentSessionId   parent session ID (non-null only for sub-agent runs)
 */
public record RunContext(String sessionId,
                         String userId,
                         String agentId,
                         String runId,
                         boolean readOnly,
                         Path workspace,
                         String terminalSessionId,
                         boolean dynamicDispatch,
                         String parentSessionId) {

    public boolean hasTerminal() {
        return terminalSessionId != null && !terminalSessionId.isBlank();
    }

    /** Derive a child-session context: inherit parent identity, workspace, and terminal. */
    public RunContext forChildSession(String childSessionId) {
        return new RunContext(childSessionId, userId, agentId, runId, readOnly, workspace,
                terminalSessionId, dynamicDispatch, sessionId);
    }

    public RunContext withReadOnly(boolean value) {
        return new RunContext(sessionId, userId, agentId, runId, value, workspace,
                terminalSessionId, dynamicDispatch, parentSessionId);
    }

    public RunContext asDynamicDispatch() {
        return new RunContext(sessionId, userId, agentId, runId, readOnly, workspace,
                terminalSessionId, true, parentSessionId);
    }
}
