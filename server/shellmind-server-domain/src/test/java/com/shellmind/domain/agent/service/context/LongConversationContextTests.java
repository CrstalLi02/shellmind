package com.shellmind.domain.agent.service.context;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Long-conversation context-management evaluation.
 *
 * Verifies:
 * 1. Growing message count → token estimate increases
 * 2. trimHistory trims when over budget
 * 3. After trim, message count drops but recent messages are kept
 */
class LongConversationContextTests {

    @Test
    void scenario1_historyGrowthTriggersHigherTokenEstimate() {
        List<Map<String, Object>> shortHistory = buildHistory(10, "短消息");
        List<Map<String, Object>> longHistory = buildHistory(80, "这是一条比较长的中文消息，包含足够的内容来测试token估算");

        long shortTokens = estimateTokens(shortHistory);
        long longTokens = estimateTokens(longHistory);

        assertTrue(longTokens > shortTokens * 4,
                "80 messages should be significantly more tokens than 10: " + longTokens + " > " + shortTokens * 4);
    }

    @Test
    void scenario2_tokenEstimationWithMixedContent() {
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(msg("user", "帮我看看这个 UserService.java 文件有什么问题"));
        history.add(msg("assistant", "我找到了几个问题：\n1. 空指针风险\n2. 未关闭资源"));
        history.add(msg("user", "能修复第一个问题吗？就是空指针的那个"));
        history.add(msg("tool", "{\"content\": \"UserService.java modified, 3 lines changed\"}"));

        long tokens = estimateTokens(history);
        assertTrue(tokens > 50, "Mixed CJK + tool output should be > 50 tokens: " + tokens);
    }

    @Test
    void scenario3_trimReducesLongHistory() {
        List<Map<String, Object>> history = buildHistory(100, "这是一条长消息，包含大量的上下文内容用来测试裁剪功能");
        int originalSize = history.size();

        List<Map<String, Object>> trimmed = trimToBudget(history, 2000);

        assertTrue(trimmed.size() < originalSize,
                "Trimmed history should be smaller: " + trimmed.size() + " < " + originalSize);
        assertTrue(trimmed.size() > 0, "Trimmed history should not be empty");
    }

    @Test
    void scenario4_trimPreservesRecentMessages() {
        List<Map<String, Object>> history = buildHistory(50, "message content");
        String lastMessageContent = String.valueOf(history.get(history.size() - 1).get("content"));

        List<Map<String, Object>> trimmed = trimToBudget(history, 30);

        assertTrue(trimmed.size() < 50);
        boolean lastFound = trimmed.stream()
                .anyMatch(m -> String.valueOf(m.get("content")).equals(lastMessageContent));
        assertTrue(lastFound, "Most recent message should be preserved after trimming");
    }

    private List<Map<String, Object>> buildHistory(int count, String contentTemplate) {
        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String role = i % 2 == 0 ? "user" : "assistant";
            history.add(msg(role, contentTemplate + " #" + i));
        }
        return history;
    }

    private Map<String, Object> msg(String role, String content) {
        Map<String, Object> map = new HashMap<>();
        map.put("role", role);
        map.put("content", content);
        return map;
    }

    private long estimateTokens(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        long total = 0;
        for (Map<String, Object> msg : messages) {
            String content = String.valueOf(msg.get("content"));
            total += com.shellmind.domain.agent.service.engine.TokenEstimator.estimate(content);
        }
        return total;
    }

    /** Simulate trimHistory: keep recent messages until the token budget is exhausted */
    private List<Map<String, Object>> trimToBudget(List<Map<String, Object>> history, int budget) {
        List<Map<String, Object>> result = new ArrayList<>();
        long used = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            long cost = estimateTokens(List.of(history.get(i)));
            if (used + cost > budget) break;
            result.add(0, history.get(i));
            used += cost;
        }
        return result;
    }
}
