package com.shellmind.infrastructure.agent.armory.mcp.client;

import com.shellmind.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.springframework.ai.tool.ToolCallback;

/**
 * MCP tool builder service.
 *
 * @author xiaofuge bugstack.cn
 * 2026/1/2 09:31
 */
public interface TooMcpCreateService {

    ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) throws Exception;

}
