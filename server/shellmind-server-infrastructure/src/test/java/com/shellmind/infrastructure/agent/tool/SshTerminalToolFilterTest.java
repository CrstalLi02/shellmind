package com.shellmind.infrastructure.agent.tool;

import com.shellmind.domain.agent.service.run.AgentRunRegistry;
import com.shellmind.domain.shared.model.RunContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.tools.BaseTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the SSH tool visibility filter.
 */
class SshTerminalToolFilterTest {

    private final AgentRunRegistry registry = new AgentRunRegistry();
    private final SshTerminalToolFilter filter = new SshTerminalToolFilter(registry);

    @AfterEach
    void cleanup() {
        registry.unregister("session-a");
    }

    /** Register a run; a null terminal means no SSH connection. */
    private void startRun(String sessionId, String terminal) {
        registry.register(new RunContext(sessionId, "u", "a", null, false, null, terminal, false, null), null);
    }

    private static BaseTool namedTool(String name) {
        return new BaseTool(name, "test tool " + name) {
        };
    }

    private static LlmRequest.Builder requestWithTools(String... names) {
        Map<String, BaseTool> tools = new LinkedHashMap<>();
        for (String name : names) {
            tools.put(name, namedTool(name));
        }
        return LlmRequest.builder().model("test-model").tools(tools);
    }

    private static int sshToolCount(LlmRequest request) {
        return (int) request.tools().values().stream()
                .filter(t -> SshTerminalToolFilter.SSH_TOOLS.contains(t.name()))
                .count();
    }

    @Test
    void hidesSshToolsWhenNoTerminalSessionBound() {
        LlmRequest.Builder builder = requestWithTools(
                "executeCommand", "readFile", "writeLocalFile", "readLocalFile", "executeLocalCommand", "listFiles");

        filter.call(new StubCallbackContext("session-a"), builder);

        LlmRequest result = builder.build();
        assertEquals(0, sshToolCount(result), "Remote tools should all be hidden when no SSH terminal is bound");
        assertEquals(3, result.tools().size(), "Local tools should be kept");
        assertTrue(result.tools().containsKey("readLocalFile"));
        assertTrue(result.tools().containsKey("writeLocalFile"));
        assertTrue(result.tools().containsKey("executeLocalCommand"));
    }

    @Test
    void keepsSshToolsWhenTerminalSessionBound() {
        startRun("session-a", "terminal-1");

        LlmRequest.Builder builder = requestWithTools("readFile", "readLocalFile");

        filter.call(new StubCallbackContext("session-a"), builder);

        LlmRequest result = builder.build();
        assertEquals(1, sshToolCount(result), "Remote tools should stay visible when an SSH terminal is bound");
        assertEquals(2, result.tools().size());
    }

    @Test
    void rebindingRestoresVisibility() {
        startRun("session-a", null);
        LlmRequest.Builder first = requestWithTools("readFile", "readLocalFile");
        filter.call(new StubCallbackContext("session-a"), first);
        assertEquals(1, first.build().tools().size());

        // User opens an SSH connection and rebinds
        startRun("session-a", "terminal-1");

        LlmRequest.Builder second = requestWithTools("readFile", "readLocalFile");
        filter.call(new StubCallbackContext("session-a"), second);
        assertEquals(2, second.build().tools().size(), "Remote tools should become visible again after binding");
    }

    @Test
    void unknownSessionIdKeepsLocalToolsOnly() {
        LlmRequest.Builder builder = requestWithTools("readLocalFile", "executeCommand");
        filter.call(new StubCallbackContext("never-bound-session"), builder);
        assertFalse(builder.build().tools().containsKey("executeCommand"));
    }

    /** Minimal CallbackContext stub: only provides sessionId. */
    private static final class StubCallbackContext extends com.google.adk.agents.CallbackContext {
        private final String sessionId;

        StubCallbackContext(String sessionId) {
            // CallbackContext construction calls invocationContext.session(), so a real InvocationContext with a session is required.
            // InvocationContext.build() requires non-null agent/sessionService; this minimal stub satisfies that.
            super(com.google.adk.agents.InvocationContext.builder()
                            .invocationId("test-invocation")
                            .session(com.google.adk.sessions.Session.builder(sessionId).build())
                            .sessionService(new com.google.adk.sessions.InMemorySessionService())
                            .agent(new com.google.adk.agents.BaseAgent("stub", "stub", null, null, null) {
                                @Override
                                protected io.reactivex.rxjava3.core.Flowable<com.google.adk.events.Event> runAsyncImpl(com.google.adk.agents.InvocationContext ctx) {
                                    return io.reactivex.rxjava3.core.Flowable.empty();
                                }

                                @Override
                                protected io.reactivex.rxjava3.core.Flowable<com.google.adk.events.Event> runLiveImpl(com.google.adk.agents.InvocationContext ctx) {
                                    return io.reactivex.rxjava3.core.Flowable.empty();
                                }
                            })
                            .build(),
                    com.google.adk.events.EventActions.builder().build());
            this.sessionId = sessionId;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }
    }
}
