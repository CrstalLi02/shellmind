package com.shellmind.infrastructure.llm;

import com.shellmind.domain.llm.adapter.repository.IModelConfigRepository;
import com.shellmind.domain.llm.model.entity.ModelConfigEntity;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for user-configured ChatModel instances.
 *
 * <p>Reused for lighter-weight calls such as intent classification and task decomposition:
 * <b>prefer the model the user configured in settings</b> ({@code ModelConfigEntity}); only when
 * the user has no model configured should the caller fall back to the default intent-ai-api.
 *
 * <p>Built ChatModels have connect/read timeouts and internal retries disabled, so an unreachable
 * LLM gateway does not trigger a long RetryTemplate loop that looks like "no response" on the client.
 *
 * @author shellmind dev
 * 2026/9/28
 */
@Slf4j
@Service
public class UserModelChatModelFactory {

    private static final String DEFAULT_COMPLETIONS_PATH = "v1/chat/completions";

    /** Intent classification / task decomposition are auxiliary; keep timeouts tight: connect 5s, read 60s */
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    @Resource
    private IModelConfigRepository modelConfigRepository;

    /** modelId -> ChatModel cache; ModelConfigController evicts on save/delete */
    private final Map<Long, ChatModel> chatModelCache = new ConcurrentHashMap<>();

    /**
     * Resolve the user-configured ChatModel for the request's modelId.
     *
     * @param modelId model ID from the frontend request; null picks the first user model config
     * @return ChatModel for the user's config, or null when the user has none (caller falls back)
     */
    public ChatModel getChatModel(Long modelId) {
        ModelConfigEntity config = resolveConfig(modelId);
        if (config == null) {
            return null;
        }
        return chatModelCache.computeIfAbsent(config.getId(), id -> buildChatModel(config));
    }

    /**
     * Whether the user has configured any model (used by callers to decide on the default gateway fallback).
     */
    public boolean hasUserModel() {
        return !modelConfigRepository.queryAll().isEmpty();
    }

    /**
     * Clear the cache after a model config change (called on save/delete).
     */
    public void evict(Long modelConfigId) {
        if (modelConfigId != null) {
            chatModelCache.remove(modelConfigId);
        } else {
            chatModelCache.clear();
        }
    }

    private ModelConfigEntity resolveConfig(Long modelId) {
        if (modelId != null) {
            ModelConfigEntity config = modelConfigRepository.queryById(modelId);
            if (config != null) {
                return config;
            }
            log.warn("Specified model config does not exist: modelId={}, falling back to the first user model", modelId);
        }
        return modelConfigRepository.queryAll().stream().findFirst().orElse(null);
    }

    private ChatModel buildChatModel(ModelConfigEntity config) {
        String completionsPath = StringUtils.defaultIfBlank(config.getCompletionsPath(), DEFAULT_COMPLETIONS_PATH);

        OpenAIClient openAiClient = OpenAIOkHttpClient.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .timeout(java.time.Duration.ofMillis(READ_TIMEOUT_MS))
                .build();
        OpenAIClientAsync openAiClientAsync = OpenAIOkHttpClientAsync.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .timeout(java.time.Duration.ofMillis(READ_TIMEOUT_MS))
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClientAsync)
                .options(OpenAiChatOptions.builder()
                        .model(config.getModelName())
                        .build())
                .build();

        log.info("User-model ChatModel built: modelConfigId={}, name={}, model={}, baseUrl={}",
                config.getId(), config.getName(), config.getModelName(), config.getBaseUrl());
        return chatModel;
    }
}
