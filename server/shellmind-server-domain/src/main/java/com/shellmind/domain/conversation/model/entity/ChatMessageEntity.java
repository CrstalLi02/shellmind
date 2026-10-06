package com.shellmind.domain.conversation.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Chat message entity
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatMessageEntity {
    private Long id;
    private String sessionId;
    private String role;
    private String content;
    private String toolName;
    private String toolCallId;
    private String priority;
    private Integer tokenCount;
    private Date createdAt;
}
