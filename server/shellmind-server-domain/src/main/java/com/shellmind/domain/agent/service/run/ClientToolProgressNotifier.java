package com.shellmind.domain.agent.service.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shellmind.domain.agent.adapter.port.ClientChannel;
import com.shellmind.domain.shared.adapter.port.ToolProgressNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Push tool_progress events through the client channel of the run's session.
 */
@Slf4j
@Component
public class ClientToolProgressNotifier implements ToolProgressNotifier {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentRunRegistry runRegistry;

    public ClientToolProgressNotifier(AgentRunRegistry runRegistry) {
        this.runRegistry = runRegistry;
    }

    @Override
    public void onToolStart(String sessionId, String toolName, String args) {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "tool_progress");
        event.put("toolName", toolName);
        event.put("args", args);
        event.put("status", "executing");
        event.put("timestamp", System.currentTimeMillis());
        send(sessionId, event);
    }

    @Override
    public void onToolEnd(String sessionId, String toolName, String resultSummary, boolean success) {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "tool_progress");
        event.put("toolName", toolName);
        event.put("summary", resultSummary);
        event.put("status", success ? "success" : "error");
        event.put("timestamp", System.currentTimeMillis());
        send(sessionId, event);
    }

    private void send(String sessionId, Map<String, Object> event) {
        Optional<ClientChannel> channel = runRegistry.channel(sessionId);
        if (channel.isEmpty()) {
            log.debug("[ToolProgress] Session has no client channel, skip notify: session={}, event={}", sessionId, event.get("toolName"));
            return;
        }
        try {
            channel.get().send(OBJECT_MAPPER.writeValueAsString(event) + "\n");
        } catch (Exception e) {
            log.debug("[ToolProgress] Push failed: session={}", sessionId, e);
        }
    }
}
