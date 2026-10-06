package com.shellmind.test.domain.agent;

import com.shellmind.domain.agent.model.entity.ChatCommandEntity;
import com.shellmind.domain.agent.service.IChatService;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.MimeTypeUtils;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.List;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class ChatServiceTest {

    @Resource
    private IChatService chatService;

    @Value("classpath:file/dog.png")
    private org.springframework.core.io.Resource imageResource;

    @Test
    public void test_handleMessage_01() {
        List<String> message = chatService.handleMessage("100003", "xiaofuge", "What skills do you have?");
        log.info("Test result:{}", JSON.toJSONString(message));
    }

    @Test
    public void test_handleMessage_04_withImage() throws IOException {
        String agentId = "100003";
        String userId = "xiaofuge";

        String sessionId = chatService.createSession(agentId, userId);

        ChatCommandEntity chatCommandEntity = ChatCommandEntity.builder()
                .agentId(agentId)
                .userId(userId)
                .sessionId(sessionId)
                .texts(List.of(new ChatCommandEntity.Content.Text("Identify this image. Tell me what animal it is, and describe it in one sentence.")))
                .files(List.of())
                .inlineDatas(List.of(new ChatCommandEntity.Content.InlineData(imageResource.getContentAsByteArray(), MimeTypeUtils.IMAGE_PNG_VALUE)))
                .build();

        List<String> message = chatService.handleMessage(chatCommandEntity);
        log.info("Test result:{}", JSON.toJSONString(message));
    }

}
