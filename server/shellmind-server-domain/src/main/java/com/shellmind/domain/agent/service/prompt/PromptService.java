package com.shellmind.domain.agent.service.prompt;

import com.shellmind.domain.agent.model.valobj.prompt.PromptContextVO;
import com.shellmind.domain.agent.model.valobj.prompt.TaskModeVO;
import com.shellmind.domain.agent.service.IChatContextService;
import com.shellmind.domain.agent.service.IPromptService;
import com.shellmind.domain.agent.service.prompt.dynamic.DynamicPromptBuilder;
import com.shellmind.domain.agent.service.prompt.dynamic.MilestoneTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * Prompt service.
 * <p>
 * Composes DynamicPromptBuilder, MilestoneTracker, and IChatContextService
 * to give the case layer a unified prompt-domain API.
 *
 * @author xiaofuge bugstack.cn @xiaofuge
 * 2026/5/5 22:18
 */
@Slf4j
@Service
public class PromptService implements IPromptService {

    @Resource
    private DynamicPromptBuilder dynamicPromptBuilder;

    @Resource
    private MilestoneTracker milestoneTracker;

    @Resource
    private IChatContextService chatContextService;

    @Override
    public void detectAndRecordMilestone(String userId, String sessionId, String role, String content) {
        milestoneTracker.detectAndRecord(userId, sessionId, role, content);
    }

    @Override
    public String buildEnrichedMessage(String userMessage, String userId, String sessionId, String terminalSessionId, List<String> recentCommands, List<Map<String, Object>> messageHistory, String projectName, String projectRootPath, TaskModeVO taskMode) {
        // 1. Collect context via ChatContextService
        PromptContextVO promptContextVO = chatContextService.buildPromptContext(sessionId, userId, terminalSessionId, messageHistory);
        promptContextVO.setTaskMode(taskMode);
        
        // Append recentCommands from the case layer
        promptContextVO.setRecentCommands(recentCommands);

        // Append current project context (injected by the frontend)
        if (projectName != null && !projectName.isBlank()) {
            promptContextVO.setProjectName(projectName);
        }
        if (projectRootPath != null && !projectRootPath.isBlank()) {
            promptContextVO.setProjectRootPath(projectRootPath);
        }

        // 2. Build the message prefix
        String prefix = dynamicPromptBuilder.buildMessagePrefix(promptContextVO);
        String outputDiscipline = dynamicPromptBuilder.buildOutputDiscipline();

        String contextPrefix = prefix.isEmpty() ? "" : prefix + "\n---\n";
        return contextPrefix + userMessage + "\n\n" + outputDiscipline;
    }

    @Override
    public void clearMilestones(String sessionId) {
        milestoneTracker.clear(sessionId);
    }
}
