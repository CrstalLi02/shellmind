package com.shellmind.infrastructure.agent.armory.mcp.client.impl;

import com.shellmind.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shellmind.infrastructure.agent.armory.mcp.client.TooMcpCreateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

/**
 * Local Spring AI ToolCallbackProvider builder.
 *
 * Handles ToolCallbackProvider beans registered in Spring (e.g. sshToolCallbackProvider),
 * as opposed to SSE/Stdio MCP services.
 */
@Slf4j
@Service
public class LocalToolMcpCreateService implements TooMcpCreateService {

    @Autowired
    private ApplicationContext applicationContext;

    @Override
    public ToolCallback[] buildToolCallback(AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp) throws Exception {
        AiAgentConfigTableVO.Module.ChatModel.ToolMcp.LocalParameters local = toolMcp.getLocal();
        if (local == null) {
            log.warn("LocalParameters is null");
            return new ToolCallback[0];
        }

        String name = local.getName();
        log.info("Loading ToolCallbackProvider from Spring context: {}", name);

        // Bean name from config
        String providerBeanName = name;

        // Try to load the ToolCallbackProvider bean from Spring
        ToolCallbackProvider provider = null;
        try {
            provider = applicationContext.getBean(providerBeanName, ToolCallbackProvider.class);
        } catch (Exception e) {
            log.warn("ToolCallbackProvider bean not found: {}", providerBeanName);
        }

        if (provider == null) {
            // Retry without a type filter
            try {
                Object bean = applicationContext.getBean(providerBeanName);
                if (bean instanceof ToolCallbackProvider) {
                    provider = (ToolCallbackProvider) bean;
                }
            } catch (Exception e) {
                log.warn("ToolCallbackProvider bean type mismatch: {}", providerBeanName);
            }
        }

        if (provider == null) {
            log.error("ToolCallbackProvider not found: {}", providerBeanName);
            return new ToolCallback[0];
        }

        ToolCallback[] callbacks = provider.getToolCallbacks();
        log.info("ToolCallbackProvider '{}' returned {} tools", providerBeanName, callbacks.length);

        return callbacks;
    }
}
