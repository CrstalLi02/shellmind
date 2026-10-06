package com.shellmind.domain.conversation.adapter.repository;

import com.shellmind.domain.conversation.model.entity.ChatMessageEntity;
import com.shellmind.domain.conversation.model.entity.ChatSessionEntity;
import com.shellmind.domain.conversation.model.valobj.MilestoneVO;

import java.util.List;

/**
 * Session persistence repository.
 */
public interface IChatHistoryRepository {

    /**
     * Get session metadata.
     */
    ChatSessionEntity getSession(String sessionId);

    /**
     * Save session metadata.
     */
    void saveSession(ChatSessionEntity session);

    /**
     * Save a chat message.
     */
    void saveMessage(ChatMessageEntity message);

    /**
     * Get the most recent messages.
     */
    List<ChatMessageEntity> getRecentMessages(String sessionId, int limit);

    /**
     * Get messages within the given token budget.
     */
    List<ChatMessageEntity> getMessagesWithBudget(String sessionId, int tokenBudget);

    /**
     * Save a milestone event.
     */
    void saveMilestone(String sessionId, MilestoneVO milestoneVO);

    /**
     * Get the most recent milestones.
     */
    List<MilestoneVO> getRecentMilestones(String sessionId, int limit);
}
