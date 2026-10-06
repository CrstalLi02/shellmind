package com.shellmind.domain.agent.service.chat;

import com.shellmind.domain.agent.adapter.port.AgentRuntime;
import com.shellmind.domain.agent.adapter.repository.IAgentRunRepository;
import com.shellmind.domain.agent.model.entity.AgentRunEntity;
import com.shellmind.domain.agent.model.entity.ChatCommandEntity;
import com.shellmind.domain.agent.model.valobj.AiAgentConfigTableVO;
import com.shellmind.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import com.shellmind.domain.agent.model.valobj.run.AgentRunStatus;
import com.shellmind.domain.agent.model.valobj.runtime.AgentRunRequest;
import com.shellmind.domain.agent.model.valobj.runtime.AgentRuntimeEvent;
import com.shellmind.domain.agent.model.valobj.runtime.InputPart;
import com.shellmind.domain.agent.service.IChatService;
import com.shellmind.domain.agent.service.run.AgentRunRegistry;
import com.shellmind.domain.conversation.adapter.repository.IChatHistoryRepository;
import com.shellmind.domain.conversation.model.entity.ChatSessionEntity;
import com.shellmind.domain.shared.model.RunContext;
import com.shellmind.types.enums.ResponseCode;
import com.shellmind.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Basic chat service: agent list, session creation, non-streaming chat (does not go through the ReAct engine).
 */
@Slf4j
@Service
public class ChatService implements IChatService {

    @Resource
    private AgentRuntime agentRuntime;

    @Resource
    private AgentRunRegistry runRegistry;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    @Resource
    private IAgentRunRepository agentRunRepository;

    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();
        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    agentList.add(vo.getAgent());
                }
            }
        }
        return agentList;
    }

    @Override
    public String createSession(String agentId, String userId) {
        requireAgent(agentId);
        String sessionId = agentRuntime.createSession(agentId, userId);

        // Persist session metadata to the database
        try {
            chatHistoryRepository.saveSession(ChatSessionEntity.builder()
                    .id(sessionId)
                    .agentId(agentId)
                    .userId(userId)
                    .title("New session")
                    .messageCount(0)
                    .build());
        } catch (Exception e) {
            log.error("Failed to save session metadata", e);
        }

        log.info("Created new session - agentId:{}, userId:{}, sessionId:{}", agentId, userId, sessionId);
        return sessionId;
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {
        requireAgent(agentId);
        String sessionId = createSession(agentId, userId);
        return handleMessage(agentId, userId, sessionId, message);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
        requireAgent(agentId);

        // /chat sync path does not go through the ReAct engine (no AgentLoopExecutor); persist an agent_run here manually
        long startedAt = System.currentTimeMillis();
        String runId = UUID.randomUUID().toString();
        List<String> outputs = new ArrayList<>();
        int totalToolCalls = 0;

        runRegistry.register(new RunContext(sessionId, userId, agentId, runId, false, null, null, false, null), null);
        try {
            Iterator<AgentRuntimeEvent> events = agentRuntime.run(
                    AgentRunRequest.text(agentId, userId, sessionId, message, false));
            while (events.hasNext()) {
                AgentRuntimeEvent event = events.next();
                outputs.add(event.displayText());
                totalToolCalls += event.functionCallCount();
            }
        } catch (RuntimeException e) {
            saveRunRecord(runId, sessionId, userId, agentId, AgentRunStatus.FAILED, "error", startedAt, totalToolCalls);
            throw e;
        } finally {
            runRegistry.unregister(sessionId);
        }
        saveRunRecord(runId, sessionId, userId, agentId, AgentRunStatus.COMPLETED, "completed", startedAt, totalToolCalls);
        return outputs;
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        requireAgent(chatCommandEntity.getAgentId());

        List<InputPart> parts = new ArrayList<>();
        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(InputPart.text(text.getMessage()));
            }
        }
        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(InputPart.uri(file.getFileUri(), file.getMimeType()));
            }
        }
        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (null != inlineDatas) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(InputPart.inline(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        Iterator<AgentRuntimeEvent> events = agentRuntime.run(new AgentRunRequest(
                chatCommandEntity.getAgentId(), chatCommandEntity.getUserId(),
                chatCommandEntity.getSessionId(), parts, false));
        List<String> outputs = new ArrayList<>();
        events.forEachRemaining(event -> outputs.add(event.displayText()));
        return outputs;
    }

    private void requireAgent(String agentId) {
        if (!agentRuntime.isRegistered(agentId)) {
            throw new AppException(ResponseCode.E0001.getCode());
        }
    }

    private void saveRunRecord(String runId, String sessionId, String userId, String agentId,
                               AgentRunStatus status, String stopReason, long startedAt, int totalToolCalls) {
        try {
            Date now = new Date();
            agentRunRepository.save(AgentRunEntity.builder()
                    .runId(runId)
                    .sessionId(sessionId)
                    .userId(userId)
                    .agentId(agentId)
                    .status(status)
                    .stopReason(stopReason)
                    .round(0)
                    .totalToolCalls(totalToolCalls)
                    .totalTokens(0L)
                    .startedAt(new Date(startedAt))
                    .finishedAt(now)
                    .cancelled(false)
                    .contextCompressed(false)
                    .createdAt(new Date(startedAt))
                    .updatedAt(now)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to save /chat run record runId={}", runId, e);
        }
    }
}
