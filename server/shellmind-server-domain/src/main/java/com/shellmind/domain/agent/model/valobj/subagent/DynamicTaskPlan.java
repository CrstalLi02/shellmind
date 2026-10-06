package com.shellmind.domain.agent.model.valobj.subagent;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * Dynamic task plan — a complete execution plan for one multi-agent dispatch.
 * <p>
 * Built from planner JSON after PlanParser parsing and PlanValidator checks,
 * then executed concurrently by DynamicAgentOrchestrator according to the DAG.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DynamicTaskPlan {

    /** Tasks in the plan, forming a directed acyclic graph (DAG); cycles are not allowed */
    private List<DynamicTask> tasks;
    /** Max concurrency; the orchestrator uses a Semaphore to limit simultaneous sub-agents */
    @Builder.Default
    private Integer maxConcurrency = 4;
    /** Whether to wait for user confirmation before executing (reserved) */
    @Builder.Default
    private Boolean requireConfirmation = false;
    /**
     * Fail-fast: default false — after a task fails, remaining independent tasks continue
     * and only its downstream tasks are skipped; when true, the first failure stops scheduling
     * new PENDING tasks (in-flight ones finish) and remaining tasks are all SKIPPED.
     */
    @Builder.Default
    private Boolean failFast = false;

}
