package com.shellmind.cases.react.engine;

import com.shellmind.api.dto.ReActResultDTO;

/**
 * Agent-loop SSE callback interface
 * <p>All events are pushed to the frontend through this interface
 * <p>Aligned with StreamingCallbacks in ShellMind streamingAgent.ts
 *
 * @author Teaching Edition - ShellMind
 * 2026/6/22
 */
public interface AgentLoopCallbacks {

    /**
     * AI text stream (incremental)
     *
     * @param chunk    this incremental text
     * @param fullText accumulated full text
     */
    void onText(String chunk, String fullText);

    /**
     * tool call started
     */
    void onToolCall(String toolCallId, String toolName, String args);

    /**
     * Tool execution progress
     */
    void onToolProgress(String toolCallId, String progress);

    /**
     * tool execution result
     *
     * @param status "success" / "error" / "denied"
     */
    void onToolResult(String toolCallId, String content, String status);

    /**
     * round ended
     */
    void onRoundEnd(int currentRound, int maxRounds, int totalToolCalls);

    /**
     * Warning (non-fatal; the loop may continue)
     */
    void onWarning(String message);

    /**
     * Error (fatal; the loop will stop)
     */
    void onError(String message);

    /**
     * Complete
     */
    void onDone(ReActResultDTO result);
}
