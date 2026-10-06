package com.shellmind.domain.agent.service.subagent.plan;

import com.shellmind.domain.shared.model.RunContext;
import com.shellmind.domain.agent.model.valobj.subagent.DynamicTask;
import com.shellmind.domain.agent.model.valobj.subagent.DynamicTaskPlan;
import com.shellmind.domain.agent.model.valobj.subagent.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dynamic agent orchestrator tests — DAG scheduling and failure-handling scenarios:
 * <ul>
 *   <li>Independent concurrent tasks all succeed</li>
 *   <li>Dependency chain runs in order (downstream sees upstream COMPLETED)</li>
 *   <li>Mid-chain failure → direct/indirect downstream cascade SKIPPED; independent branches unaffected</li>
 *   <li>failFast=true → remaining tasks all SKIPPED after the first failure</li>
 *   <li>failFast=false (default) → a failure does not abort the rest</li>
 *   <li>Concurrency cap works (serial when maxConcurrency=1)</li>
 *   <li>Summary includes failedCount / skippedCount</li>
 * </ul>
 * Note: FakeDispatchService replaces the real SubAgentDispatchService;
 * this does not touch SubAgentManager / Runner / LLM and only verifies orchestration and failure propagation.
 */
public class DynamicAgentOrchestratorTest {

    /** Programmable dispatch-service stand-in: taskId -> action */
    private static class FakeDispatchService extends SubAgentDispatchService {
        private final Map<String, Runnable> behaviors = new ConcurrentHashMap<>();
        private final Map<String, Integer> callCounts = new ConcurrentHashMap<>();
        private final Set<String> concurrentRunning = ConcurrentHashMap.newKeySet();
        private volatile int maxConcurrentObserved = 0;

        void onTask(String taskId, Runnable behavior) {
            behaviors.put(taskId, behavior);
        }

        int callCount(String taskId) {
            return callCounts.getOrDefault(taskId, 0);
        }

        @Override
        public void execute(RunContext context, DynamicTask task) {
            callCounts.merge(task.getTaskId(), 1, Integer::sum);
            concurrentRunning.add(task.getTaskId());
            synchronized (concurrentRunning) {
                maxConcurrentObserved = Math.max(maxConcurrentObserved, concurrentRunning.size());
            }
            try {
                Runnable behavior = behaviors.get(task.getTaskId());
                if (behavior != null) {
                    behavior.run();
                }
                // Default to success when the behavior does not explicitly set a terminal state
                if (task.getStatus() == TaskStatus.RUNNING || task.getStatus() == TaskStatus.PENDING) {
                    task.setStatus(TaskStatus.COMPLETED);
                    task.setResult("fake-result-" + task.getTaskId());
                }
            } finally {
                concurrentRunning.remove(task.getTaskId());
            }
        }

        int maxConcurrentObserved() {
            return maxConcurrentObserved;
        }
    }

    private FakeDispatchService dispatchService;
    private DynamicAgentOrchestrator orchestrator;
    private RunContext context;

    @BeforeEach
    public void setUp() {
        dispatchService = new FakeDispatchService();
        orchestrator = new DynamicAgentOrchestrator(dispatchService);
        context = new RunContext("test-session", "test-user", "parent-agent", null,
                false, null, null, false, null);
    }

    private DynamicTask task(String taskId, String... dependsOn) {
        return DynamicTask.builder()
                .taskId(taskId)
                .agentName("fakeAgent")
                .request("request-" + taskId)
                .dependsOn(List.of(dependsOn))
                .build();
    }

    @Test
    public void shouldCompleteAllIndependentTasks() {
        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(task("a"), task("b"), task("c")))
                .build();

        Map<String, Object> result = orchestrator.execute(context, plan);

