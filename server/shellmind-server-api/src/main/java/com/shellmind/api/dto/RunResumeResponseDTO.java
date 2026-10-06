package com.shellmind.api.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Run resume context: run info + recent session messages
 */
@Data
public class RunResumeResponseDTO {
    private String runId;
    private String sessionId;
    private String userId;
    private String agentId;
    private String status;
    private String stopReason;
    private List<Map<String, Object>> messages;
}
