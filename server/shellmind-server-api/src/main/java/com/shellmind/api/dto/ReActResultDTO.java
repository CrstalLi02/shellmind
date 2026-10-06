package com.shellmind.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ReAct execution result DTO
 *
 * @author xiaofuge bugstack.cn
 * 2026/5/4
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ReActResultDTO {

    /**
     * Final response content
     */
    private String content;

    /**
     * Total steps executed
     */
    private int totalSteps;

    /**
     * Total tool calls
     */
    private int totalToolCalls;

    /**
     * Whether stopped because max steps were reached
     */
    private boolean maxStepsReached;

    /**
     * Whether the user stopped it
     */
    private boolean userStopped;

    /**
     * Whether stopped by idle timeout
     */
    private boolean idleTimeout;

    /**
     * Stop reason: completed / finish / max_steps / max_tool_calls / user_stop / idle_timeout / error
     */
    private String stopReason;

    /**
     * Tool-call list
     */
    private java.util.List<java.util.Map<String, Object>> toolCalls;

    /**
     * Tool-result list
     */
    private java.util.List<java.util.Map<String, Object>> toolResults;

    /**
     * Error message if any
     */
    private String error;

}
