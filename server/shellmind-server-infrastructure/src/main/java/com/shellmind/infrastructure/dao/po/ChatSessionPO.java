package com.shellmind.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatSessionPO {
    private String id;
    private String agentId;
    private String userId;
    private String title;
    private Integer messageCount;
    private Date createdAt;
    private Date updatedAt;
}
