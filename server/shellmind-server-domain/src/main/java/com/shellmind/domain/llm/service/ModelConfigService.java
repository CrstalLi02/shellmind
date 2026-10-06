package com.shellmind.domain.llm.service;

import com.shellmind.domain.llm.adapter.port.ModelEndpointProbe;
import com.shellmind.domain.llm.adapter.repository.IModelConfigRepository;
import com.shellmind.domain.llm.model.entity.ModelConfigEntity;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ModelConfigService {

    private static final String DEFAULT_COMPLETIONS_PATH = "v1/chat/completions";

    @Resource
    private IModelConfigRepository modelConfigRepository;

    @Resource
    private ModelEndpointProbe modelEndpointProbe;

    public List<ModelConfigEntity> list() {
        return modelConfigRepository.queryAll();
    }

    public ModelConfigEntity save(ModelConfigEntity request) {
        validate(request);

        if (request.getId() == null) {
            request.setBaseUrl(normalizeBaseUrl(request.getBaseUrl()));
            request.setCompletionsPath(normalizeCompletionsPath(request.getCompletionsPath()));
            return modelConfigRepository.insert(request);
        }

        ModelConfigEntity existing = modelConfigRepository.queryById(request.getId());
        if (existing == null) {
            throw new ModelConfigException("Model config not found: " + request.getId());
        }

        if (StringUtils.isBlank(request.getApiKey())) {
            request.setApiKey(existing.getApiKey());
        }
        request.setBaseUrl(normalizeBaseUrl(request.getBaseUrl()));
        request.setCompletionsPath(normalizeCompletionsPath(request.getCompletionsPath()));
        modelConfigRepository.update(request);
        return modelConfigRepository.queryById(request.getId());
    }

    public void delete(Long id) {
        if (id == null || modelConfigRepository.queryById(id) == null) {
            throw new ModelConfigException("Model config not found: " + id);
        }
        modelConfigRepository.deleteById(id);
    }

    public String revealApiKey(Long id) {
        if (id == null) {
            throw new ModelConfigException("Model config ID must not be empty");
        }
        ModelConfigEntity config = modelConfigRepository.queryById(id);
        if (config == null) {
            throw new ModelConfigException("Model config not found: " + id);
        }
        return StringUtils.defaultString(config.getApiKey());
    }

    public String test(ModelConfigEntity request) {
        if (request.getId() != null && StringUtils.isBlank(request.getApiKey())) {
            ModelConfigEntity existing = modelConfigRepository.queryById(request.getId());
            if (existing == null) {
                throw new ModelConfigException("Model config not found: " + request.getId());
            }
            request.setApiKey(existing.getApiKey());
        }
        validate(request);

        String endpoint = completionEndpoint(request.getBaseUrl(), request.getCompletionsPath());
        try {
            String response = modelEndpointProbe.probe(endpoint, request.getApiKey(), request.getModelName());
            return StringUtils.defaultIfBlank(response, "Connection succeeded");
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw new ModelConfigException("Model connection failed: " + message, e);
        }
    }

    private void validate(ModelConfigEntity request) {
        if (request == null || StringUtils.isBlank(request.getName())) {
            throw new ModelConfigException("Model name must not be empty");
        }
        if (StringUtils.isBlank(request.getBaseUrl())) {
            throw new ModelConfigException("API Base URL must not be empty");
        }
        if (StringUtils.isBlank(request.getModelName())) {
            throw new ModelConfigException("Model ID must not be empty");
        }
        if (request.getId() == null && StringUtils.isBlank(request.getApiKey())) {
            throw new ModelConfigException("API Key must not be empty");
        }
    }

    private String normalizeBaseUrl(String value) {
        String normalized = value.trim();
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private String normalizeCompletionsPath(String value) {
        return StringUtils.defaultIfBlank(StringUtils.trimToNull(value), DEFAULT_COMPLETIONS_PATH);
    }

    private String completionEndpoint(String baseUrl, String completionsPath) {
        String normalizedBase = normalizeBaseUrl(baseUrl);
        String path = normalizeCompletionsPath(completionsPath);
        return path.startsWith("/") ? normalizedBase.substring(0, normalizedBase.length() - 1) + path : normalizedBase + path;
    }
}
