package com.shellmind.infrastructure.adapter.repository;

import com.shellmind.domain.conversation.adapter.repository.IChatHistoryRepository;
import com.shellmind.domain.conversation.model.entity.ChatMessageEntity;
import com.shellmind.domain.conversation.model.entity.ChatSessionEntity;
import com.shellmind.domain.conversation.model.valobj.MilestoneVO;
import com.shellmind.infrastructure.dao.IChatMessageDao;
import com.shellmind.infrastructure.dao.IChatMilestoneDao;
import com.shellmind.infrastructure.dao.IChatSessionDao;
import com.shellmind.infrastructure.dao.po.ChatMessagePO;
import com.shellmind.infrastructure.dao.po.ChatMilestonePO;
import com.shellmind.infrastructure.dao.po.ChatSessionPO;
import org.springframework.stereotype.Repository;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class ChatHistoryRepository implements IChatHistoryRepository {

    @Resource
    private IChatSessionDao chatSessionDao;

    @Resource
    private IChatMessageDao chatMessageDao;

    @Resource
    private IChatMilestoneDao chatMilestoneDao;

    @Override
    public ChatSessionEntity getSession(String sessionId) {
        ChatSessionPO po = chatSessionDao.queryById(sessionId);
        if (po == null) return null;
        return ChatSessionEntity.builder()
                .id(po.getId())
                .agentId(po.getAgentId())
                .userId(po.getUserId())
                .title(po.getTitle())
                .messageCount(po.getMessageCount())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    @Override
    public void saveSession(ChatSessionEntity session) {
        ChatSessionPO po = ChatSessionPO.builder()
                .id(session.getId())
                .agentId(session.getAgentId())
                .userId(session.getUserId())
                .title(session.getTitle())
                .messageCount(session.getMessageCount())
                .build();
        chatSessionDao.insert(po);
    }

    @Override
    public void saveMessage(ChatMessageEntity message) {
        ChatMessagePO po = ChatMessagePO.builder()
                .sessionId(message.getSessionId())
                .role(message.getRole())
                .content(message.getContent())
                .toolName(message.getToolName())
                .toolCallId(message.getToolCallId())
                .priority(message.getPriority())
                .tokenCount(message.getTokenCount())
                .build();
        chatMessageDao.insert(po);
        chatSessionDao.updateMessageCount(message.getSessionId());
    }

    @Override
    public List<ChatMessageEntity> getRecentMessages(String sessionId, int limit) {
        List<ChatMessagePO> pos = chatMessageDao.queryRecentBySessionId(sessionId, limit);
        if (pos == null || pos.isEmpty()) {
            return Collections.emptyList();
        }
        
        // The DB uses ORDER BY id DESC, so rows arrive as [newest, older, oldest].
        // Reverse to [oldest, older, newest] for a natural LLM conversation order.
        List<ChatMessagePO> reversedPos = new java.util.ArrayList<>(pos);
        Collections.reverse(reversedPos);
        
        return reversedPos.stream().map(po -> ChatMessageEntity.builder()
                .id(po.getId())
                .sessionId(po.getSessionId())
                .role(po.getRole())
                .content(po.getContent())
                .toolName(po.getToolName())
                .toolCallId(po.getToolCallId())
                .priority(po.getPriority())
                .tokenCount(po.getTokenCount())
                .createdAt(po.getCreatedAt())
                .build()).collect(Collectors.toList());
    }

    @Override
    public List<ChatMessageEntity> getMessagesWithBudget(String sessionId, int tokenBudget) {
        // Load a bounded window of recent messages
        List<ChatMessageEntity> recent = getRecentMessages(sessionId, 100);
        if (recent.isEmpty() || tokenBudget <= 0) {
            return recent;
        }

        List<ChatMessageEntity> result = new java.util.ArrayList<>();
        int currentTokens = 0;
        
        // Walk from newest to oldest (recent is chronological, so iterate backwards)
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatMessageEntity msg = recent.get(i);
            int tokens = msg.getTokenCount() != null ? msg.getTokenCount() : 0;
            
            // Stop once adding this message would exceed the budget (keep at least one)
            if (currentTokens + tokens > tokenBudget && !result.isEmpty()) {
                break;
            }
            
            result.add(0, msg); // Insert at the front to keep chronological order
            currentTokens += tokens;
        }
        
        return result;
    }

    @Override
    public void saveMilestone(String sessionId, MilestoneVO milestoneVO) {
        ChatMilestonePO po = ChatMilestonePO.builder()
                .sessionId(sessionId)
                .type(milestoneVO.getType().name())
                .content(milestoneVO.getContent())
                .build();
        chatMilestoneDao.insert(po);
    }

    @Override
    public List<MilestoneVO> getRecentMilestones(String sessionId, int limit) {
        List<ChatMilestonePO> pos = chatMilestoneDao.queryRecentBySessionId(sessionId, limit);
        if (pos == null || pos.isEmpty()) {
            return Collections.emptyList();
        }
        return pos.stream().map(po -> MilestoneVO.builder()
                .type(MilestoneVO.Type.valueOf(po.getType()))
                .content(po.getContent())
                .timestamp(po.getCreatedAt() != null ? po.getCreatedAt().getTime() : System.currentTimeMillis())
                .build()).collect(Collectors.toList());
    }
}
