package com.shellmind.cases.agent;

import com.shellmind.api.dto.AiAgentConfigResponseDTO;
import com.shellmind.api.dto.ChatRequestDTO;
import com.shellmind.domain.agent.service.IChatService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent-session use case: agent list, create session, non-streaming chat.
 */
@Service
public class AgentSessionCase {

    @Resource
    private IChatService chatService;

    public List<AiAgentConfigResponseDTO> queryAgentConfigs() {
        return chatService.queryAiAgentConfigList().stream().map(agentConfig -> {
            AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
            responseDTO.setAgentId(agentConfig.getAgentId());
            responseDTO.setAgentName(agentConfig.getAgentName());
            responseDTO.setAgentDesc(agentConfig.getAgentDesc());
            return responseDTO;
        }).toList();
    }

    public String createSession(String agentId, String userId) {
        return chatService.createSession(agentId, userId);
    }

    /**
     * Non-streaming chat: create a session if missing, then return the concatenated reply.
     */
    public String chat(ChatRequestDTO request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = chatService.createSession(request.getAgentId(), request.getUserId());
        }
        List<String> messages = chatService.handleMessage(
                request.getAgentId(), request.getUserId(), sessionId, request.getMessage());
        return String.join("\n", messages);
    }

    /**
     * Ensure a session exists before streaming chat: create one if missing and write it back onto the request.
     */
    public void ensureSession(ChatRequestDTO request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            request.setSessionId(chatService.createSession(request.getAgentId(), request.getUserId()));
        }
    }
}
