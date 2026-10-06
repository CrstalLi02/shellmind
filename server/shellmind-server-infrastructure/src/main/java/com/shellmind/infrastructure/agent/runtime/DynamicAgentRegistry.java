package com.shellmind.infrastructure.agent.runtime;

import com.shellmind.domain.llm.service.ModelConfigException;
import com.shellmind.domain.llm.adapter.repository.IModelConfigRepository;
import com.shellmind.domain.llm.model.entity.ModelConfigEntity;
import com.shellmind.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shellmind.infrastructure.agent.model.AiAgentRegisterVO;
import com.shellmind.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.shellmind.infrastructure.agent.armory.IArmoryService;
import com.shellmind.infrastructure.agent.armory.factory.DefaultArmoryFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DynamicAgentRegistry implements IDynamicAgentRegistry {

    private static final String RUNTIME_AGENT_ID_FORMAT = "%s::model::%d";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<Long, String> runtimeAgentIds = new ConcurrentHashMap<>();

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;
    @Resource
    private IArmoryService armoryService;
    @Resource
    private IModelConfigRepository modelConfigRepository;
    @Resource
    private ApplicationContext applicationContext;

    @Override
    public String registerAgentForModel(String sourceAgentId, Long modelId) {
        if (modelId == null) {
            return sourceAgentId;
        }

        return runtimeAgentIds.computeIfAbsent(modelId, id -> buildRuntimeAgent(sourceAgentId, id));
    }

    @Override
    public AiAgentRegisterVO getRuntimeAgent(String runtimeAgentId) {
        return applicationContext.getBean(runtimeAgentId, AiAgentRegisterVO.class);
    }

    @Override
    public synchronized void evict(Long modelId) {
        String runtimeAgentId = runtimeAgentIds.remove(modelId);
        if (runtimeAgentId == null || !applicationContext.containsBean(runtimeAgentId)) {
            return;
        }

        DefaultListableBeanFactory beanFactory =
                (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
        if (beanFactory.containsBeanDefinition(runtimeAgentId)) {
            beanFactory.removeBeanDefinition(runtimeAgentId);
        }
    }

    private String buildRuntimeAgent(String sourceAgentId, Long modelId) {
        ModelConfigEntity modelConfig = modelConfigRepository.queryById(modelId);
        if (modelConfig == null) {
            throw new ModelConfigException("Model config does not exist: " + modelId);
        }

        AiAgentConfigTableVO template = findSourceConfig(sourceAgentId);
        AiAgentConfigTableVO runtimeConfig = deepCopy(template);
        String runtimeAgentId = String.format(RUNTIME_AGENT_ID_FORMAT, sourceAgentId, modelId);

        Objects.requireNonNull(runtimeConfig.getAgent(), "Agent config is missing the agent node");
        Objects.requireNonNull(runtimeConfig.getModule(), "Agent config is missing the module node");
        Objects.requireNonNull(runtimeConfig.getModule().getAiApi(), "Agent config is missing the ai-api node");
        Objects.requireNonNull(runtimeConfig.getModule().getChatModel(), "Agent config is missing the chat-model node");

        runtimeConfig.getAgent().setAgentId(runtimeAgentId);
        runtimeConfig.getModule().getAiApi().setBaseUrl(modelConfig.getBaseUrl());
        runtimeConfig.getModule().getAiApi().setApiKey(modelConfig.getApiKey());
        runtimeConfig.getModule().getAiApi().setCompletionsPath(StringUtils.defaultIfBlank(
                modelConfig.getCompletionsPath(), "v1/chat/completions"));
        runtimeConfig.getModule().getChatModel().setModel(modelConfig.getModelName());

        try {
            armoryService.acceptArmoryAgents(java.util.List.of(runtimeConfig));
        } catch (Exception e) {
            throw new ModelConfigException("Model assembly failed: " + modelConfig.getName(), e);
        }

        if (!applicationContext.containsBean(runtimeAgentId)) {
            throw new ModelConfigException("Runtime agent was not registered after model assembly: " + runtimeAgentId);
        }

        log.info("Model runtime assembled: sourceAgentId={}, modelId={}, runtimeAgentId={}, model={}",
                sourceAgentId, modelId, runtimeAgentId, modelConfig.getModelName());
        return runtimeAgentId;
    }

    private AiAgentConfigTableVO findSourceConfig(String sourceAgentId) {
        return aiAgentAutoConfigProperties.getTables().values().stream()
                .filter(config -> config.getAgent() != null && sourceAgentId.equals(config.getAgent().getAgentId()))
                .findFirst()
                .orElseThrow(() -> new ModelConfigException("Agent config does not exist: " + sourceAgentId));
    }

    private AiAgentConfigTableVO deepCopy(AiAgentConfigTableVO config) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(config), AiAgentConfigTableVO.class);
        } catch (Exception e) {
            throw new ModelConfigException("Failed to clone agent model config", e);
        }
    }
}
