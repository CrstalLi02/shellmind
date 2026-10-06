package com.shellmind.test.api.model;

import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;

/**
 * LangChain4j
 * <p>
 * Docs: <a href="https://docs.langchain4j.info/">langchain4j</a>
 * Examples: <a href="https://github.com/langchain4j/langchain4j-examples">langchain4j-examples</a>
 *
 * @author xiaofuge bugstack.cn
 * 2025/12/14 09:20
 */
@Slf4j
public class LangChain4jApiTest {

    public static void main(String[] args) {
        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://apis.itedus.cn/v1")
                .apiKey(System.getenv("SHELLMIND_AI_API_KEY"))
                .modelName("gpt-4o")
                .build();

        String chat = model.chat("hi there!");
        log.info("Test result:{}", chat);
    }

}
