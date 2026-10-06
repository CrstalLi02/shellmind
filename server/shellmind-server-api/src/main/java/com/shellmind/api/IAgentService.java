package com.shellmind.api;

import com.shellmind.api.dto.*;
import com.shellmind.api.response.Response;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.List;

/**
 * Agent service API
 * @author xiaofuge bugstack.cn
 * 2026/1/20 08:16
 */
public interface IAgentService {

    Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList();

    Response<CreateSessionResponseDTO> createSession(CreateSessionRequestDTO requestDTO);

    Response<ChatResponseDTO> chat(ChatRequestDTO requestDTO);

    ResponseBodyEmitter chatStream(ChatRequestDTO requestDTO);

    Response<AgentRunResponseDTO> queryAgentRun(String runId);

    Response<List<AgentRunResponseDTO>> queryAgentRunsBySession(String sessionId, int limit);

}
