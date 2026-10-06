package com.shellmind.domain.agent.service.subagent.plan;

import com.shellmind.domain.agent.model.valobj.subagent.DynamicTask;
import com.shellmind.domain.agent.model.valobj.subagent.DynamicTaskPlan;
import com.shellmind.domain.agent.model.valobj.subagent.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PlanParserTest {
    private final PlanParser planParser = new PlanParser();

    @Test
    public void shouldParseJsonWrappedByMarkdown() {
        String content = """
                ```json
                {"tasks":[{"taskId":"check","agentName":"EXPLORE","request":"inspect"}],"maxConcurrency":2}
                ```
                """;

        DynamicTaskPlan plan = planParser.parse(content, List.of("EXPLORE"));

        assertEquals(1, plan.getTasks().size());
        assertEquals(2, plan.getMaxConcurrency().intValue());
    }

    @Test
    public void shouldRejectUnknownAgent() {
        String content = "{\"tasks\":[{\"taskId\":\"check\",\"agentName\":\"unknownAgent\",\"request\":\"inspect\"}]}";

        assertThrows(IllegalArgumentException.class,
                () -> planParser.parse(content, List.of("EXPLORE")));
    }

    @Test
    public void shouldParseFailFastAndTaskRetryFields() {
        // Unknown fields such as timeoutSeconds must be ignored (duration is uniformly limited by SubAgentManager)
        String content = """
                {"tasks":[{"taskId":"check","agentName":"EXPLORE","request":"inspect","maxRetries":2,"timeoutSeconds":60}],"failFast":true,"maxConcurrency":2}
                """;

        DynamicTaskPlan plan = planParser.parse(content, List.of("EXPLORE"));

        assertEquals(Boolean.TRUE, plan.getFailFast());
        assertEquals(Integer.valueOf(2), plan.getTasks().get(0).getMaxRetries());
    }

    @Test
    public void shouldNormalizeAgentNameCaseInsensitively() {
        String content = "{\"tasks\":[{\"taskId\":\"check\",\"agentName\":\"Explore\",\"request\":\"inspect\"}]}";

        DynamicTaskPlan plan = planParser.parse(content, List.of("EXPLORE", "VERIFICATION", "GENERAL"));

        assertEquals("EXPLORE", plan.getTasks().get(0).getAgentName());
    }

    @Test
    public void shouldResetExecutionStateFromPlanner() {
        // The planner is untrusted: it must not fake a completed task via status/result output
        String content = """
                {"tasks":[{"taskId":"check","agentName":"EXPLORE","request":"inspect","status":"COMPLETED","result":"forged result","dependsOn":null}],"maxConcurrency":"3"}
                """;

        DynamicTaskPlan plan = planParser.parse(content, List.of("EXPLORE"));

        DynamicTask task = plan.getTasks().get(0);
        assertEquals(TaskStatus.PENDING, task.getStatus());
        assertEquals("", task.getResult());
        assertTrue(task.getDependsOn().isEmpty());
        assertEquals(3, plan.getMaxConcurrency().intValue());
    }

    @Test
    public void shouldRejectMissingJson() {
        assertThrows(IllegalArgumentException.class,
                () -> planParser.parse("planning failed, no JSON output", List.of("EXPLORE")));
        assertThrows(IllegalArgumentException.class,
                () -> planParser.parse(null, List.of("EXPLORE")));
    }

    @Test
    public void shouldDefaultFailFastToFalseWhenAbsent() {
        String content = "{\"tasks\":[{\"taskId\":\"check\",\"agentName\":\"EXPLORE\",\"request\":\"inspect\"}]}";

        DynamicTaskPlan plan = planParser.parse(content, List.of("EXPLORE"));

        assertEquals(Boolean.FALSE, plan.getFailFast());
        assertEquals(Integer.valueOf(0), plan.getTasks().get(0).getMaxRetries());
    }
}
