package com.shellmind.domain.agent.service.run;

import com.shellmind.domain.agent.adapter.repository.IAgentRunRepository;
import com.shellmind.domain.agent.model.entity.AgentRunEntity;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class AgentRunQueryService {

    @Resource
    private IAgentRunRepository agentRunRepository;

    public AgentRunEntity queryById(String runId) {
        return agentRunRepository.findById(runId);
    }

    public List<AgentRunEntity> queryBySessionId(String sessionId, int limit) {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, 100);
        return agentRunRepository.findBySessionId(sessionId, safeLimit);
    }

    /** Latest resumable run among the session's last 10 runs (failed or cancelled) */
    public Optional<AgentRunEntity> findLatestResumable(String sessionId) {
        return queryBySessionId(sessionId, 10).stream()
                .filter(AgentRunEntity::isResumable)
                .findFirst();
    }
}
