package com.shellmind.infrastructure.agent.armory.node;

import com.shellmind.infrastructure.agent.model.ArmoryCommandEntity;
import com.shellmind.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shellmind.infrastructure.agent.model.AiAgentRegisterVO;
import com.shellmind.infrastructure.agent.armory.AbstractArmorySupport;
import com.shellmind.infrastructure.agent.armory.factory.DefaultArmoryFactory;
import com.shellmind.infrastructure.agent.armory.mcp.client.TooMcpCreateService;
import com.shellmind.infrastructure.agent.armory.mcp.client.factory.DefaultMcpClientFactory;
import com.shellmind.infrastructure.agent.armory.skills.ToolSkillsCreateService;
import com.shellmind.types.design.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ChatModelNode extends AbstractArmorySupport {

    @Resource
    private AgentNode agentNode;

    @Resource
    private DefaultMcpClientFactory defaultMcpClientFactory;

    @Resource
    private ToolSkillsCreateService toolSkillsCreateService;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent assembly - ChatModelNode");

        // Load context objects
        OpenAIClient openAiClient = dynamicContext.getOpenAiClient();
        OpenAIClientAsync openAiClientAsync = dynamicContext.getOpenAiClientAsync();

        // Load config objects
        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.ChatModel chatModelConfig = aiAgentConfigTableVO.getModule().getChatModel();
        List<AiAgentConfigTableVO.Module.ChatModel.ToolMcp> toolMcpList = chatModelConfig.getToolMcpList();
        List<AiAgentConfigTableVO.Module.ChatModel.ToolSkills> toolSkillsList = chatModelConfig.getToolSkillsList();

        // Build MCP services (factory)
        List<ToolCallback> toolCallbackList = new ArrayList<>();

        if (null != toolMcpList && !toolMcpList.isEmpty()) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolMcp toolMcp : toolMcpList) {
                TooMcpCreateService tooMcpCreateService = defaultMcpClientFactory.getTooMcpCreateService(toolMcp);
                ToolCallback[] toolCallbacks = tooMcpCreateService.buildToolCallback(toolMcp);
                toolCallbackList.addAll(List.of(toolCallbacks));
            }
        }

        // Build skills services
        if (null != toolSkillsList && !toolSkillsList.isEmpty()) {
            for (AiAgentConfigTableVO.Module.ChatModel.ToolSkills toolSkills : toolSkillsList) {
                ToolCallback[] toolCallbacks = toolSkillsCreateService.buildToolCallback(toolSkills);
                toolCallbackList.addAll(List.of(toolCallbacks));
            }
        }

        // Store the tool list in DynamicContext for AgentNode
        dynamicContext.setToolCallbacks(toolCallbackList);
        log.info("Loaded {} tools", toolCallbackList.size());

        // Read retry config (for logging and later extension)
        AiAgentConfigTableVO.Module.ReactBudget reactBudget = aiAgentConfigTableVO.getModule().getRunner() != null
                ? aiAgentConfigTableVO.getModule().getRunner().getReactBudget() : null;
        int maxAiRetries = 3;
        if (reactBudget != null && reactBudget.getMaxAiRetries() != null) {
            maxAiRetries = Math.max(1, reactBudget.getMaxAiRetries());
        }
        log.info("ChatModel retry config: maxAiRetries={} (retries are performed by AiCallNode)", maxAiRetries);

        // Build the chat model (no tools here; AgentNode passes them directly)
        // Timeouts are already set in AiApiNode via RestClient.Builder
        // Retries are controlled by the for-loop in AiCallNode
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(chatModelConfig.getModel())
                .parallelToolCalls(false)
                // Enable streaming usage: OpenAI requires stream_options.include_usage=true;
                // the last chunk then includes full usage (including prompt_tokens_details.cached_tokens).
                .streamUsage(true);

        // Reasoning effort (honored by reasoning models; ignored otherwise)
        String reasoningEffort = chatModelConfig.getReasoningEffort();
        if (StringUtils.isNotBlank(reasoningEffort)) {
            optionsBuilder.reasoningEffort(reasoningEffort);
        }

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClientAsync)
                .options(optionsBuilder.build())
                .build();

        dynamicContext.setChatModel(chatModel);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return agentNode;
    }

}
