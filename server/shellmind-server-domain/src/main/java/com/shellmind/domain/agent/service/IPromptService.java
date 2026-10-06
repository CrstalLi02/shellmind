package com.shellmind.domain.agent.service;

import java.util.List;
import java.util.Map;

import com.shellmind.domain.agent.model.valobj.prompt.TaskModeVO;

/**
 * Prompt domain-service interface.
 * <p>
 * Encapsulates dynamic prompt building, milestone tracking, and environment collection
 * so the case layer depends only on this interface and not on DynamicPromptBuilder / MilestoneTracker internals.
 *
 * @author xiaofuge bugstack.cn @xiaofuge
 * 2026/5/5 22:16
 */
public interface IPromptService {

    /**
     * Detect and record a milestone event (user correction, task switch, error, etc.).
     *
     * @param sessionId conversation session ID
     * @param role      message role: "user" or "tool"
     * @param content   message content
     */
    void detectAndRecordMilestone(String userId, String sessionId, String role, String content);

    /**
     * Build a user message with dynamic context injected.
     * <p>
     * Internally:
     * 1. Collect environment info from the SSH terminal (OS, user, working directory)
     * 2. Load recent milestone events
     * 3. Build PromptContextVO and generate a message prefix
     * 4. Concatenate the prefix with the original user message
     *
     * @param userMessage        original user message
     * @param sessionId          conversation session ID
     * @param terminalSessionId  SSH terminal session ID (may be null)
     * @param recentCommands     recently executed commands
     * @param messageHistory     conversation history
     * @return user message with dynamic context injected
     */
    String buildEnrichedMessage(String userMessage, String userId, String sessionId, String terminalSessionId, List<String> recentCommands, List<Map<String, Object>> messageHistory, String projectName, String projectRootPath, TaskModeVO taskMode);

    /**
     * Clear milestone records for the given session.
     *
     * @param sessionId conversation session ID
     */
    void clearMilestones(String sessionId);
}
