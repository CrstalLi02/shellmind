package com.shellmind.infrastructure.adapter.repository;

import com.shellmind.domain.agent.adapter.repository.IAgentRunRepository;
import com.shellmind.domain.agent.model.entity.AgentRunEntity;
import com.shellmind.domain.agent.model.valobj.run.AgentRunStatus;
import com.shellmind.infrastructure.dao.IAgentRunDao;
import com.shellmind.infrastructure.dao.po.AgentRunPO;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.stream.Collectors;

@Repository
public class AgentRunRepository implements IAgentRunRepository {

    @Resource
    private IAgentRunDao agentRunDao;

    @Override
    public void save(AgentRunEntity entity) {
        AgentRunPO po = toPO(entity);
        if (agentRunDao.queryById(po.getRunId()) == null) {
            agentRunDao.insert(po);
        } else {
            agentRunDao.update(po);
        }
    }

    @Override
    public AgentRunEntity findById(String runId) {
        AgentRunPO po = agentRunDao.queryById(runId);
        return po == null ? null : toEntity(po);
    }

    @Override
    public List<AgentRunEntity> findBySessionId(String sessionId, int limit) {
        return agentRunDao.queryBySessionId(sessionId, limit).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    private AgentRunPO toPO(AgentRunEntity entity) {
        return AgentRunPO.builder()
                .runId(entity.getRunId())
                .sessionId(entity.getSessionId())
                .userId(entity.getUserId())
                .agentId(entity.getAgentId())
                .status(entity.getStatus() == null ? null : entity.getStatus().name())
                .stopReason(entity.getStopReason())
                .round(entity.getRound())
                .totalToolCalls(entity.getTotalToolCalls())
                .totalTokens(entity.getTotalTokens())
                .startedAt(entity.getStartedAt())
                .finishedAt(entity.getFinishedAt())
                .cancelled(entity.getCancelled())
                .cancelReason(entity.getCancelReason())
                .contextCompressed(entity.getContextCompressed())
                .maxRounds(entity.getMaxRounds())
                .maxToolCallsPerRound(entity.getMaxToolCallsPerRound())
                .maxAiRetries(entity.getMaxAiRetries())
                .idleTimeoutMs(entity.getIdleTimeoutMs())
                .maxTokenBudget(entity.getMaxTokenBudget())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private AgentRunEntity toEntity(AgentRunPO po) {
        return AgentRunEntity.builder()
                .runId(po.getRunId())
                .sessionId(po.getSessionId())
                .userId(po.getUserId())
                .agentId(po.getAgentId())
                .status(po.getStatus() == null ? null : AgentRunStatus.valueOf(po.getStatus()))
                .stopReason(po.getStopReason())
                .round(po.getRound())
                .totalToolCalls(po.getTotalToolCalls())
                .totalTokens(po.getTotalTokens())
                .startedAt(po.getStartedAt())
                .finishedAt(po.getFinishedAt())
                .cancelled(po.getCancelled())
                .cancelReason(po.getCancelReason())
                .contextCompressed(po.getContextCompressed())
                .maxRounds(po.getMaxRounds())
                .maxToolCallsPerRound(po.getMaxToolCallsPerRound())
                .maxAiRetries(po.getMaxAiRetries())
                .idleTimeoutMs(po.getIdleTimeoutMs())
                .maxTokenBudget(po.getMaxTokenBudget())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }
}
