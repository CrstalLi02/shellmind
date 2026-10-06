package com.shellmind.domain.agent.adapter.repository;

import com.shellmind.domain.agent.model.entity.AgentRunEntity;

import java.util.List;

public interface IAgentRunRepository {

    void save(AgentRunEntity entity);

    AgentRunEntity findById(String runId);

    List<AgentRunEntity> findBySessionId(String sessionId, int limit);
}
