package com.shellmind.infrastructure.agent.armory;

import com.shellmind.domain.agent.model.valobj.AiAgentConfigTableVO;

import java.util.List;

/**
 * Assembly interface.
 *
 * @author xiaofuge bugstack.cn
 * 2025/12/17 08:13
 */
public interface IArmoryService {

    void acceptArmoryAgents(List<AiAgentConfigTableVO> tables) throws Exception;

}
