package com.shellmind.domain.agent.service.context.provider;

import com.shellmind.domain.memory.service.ILongTermMemoryService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Long-term memory context provider (order=26).
 * <p>
 * During ReAct prompt construction, automatically recall long-term memories related to the current
 * conversation, emit a {@code longTermMemorySummary} field, and have DynamicPromptBuilder render it
 * as a [Long-term Memory] section.
 * <p>
 * Sort order=26, after CoreMemoryProvider(25) and before MilestoneProvider(30):
 * core memories (distilled rules/preferences) first; episodic memories (environment/versions/troubleshooting cases) as supplement.
 * <p>
 * Recall-query strategy: concatenate the first user message in history (initial goal) + the latest
 * user message (current need), so recalled memories relate both to the overall task and to this round.
 *
 * @see ContextProvider
 * @see com.shellmind.domain.memory.service.LongTermMemoryService#buildMemorySummary
 */
@Component
public class LongTermMemoryProvider implements ContextProvider {

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Override
    public String getName() {
        return "long-term-memory";
    }

    /**
     * Sort weight 26, between CoreMemoryProvider(25) and MilestoneProvider(30)
     */
    @Override
    public int getOrder() {
        return 26;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    /**
     * Recall long-term memory, build a summary, and inject PromptContextVO.longTermMemorySummary.
     *
     * @param sessionId          session ID
     * @param userId             user ID
     * @param terminalSessionId  terminal session ID
     * @param messageHistory     message history (used to build the recall query)
     * @return map containing longTermMemorySummary, or empty when there is no memory
     */
    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        String query = buildQuery(messageHistory);
        String summary = longTermMemoryService.buildMemorySummary(userId, query, 5);
        if (!summary.isBlank()) {
            result.put("longTermMemorySummary", summary);
        }
        return result;
    }

    /**
     * Build the recall query: first user message (initial goal) + latest user message (current need).
     * <p>
     * The first user message is the session's initial goal (e.g. "troubleshoot nginx 502");
     * the latest user message is this round's specific need (e.g. "also check the redis connection pool").
     * Concatenating them keeps recalled memories relevant both globally and to this round.
     *
     * @param messageHistory message history
     * @return recall query string, or empty when there is no user message
     */
    private String buildQuery(List<Map<String, Object>> messageHistory) {
        if (messageHistory == null || messageHistory.isEmpty()) {
            return "";
        }

        String latestUser = "";
        String firstUser = "";
        for (Map<String, Object> message : messageHistory) {
            if (!"user".equals(message.get("role"))) {
                continue;
            }
            String content = String.valueOf(message.getOrDefault("content", ""));
            if (firstUser.isBlank()) {
                firstUser = content;
            }
            latestUser = content;
        }
        return (firstUser + " " + latestUser).trim();
    }
}
