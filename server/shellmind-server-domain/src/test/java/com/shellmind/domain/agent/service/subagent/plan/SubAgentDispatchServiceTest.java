package com.shellmind.domain.agent.service.subagent.plan;

import com.shellmind.domain.agent.model.valobj.subagent.DynamicTask;
import com.shellmind.domain.agent.model.valobj.subagent.TaskStatus;
import com.shellmind.domain.agent.service.subagent.SubAgentManager;
import com.shellmind.domain.shared.model.RunContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sub-agent dispatch service tests — FakeSubAgentManager replaces real sub-agent execution,
 * without touching the runtime / LLM, verifying status write-back, retries, and explicit run-context passing.
 */
public class SubAgentDispatchServiceTest {

    /** Programmable sub-agent manager stand-in: returns results by call count and records the parent context received */
    private static class FakeSubAgentManager extends SubAgentManager {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<RunContext> parents = new ArrayList<>();
        private Function<Integer, AgentResult> behavior =
                attempt -> AgentResult.success(AgentType.EXPLORE, "fake", "ok", 1, 0, 1);

        @Override
        public AgentResult execute(RunContext parent, AgentType agentType, String prompt, String name) {
            parents.add(parent);
            return behavior.apply(calls.incrementAndGet());
        }
    }

    private FakeSubAgentManager subAgentManager;
    private SubAgentDispatchService dispatchService;
    private RunContext context;

    @BeforeEach
    public void setUp() {
        subAgentManager = new FakeSubAgentManager();
        dispatchService = new SubAgentDispatchService();
        ReflectionTestUtils.setField(dispatchService, "subAgentManager", subAgentManager);
        context = new RunContext("test-session", "test-user", "parent-agent", "run-1",
                false, Path.of("/tmp/workspace"), "terminal-1", false, null);
    }

    private DynamicTask task(String agentName, int maxRetries) {
        return DynamicTask.builder()
                .taskId("t1")
                .agentName(agentName)
                .request("check service status")
                .maxRetries(maxRetries)
                .build();
    }

    @Test
    public void shouldCompleteTask() {
        DynamicTask task = task("EXPLORE", 0);

        dispatchService.execute(context, task);

        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertEquals("ok", task.getResult());
        assertEquals(1, task.getAttempts().intValue());
    }

    @Test
    public void shouldPassParentContextMarkedAsDynamicDispatch() {
        dispatchService.execute(context, task("EXPLORE", 0));

        RunContext passed = subAgentManager.parents.get(0);
        assertTrue(passed.dynamicDispatch(), "Dynamically dispatched sub-tasks must be flagged; nested planning is forbidden");
        assertEquals("test-session", passed.sessionId());
        assertEquals("terminal-1", passed.terminalSessionId());
        assertEquals(Path.of("/tmp/workspace"), passed.workspace());
        assertEquals("run-1", passed.runId());
        assertFalse(context.dynamicDispatch(), "Must not mutate the caller context");
    }

    @Test
    public void shouldFailWithoutRetryForUnknownAgentType() {
        DynamicTask task = task("UNKNOWN_AGENT", 3);

        dispatchService.execute(context, task);

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertTrue(task.getError().contains("agent not found"));
        assertEquals(0, subAgentManager.calls.get());
    }

    @Test
    public void shouldMarkFailedWhenRetriesExhausted() {
        subAgentManager.behavior = attempt -> SubAgentManager.AgentResult.failure(
                SubAgentManager.AgentType.EXPLORE, "fake", "boom", 1);
        DynamicTask task = task("EXPLORE", 0);

        dispatchService.execute(context, task);

        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertTrue(task.getError().contains("boom"));
        assertEquals(1, subAgentManager.calls.get());
    }

    @Test
    public void shouldRetryAndSucceedOnSecondAttempt() {
        subAgentManager.behavior = attempt -> attempt == 1
                ? SubAgentManager.AgentResult.failure(SubAgentManager.AgentType.EXPLORE, "fake", "flaky", 1)
                : SubAgentManager.AgentResult.success(SubAgentManager.AgentType.EXPLORE, "fake", "recovered", 1, 0, 1);
        DynamicTask task = task("EXPLORE", 1);

        dispatchService.execute(context, task);

        assertEquals(TaskStatus.COMPLETED, task.getStatus());
        assertEquals("recovered", task.getResult());
        assertEquals(2, task.getAttempts().intValue());
    }
}
