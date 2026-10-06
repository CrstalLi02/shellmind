package com.shellmind.cases.agent;

import com.shellmind.api.dto.AgentRunResponseDTO;
import com.shellmind.api.dto.RunResumeResponseDTO;
import com.shellmind.domain.agent.model.entity.AgentRunEntity;
import com.shellmind.domain.agent.service.run.AgentRunQueryService;
import com.shellmind.domain.conversation.adapter.repository.IChatHistoryRepository;
import com.shellmind.domain.conversation.model.entity.ChatMessageEntity;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Run-record query use case: run details, session runs, resumable run, resume context.
 */
@Service
public class AgentRunCase {

    /** Number of recent messages carried in resume context */
    private static final int RESUME_MESSAGE_LIMIT = 20;

    @Resource
    private AgentRunQueryService agentRunQueryService;

    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    public Optional<AgentRunResponseDTO> queryRun(String runId) {
        return Optional.ofNullable(agentRunQueryService.queryById(runId)).map(this::toResponse);
    }

    public List<AgentRunResponseDTO> queryRunsBySession(String sessionId, int limit) {
        return agentRunQueryService.queryBySessionId(sessionId, limit).stream().map(this::toResponse).toList();
    }

    public Optional<AgentRunResponseDTO> latestResumable(String sessionId) {
        return agentRunQueryService.findLatestResumable(sessionId).map(this::toResponse);
    }

    public Optional<RunResumeResponseDTO> resumeContext(String runId) {
        AgentRunEntity run = agentRunQueryService.queryById(runId);
        if (run == null) {
            return Optional.empty();
        }
        RunResumeResponseDTO dto = new RunResumeResponseDTO();
        dto.setRunId(run.getRunId());
        dto.setSessionId(run.getSessionId());
        dto.setUserId(run.getUserId());
        dto.setAgentId(run.getAgentId());
        dto.setStatus(run.getStatus() != null ? run.getStatus().name() : null);
        dto.setStopReason(run.getStopReason());
        List<Map<String, Object>> messageList = new ArrayList<>();
        for (ChatMessageEntity m : chatHistoryRepository.getRecentMessages(run.getSessionId(), RESUME_MESSAGE_LIMIT)) {
            Map<String, Object> msgMap = new HashMap<>();
            msgMap.put("role", m.getRole());
            msgMap.put("content", m.getContent());
            messageList.add(msgMap);
        }
        dto.setMessages(messageList);
        return Optional.of(dto);
    }

    private AgentRunResponseDTO toResponse(AgentRunEntity run) {
        return AgentRunResponseDTO.builder()
                .runId(run.getRunId())
                .sessionId(run.getSessionId())
                .userId(run.getUserId())
                .agentId(run.getAgentId())
                .status(run.getStatus() == null ? null : run.getStatus().name())
                .stopReason(run.getStopReason())
                .round(run.getRound())
                .totalToolCalls(run.getTotalToolCalls())
                .totalTokens(run.getTotalTokens())
                .startedAt(run.getStartedAt())
                .finishedAt(run.getFinishedAt())
                .cancelled(run.getCancelled())
                .cancelReason(run.getCancelReason())
                .contextCompressed(run.getContextCompressed())
                .build();
    }
}
