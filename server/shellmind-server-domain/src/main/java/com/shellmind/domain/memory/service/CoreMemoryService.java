package com.shellmind.domain.memory.service;

import com.shellmind.domain.memory.adapter.repository.ICoreMemoryRepository;
import com.shellmind.domain.memory.model.valobj.CoreMemoryVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Core memory service.
 * <p>
 * Aligned with Android memoryService.ts CoreMemory:
 * 1. Add memories (in-conversation learning)
 * 2. Relevance filtering (keyword match against the query)
 * 3. Format for prompt injection
 * 4. Evict cold memories
 * <p>
 * Relationship to MilestoneTracker:
 * - MilestoneTracker records events (TASK_CHANGE/ERROR, etc.)
 * - CoreMemoryService stores refined long-term knowledge (rules/preferences/corrections)
 * - MilestoneTracker USER_CORRECTION events can trigger CoreMemoryService writes
 *
 * @author ShellMind
 * 2026/6/26
 */
@Slf4j
@Service
public class CoreMemoryService {

    @Resource
    private ICoreMemoryRepository coreMemoryRepository;

    /** Maximum number of memories. */
    private static final int MAX_MEMORIES = 50;

    /** Minimum keyword hits required for a relevance match. */
    private static final int MIN_KEYWORD_MATCH = 1;

    /** Default priority. */
    private static final int DEFAULT_PRIORITY = 3;

    /** Priority for correction memories (higher). */
    private static final int CORRECTION_PRIORITY = 5;

    /**
     * Add a core memory.
     *
     * @param scope      scope (user / session)
     * @param category   category (Rule / Preference / Decision / Correction / Fact)
     * @param title      short title
     * @param keywords   keywords (comma-separated)
     * @param content    body
     * @param priority   priority (1-5)
     * @param sourceSid  source session ID
     */
    public void addMemory(String userId, String scope, String category, String title,
                          String keywords, String content, int priority, String sourceSid) {
        String effectiveUserId = resolveUserId(userId);
        CoreMemoryVO memory = CoreMemoryVO.builder()
                .userId(effectiveUserId)
                .scope(scope)
                .category(category)
                .title(title)
                .keywords(keywords)
                .content(content)
                .priority(priority)
                .createdAt(System.currentTimeMillis())
                .lastUsedAt(System.currentTimeMillis())
                .useCount(1)
                .sourceSessionId(sourceSid)
                .build();

        coreMemoryRepository.addMemory(memory);
        log.info("Core memory added: scope={}, category={}, title={}, priority={}",
                effectiveUserId, scope, category, title, priority);

        // Evict cold memories so the total stays within MAX_MEMORIES
        coreMemoryRepository.evictColdMemories(effectiveUserId, MAX_MEMORIES);
    }

    /**
     * Add a correction memory (in-conversation learning).
     * <p>
     * Triggered from MilestoneTracker USER_CORRECTION events.
     *
     * @param originalText original user correction text
     * @param sourceSid    source session ID
     */
    public void addCorrectionMemory(String userId, String originalText, String sourceSid) {
        // Extract keywords (simple split on whitespace and common separators)
        String keywords = extractKeywords(originalText);

        // Build title and content
        String title = "User correction: " + truncate(originalText, 50);
        String content = buildCorrectionContent(originalText);

        addMemory(userId, "user", "Correction", title, keywords, content, CORRECTION_PRIORITY, sourceSid);
    }

    /**
     * Get core memories relevant to the current query and format them for prompt injection.
     * <p>
     * Aligned with Android memoryService.ts formatMemoriesForPrompt(query).
     *
     * @param userId user ID
     * @param query  current user message (used for relevance filtering)
     * @return formatted memory text (XML, easy for the LLM to parse)
     */
    public String formatMemoriesForPrompt(String userId, String query) {
        List<CoreMemoryVO> memories;
        String effectiveUserId = resolveUserId(userId);

        if (query != null && !query.isBlank()) {
            // Filter by relevance when a query is present
            memories = coreMemoryRepository.getRelevantMemories(effectiveUserId, query, 10);
        } else {
            // Without a query, take the most recent high-priority memories
            memories = coreMemoryRepository.getAllMemories(effectiveUserId, 5);
        }

        if (memories.isEmpty()) {
            return "<core_memories>\n<no_relevant_memories>\nNo relevant core memories for the current context. Ignore the memory section and analyze based on the current request.\n</no_relevant_memories>\n</core_memories>";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<core_memories>\n");
        for (CoreMemoryVO m : memories) {
            sb.append("<memory category=\"").append(m.getCategory())
              .append("\" priority=\"").append(m.getPriority())
              .append("\" keywords=\"").append(m.getKeywords())
              .append("\">\n");
            sb.append("  <title>").append(m.getTitle()).append("</title>\n");
            sb.append("  <content>").append(m.getContent()).append("</content>\n");
            sb.append("</memory>\n");
        }
        sb.append("</core_memories>");

        // Update usage state
        for (CoreMemoryVO m : memories) {
            coreMemoryRepository.touchMemory(m.getId());
        }

        return sb.toString();
    }

    private String resolveUserId(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private String extractKeywords(String text) {
        if (text == null || text.isBlank()) return "";

        // Simple tokenization: drop common negation words, keep content keywords
        Set<String> negationWords = Set.of("不要", "别", "不对", "不是", "错了", "不应该", "别再", "换", "改成",
                "don't", "do not", "wrong", "not", "incorrect", "shouldn't", "stop", "change", "instead");

        // Split on whitespace, commas, and punctuation
        String[] tokens = text.split("[\\s,，。.!！?？;；:：/\\\\|]+");
        List<String> keywords = new ArrayList<>();

        for (String token : tokens) {
            if (token.length() < 2) continue;
            if (negationWords.contains(token)) continue;
            keywords.add(token.toLowerCase());
        }

        // If nothing remains after filtering, keep the first 20 characters of the original text
        if (keywords.isEmpty()) {
            return truncate(text, 20).toLowerCase();
        }

        return keywords.stream().collect(Collectors.joining(","));
    }

    private String buildCorrectionContent(String originalText) {
        return "The user explicitly corrected previous behavior. Original: \"" + truncate(originalText, 200)
                + "\". Subsequent actions should follow this correction.";
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
