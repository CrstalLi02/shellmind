package com.shellmind.test.api.model;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Spring AI Test
 * Docs: <a href="https://docs.spring.io/spring-ai/reference/1.0/api/advisors.html">spring ai</a>
 * @author xiaofuge bugstack.cn
 * 2025/12/14 09:15
 */
@Slf4j
public class SpringAiApiTest {

    public static void main(String[] args) {
        OpenAIClient openAiClient = OpenAIOkHttpClient.builder()
                .baseUrl("https://apis.itedus.cn/v1")
                .apiKey(System.getenv("SHELLMIND_AI_API_KEY"))
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .options(OpenAiChatOptions.builder()
                        .model("gpt-4.1")
                        .build())
                .build();

        String call = chatModel.call("hi there!");

        log.info("Test result:{}", call);
    }

}
