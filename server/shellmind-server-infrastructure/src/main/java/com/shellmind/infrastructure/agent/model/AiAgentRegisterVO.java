package com.shellmind.infrastructure.agent.model;

import com.google.adk.runner.InMemoryRunner;
import com.google.adk.runner.Runner;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * AI agent registration value object.
 * @author xiaofuge bugstack.cn
 * 2025/12/17 08:19
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AiAgentRegisterVO {

    /**
     * Agent application name
     */
    private String appName;

    /**
     * Agent ID
     */
    private String agentId;

    /**
     * Agent name
     */
    private String agentName;

    /**
     * Agent description
     */
    private String agentDesc;

    /**
     * Agent runner
     */
    private Runner runner;

    /**
     * LLM ChatModel (for calling the model directly, bypassing ADK tool auto-execution)
     */
    private org.springframework.ai.chat.model.ChatModel chatModel;

}
