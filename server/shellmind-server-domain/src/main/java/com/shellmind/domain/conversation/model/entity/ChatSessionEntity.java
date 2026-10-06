package com.shellmind.domain.conversation.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Session metadata entity
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatSessionEntity {
    private String id;
    private String agentId;
    private String userId;
    private String title;
    private Integer messageCount;
    private Date createdAt;
    private Date updatedAt;
}
