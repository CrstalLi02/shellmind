package com.shellmind.infrastructure.agent.runtime;

import com.shellmind.infrastructure.agent.model.AiAgentRegisterVO;

public interface IDynamicAgentRegistry {

    String registerAgentForModel(String sourceAgentId, Long modelId);

    AiAgentRegisterVO getRuntimeAgent(String runtimeAgentId);

    void evict(Long modelId);
}
