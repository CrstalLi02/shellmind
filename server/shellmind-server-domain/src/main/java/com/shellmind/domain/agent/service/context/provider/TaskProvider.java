package com.shellmind.domain.agent.service.context.provider;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class TaskProvider implements ContextProvider {
    @Override public String getName() { return "task"; }
    @Override public int getOrder() { return 20; }
    @Override public boolean enabled() { return true; }

    /** Max task-description length, so a huge analysis report is not stuffed into the prefix */
    private static final int MAX_TASK_DESC_LENGTH = 800;

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();

        if (messageHistory != null && !messageHistory.isEmpty()) {
            // Use the *latest* user message as the current task description (not the first):
            // continuation instructions like "start handling it" / "improve this content" are the
            // task to run now; the original analysis request is only background.
            String latestUserMessage = null;
            for (int i = messageHistory.size() - 1; i >= 0; i--) {
                Map<String, Object> msg = messageHistory.get(i);
                if ("user".equals(msg.get("role")) && msg.get("content") != null
                        && !String.valueOf(msg.get("content")).isBlank()) {
                    latestUserMessage = String.valueOf(msg.get("content"));
                    break;
                }
            }
            if (latestUserMessage != null) {
                result.put("taskDescription", truncate(latestUserMessage));
            }

            final String latest = latestUserMessage;
            // Attach the recent user-instruction sequence (excluding the current message) so the model can resolve "these"
            List<String> recentUserMessages = messageHistory.stream()
                    .filter(m -> "user".equals(m.get("role")) && m.get("content") != null)
                    .map(m -> String.valueOf(m.get("content")))
                    .filter(c -> !c.isBlank() && !c.equals(latest))
                    .toList();
            if (!recentUserMessages.isEmpty()) {
                String history = recentUserMessages.stream()
                        .map(this::truncate)
                        .collect(Collectors.joining("\n---\n"));
                result.put("priorUserInstructions", history);
            }
        }

        return result;
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() <= MAX_TASK_DESC_LENGTH ? s : s.substring(0, MAX_TASK_DESC_LENGTH) + "...(truncated)";
    }
}
