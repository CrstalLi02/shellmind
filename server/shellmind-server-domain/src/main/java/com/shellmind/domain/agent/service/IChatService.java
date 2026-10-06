package com.shellmind.domain.agent.service;

import com.shellmind.domain.agent.model.entity.ChatCommandEntity;
import com.shellmind.domain.agent.model.valobj.AiAgentConfigTableVO;

import java.util.List;

/**
 * Basic chat service: agent list, session creation, non-streaming chat (does not go through the ReAct engine).
 * Streaming chat is in the use-case layer IAIAgentReActServiceCase.
 */
public interface IChatService {

    List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList();

    String createSession(String agentId, String userId);

    List<String> handleMessage(String agentId, String userId, String message);

    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    List<String> handleMessage(ChatCommandEntity chatCommandEntity);
}
