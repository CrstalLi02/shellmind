package com.shellmind.cases.llm;

import com.shellmind.api.dto.ModelConfigRequestDTO;
import com.shellmind.api.dto.ModelConfigResponseDTO;
import com.shellmind.domain.agent.adapter.port.AgentRuntime;
import com.shellmind.domain.llm.adapter.port.LlmClient;
import com.shellmind.domain.llm.model.entity.ModelConfigEntity;
import com.shellmind.domain.llm.service.ModelConfigException;
import com.shellmind.domain.llm.service.ModelConfigService;
import com.shellmind.types.enums.ResponseCode;
import com.shellmind.types.exception.AppException;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Supplier;

/**
 * User model-config use case. After a change, clear that model's agent and client caches.
 * <p>
 * Validation failures are thrown as {@link AppException} ({@link ResponseCode#ILLEGAL_PARAMETER}).
 */
@Service
public class ModelConfigCase {

    @Resource
    private ModelConfigService modelConfigService;
    @Resource
    private AgentRuntime agentRuntime;
    @Resource
    private LlmClient llmClient;

    public List<ModelConfigResponseDTO> list() {
        return modelConfigService.list().stream().map(this::toResponse).toList();
    }

    public ModelConfigResponseDTO save(ModelConfigRequestDTO requestDTO) {
        return translate(() -> {
            ModelConfigEntity saved = modelConfigService.save(toEntity(requestDTO));
            evictCaches(saved.getId());
            return toResponse(saved);
        });
    }

    public void delete(Long id) {
        translate(() -> {
            modelConfigService.delete(id);
            evictCaches(id);
            return null;
        });
    }

    public String revealApiKey(Long id) {
        return translate(() -> modelConfigService.revealApiKey(id));
    }

    public String test(ModelConfigRequestDTO requestDTO) {
        return translate(() -> modelConfigService.test(toEntity(requestDTO)));
    }

    private void evictCaches(Long modelId) {
        agentRuntime.evictModelAgent(modelId);
        llmClient.invalidateUserModel(modelId);
    }

    private <T> T translate(Supplier<T> action) {
        try {
            return action.get();
        } catch (ModelConfigException e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), e.getMessage(), e);
        }
    }

    private ModelConfigResponseDTO toResponse(ModelConfigEntity entity) {
        String apiKey = StringUtils.defaultString(entity.getApiKey());
        String masked = apiKey.length() <= 8 ? "****" : apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
        return ModelConfigResponseDTO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .baseUrl(entity.getBaseUrl())
                .modelName(entity.getModelName())
                .completionsPath(entity.getCompletionsPath())
                .hasApiKey(StringUtils.isNotBlank(apiKey))
                .apiKeyMasked(masked)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private ModelConfigEntity toEntity(ModelConfigRequestDTO requestDTO) {
        if (requestDTO == null) {
            throw new ModelConfigException("Request parameters cannot be empty");
        }
        return ModelConfigEntity.builder()
                .id(requestDTO.getId())
                .name(StringUtils.trimToNull(requestDTO.getName()))
                .baseUrl(StringUtils.trimToNull(requestDTO.getBaseUrl()))
                .apiKey(StringUtils.trimToNull(requestDTO.getApiKey()))
                .modelName(StringUtils.trimToNull(requestDTO.getModelName()))
                .completionsPath(StringUtils.trimToNull(requestDTO.getCompletionsPath()))
                .build();
    }
}
