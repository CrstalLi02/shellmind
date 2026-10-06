package com.shellmind.domain.agent.service.subagent.plan;

import com.shellmind.domain.shared.model.RunContext;
import com.shellmind.domain.agent.model.valobj.subagent.DynamicTask;
import com.shellmind.domain.agent.model.valobj.subagent.DynamicTaskPlan;
import com.shellmind.domain.agent.model.valobj.subagent.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dynamic agent orchestrator — schedule a batch of sub-agent tasks according to their dependency DAG.
 * <p>
 * Scheduling:
 * <ol>
 *   <li>Each round pick PENDING tasks whose every dependency is already COMPLETED</li>
 *   <li>Submit that ready batch concurrently to a thread pool exclusive to this plan; pool size is the concurrency cap (maxConcurrency, clamped to 1–4)</li>
 *   <li>Wait for the round to finish, then start the next, until no PENDING tasks remain (or exit early on deadlock)</li>
 * </ol>
 * Failure handling:
 * <ul>
 *   <li>An exception on a single task only marks that task FAILED (the execution service already retried per maxRetries); the overall run continues</li>
 *   <li>Downstream PENDING tasks that depend on FAILED/SKIPPED tasks are explicitly SKIPPED, cascading along the dependency chain</li>
 *   <li>When the plan has failFast, after the first failure no new tasks are scheduled (in-flight ones finish); remaining PENDING tasks are SKIPPED</li>
 * </ul>
 * After all execution, return a summary (plan, per-task status, whether all succeeded).
 * <p>
 * Why not a global shared pool: a sub-agent may run for minutes, and the parent agent's tool thread blocks
 * waiting for the result; a shared pool could fill up or even deadlock with the parent. Each plan gets a
 * small dedicated pool that is shut down when done.
 */
@Slf4j
@Service
public class DynamicAgentOrchestrator {

    /** Hard cap on concurrency (sub-agents reuse the parent Runner and model quota, so keep it modest) */
    private static final int MAX_CONCURRENCY_CAP = 4;

    private final SubAgentDispatchService dispatchService;

    public DynamicAgentOrchestrator(SubAgentDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    /**
     * Execute the entire task plan (blocks until every task reaches a terminal state).
     *
     * @param context sub-agent execution context (carries the terminal session, etc.)
     * @param plan    validated task plan
     * @return {plan, tasks (with status/result), allSucceeded}
     */
    public Map<String, Object> execute(RunContext context, DynamicTaskPlan plan) {
        // taskId -> task index, for dependency lookup and status updates
        Map<String, DynamicTask> tasks = new HashMap<>();
        plan.getTasks().forEach(task -> tasks.put(task.getTaskId(), task));

        // Concurrency budget: at most maxConcurrency tasks running at once (thread count = concurrency)
        int maxConcurrency = Math.min(MAX_CONCURRENCY_CAP,
                Math.max(1, plan.getMaxConcurrency() == null ? 1 : plan.getMaxConcurrency()));
        AtomicInteger threadIndex = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(maxConcurrency, runnable -> {
            Thread thread = new Thread(runnable, "dynamic-subagent-" + threadIndex.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        try {
            // Scheduling loop: each round runs a batch of ready tasks whose dependencies are fully satisfied
            while (tasks.values().stream().anyMatch(task -> task.getStatus() == TaskStatus.PENDING)) {
                // failFast: a task has already failed; do not schedule new ones; SKIP remaining PENDING and exit
                if (Boolean.TRUE.equals(plan.getFailFast())
                        && tasks.values().stream().anyMatch(task -> task.getStatus() == TaskStatus.FAILED)) {
                    tasks.values().stream()
                            .filter(task -> task.getStatus() == TaskStatus.PENDING)
                            .forEach(task -> {
                                task.setStatus(TaskStatus.SKIPPED);
                                task.setError("skipped by failFast: another task failed");
                            });
                    break;
                }

                // Cascade skip: PENDING tasks that depend on FAILED/SKIPPED are explicitly SKIPPED (propagates round by round)
                tasks.values().stream()
                        .filter(task -> task.getStatus() == TaskStatus.PENDING)
                        .filter(task -> task.getDependsOn().stream()
                                .map(tasks::get)
                                .anyMatch(parent -> parent == null
                                        || parent.getStatus() == TaskStatus.FAILED
                                        || parent.getStatus() == TaskStatus.SKIPPED))
                        .forEach(task -> {
                            task.setStatus(TaskStatus.SKIPPED);
                            task.setError("skipped: dependency failed or skipped");
                        });

                // Ready tasks: PENDING and every dependency is COMPLETED
                List<DynamicTask> readyTasks = tasks.values().stream()
                        .filter(task -> task.getStatus() == TaskStatus.PENDING)
                        .filter(task -> task.getDependsOn().stream()
                                .map(tasks::get)
                                .allMatch(parent -> parent != null && parent.getStatus() == TaskStatus.COMPLETED))
                        .toList();

                // No ready tasks: remaining PENDING dependencies cannot be satisfied (validator already excluded cycles; this is a fallback) — exit early
                if (readyTasks.isEmpty()) {
                    break;
                }

                // Run this round concurrently: the pool rate-limits; a failed task is marked FAILED without aborting the rest
                List<CompletableFuture<Void>> futures = readyTasks.stream()
                        .map(task -> CompletableFuture.runAsync(() -> {
                            try {
                                dispatchService.execute(context, task);
                            } catch (Exception exception) {
                                task.setStatus(TaskStatus.FAILED);
                                task.setError(exception.getMessage());
                            }
                        }, executor))
                        .toList();

                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
            }
        } finally {
            executor.shutdownNow();
        }

        // Summary: plan, each task's final status (including success/fail/skip counts), whether all succeeded
        Map<String, Object> response = new HashMap<>();

        response.put("plan", plan);
        response.put("tasks", new ArrayList<>(tasks.values()));
        response.put("allSucceeded", tasks.values().stream().allMatch(task -> task.getStatus() == TaskStatus.COMPLETED));
        response.put("failedCount", tasks.values().stream().filter(task -> task.getStatus() == TaskStatus.FAILED).count());
        response.put("skippedCount", tasks.values().stream().filter(task -> task.getStatus() == TaskStatus.SKIPPED).count());

        return response;
    }

}
