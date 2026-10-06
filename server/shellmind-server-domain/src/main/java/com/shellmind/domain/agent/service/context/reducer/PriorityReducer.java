package com.shellmind.domain.agent.service.context.reducer;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PriorityReducer implements MessageReducer {

    @Override
    public List<Map<String, Object>> reduce(List<Map<String, Object>> messages, int tokenBudget) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<PrioritizedMessage> prioritized = messages.stream()
            .map(m -> new PrioritizedMessage(m, inferPriority(m)))
            .collect(Collectors.toList());

        Set<Integer> keptIndices = new HashSet<>();
        int minKeep = Math.min(2, prioritized.size());
        int usedTokens = 0;
        for (int index = prioritized.size() - minKeep; index < prioritized.size(); index++) {
            keptIndices.add(index);
            usedTokens += estimateToken(prioritized.get(index).getMessage());
        }

        List<ScoredMessage> candidates = new ArrayList<>();
        for (int index = 0; index < prioritized.size() - minKeep; index++) {
            PrioritizedMessage message = prioritized.get(index);
            candidates.add(new ScoredMessage(index, message.getPriority(), estimateToken(message.getMessage())));
        }
        candidates.sort(Comparator
                .comparing((ScoredMessage candidate) -> candidate.priority())
                .thenComparing(candidate -> -candidate.index()));

        for (ScoredMessage candidate : candidates) {
            if (usedTokens + candidate.tokenCount() <= tokenBudget) {
                keptIndices.add(candidate.index());
                usedTokens += candidate.tokenCount();
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            if (keptIndices.contains(index)) {
                result.add(messages.get(index));
            }
        }
        return result;
    }

    private Priority inferPriority(Map<String, Object> message) {
        String role = (String) message.get("role");
        String content = String.valueOf(message.get("content"));

        if ("tool".equals(role) && containsAny(content, "error", "failed", "exception", "permission denied")) {
            return Priority.CRITICAL;
        }
        if ("user".equals(role) && containsAny(content, "/", ".conf", ".yml", ".properties")) {
            return Priority.HIGH;
        }
        if ("system".equals(role)) {
            return Priority.HIGH;
        }
        if ("assistant".equals(role) && content.length() > 5000) {
            return Priority.LOW;
        }
        return Priority.MEDIUM;
    }

    private boolean containsAny(String content, String... keywords) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword)) return true;
        }
        return false;
    }

    private int estimateToken(Map<String, Object> message) {
        String content = String.valueOf(message.get("content"));
        // Rough estimate: 1 token per 2 characters
        return content != null ? content.length() / 2 : 0;
    }

    private int estimateTokens(List<PrioritizedMessage> messages) {
        return messages.stream().mapToInt(m -> estimateToken(m.getMessage())).sum();
    }

    @Data
    @AllArgsConstructor
    private static class PrioritizedMessage {
        private Map<String, Object> message;
        private Priority priority;
    }

    private record ScoredMessage(int index, Priority priority, int tokenCount) {}

    enum Priority { CRITICAL, HIGH, MEDIUM, LOW }
}
