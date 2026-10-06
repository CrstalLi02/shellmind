package com.shellmind.domain.agent.service.subagent;

import com.shellmind.domain.agent.adapter.port.AgentRuntime;
import com.shellmind.domain.agent.model.valobj.runtime.AgentRunRequest;
import com.shellmind.domain.agent.model.valobj.runtime.AgentRuntimeEvent;
import com.shellmind.domain.agent.service.run.AgentRunRegistry;
import com.shellmind.domain.shared.model.RunContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentManagerTest {

    /** Runtime stand-in: snapshot the child session's registered state while running */
    private static class FakeRuntime implements AgentRuntime {
        private final AgentRunRegistry registry;
        final List<AgentRunRequest> requests = new ArrayList<>();
        final List<RunContext> contextsDuringRun = new ArrayList<>();
        boolean registered = true;

        FakeRuntime(AgentRunRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Iterator<AgentRuntimeEvent> run(AgentRunRequest request) {
            requests.add(request);
            contextsDuringRun.add(registry.requireContext(request.sessionId()));
            return List.of(new AgentRuntimeEvent("done", "done", 0, List.of())).iterator();
        }

        @Override public String resolveAgent(String sourceAgentId, Long modelId) { return sourceAgentId; }
        @Override public void evictModelAgent(Long modelId) { }
        @Override public String createSession(String agentId, String userId) { return "s"; }
        @Override public boolean isRegistered(String agentId) { return registered; }
    }

    private AgentRunRegistry registry;
    private FakeRuntime runtime;
    private SubAgentManager manager;
    private RunContext parent;

    @BeforeEach
    void setUp() {
        registry = new AgentRunRegistry();
        runtime = new FakeRuntime(registry);
        manager = new SubAgentManager();
        ReflectionTestUtils.setField(manager, "agentRuntime", runtime);
        ReflectionTestUtils.setField(manager, "runRegistry", registry);
        parent = new RunContext("parent", "user", "agent-1", "run-1", false, Path.of("/ws"), "terminal-1", false, null);
        registry.register(parent, null);
    }

    @Test
    void readOnlyAgentRunsInReadOnlyChildSession() {
        SubAgentManager.AgentResult result = manager.execute(parent, SubAgentManager.AgentType.EXPLORE, "list the directory", "explorer");

        assertTrue(result.isSuccess());
        assertEquals("done", result.getOutput());
        RunContext child = runtime.contextsDuringRun.get(0);
        assertTrue(child.sessionId().startsWith("parent_sub_"));
        assertEquals("parent", child.parentSessionId());
        assertTrue(child.readOnly(), "Explore sub-agent must be read-only");
        assertEquals("terminal-1", child.terminalSessionId());
        assertEquals(Path.of("/ws"), child.workspace());
        assertEquals("agent-1", runtime.requests.get(0).agentId());
        assertTrue(registry.context(child.sessionId()).isEmpty(), "Child session must be unregistered after the run ends");
        assertTrue(registry.isActive("parent"), "Parent session is unaffected");
    }

    @Test
    void generalAgentInheritsParentWritePermission() {
        manager.execute(parent, SubAgentManager.AgentType.GENERAL, "modify files", "worker");

        assertFalse(runtime.contextsDuringRun.get(0).readOnly());
    }

    @Test
    void readOnlyParentStaysReadOnlyForGeneralAgent() {
        RunContext readOnlyParent = parent.withReadOnly(true);
        registry.register(readOnlyParent, null);

        manager.execute(readOnlyParent, SubAgentManager.AgentType.GENERAL, "modify files", "worker");

        assertTrue(runtime.contextsDuringRun.get(0).readOnly(), "A sub-agent derived from a read-only parent run must not gain write permission");
    }

    @Test
    void failsWhenParentAgentNotRegistered() {
        runtime.registered = false;

        SubAgentManager.AgentResult result = manager.execute(parent, SubAgentManager.AgentType.EXPLORE, "x", "e");

        assertFalse(result.isSuccess());
        assertTrue(result.getOutput().contains("Parent agent is not registered"));
        assertTrue(registry.isActive("parent"));
    }
}
