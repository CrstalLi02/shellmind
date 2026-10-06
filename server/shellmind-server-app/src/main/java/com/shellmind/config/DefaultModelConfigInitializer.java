package com.shellmind.config;

import com.shellmind.domain.llm.adapter.repository.IModelConfigRepository;
import com.shellmind.domain.llm.model.entity.ModelConfigEntity;
import com.shellmind.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shellmind.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

@Slf4j
@Component
public class DefaultModelConfigInitializer implements ApplicationListener<ApplicationReadyEvent> {

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;
    @Resource
    private IModelConfigRepository modelConfigRepository;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            if (!modelConfigRepository.queryAll().isEmpty()) {
                return;
            }

            AiAgentConfigTableVO table = aiAgentAutoConfigProperties.getTables().values().stream()
                    .findFirst()
                    .orElse(null);
            if (table == null || table.getModule() == null
                    || table.getModule().getAiApi() == null || table.getModule().getChatModel() == null) {
                return;
            }

            modelConfigRepository.insert(ModelConfigEntity.builder()
                    .name("Default model")
                    .baseUrl(table.getModule().getAiApi().getBaseUrl())
                    .apiKey(table.getModule().getAiApi().getApiKey())
                    .modelName(table.getModule().getChatModel().getModel())
                    .completionsPath(StringUtils.defaultIfBlank(
                            table.getModule().getAiApi().getCompletionsPath(), "v1/chat/completions"))
                    .build());
            log.info("Initialized model config from the default agent config");
        } catch (Exception e) {
            log.error("Failed to initialize default model config", e);
        }
    }
}
