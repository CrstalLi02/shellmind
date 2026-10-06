package com.shellmind.infrastructure.agent.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Local MCP tool provider (referenced from agent YAML via tool-mcp-list.local.name).
 */
@Configuration
public class LocalMcpToolConfig {

    /** Test tool: case conversion (referenced by docs/examples/agent/demo.yml) */
    @Bean("myToolCallbackProvider")
    public ToolCallbackProvider testTools(MyTestMcpService toolService) {
        return MethodToolCallbackProvider.builder().toolObjects(toolService).build();
    }
}
