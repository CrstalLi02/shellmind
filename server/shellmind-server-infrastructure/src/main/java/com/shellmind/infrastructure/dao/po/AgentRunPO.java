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
public class AgentRunPO {
    private String runId;
    private String sessionId;
    private String userId;
    private String agentId;
    private String status;
    private String stopReason;
    private Integer round;
    private Integer totalToolCalls;
    private Long totalTokens;
    private Date startedAt;
    private Date finishedAt;
    private Boolean cancelled;
    private String cancelReason;
    private Boolean contextCompressed;
    private Integer maxRounds;
    private Integer maxToolCallsPerRound;
    private Integer maxAiRetries;
    private Long idleTimeoutMs;
    private Integer maxTokenBudget;
    private Date createdAt;
    private Date updatedAt;
}
