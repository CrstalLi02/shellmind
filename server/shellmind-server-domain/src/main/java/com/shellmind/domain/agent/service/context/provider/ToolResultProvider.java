package com.shellmind.domain.agent.service.context.provider;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ToolResultProvider implements ContextProvider {
    private final Map<String, List<ToolResultEntry>> results = new ConcurrentHashMap<>();
    private final Map<String, String> summaryCache = new ConcurrentHashMap<>();

    // ── Truncation policy constants ──
    /** Max length kept for the current-round tool result */
    private static final int CURRENT_RESULT_MAX_LENGTH = 2000;
    /** Head length kept for historical-round tool results */
    private static final int HISTORY_HEAD_LENGTH = 500;
    /** Tail length kept for historical-round tool results */
    private static final int HISTORY_TAIL_LENGTH = 500;
    /** Max length per tool category in the structured summary */
    private static final int CATEGORY_SUMMARY_MAX_LENGTH = 200;
    /** Max index offset treated as "current round" (the most recent results) */
    private static final int CURRENT_ROUND_COUNT = 5;

    @Override public String getName() { return "tool-result"; }
    @Override public int getOrder() { return 40; }
    @Override public boolean enabled() { return true; }

    @Override
    public Map<String, Object> provide(String sessionId, String userId, String terminalSessionId, List<Map<String, Object>> messageHistory) {
        Map<String, Object> result = new HashMap<>();
        List<ToolResultEntry> entries = results.getOrDefault(sessionId, Collections.emptyList());
        if (entries.isEmpty()) return result;

        // Lazy summary: return cache if present, otherwise regenerate
        String summary = summaryCache.computeIfAbsent(sessionId, id -> generateSummary(entries));
        result.put("toolResultSummary", summary);
        return result;
    }

    public void pushResult(String sessionId, String toolName, String result) {
        // Truncate immediately on pushResult to reduce memory
        String truncated = truncateSmart(result, CURRENT_RESULT_MAX_LENGTH);
        results.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>())
               .add(new ToolResultEntry(toolName, truncated));
        summaryCache.remove(sessionId);  // invalidate summary cache
        log.debug("Tool result cached: session={}, tool={}, originalLen={}, truncatedLen={}",
                sessionId, toolName, result.length(), truncated.length());
    }

    /**
     * Generate a structured summary.
     * <p>
     * Improvements (aligned with Android contextCompressor P1-4):
     * 1. Distinguish current round vs historical rounds with different truncation
     * 2. Group by tool type; summarize each category independently
     * 3. Structured 5-section output
     */
    private String generateSummary(List<ToolResultEntry> entries) {
        int total = entries.size();
        int currentStart = Math.max(0, total - CURRENT_ROUND_COUNT);
        List<ToolResultEntry> currentEntries = entries.subList(currentStart, total);
        List<ToolResultEntry> historyEntries = currentStart > 0 ? entries.subList(0, currentStart) : Collections.emptyList();

        StringBuilder sb = new StringBuilder();

        // ── 1. Overview ──
        sb.append("[Tool execution summary]\n");
        sb.append("Total tool calls: ").append(total);
        if (!historyEntries.isEmpty()) {
            sb.append(" (history ").append(historyEntries.size()).append(" + current ").append(currentEntries.size()).append(")");
        }
        sb.append("\n\n");

        // ── 2. Group by tool type ──
        Map<String, List<ToolResultEntry>> categorized = entries.stream()
                .collect(Collectors.groupingBy(ToolResultEntry::getToolName));

        sb.append("[Call categories]\n");
        for (Map.Entry<String, List<ToolResultEntry>> cat : categorized.entrySet()) {
            int catCount = cat.getValue().size();
            // Summary of the last result in this category
            String lastResult = truncateSmart(cat.getValue().get(cat.getValue().size() - 1).getResult(), CATEGORY_SUMMARY_MAX_LENGTH);
            sb.append("- ").append(cat.getKey()).append("(").append(catCount).append(" times): ").append(lastResult).append("\n");
        }
        sb.append("\n");

        // ── 3. Historical rounds (compressed) ──
        if (!historyEntries.isEmpty()) {
            sb.append("[Historical rounds]\n");
            for (ToolResultEntry e : historyEntries) {
                sb.append("- ").append(e.getToolName()).append(": ")
                  .append(truncateWithHeadTail(e.getResult(), HISTORY_HEAD_LENGTH, HISTORY_TAIL_LENGTH)).append("\n");
            }
            sb.append("\n");
        }

        // ── 4. Current round (full) ──
        sb.append("[Current round]\n");
        for (ToolResultEntry e : currentEntries) {
            sb.append("- ").append(e.getToolName()).append(": ").append(e.getResult()).append("\n");
        }
        sb.append("\n");

        // ── 5. Key findings ──
        sb.append("[Key findings]\n");
        // Extract errors/key info from current-round results
        for (ToolResultEntry e : currentEntries) {
            String r = e.getResult();
            if (r != null) {
                String lower = r.toLowerCase();
                if (lower.contains("error") || lower.contains("failed") || lower.contains("exception") || lower.contains("permission denied")) {
                    sb.append("- ⚠️ ").append(e.getToolName()).append(" detected an error\n");
                }
                if (lower.contains("success") || lower.contains("build success") || lower.contains("passed")) {
                    sb.append("- ✅ ").append(e.getToolName()).append(" succeeded\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * Smart truncation: keep the head plus a truncation marker.
     */
    private String truncateSmart(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "... [truncated, original " + s.length() + " chars]";
    }

    /**
     * Head-and-tail truncation: keep head + tail with an ellipsis in the middle.
     */
    private String truncateWithHeadTail(String s, int headLen, int tailLen) {
        if (s == null) return "";
        if (s.length() <= headLen + tailLen + 50) return s;  // too short to truncate
        String head = s.substring(0, headLen);
        String tail = s.substring(s.length() - tailLen);
        return head + "\n... [omitted " + (s.length() - headLen - tailLen) + " chars in the middle]\n" + tail;
    }

    @Data
    @AllArgsConstructor
    public static class ToolResultEntry {
        private String toolName;
        private String result;
    }
}
