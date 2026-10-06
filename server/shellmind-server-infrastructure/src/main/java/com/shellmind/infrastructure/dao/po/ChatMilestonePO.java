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
public class ChatMilestonePO {
    private Long id;
    private String sessionId;
    private String type;
    private String content;
    private Date createdAt;
}
