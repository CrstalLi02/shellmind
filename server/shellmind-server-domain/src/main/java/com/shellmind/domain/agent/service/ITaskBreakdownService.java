package com.shellmind.domain.agent.service;

import com.shellmind.domain.agent.model.valobj.task.TaskBreakdownVO;

import java.util.List;
import java.util.Map;

/**
 * Task-breakdown domain-service interface.
 *
 * <p>When the user poses a complex task, split it into ordered subtasks.
 * Corresponds to the Task Breakdown pattern in the ShellMind reference project.
 *
 * <p>Flow:
 * 1. Detect whether the user request needs breakdown (based on complexity)
 * 2. Call AI to generate a breakdown plan
 * 3. Return the breakdown for the frontend to display and the user to confirm
 *
 * @author shellmind dev
 * 2026/6/19
 */
public interface ITaskBreakdownService {

    /**
     * Detect whether the user request needs task breakdown.
     */
    boolean shouldBreakdown(String userMessage, String sessionId);

    /**
     * Run task breakdown.
     */
    TaskBreakdownVO breakdown(String userMessage, String sessionId, String agentId,
                               List<Map<String, Object>> messageHistory);

    /**
     * Run task breakdown with a specified model: modelId prefers the user-configured model;
     * when unset, the implementation falls back to the default weak model.
     */
    TaskBreakdownVO breakdown(String userMessage, String sessionId, String agentId,
                               List<Map<String, Object>> messageHistory, Long modelId);

    /**
     * Update a subtask's status.
     */
    void updateSubTaskStatus(String sessionId, int subTaskIndex, String status, String result);

    /**
     * Get the session's task-breakdown result.
     */
    TaskBreakdownVO getBreakdown(String sessionId);

    /**
     * Clear the session's breakdown record.
     */
    void clearBreakdown(String sessionId);

}
