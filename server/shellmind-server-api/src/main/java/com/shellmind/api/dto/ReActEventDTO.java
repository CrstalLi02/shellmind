package com.shellmind.api.dto;

import lombok.Data;

/**
 * ReAct chat event DTO
 *
 * <p>Matches the callback event structure of ShellMind streamingAgent.ts
 * <p>Extra events: task_breakdown (task-split proposal), task_progress (subtask progress)
 *
 * <p>Matches the callback event structure of ShellMind streamingAgent.ts
 *
 * @author xiaofuge bugstack.cn
 * 2026/5/4
 */
@Data
public class ReActEventDTO {

    /**
     * Event type
     * - text: text fragment
     * - tool_call: tool call started
     * - tool_result: tool execution result
     * - round_end: round ended
     * - done: all done
     * - error: error
     * - warning: warning (non-fatal)
     * - task_breakdown: task-breakdown proposal
     * - task_progress: subtask status change
     * - permission_confirm: permission confirm request (DENY/CONFIRM tools need user confirmation)
     * - tool_output: live tool output chunk (stdout/stderr increment during a long command)
     * - round_start: new round started
     * - status: status update (context compression / fallback / reconnect, etc.)
     */
    private String event;

    /**
     * Permission info (when event=permission_confirm)
     */
    private PermissionInfo permission;

    /**
     * Live tool output (when event=tool_output)
     */
    private String outputChunk;

    /**
     * Status update (when event=status)
     */
    private String statusMessage;

    /**
     * File-change summary (when event=done; extracted from tool results)
     */
    private ChangeSummaryDTO changeSummary;

    /**
     * Task-breakdown info (when event=task_breakdown)
     */
    private TaskBreakdownDTO taskBreakdown;

    /**
     * Subtask progress (when event=task_progress)
     */
    private TaskProgress taskProgress;

    @Data
    public static class TaskProgress {
        /** Subtask index */
        private int subTaskIndex;
        /** Subtask title */
        private String subTaskTitle;
        /** Status: pending / executing / completed / failed / skipped */
        private String status;
        /** Total subtasks */
        private int totalSubTasks;
        /** Completed subtasks */
        private int completedSubTasks;
    }

    /**
     * Event content (text, fragment IDs, etc.)
     */
    private String content;

    /**
     * Tool-call ID (for tool_call / tool_result)
     */
    private String toolCallId;

    /**
     * Tool name (for tool_call)
     */
    private String toolName;

    /**
     * Tool-call args (for tool_call, e.g. the command string)
     */
    private String args;

    /**
     * Tool-call status (for tool_call / tool_result)
     * - pending: waiting to run
     * - running: running
     * - success: succeeded
     * - error: failed
     */
    private String status;

    /**
     * Full accumulated text (when event=text)
     */
    private String fullText;

    /**
     * Step info (when event=round_end)
     */
    private StepInfo stepInfo;

    @Data
    public static class StepInfo {
        /** Current step */
        private int currentStep;
        /** Max steps */
        private int maxSteps;
        /** Whether to continue */
        private boolean shouldContinue;
        /** Total tool calls */
        private int totalToolCalls;
    }

    /**
     * File-change summary DTO
     */
    @Data
    public static class ChangeSummaryDTO {
        /** AI-generated change description */
        private String description;
        /** Core topic of this conversation */
        private String topic;
        /** Created files */
        private java.util.List<ChangeFile> created;
        /** Modified files */
        private java.util.List<ChangeFile> modified;
        /** Deleted files */
        private java.util.List<ChangeFile> deleted;
    }

    /**
     * Single file change
     */
    @Data
    public static class ChangeFile {
        /** File path */
        private String path;
        /** Change kind: create / modify / delete */
        private String kind;
        /** Line-change stats */
        private int addedLines;
        private int removedLines;
    }

    /**
     * Permission confirmation info
     */
    @Data
    public static class PermissionInfo {
        /** Unique confirm-request ID */
        private String confirmId;
        /** Tool name */
        private String toolName;
        /** Tool args (command/path, etc.) */
        private String toolArgs;
        /** Risk level: DENY / CONFIRM / ALLOW */
        private String riskLevel;
        /** Risk reason (why confirmation is needed) */
        private String reason;
        /** Timeout in milliseconds; 0 = no timeout */
        private long timeoutMs;
    }

}
