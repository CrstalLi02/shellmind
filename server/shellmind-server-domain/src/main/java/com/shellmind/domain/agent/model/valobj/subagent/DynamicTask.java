package com.shellmind.domain.agent.model.valobj.subagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Dynamic task — a task unit in a multi-agent dispatch plan.
 * <p>
 * Parsed by PlanParser from JSON produced by the planner (PlannerAgentBuilder).
 * Describes which sub-agent type (EXPLORE / VERIFICATION / GENERAL) should run which request
 * and which prerequisite tasks it depends on.
 * The task object itself carries execution status (status) and result (result / error),
 * updated in place during scheduling by the orchestrator (DynamicAgentOrchestrator).
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DynamicTask {
    /** Unique task ID; PlanParser auto-generates task-{uuid} when missing */
    private String taskId;
    /** Sub-agent type to dispatch (SubAgentManager.AgentType name), normalized to uppercase by PlanParser */
    private String agentName;
    /** Complete task instruction sent to the sub-agent */
    private String request;
    /** Prerequisite task IDs; this task is scheduled only after all dependencies are COMPLETED */
    @Builder.Default
    private List<String> dependsOn = new ArrayList<>();
    /**
     * Max automatic retries after failure (excluding the first attempt); default 0 means no retry.
     * <p>
     * Applies only to "runtime failures" (timeout/exception); deterministic errors such as a missing
     * agent type go straight to FAILED. Retry interval is exponential 2^n seconds, capped at 30s.
     * Note: GENERAL sub-agents can write; keep 0 for non-idempotent write tasks and let the main
     * agent decide after seeing the failure reason.
     */
    @Builder.Default
    private Integer maxRetries = 0;
    /** Actual attempt count (including the first), written back by SubAgentDispatchService for the main agent to observe retries */
    @Builder.Default
    private Integer attempts = 0;
    /** Current task status, advanced by orchestration scheduling */
    @Builder.Default
    private TaskStatus status = TaskStatus.PENDING;
    /** Final reply text after the sub-agent finishes */
    @Builder.Default
    private String result = "";
    /** Error message when execution fails */
    @Builder.Default
    private String error = "";
}
