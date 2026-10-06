package com.shellmind.infrastructure.agent.armory.skills;

import com.shellmind.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

/**
 * Tool skills builder service.
 *
 * @author xiaofuge bugstack.cn
 * 2026/2/6 08:03
 */
public interface ToolSkillsCreateService {

    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills) throws Exception;

}
