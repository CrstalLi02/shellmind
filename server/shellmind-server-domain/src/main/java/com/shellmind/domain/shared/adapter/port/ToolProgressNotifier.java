package com.shellmind.domain.shared.adapter.port;

/**
 * Tool-execution progress notification (pushed to the client that started the run).
 * <p>
 * Only lightweight progress is pushed (tool name, argument summary, success/failure); full tool results return via the agent runtime event stream.
 */
public interface ToolProgressNotifier {

    /**
     * @param sessionId session of the run (sub-agent uses a child session ID; notifications are routed to the parent session's client)
     */
    void onToolStart(String sessionId, String toolName, String args);

    void onToolEnd(String sessionId, String toolName, String resultSummary, boolean success);
}