        assertEquals(Boolean.TRUE, result.get("allSucceeded"));
        assertEquals(0L, result.get("failedCount"));
        assertEquals(0L, result.get("skippedCount"));
        plan.getTasks().forEach(t -> assertEquals(TaskStatus.COMPLETED, t.getStatus()));
    }

    @Test
    public void shouldExecuteDependencyChainInOrder() {
        DynamicTask a = task("a");
        DynamicTask b = task("b", "a");
        DynamicTask c = task("c", "b");
        // Verify order: upstream must already be COMPLETED when downstream runs
        dispatchService.onTask("b", () ->
                assertEquals(TaskStatus.COMPLETED, a.getStatus(), "a must already be complete when b runs"));
        dispatchService.onTask("c", () ->
                assertEquals(TaskStatus.COMPLETED, b.getStatus(), "b must already be complete when c runs"));

        DynamicTaskPlan plan = DynamicTaskPlan.builder().tasks(List.of(a, b, c)).build();
        Map<String, Object> result = orchestrator.execute(context, plan);

        assertEquals(Boolean.TRUE, result.get("allSucceeded"));
        assertEquals(1, dispatchService.callCount("a"));
        assertEquals(1, dispatchService.callCount("b"));
        assertEquals(1, dispatchService.callCount("c"));
    }

    @Test
    public void shouldCascadeSkipDependentsWhenMiddleTaskFails() {
        // a → b(FAILED) → c; independent branch d is unaffected
        DynamicTask a = task("a");
        DynamicTask b = task("b", "a");
        DynamicTask c = task("c", "b");
        DynamicTask d = task("d");
        dispatchService.onTask("b", () -> {
            b.setStatus(TaskStatus.FAILED);
            b.setError("simulated execution failure");
        });

        DynamicTaskPlan plan = DynamicTaskPlan.builder().tasks(List.of(a, b, c, d)).build();
        Map<String, Object> result = orchestrator.execute(context, plan);

        assertEquals(Boolean.FALSE, result.get("allSucceeded"));
        assertEquals(1L, result.get("failedCount"));
        assertEquals(1L, result.get("skippedCount"));

        assertEquals(TaskStatus.COMPLETED, a.getStatus());
        assertEquals(TaskStatus.FAILED, b.getStatus());
        // Key assertion: c is explicitly skipped rather than stuck PENDING, and was not dispatched
        assertEquals(TaskStatus.SKIPPED, c.getStatus());
        assertTrue(c.getError().contains("skipped"));
        assertEquals(0, dispatchService.callCount("c"));
        // Independent branch still completes
        assertEquals(TaskStatus.COMPLETED, d.getStatus());
        assertEquals(1, dispatchService.callCount("d"));
    }

    @Test
    public void shouldCascadeSkipAlongMultiLevelChain() {
        // a(FAILED) → b → c → d: skip should cascade to the end of the chain
        DynamicTask a = task("a");
        DynamicTask b = task("b", "a");
        DynamicTask c = task("c", "b");
        DynamicTask d = task("d", "c");
        dispatchService.onTask("a", () -> a.setStatus(TaskStatus.FAILED));

        DynamicTaskPlan plan = DynamicTaskPlan.builder().tasks(List.of(a, b, c, d)).build();
        Map<String, Object> result = orchestrator.execute(context, plan);

        assertEquals(1L, result.get("failedCount"));
        assertEquals(3L, result.get("skippedCount"));
        assertEquals(TaskStatus.FAILED, a.getStatus());
        assertEquals(TaskStatus.SKIPPED, b.getStatus());
        assertEquals(TaskStatus.SKIPPED, c.getStatus());
        assertEquals(TaskStatus.SKIPPED, d.getStatus());
        assertEquals(0, dispatchService.callCount("d"));
    }

    @Test
    public void shouldSkipRemainingTasksWhenFailFastEnabled() {
        // failFast=true: after b fails, c which depends on b is cascade-SKIPPED; d depending on slow task e is also aborted by failFast
        DynamicTask a = task("a");
        DynamicTask b = task("b");
        DynamicTask c = task("c", "b");  // c depends on b
        DynamicTask e = task("e");       // e is a slow task
        DynamicTask d = task("d", "e");  // d depends on e; d is still PENDING when b fails
        dispatchService.onTask("b", () -> b.setStatus(TaskStatus.FAILED));
        dispatchService.onTask("e", () -> sleepQuietly(500));  // e runs 500ms so b fails first

        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(a, b, c, e, d))
                .maxConcurrency(4)
                .failFast(true)
                .build();

        Map<String, Object> result = orchestrator.execute(context, plan);

        assertEquals(Boolean.FALSE, result.get("allSucceeded"));
        assertEquals(TaskStatus.FAILED, b.getStatus());
        // c depends on b; after b fails, c is cascade-SKIPPED
        assertEquals(TaskStatus.SKIPPED, c.getStatus());
        assertTrue(c.getError().contains("skipped"));
        assertEquals(0, dispatchService.callCount("c"));
        // d depends on e (in flight); under failFast, d is SKIPPED after b fails
        assertEquals(TaskStatus.SKIPPED, d.getStatus());
        assertTrue(d.getError().contains("failFast"));
        assertEquals(0, dispatchService.callCount("d"));
    }

    @Test
    public void shouldContinueOtherBranchesWhenFailFastDisabled() {
        // failFast=false (default): b failing does not interrupt c
        DynamicTask a = task("a");
        DynamicTask b = task("b");
        DynamicTask c = task("c");
        dispatchService.onTask("b", () -> b.setStatus(TaskStatus.FAILED));

        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(a, b, c))
                .failFast(false)
                .build();

        Map<String, Object> result = orchestrator.execute(context, plan);

        assertEquals(Boolean.FALSE, result.get("allSucceeded"));
        assertEquals(1L, result.get("failedCount"));
        assertEquals(0L, result.get("skippedCount"));
        assertEquals(TaskStatus.COMPLETED, a.getStatus());
        assertEquals(TaskStatus.COMPLETED, c.getStatus());
    }

    @Test
    public void shouldRespectMaxConcurrencyLimit() {
        // With maxConcurrency=1, the three tasks must run serially (observed max concurrency is 1)
        DynamicTaskPlan plan = DynamicTaskPlan.builder()
                .tasks(List.of(task("a"), task("b"), task("c")))
                .maxConcurrency(1)
                .build();
        for (String id : List.of("a", "b", "c")) {
            dispatchService.onTask(id, () -> sleepQuietly(50));
        }

        Map<String, Object> result = orchestrator.execute(context, plan);

        assertEquals(Boolean.TRUE, result.get("allSucceeded"));
        assertEquals(1, dispatchService.maxConcurrentObserved(), "should run serially when maxConcurrency=1");
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
