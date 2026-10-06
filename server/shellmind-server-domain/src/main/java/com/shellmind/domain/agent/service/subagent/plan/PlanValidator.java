package com.shellmind.domain.agent.service.subagent.plan;

import com.shellmind.domain.agent.model.valobj.subagent.DynamicTaskPlan;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Task-plan validator — structural and safety checks on {@link DynamicTaskPlan} before dispatch.
 * <p>
 * Role: the main Agent (LLM) dynamically plans a task graph (sub-tasks and dependencies)
 * and hands it to the orchestrator. LLM output is <b>untrusted</b> — it may be empty, exceed
 * the task cap, duplicate IDs, reference missing dependencies, or even contain cycles.
 * This class is the "foolproof + runaway-proof" firewall between LLM planning and the
 * deterministic executor: intercept dirty data so the executor does not stall or run away.
 * <p>
 * Validation rules:
 * <ol>
 *   <li>Plan is non-empty and task count does not exceed the cap (prevents runaway-scale plans)</li>
 *   <li>taskId values are unique (duplicates would scramble dispatch and make dependencies ambiguous)</li>
 *   <li>Every dependsOn reference must exist in the plan (dangling deps would wait forever)</li>
 *   <li>Dependencies must be acyclic (three-color DFS); otherwise the orchestrator stalls with no runnable task</li>
 * </ol>
 * <p>
 * Design notes:
 * <ul>
 *   <li>Fail fast: cheap structural checks (empty/over-cap/duplicates) run in O(n) first; cycle detection last</li>
 *   <li>Hard and soft constraints: hard ones (empty/duplicate/cycle) reject; soft ones (retry count) are clamped
 *       so a minor issue does not force the LLM to replan the whole round</li>
 *   <li>Any failed rule throws IllegalArgumentException; the dispatch tool returns the error to the LLM
 *       so it can fix the plan and retry</li>
 * </ul>
 */
@Service
public class PlanValidator {

    /**
     * Run full validation; throw {@link IllegalArgumentException} on failure.
     * <p>
     * Weaker models may produce errors (empty plan, hallucinated taskIds), which causes this
     * method to throw — that is intended: surface model-quality issues early and keep dirty
     * plans out of the execution layer.
     *
     * @param plan     plan to validate
     * @param maxTasks task-count cap (typically 10 when the main agent dispatches)
     */
    public void validate(DynamicTaskPlan plan, int maxTasks) {
        // Rule 1: plan is non-empty — the LLM may return an empty plan, or parse failure may yield null
        if (plan == null || plan.getTasks() == null || plan.getTasks().isEmpty()) {
            throw new IllegalArgumentException("task plan is empty");
        }
        // Rule 1b: count cap — prevent runaway plans of e.g. 50 tasks that would exhaust execution resources
        if (plan.getTasks().size() > maxTasks) {
            throw new IllegalArgumentException("too many tasks: " + plan.getTasks().size());
        }

        // Rule 2: unique taskId — HashSet.add() returning false means the ID already exists
        Set<String> taskIds = new HashSet<>();
        for (var task : plan.getTasks()) {
            if (!taskIds.add(task.getTaskId())) {
                throw new IllegalArgumentException("duplicate task id: " + task.getTaskId());
            }
            // Retry cap: at most 3 retries, so an LLM cannot plan 100 retries that blow the runtime.
            // Typically 3–5 is enough. This is a silent clamp, not an exception — a minor fix,
            // no need for the LLM to replan the whole round.
            if (task.getMaxRetries() != null && task.getMaxRetries() > 3) {
                task.setMaxRetries(3);
            }
        }

        // Rule 3: dependencies must exist — referencing a missing task is a dangling wait
        // that the orchestrator would never see complete.
        for (var task : plan.getTasks()) {
            for (String dependency : task.getDependsOn()) {
                if (!taskIds.contains(dependency)) {
                    throw new IllegalArgumentException("unknown dependency: " + dependency);
                }
            }
        }

        // Rule 4: no cycles — A→B→C→A would leave every task waiting on another,
        // so the orchestrator never finds a runnable task and stalls forever.
        if (hasCycle(plan)) {
            throw new IllegalArgumentException("task dependency cycle");
        }
    }

    /** Walk every task for a dependency cycle (true if any cycle exists).
     *  Already-visited nodes are pruned; overall complexity is O(V+E). */
    private boolean hasCycle(DynamicTaskPlan plan) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();

        for (var task : plan.getTasks()) {
            if (hasCycle(task.getTaskId(), task.getDependsOn(), plan, visiting, visited)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Three-color DFS cycle detection.
     * <p>
     * visiting = nodes on the current recursion stack (gray, the path being checked);
     * visited = nodes whose check is complete (black, can be skipped later).
     * Key: hitting a node already in visiting during DFS means the chain looped back
     * onto the current path — a back edge, hence a cycle.
     * <p>
     * Why two sets? A single visited set cannot distinguish:
     * <ul>
     *   <li>"node is on the current path" (cycle, must fail)</li>
     *   <li>"node already checked and acyclic" (safe, prune)</li>
     * </ul>
     * This is classic three-color marking (white/gray/black), the same idea used in JVM GC reachability and deadlock detection.
     */
    private boolean hasCycle(String taskId, List<String> dependencies, DynamicTaskPlan plan,
                             Set<String> visiting, Set<String> visited) {

        if (visiting.contains(taskId)) return true;   // back edge = node already on the current recursion stack → cycle
        if (visited.contains(taskId)) return false;   // fully checked acyclic node, prune
        visiting.add(taskId);

        // DFS along dependsOn: find the depended-on task and recurse into its chain
        for (String dependency : dependencies) {
            var parent = plan.getTasks().stream()
                    .filter(item -> item.getTaskId().equals(dependency))
                    .findFirst().orElse(null);
            if (parent != null && hasCycle(dependency, parent.getDependsOn(), plan, visiting, visited)) {
                return true;
            }
        }

        // This node's whole dependency chain is done and off the stack; mark as fully checked
        visiting.remove(taskId);
        visited.add(taskId);

        return false;
    }

}
