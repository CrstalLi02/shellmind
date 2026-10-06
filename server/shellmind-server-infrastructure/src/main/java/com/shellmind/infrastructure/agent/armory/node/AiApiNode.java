package com.shellmind.infrastructure.agent.armory.node;

import com.shellmind.infrastructure.agent.model.ArmoryCommandEntity;
import com.shellmind.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shellmind.infrastructure.agent.model.AiAgentRegisterVO;
import com.shellmind.infrastructure.agent.armory.AbstractArmorySupport;
import com.shellmind.infrastructure.agent.armory.factory.DefaultArmoryFactory;
import com.shellmind.types.design.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.Duration;

@Slf4j
@Service
public class AiApiNode extends AbstractArmorySupport {

    @Resource
    private ChatModelNode chatModelNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent assembly - AiApiNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.AiApi aiApiConfig = aiAgentConfigTableVO.getModule().getAiApi();

        int connectTimeoutMs = aiApiConfig.getConnectTimeoutMs() != null ? aiApiConfig.getConnectTimeoutMs() : 10_000;
        int readTimeoutMs = aiApiConfig.getReadTimeoutMs() != null ? aiApiConfig.getReadTimeoutMs() : 120_000;

        log.info("Ai API timeout config: connect={}ms, read={}ms", connectTimeoutMs, readTimeoutMs);

        OpenAIClient openAiClient = OpenAIOkHttpClient.builder()
                .baseUrl(aiApiConfig.getBaseUrl())
                .apiKey(aiApiConfig.getApiKey())
                .timeout(Duration.ofMillis(readTimeoutMs))
                .build();
        OpenAIClientAsync openAiClientAsync = OpenAIOkHttpClientAsync.builder()
                .baseUrl(aiApiConfig.getBaseUrl())
                .apiKey(aiApiConfig.getApiKey())
                .timeout(Duration.ofMillis(readTimeoutMs))
                .build();

        dynamicContext.setOpenAiClient(openAiClient);
        dynamicContext.setOpenAiClientAsync(openAiClientAsync);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return chatModelNode;
    }

}
