package com.shellmind.domain.agent.service.subagent.plan;

import com.shellmind.domain.agent.model.valobj.subagent.DynamicTask;
import com.shellmind.domain.agent.model.valobj.subagent.DynamicTaskPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PlanValidatorTest {
    private final PlanValidator planValidator = new PlanValidator();

    @Test
    public void shouldValidateTaskDependencies() {
        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(
                        DynamicTask.builder().taskId("check").agentName("EXPLORE").request("inspect").build(),
                        DynamicTask.builder().taskId("fix").agentName("GENERAL").request("fix")
                                .dependsOn(List.of("check")).build()))
                .build();

        planValidator.validate(plan, 2);
    }

    @Test
    public void shouldRejectTooManyTasks() {
        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(
                        DynamicTask.builder().taskId("a").agentName("EXPLORE").request("a").build(),
                        DynamicTask.builder().taskId("b").agentName("EXPLORE").request("b").build()))
                .build();

        assertThrows(IllegalArgumentException.class, () -> planValidator.validate(plan, 1));
    }

    @Test
    public void shouldRejectDependencyCycle() {
        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(
                        DynamicTask.builder().taskId("a").agentName("EXPLORE").request("a")
                                .dependsOn(List.of("b")).build(),
                        DynamicTask.builder().taskId("b").agentName("EXPLORE").request("b")
                                .dependsOn(List.of("a")).build()))
                .build();

        assertThrows(IllegalArgumentException.class, () -> planValidator.validate(plan, 2));
    }

    @Test
    public void shouldClampMaxRetriesToUpperBound() {
        // Runaway retry counts from the LLM planner must be clamped to 3 without throwing
        DynamicTask task = DynamicTask.builder()
                .taskId("a").agentName("EXPLORE").request("a")
                .maxRetries(100).build();
        DynamicTaskPlan plan = DynamicTaskPlan.builder().tasks(List.of(task)).build();

        planValidator.validate(plan, 1);

        assertEquals(Integer.valueOf(3), task.getMaxRetries());
    }

    @Test
    public void shouldKeepReasonableMaxRetries() {
        DynamicTask task = DynamicTask.builder()
                .taskId("a").agentName("EXPLORE").request("a")
                .maxRetries(2).build();
        DynamicTaskPlan plan = DynamicTaskPlan.builder().tasks(List.of(task)).build();

        planValidator.validate(plan, 1);

        assertEquals(Integer.valueOf(2), task.getMaxRetries());
    }
}
