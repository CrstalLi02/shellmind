package com.shellmind.domain.agent.service;

import com.shellmind.domain.agent.model.valobj.prompt.PromptContextVO;

import java.util.List;
import java.util.Map;

/**
 * Context-management domain-service interface.
 *
 * @author xiaofuge bugstack.cn @xiaofuge
 */
public interface IChatContextService {
    PromptContextVO buildPromptContext(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory);
    List<Map<String, Object>> trimHistory(List<Map<String, Object>> history, int tokenBudget);
    void pushToolResult(String sessionId, String toolName, String result);
}
