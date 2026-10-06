package com.shellmind.domain.agent.service.engine;

import com.shellmind.domain.agent.service.context.reducer.HybridReducer;
import com.shellmind.domain.agent.service.context.reducer.MessageReducer;
import com.shellmind.domain.agent.service.context.reducer.PriorityReducer;
import com.shellmind.domain.agent.service.context.reducer.SlidingWindowReducer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import com.shellmind.domain.llm.adapter.port.LlmClient;
import com.shellmind.domain.llm.model.valobj.LlmRequest;
import com.shellmind.domain.llm.model.valobj.LlmTarget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Context compressor.
 * <p>Aligned with ShellMind contextCompressor.ts
 * <p>
 * Trigger conditions:
 * 1. 413 Payload Too Large error
 * 2. Token budget exceeds 80%
 * 3. Message history exceeds 50 entries
 * <p>
 * Compression strategy (three-level fallback):
 * 1. Priority trim: PriorityReducer keeps CRITICAL/HIGH
 * 2. Sliding window: SlidingWindowReducer keeps the latest N messages
 * 3. Hybrid trim: HybridReducer takes the union (already fixed)
 * <p>
 * Note: AI summary compression (Level 3) requires ChatClient support; implemented in Phase 2
 *
 * @author ShellMind Teaching Edition
 * 2026/6/22
 */
@Slf4j
@Component
public class ContextCompressor {

    @Resource
    private PriorityReducer priorityReducer;

    @Resource
    private SlidingWindowReducer slidingWindowReducer;

    @Resource
    private HybridReducer hybridReducer;

    @Resource
    private LlmClient llmClient;

    /** AI summary compression: off by default (rule-based trim only); when on, uses an auxiliary model to generate a summary */
    @Value("${shellmind.context.ai-summary.enabled:false}")
    private boolean aiSummaryEnabled;

    /** Token threshold that triggers compression (80% of 200k) */
    private static final long COMPRESS_TOKEN_THRESHOLD = 160_000L;

    /** Message-count threshold that triggers compression */
    private static final int COMPRESS_MESSAGE_THRESHOLD = 50;

    /**
     * Whether compression is needed.
     */
    public boolean needsCompression(long currentTokens, int messageCount) {
        return currentTokens > COMPRESS_TOKEN_THRESHOLD
                || messageCount > COMPRESS_MESSAGE_THRESHOLD;
    }

    /**
     * Compress context (three-level fallback).
     *
     * @param history       original message history
     * @param currentTokens current token count
     * @return compressed message history
     */
    public List<Map<String, Object>> compress(List<Map<String, Object>> history, long currentTokens) {
        if (history == null || history.isEmpty()) {
            return history;
        }

        int originalSize = history.size();
        long originalTokens = currentTokens;
        log.info("Starting context compression: messages={}, tokens={}", originalSize, originalTokens);

        // Target token count: compress to 50% of current
        int targetTokens = (int) (currentTokens / 2);

        // Level 1: PriorityReducer (keep CRITICAL/HIGH)
        List<Map<String, Object>> afterPriority = priorityReducer.reduce(history, targetTokens);
        long afterPriorityTokens = LoopState.estimateTokens(afterPriority);
        log.info("Level 1 PriorityReducer: {} → {} messages, {} → {} tokens",
                originalSize, afterPriority.size(), originalTokens, afterPriorityTokens);

        if (afterPriorityTokens <= targetTokens) {
            log.info("Level 1 compression met the target");
            return afterPriority;
        }

        // Level 2: SlidingWindowReducer (sliding window)
        List<Map<String, Object>> afterSliding = slidingWindowReducer.reduce(history, targetTokens);
        long afterSlidingTokens = LoopState.estimateTokens(afterSliding);
        log.info("Level 2 SlidingWindowReducer: {} → {} messages, {} → {} tokens",
                originalSize, afterSliding.size(), afterPriorityTokens, afterSlidingTokens);

        if (afterSlidingTokens <= targetTokens) {
            log.info("Level 2 compression met the target");
            return afterSliding;
        }

        // Level 3: AI summary compression (summarize old messages, keep the latest 8 as-is)
        List<Map<String, Object>> afterSummary = summarizeOldMessages(history, 8);
        long afterSummaryTokens = LoopState.estimateTokens(afterSummary);
        log.info("Level 3 AI summary: {} → {} messages, {} → {} tokens",
                originalSize, afterSummary.size(), afterSlidingTokens, afterSummaryTokens);

        if (afterSummaryTokens <= targetTokens) {
            log.info("Level 3 AI summary compression met the target");
            return afterSummary;
        }

        // Level 4: HybridReducer (union — last resort)
        List<Map<String, Object>> afterHybrid = hybridReducer.reduce(history, targetTokens);
        long afterHybridTokens = LoopState.estimateTokens(afterHybrid);
        log.info("Level 4 HybridReducer: {} → {} messages, {} → {} tokens",
                originalSize, afterHybrid.size(), afterSummaryTokens, afterHybridTokens);

        return afterHybrid;
    }

    // ═══════════════════════════════════════════════════════════════
    //  AI summary compression
    // ═══════════════════════════════════════════════════════════════

