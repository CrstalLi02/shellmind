package com.shellmind.domain.agent.service.context.provider;

import com.shellmind.domain.memory.service.CoreMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core-memory context provider.
 * <p>
 * Aligned with CoreMemory injection in Android memoryService.ts:
 * - Extract text from the last user message as the query
 * - Call CoreMemoryService.formatMemoriesForPrompt(query) for relevance filtering
 * - Inject into PromptContextVO for DynamicPromptBuilder
 * <p>
 * Relationship to MilestoneProvider:
 * - MilestoneProvider injects events (TASK_CHANGE/ERROR, etc., immediate)
 * - CoreMemoryProvider injects long-term knowledge (rules/preferences/corrections, cross-session)
 *
 * @author ShellMind Teaching Edition
 * 2026/6/26
 */
@Slf4j
@Component
public class CoreMemoryProvider implements ContextProvider {

    @Resource
    private CoreMemoryService coreMemoryService;

    @Override public String getName() { return "coreMemory"; }
    @Override public int getOrder() { return 25; }  // before milestone(30)
    @Override public boolean enabled() { return true; }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();

        // Extract query from the last user message (aligned with Android ai.ts)
        String query = extractLastUserMessage(messageHistory);
        if (query != null && query.length() > 200) {
            query = query.substring(0, 200);  // cap query length
        }

        String memoriesXml = coreMemoryService.formatMemoriesForPrompt(userId, query);
        result.put("coreMemories", memoriesXml);

        log.debug("Core-memory injection: session={}, queryLen={}, memoriesLen={}",
                sessionId, query != null ? query.length() : 0, memoriesXml.length());

        return result;
    }

    /**
     * Extract the text of the last user message from history.
     */
    private String extractLastUserMessage(List<Map<String, Object>> messageHistory) {
        if (messageHistory == null || messageHistory.isEmpty()) return null;

        // Search backwards for the last user message
        for (int i = messageHistory.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messageHistory.get(i);
            Object role = msg.get("role");
            if ("user".equals(role)) {
                Object content = msg.get("content");
                if (content instanceof String) {
                    return (String) content;
                }
            }
        }
        return null;
    }
}
