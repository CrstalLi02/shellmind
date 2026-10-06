package com.shellmind.domain.agent.service.subagent;

import com.shellmind.domain.agent.model.valobj.subagent.DynamicTask;
import com.shellmind.domain.agent.model.valobj.subagent.DynamicTaskPlan;
import com.shellmind.domain.agent.model.valobj.subagent.TaskStatus;
import com.shellmind.domain.agent.service.subagent.plan.DynamicAgentOrchestrator;
import com.shellmind.domain.agent.service.subagent.plan.PlanParser;
import com.shellmind.domain.agent.service.subagent.plan.PlanValidator;
import com.shellmind.domain.agent.service.subagent.plan.PlannerAgentBuilder;
import com.shellmind.domain.shared.model.RunContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Sub-agent tool (domain service):
 * <ul>
 *   <li>launchSubAgent: dispatch a single sub-agent (Explore / Verification / General)</li>
 *   <li>planAndDispatchSubAgents: the planner splits a complex task into a dependent sub-task plan (DAG), then dispatches concurrently by dependency</li>
 * </ul>
 * Sub-agents run in an independent child session; run context is derived from the parent (see {@link SubAgentManager}).
 */
@Slf4j
@Service
public class SubAgentToolService {

    /** Maximum tasks in a single dynamic plan */
    private static final int MAX_PLAN_TASKS = 10;

    /** Max characters of a single sub-task result returned to the parent conversation, to avoid blowing context */
    private static final int MAX_TASK_RESULT_CHARS = 3000;

    @Resource
    private SubAgentManager subAgentManager;

    @Resource
    private PlannerAgentBuilder plannerAgentBuilder;

    @Resource
    private PlanParser planParser;

    @Resource
    private PlanValidator planValidator;

    @Resource
    private DynamicAgentOrchestrator dynamicAgentOrchestrator;

    /**
     * Launch a sub-agent to handle a complex task.
     *
     * @param subagentType agent type: Explore / Verification / General
     * @param prompt       task description
     * @param name         short identifier (optional)
     * @return execution result text
     */
    public String launchSubAgent(RunContext ctx, String subagentType, String prompt, String name) {
        log.info("Sub-agent tool call: type={}, name={}, promptLen={}, agentId={}",
                subagentType, name, prompt != null ? prompt.length() : 0, ctx.agentId());

        try {
            SubAgentManager.AgentType agentType = SubAgentManager.AgentType.valueOf(subagentType.toUpperCase());
            String agentName = (name != null && !name.isEmpty()) ? name : subagentType.toLowerCase();

            SubAgentManager.AgentResult result = subAgentManager.execute(ctx, agentType, prompt, agentName);

            log.info("Sub-agent finished: type={}, success={}, durationMs={}",
                    subagentType, result.isSuccess(), result.getDurationMs());

            return result.toToolResult();

        } catch (IllegalArgumentException e) {
            log.warn("Unsupported agent type: {}", subagentType);
            return "Unsupported agent type: " + subagentType + ", supported: Explore/Verification/General";
        } catch (Exception e) {
            log.error("Sub-agent execution exception", e);
            return "Agent error: " + e.getMessage();
        }
    }

    /**
     * Plan then dispatch: a complex task is split into dependent sub-tasks by the planner, then dispatched concurrently as a DAG.
     * <p>
     * Flow: PlannerAgentBuilder has the model output a JSON plan → PlanParser parses (allowlist/non-empty) →
     * PlanValidator validates (count/uniqueness/existing deps/acyclic) → DynamicAgentOrchestrator executes by dependency →
     * sub-task results are summarized. Any failure returns error text and does not throw.
     *
     * @param request full task description
     * @return summarized result text
     */
    public String planAndDispatchSubAgents(RunContext ctx, String request) {
        if (request == null || request.isBlank()) {
            return "Error: request cannot be empty";
        }
        if (ctx.dynamicDispatch()) {
            return "Error: nested plan-and-dispatch is not allowed inside a sub-task; use other tools to finish the current sub-task";
        }

        List<String> allowedAgents = Arrays.stream(SubAgentManager.AgentType.values())
                .map(Enum::name)
                .toList();

        try {
            // 1. Planner generates a JSON task plan
            String planJson = plannerAgentBuilder.plan(ctx.agentId(), request, allowedAgents);

            // 2. Parse (including allowlist/non-empty checks) and validate structure and dependencies
            DynamicTaskPlan plan = planParser.parse(planJson, allowedAgents);
            planValidator.validate(plan, MAX_PLAN_TASKS);
            log.info("Dynamic plan dispatch: tasks={}, maxConcurrency={}, agentId={}",
                    plan.getTasks().size(), plan.getMaxConcurrency(), ctx.agentId());

            // 3. Orchestrator executes the DAG concurrently (sub-task context is derived from the parent run)
            Map<String, Object> result = dynamicAgentOrchestrator.execute(ctx, plan);
            return formatPlanResult(plan, result);
        } catch (Exception e) {
            log.error("Dynamic plan dispatch failed", e);
            return "Dynamic plan dispatch failed: " + e.getMessage() + ". You can fall back to launchSubAgent one at a time, or use other tools directly.";
        }
    }

    /**
     * Summarize as parent-conversation-readable text: overall status plus each sub-task's type, deps, status, and result
     */
    private String formatPlanResult(DynamicTaskPlan plan, Map<String, Object> result) {
        StringBuilder sb = new StringBuilder();
        // Note: status inference treats tool results containing failed/error as failure, so do not emit those words when all succeeded
        boolean allSucceeded = Boolean.TRUE.equals(result.get("allSucceeded"));
        sb.append(allSucceeded
                ? String.format("[Plan] %d sub-tasks all completed%n", plan.getTasks().size())
                : String.format("[Plan] %d sub-tasks, %s failed, %s skipped%n",
                        plan.getTasks().size(), result.get("failedCount"), result.get("skippedCount")));

        plan.getTasks().stream()
                .sorted(Comparator.comparing(DynamicTask::getTaskId))
                .forEach(task -> {
                    sb.append("\n### ").append(task.getTaskId())
                            .append(" (").append(task.getAgentName()).append(") ")
                            .append(task.getStatus());
                    if (task.getDependsOn() != null && !task.getDependsOn().isEmpty()) {
                        sb.append(" dependsOn=").append(task.getDependsOn());
                    }
                    sb.append("\nRequest: ").append(task.getRequest()).append("\n");
                    if (task.getStatus() == TaskStatus.COMPLETED) {
                        sb.append(truncate(task.getResult(), MAX_TASK_RESULT_CHARS)).append("\n");
                    } else if (task.getError() != null && !task.getError().isBlank()) {
                        sb.append("Error: ").append(truncate(task.getError(), 500)).append("\n");
                    }
                });
        return sb.toString();
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "...(truncated)";
    }
}
