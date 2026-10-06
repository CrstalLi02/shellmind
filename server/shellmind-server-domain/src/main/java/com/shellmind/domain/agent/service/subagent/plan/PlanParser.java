package com.shellmind.domain.agent.service.subagent.plan;

import com.shellmind.domain.agent.model.valobj.subagent.DynamicTask;
import com.shellmind.domain.agent.model.valobj.subagent.DynamicTaskPlan;
import com.shellmind.domain.agent.model.valobj.subagent.TaskStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Task-plan parser — parse planner output text into a {@link DynamicTaskPlan}.
 * <p>
 * Planner output may mix in explanatory text; this class extracts the JSON between the first '{'
 * and the last '}', converts each item, fills a missing taskId (auto-generated task-{uuid}),
 * and already at parse time checks the agent allowlist and non-empty request, throwing
 * IllegalArgumentException on invalid data.
 * <p>
 * Planner output is untrusted: execution-state fields (status/result/error/attempts) are always reset,
 * and agent names are matched case-insensitively against the allowlist and normalized.
 */
@Service
public class PlanParser {
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse the planner's JSON task plan.
     *
     * @param content        raw planner output (may contain text besides JSON)
     * @param allowedAgents  allowlist of sub-agent names that may be dispatched
     * @return structured task plan (maxConcurrency defaults to 4)
     * @throws IllegalArgumentException JSON missing, malformed, empty tasks, agent not on allowlist, or blank request
     */
    public DynamicTaskPlan parse(String content, List<String> allowedAgents) {
        try {
            Map<String, Object> root = objectMapper.readValue(extractJson(content), new TypeReference<>() {});
            Object tasksObject = root.get("tasks");
            List<Map<String, Object>> rawTasks = objectMapper.convertValue(tasksObject, new TypeReference<>() {});
            if (rawTasks == null || rawTasks.isEmpty()) {
                throw new IllegalArgumentException("tasks is empty");
            }

            List<DynamicTask> tasks = rawTasks.stream()
                    .map(item -> objectMapper.convertValue(item, DynamicTask.class))
                    .peek(task -> {
                        if (task.getTaskId() == null || task.getTaskId().isBlank()) {
                            task.setTaskId("task-" + UUID.randomUUID());
                        }
                        task.setAgentName(resolveAgentName(task.getAgentName(), allowedAgents));
                        if (task.getRequest() == null || task.getRequest().isBlank()) {
                            throw new IllegalArgumentException("task request is blank: " + task.getTaskId());
                        }
                        if (task.getDependsOn() == null) {
                            task.setDependsOn(new ArrayList<>());
                        }
                        if (task.getMaxRetries() == null || task.getMaxRetries() < 0) {
                            task.setMaxRetries(0);
                        }
                        // Execution-state fields may only be written by the orchestrator; ignore any planner output
                        task.setStatus(TaskStatus.PENDING);
                        task.setAttempts(0);
                        task.setResult("");
                        task.setError("");
                    })
                    .toList();

            return DynamicTaskPlan.builder()
                    .tasks(tasks)
                    .maxConcurrency(toInt(root.get("maxConcurrency"), 4))
                    .requireConfirmation(Boolean.TRUE.equals(root.get("requireConfirmation")))
                    .failFast(Boolean.TRUE.equals(root.get("failFast")))
                    .build();
        } catch (JsonProcessingException | ClassCastException exception) {
            throw new IllegalArgumentException("invalid agent plan", exception);
        }
    }

    /**
     * Allowlist match (case-insensitive); return the canonical name from the allowlist; reject if not listed.
     */
    private String resolveAgentName(String agentName, List<String> allowedAgents) {
        if (agentName != null) {
            String normalized = agentName.trim().toUpperCase(Locale.ROOT);
            for (String allowed : allowedAgents) {
                if (allowed.toUpperCase(Locale.ROOT).equals(normalized)) {
                    return allowed;
                }
            }
        }
        throw new IllegalArgumentException("agent not allowed: " + agentName);
    }

    /**
     * Tolerate the planner writing numbers as strings; use the default when parsing fails.
     */
    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * Extract a JSON fragment from mixed text: between the first '{' and the last '}'.
     */
    private String extractJson(String content) {
        if (content == null) {
            throw new IllegalArgumentException("plan json not found");
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("plan json not found");
        }
        return content.substring(start, end + 1);
    }
}
