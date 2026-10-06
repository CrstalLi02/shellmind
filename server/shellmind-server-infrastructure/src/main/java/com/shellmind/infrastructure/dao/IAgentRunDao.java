package com.shellmind.infrastructure.dao;

import com.shellmind.infrastructure.dao.po.AgentRunPO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface IAgentRunDao {
    void insert(AgentRunPO po);

    void update(AgentRunPO po);

    AgentRunPO queryById(String runId);

    List<AgentRunPO> queryBySessionId(String sessionId, int limit);
}
