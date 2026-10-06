package com.shellmind.domain.agent.service.subagent.plan;

import com.shellmind.domain.shared.model.RunContext;
import com.shellmind.domain.agent.model.valobj.subagent.DynamicTask;
import com.shellmind.domain.agent.model.valobj.subagent.TaskStatus;
import com.shellmind.domain.agent.service.subagent.SubAgentManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Sub-agent dispatch service — the Spring service that actually runs a single task in the dynamic orchestration system.
 * <p>
 * For a runtime-planned task list: hand each DynamicTask to {@link SubAgentManager}, then write back
 * status and result. DynamicAgentOrchestrator calls this from a dedicated thread pool; run context is
 * passed explicitly, and the sub-task is marked as dynamic dispatch (no nested planning), independent of the executing thread.
 */
@Slf4j
@Service
public class SubAgentDispatchService {

    /**
     * Retry backoff cap in seconds; exponential backoff 2^n is clamped to this
     */
    private static final long MAX_BACKOFF_SECONDS = 30;

    @Resource
    private SubAgentManager subAgentManager;

    /**
     * Execute a single task and update its status in place (including failure retries):
     * <ol>
     *   <li>Set RUNNING, resolve the sub-agent type; illegal types go straight to FAILED (deterministic, no retry)</li>
     *   <li>Loop up to maxRetries (default 0); each attempt runs in an independent child session via SubAgentManager</li>
     *   <li>On success, write result and set COMPLETED; on failure, exponential backoff of 2^n seconds (cap 30s) then retry; exhaust retries → FAILED</li>
     *   <li>Write back attempts (actual try count)</li>
     * </ol>
     */
    public void execute(RunContext context, DynamicTask task) {
        task.setStatus(TaskStatus.RUNNING);

        SubAgentManager.AgentType agentType;
        try {
            agentType = SubAgentManager.AgentType.valueOf(task.getAgentName());
        } catch (IllegalArgumentException | NullPointerException e) {
            task.setStatus(TaskStatus.FAILED);
            task.setError("agent not found: " + task.getAgentName());
            return;
        }

        // Max attempts = first try + retries; floor maxRetries so the LLM cannot pass a negative
        int maxAttempts = 1 + Math.max(0, task.getMaxRetries() == null ? 0 : task.getMaxRetries());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            task.setAttempts(attempt);
            SubAgentManager.AgentResult result = subAgentManager.execute(
                    context.asDynamicDispatch(), agentType, task.getRequest(), task.getTaskId());

            if (result.isSuccess()) {
                task.setResult(result.getOutput());
                task.setStatus(TaskStatus.COMPLETED);
                return;
            }

            if (attempt < maxAttempts) {
                long backoff = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(attempt, 5));
                log.warn("Sub-agent execution failed, retrying in {}s | agent={} | task={} | attempt={}/{} | error={}",
                        backoff, task.getAgentName(), task.getTaskId(), attempt, maxAttempts, result.getOutput());
                sleepQuietly(backoff);
            } else {
                task.setStatus(TaskStatus.FAILED);
                task.setError("attempts=" + attempt + " | " + result.getOutput());
                log.error("Sub-agent execution failed (retries exhausted) | agent={} | task={} | attempts={} | error={}",
                        task.getAgentName(), task.getTaskId(), attempt, result.getOutput());
            }
        }
    }

    /**
     * Backoff wait: on interrupt, restore the interrupt flag and return (the task yields to outer timeout/cancel semantics)
     */
    private void sleepQuietly(long seconds) {
        try {
            Thread.sleep(seconds * 1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

}
