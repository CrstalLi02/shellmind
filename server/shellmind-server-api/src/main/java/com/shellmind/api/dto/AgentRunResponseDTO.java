package com.shellmind.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentRunResponseDTO {

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
}