    /**
     * Generate an AI summary of old messages, keeping the latest keepRecent messages as-is.
     *
     * @param history     original message history
     * @param keepRecent  number of recent messages to keep uncompressed
     * @return merged list of summary + recent messages
     */
    private List<Map<String, Object>> summarizeOldMessages(List<Map<String, Object>> history, int keepRecent) {
        if (!aiSummaryEnabled || history.size() <= keepRecent + 2) {
            return history;
        }

        // Split: old messages (to summarize) vs recent messages (keep as-is)
        int splitIndex = history.size() - keepRecent;
        List<Map<String, Object>> oldMessages = history.subList(0, splitIndex);
        List<Map<String, Object>> recentMessages = history.subList(splitIndex, history.size());

        try {
            // Format old messages as readable text
            StringBuilder transcript = new StringBuilder();
            for (Map<String, Object> msg : oldMessages) {
                String role = String.valueOf(msg.getOrDefault("role", "unknown"));
                String content = String.valueOf(msg.getOrDefault("content", ""));
                // Truncate overly long content
                if (content.length() > 500) {
                    content = content.substring(0, 500) + "...";
                }
                switch (role) {
                    case "user" -> transcript.append("User: ").append(content).append("\n\n");
                    case "assistant" -> transcript.append("AI: ").append(content).append("\n\n");
                    case "tool" -> transcript.append("Tool result: ").append(content).append("\n\n");
                    case "system" -> transcript.append("System: ").append(content).append("\n\n");
                    default -> transcript.append(role).append(": ").append(content).append("\n\n");
                }
            }

            // [P1-4] Structured summary prompt: 4-section output format
            String summaryPrompt = "Compress the following conversation history into a structured summary. Strictly follow these 4 sections.\n\n" +
                    "## Goals and Requirements\nThe user's core needs, goals, and problems to solve (bullet list)\n\n" +
                    "## Changes and Operations\nKey operations already performed, code changes, SSH commands and their results (bullet list)\n\n" +
                    "## Issues and Blockers\nErrors, exceptions, blockers encountered, and troubleshooting progress (bullet list)\n\n" +
                    "## Progress and Decisions\nCurrent progress, key decisions, unfinished items, and next steps (bullet list)\n\n" +
                    "Requirements:\n" +
                    "1. Keep all SSH command results, file paths, and technical terms\n" +
                    "2. Keep each section under 300 words, total under 1500 words\n" +
                    "3. Output only the summary; do not add an introduction or closing remarks\n" +
                    "4. Write in the same language as the conversation\n\n" +
                    "Conversation history:\n" + transcript;

            String summary = llmClient.complete(LlmRequest.of(LlmTarget.auxiliary(null),
                    "You are a conversation-summary expert who extracts key information from technical dialogues. Strictly output the 4-section structured format and do not add extra content.",
                    summaryPrompt)).orElse(null);

            if (summary == null || summary.isBlank()) {
                log.warn("AI summary was empty, skipping summary compression");
                return history;
            }

            log.info("AI summary generated: {} old messages → {} character summary", oldMessages.size(), summary.length());

            // Assemble: summary system message + recent messages
            Map<String, Object> summaryMsg = new HashMap<>();
            summaryMsg.put("role", "system");
            summaryMsg.put("content", "[Conversation history summary - compressed summary of the previous " + oldMessages.size() + " turns]\n\n" + summary);
            summaryMsg.put("priority", "HIGH");

            List<Map<String, Object>> result = new ArrayList<>();
            result.add(summaryMsg);
            result.addAll(recentMessages);
            return result;

        } catch (Exception e) {
            log.error("AI summary compression failed, falling back to rule-based trim: {}", e.getMessage());
            return history;
        }
    }

    /**
     * Emergency compression for 413 errors.
     * Aggressive strategy: keep only the latest 4 messages plus all CRITICAL-level messages.
     */
    public List<Map<String, Object>> emergencyCompress(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) {
            return history;
        }

        log.warn("413 emergency compression: messages={}", history.size());

        // For 413 emergency compression, try AI summary first (if available)
        if (aiSummaryEnabled) {
            List<Map<String, Object>> withSummary = summarizeOldMessages(history, 4);
            long summaryTokens = LoopState.estimateTokens(withSummary);
            if (summaryTokens < LoopState.estimateTokens(history) * 0.7) {
                log.warn("413 AI summary compression succeeded: {} → {} messages, {} tokens", history.size(), withSummary.size(), summaryTokens);
                return withSummary;
            }
        }

        // AI summary unavailable or ineffective; use a tiny token budget so PriorityReducer keeps only CRITICAL
        List<Map<String, Object>> afterPriority = priorityReducer.reduce(history, 500);

        // Keep at least the latest 4 messages
        int minKeep = Math.min(4, history.size());
        if (afterPriority.size() < minKeep) {
            List<Map<String, Object>> tail = history.subList(history.size() - minKeep, history.size());
            // Merge and dedupe (roughly by content)
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Map<String, Object> msg : afterPriority) {
                seen.add(String.valueOf(msg.get("content")));
            }
            List<Map<String, Object>> result = new java.util.ArrayList<>(afterPriority);
            for (Map<String, Object> msg : tail) {
                String key = String.valueOf(msg.get("content"));
                if (seen.add(key)) {
                    result.add(msg);
                }
            }
            log.warn("413 emergency compression complete: {} → {} messages", history.size(), result.size());
            return result;
        }

        log.warn("413 emergency compression complete: {} → {} messages", history.size(), afterPriority.size());
        return afterPriority;
    }
}
