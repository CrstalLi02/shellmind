package com.shellmind.domain.agent.model.valobj.subagent;

/**
 * Task execution state machine: PENDING → RUNNING → COMPLETED / FAILED;
 * PENDING → SKIPPED (upstream dependency FAILED/SKIPPED, or the plan aborted due to failFast).
 * <p>
 * DynamicAgentOrchestrator uses this status in the scheduling loop to pick runnable tasks;
 * SubAgentDispatchService writes the terminal state after the sub-agent finishes.
 */
public enum TaskStatus {
    /** In the plan but not started (waiting for dependencies to complete and a concurrency slot) */
    PENDING,
    /** Being dispatched to a sub-agent */
    RUNNING,
    /** Succeeded; result contains the sub-agent reply */
    COMPLETED,
    /** Failed (agent not found / timeout / retries exhausted); error contains the reason */
    FAILED,
    /** Skipped without running: an upstream dependency failed, or failFast aborted the remaining tasks */
    SKIPPED
}
