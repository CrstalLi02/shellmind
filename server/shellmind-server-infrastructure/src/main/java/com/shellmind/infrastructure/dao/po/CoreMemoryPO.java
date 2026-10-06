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
public class CoreMemoryPO {
    private Long id;
    private String userId;
    private String scope;
    private String category;
    private String title;
    private String keywords;
    private String content;
    private Integer priority;
    private String sourceSessionId;
    private Integer useCount;
    private Date createdAt;
    private Date lastUsedAt;
}
