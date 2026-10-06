package com.shellmind.infrastructure.llm;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.shellmind.infrastructure.agent.model.AiAgentRegisterVO;
import com.shellmind.infrastructure.agent.armory.factory.DefaultArmoryFactory;
import com.shellmind.domain.llm.adapter.port.LlmClient;
import com.shellmind.domain.llm.model.valobj.LlmRequest;
import com.shellmind.domain.llm.model.valobj.LlmTarget;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link LlmClient} implementation based on Spring AI (OpenAI-compatible protocol).
 * <p>
 * Model resolution:
 * <ul>
 *   <li>AGENT: use the assembled agent's ChatModel</li>
 *   <li>AUXILIARY: prefer the model from user settings; if the user has none, fall back to
 *       intent-ai-api; treat intent-ai-api as unavailable when no API key is configured</li>
 * </ul>
 */
@Slf4j
@Component
public class SpringAiLlmClient implements LlmClient {

    /** Fallback models cached by sampling temperature; a null key means temperature is unspecified */
    private final Map<Optional<Double>, ChatModel> fallbackModels = new ConcurrentHashMap<>();

    @Resource
    private UserModelChatModelFactory userModelChatModelFactory;

    @Lazy
    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Value("${intent-ai-api.base-url:}")
    private String fallbackBaseUrl;

    @Value("${intent-ai-api.api-key:}")
    private String fallbackApiKey;

    @Value("${intent-ai-api.chat-model.model:}")
    private String fallbackModelName;

    @Override
    public Optional<String> complete(LlmRequest request) {
        ChatModel chatModel = resolve(request);
        if (chatModel == null) {
            return Optional.empty();
        }
        List<Message> messages = new ArrayList<>(2);
        if (request.systemPrompt() != null) {
            messages.add(new SystemMessage(request.systemPrompt()));
        }
        messages.add(new UserMessage(request.userPrompt()));
        String text = chatModel.call(new Prompt(messages)).getResult().getOutput().getText();
        return Optional.ofNullable(text);
    }

    @Override
    public void invalidateUserModel(Long modelConfigId) {
        userModelChatModelFactory.evict(modelConfigId);
    }

    private ChatModel resolve(LlmRequest request) {
        LlmTarget target = request.target();
        if (target.kind() == LlmTarget.Kind.AGENT) {
            try {
                AiAgentRegisterVO register = defaultArmoryFactory.getAiAgentRegisterVO(target.agentId());
                return register != null ? register.getChatModel() : null;
            } catch (Exception e) {
                log.warn("Agent is not registered or has no usable model: agentId={}, error={}", target.agentId(), e.getMessage());
                return null;
            }
        }

        ChatModel userModel = userModelChatModelFactory.getChatModel(target.userModelId());
        if (userModel != null) {
            return userModel;
        }
        if (target.userModelId() != null) {
            log.info("Auxiliary model falling back to intent-ai-api: modelId={} has no matching config", target.userModelId());
        }
        return fallbackModel(request.fallbackTemperature());
    }

    private ChatModel fallbackModel(Double temperature) {
        if (fallbackApiKey == null || fallbackApiKey.isBlank()) {
            return null;
        }
        return fallbackModels.computeIfAbsent(Optional.ofNullable(temperature), key -> {
            OpenAIClient client = OpenAIOkHttpClient.builder()
                    .baseUrl(fallbackBaseUrl)
                    .apiKey(fallbackApiKey)
                    .build();
            OpenAIClientAsync clientAsync = OpenAIOkHttpClientAsync.builder()
                    .baseUrl(fallbackBaseUrl)
                    .apiKey(fallbackApiKey)
                    .build();
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(fallbackModelName);
            key.ifPresent(options::temperature);
            log.info("intent-ai-api fallback model initialized: model={}, temperature={}", fallbackModelName, key.orElse(null));
            return OpenAiChatModel.builder()
                    .openAiClient(client)
                    .openAiClientAsync(clientAsync)
                    .options(options.build())
                    .build();
        });
    }
}
