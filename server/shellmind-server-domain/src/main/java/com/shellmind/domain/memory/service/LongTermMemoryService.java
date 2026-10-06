package com.shellmind.domain.memory.service;

import com.shellmind.domain.memory.adapter.repository.ILongTermMemoryRepository;
import com.shellmind.domain.memory.model.entity.LongTermMemoryEntity;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Long-term memory service — extracts structured memories from the conversation stream.
 * <p>
 * Automatically extracts 4 kinds of structured memory and supports keyword recall
 * (coarse filter + fine ranking).
 * <p>
 * <b>Memory types:</b>
 * <table border="1">
 * <tr><th>Type</th><th>Trigger</th><th>Key strategy</th><th>Confidence</th></tr>
 * <tr><td>USER_PREFERENCE</td><td>User message contains preference hints ("from now on", "default", "remember", etc.)</td><td>sha1("pref:"+content)</td><td>0.85</td></tr>
 * <tr><td>ENVIRONMENT_FACT</td><td>Tool output matches OS keywords</td><td>"os:ubuntu"</td><td>0.70</td></tr>
 * <tr><td>SOFTWARE_FACT</td><td>Tool output matches a software version pattern</td><td>"redis:version"</td><td>0.78~0.82</td></tr>
 * <tr><td>TROUBLESHOOTING_CASE</td><td>Tool failure / assistant mentions "conclusion", "cause", etc.</td><td>sha1("failure:"/"assistant:"+content)</td><td>0.68~0.72</td></tr>
 * </table>
 * <p>
 * <b>Recall strategy: coarse filter + fine ranking</b>
 * <ul>
 *   <li>Coarse filter: load the 120 most recent memories from the DB</li>
 *   <li>Fine ranking: keyword-overlap score (overlap × 2 + extra +1 for USER_PREFERENCE), take Top-N by score</li>
 * </ul>
 * <p>
 * Design principle: zero extra dependencies (no Embedding API, no vector database) — MySQL + keyword matching.
 * For SSH operations, keywords (nginx, redis, 502, timeout) are naturally distinctive enough for useful recall.
 */
@Slf4j
@Service
public class LongTermMemoryService implements ILongTermMemoryService {

    /** Memory type: user preference (highest value — stated by the user, confidence 0.85). */
    private static final String TYPE_USER_PREFERENCE = "USER_PREFERENCE";

    /** Memory type: environment fact (OS detected from tool output, confidence 0.70). */
    private static final String TYPE_ENVIRONMENT_FACT = "ENVIRONMENT_FACT";

    /** Memory type: software version (version detected from tool output, confidence 0.78~0.82). */
    private static final String TYPE_SOFTWARE_FACT = "SOFTWARE_FACT";

    /** Memory type: troubleshooting case (failure signal / assistant conclusion, confidence 0.68~0.72). */
    private static final String TYPE_TROUBLESHOOTING_CASE = "TROUBLESHOOTING_CASE";

    /** Preference-detection hints: a message containing any of these is treated as a likely preference. */
    private static final List<String> PREFERENCE_HINTS = List.of("以后", "默认", "记住", "优先", "不要", "请用", "习惯", "统一使用",
            "from now on", "always", "default", "remember", "prefer", "don't", "please use", "usually", "consistently use");

    /** Generic software-version regex: matches formats such as "nginx version: 1.24.0" and "mysql Ver 8.0.42". */
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?i)(nginx|redis|mysql|postgres|docker|java)[^\\n]{0,40}(version|ver|版本)[:= ]+([\\w.\\-]+)");

    /** Redis-specific version regex: matches "redis_version:7.0.11" (the generic pattern may miss this). */
    private static final Pattern REDIS_VERSION_PATTERN = Pattern.compile("(?i)redis_version:([\\w.\\-]+)");

    /** OS detection regex: matches ubuntu, centos, debian, and similar OS keywords. */
    private static final Pattern OS_PATTERN = Pattern.compile("(?i)(ubuntu|centos|debian|rocky|almalinux|fedora|linux|macos|darwin|windows)");

    @Resource
    private ILongTermMemoryRepository longTermMemoryRepository;

    /**
     * {@inheritDoc}
     * <p>
     * Preference detection: a message containing any PREFERENCE_HINTS keyword is treated as a preference.
     * memory_key uses sha1("pref:"+content) so the same preference is not stored twice — if the user says
     * "always add sudo" three times, all three hashes match and saveOrUpdate increments hit_count.
     * Intent labels are appended to keywords to improve later recall.
     */
    @Override
    public void recordUserMessage(String userId, String sessionId, String message, String intentLabel) {
        if (isBlank(userId) || isBlank(message)) {
            return;
        }

        String normalized = normalize(message);
        boolean looksLikePreference = PREFERENCE_HINTS.stream().anyMatch(normalized::contains);
        if (!looksLikePreference) {
            return;
        }

        saveMemory(LongTermMemoryEntity.builder()
                .userId(userId)
                .sessionId(sessionId)
                .memoryType(TYPE_USER_PREFERENCE)
                .memoryKey(sha1("pref:" + normalized))
                .content(truncate(message, 300))
                .keywords(joinKeywords(extractKeywords(message + " " + nullSafe(intentLabel))))
                .sourceRole("user")
                .confidence(0.85)
                .hitCount(1)
                .build());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Extract four kinds of information from tool output via regex:
     * <ol>
     *   <li>OS detection (OS_PATTERN → ENVIRONMENT_FACT)</li>
     *   <li>Generic software version (VERSION_PATTERN → SOFTWARE_FACT)</li>
     *   <li>Redis-specific version (REDIS_VERSION_PATTERN → SOFTWARE_FACT)</li>
     *   <li>Failure signal (only when success=false and looksLikeHighSignalFailure → TROUBLESHOOTING_CASE)</li>
     * </ol>
     * Redis needs its own regex because its output format redis_version:7.0.11
     * differs from nginx version: nginx/1.x, so the generic pattern may miss it.
     */
    @Override
    public void recordToolObservation(String userId, String sessionId, String toolName, String resultContent, boolean success) {
        if (isBlank(userId) || isBlank(resultContent)) {
            return;
        }

        String text = truncate(resultContent, 500);

        // 1. OS detection
        Matcher osMatcher = OS_PATTERN.matcher(text);
        if (osMatcher.find()) {
            String os = osMatcher.group(1).toLowerCase(Locale.ROOT);
            saveMemory(LongTermMemoryEntity.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .memoryType(TYPE_ENVIRONMENT_FACT)
                    .memoryKey("os:" + os)
                    .content("Target environment appears to be " + os)
                    .keywords(joinKeywords(extractKeywords(os + " environment system os")))
                    .sourceRole("tool")
                    .confidence(0.70)
                    .hitCount(1)
                    .build());
        }

        // 2. Software version detection (generic regex)
        Matcher versionMatcher = VERSION_PATTERN.matcher(text);
        if (versionMatcher.find()) {
            String software = versionMatcher.group(1).toLowerCase(Locale.ROOT);
            String version = versionMatcher.group(3);
            saveMemory(LongTermMemoryEntity.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .memoryType(TYPE_SOFTWARE_FACT)
                    .memoryKey(software + ":version")
                    .content(software + " version is " + version)
                    .keywords(joinKeywords(extractKeywords(software + " version " + version)))
                    .sourceRole("tool")
                    .confidence(0.78)
                    .hitCount(1)
                    .build());
        }

        // 3. Redis version detection (dedicated regex, covers gaps in the generic pattern)
        Matcher redisVersionMatcher = REDIS_VERSION_PATTERN.matcher(text);
        if (redisVersionMatcher.find()) {
            String version = redisVersionMatcher.group(1);
            saveMemory(LongTermMemoryEntity.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .memoryType(TYPE_SOFTWARE_FACT)
                    .memoryKey("redis:version")
                    .content("redis version is " + version)
                    .keywords(joinKeywords(extractKeywords("redis version " + version)))
                    .sourceRole("tool")
                    .confidence(0.82)
                    .hitCount(1)
                    .build());
        }

        // 4. Failure-signal recording (only when the tool execution failed)
        if (!success && looksLikeHighSignalFailure(text)) {
            saveMemory(LongTermMemoryEntity.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .memoryType(TYPE_TROUBLESHOOTING_CASE)
                    .memoryKey(sha1("failure:" + normalize(text)))
                    .content("Historical failure signal: " + text)
                    .keywords(joinKeywords(extractKeywords(text + " " + nullSafe(toolName))))
                    .sourceRole("tool")
                    .confidence(0.68)
                    .hitCount(1)
                    .build());
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * Extract only when the assistant reply contains keywords such as "conclusion", "cause",
     * "recommend", "fix", or "root cause" — avoid storing every intermediate reasoning step,
     * most of which have little memory value.
     */
    @Override
    public void recordAssistantConclusion(String userId, String sessionId, String assistantContent) {
        if (isBlank(userId) || isBlank(assistantContent)) {
            return;
        }

        String normalized = normalize(assistantContent);
        if (!(normalized.contains("conclusion") || normalized.contains("cause") || normalized.contains("recommend") || normalized.contains("fix") || normalized.contains("root cause")
                || normalized.contains("结论") || normalized.contains("原因") || normalized.contains("建议") || normalized.contains("修复") || normalized.contains("根因"))) {
            return;
        }

        String summary = truncate(assistantContent, 400);
        saveMemory(LongTermMemoryEntity.builder()
                .userId(userId)
                .sessionId(sessionId)
                .memoryType(TYPE_TROUBLESHOOTING_CASE)
                .memoryKey(sha1("assistant:" + normalize(summary)))
                .content(summary)
                .keywords(joinKeywords(extractKeywords(summary)))
                .sourceRole("assistant")
                .confidence(0.72)
                .hitCount(1)
                .build());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Two-stage recall (direct from DB, typically more precise than RAG):
     * <ol>
     *   <li>Coarse filter: load the 120 most recent memories from the DB</li>
     *   <li>Fine ranking: keyword-overlap scoring, keep score&gt;0, take Top-N by score then updated-at desc</li>
     * </ol>
     */
    @Override
    public List<LongTermMemoryEntity> queryRelevantMemories(String userId, String query, int limit) {
        if (isBlank(userId)) {
            return List.of();
        }

        // Stage 1: coarse filter — load the 120 most recent memories (could be made configurable)
        List<LongTermMemoryEntity> candidates = longTermMemoryRepository.queryRecentByUserId(userId, 120);
        if (candidates.isEmpty()) {
            return List.of();
        }

        // Stage 2: fine ranking — keyword-overlap scoring
        Set<String> queryKeywords = extractKeywords(query);
        return candidates.stream()
                .map(memory -> new MemoryScore(memory, score(memory, queryKeywords)))
                .filter(scored -> scored.score > 0)
                .sorted(Comparator.comparingInt(MemoryScore::score).reversed()
                        .thenComparing(ms -> ms.memory.getUpdatedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit > 0 ? limit : 5)
                .map(MemoryScore::memory)
                .collect(Collectors.toList());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Output format: one memory per line, "- [type] content"
     */
    @Override
    public String buildMemorySummary(String userId, String query, int limit) {
        List<LongTermMemoryEntity> memories = queryRelevantMemories(userId, query, limit);
        if (memories.isEmpty()) {
            return "";
        }

        return memories.stream()
                .map(memory -> "- [" + memory.getMemoryType() + "] " + truncate(memory.getContent(), 160))
                .collect(Collectors.joining("\n"));
    }

    // ==================== private helpers ====================

    /**
     * Save a long-term memory (try-catch side-channel write; failure does not affect the main flow).
     */
    private void saveMemory(LongTermMemoryEntity memoryEntity) {
        try {
            longTermMemoryRepository.saveOrUpdate(memoryEntity);
        } catch (Exception e) {
            log.warn("Failed to save long-term memory type={}, key={}", memoryEntity.getMemoryType(), memoryEntity.getMemoryKey(), e);
        }
    }

    /**
     * Score how well a memory matches the query keywords.
     * <p>
     * Formula: keyword overlap count × 2 + USER_PREFERENCE bonus (+1).
     * USER_PREFERENCE gets an extra point because preferences apply globally — regardless of what
     * the user asks, a preference like "always add sudo" should be recalled.
     *
     * @param memory         long-term memory
     * @param queryKeywords  query keyword set
     * @return match score; 0 means no match
     */
    private int score(LongTermMemoryEntity memory, Set<String> queryKeywords) {
        if (queryKeywords.isEmpty()) {
            // With no query keywords, only USER_PREFERENCE scores 1 (globally applicable)
            return TYPE_USER_PREFERENCE.equals(memory.getMemoryType()) ? 1 : 0;
        }

        Set<String> memoryKeywords = extractKeywords(nullSafe(memory.getKeywords()) + " " + nullSafe(memory.getContent()));
        int overlap = 0;
        for (String keyword : queryKeywords) {
            if (memoryKeywords.contains(keyword)) {
                overlap += 2;
            }
        }
        // User-preference memories get +1 (globally applicable, should be recalled first)
        if (TYPE_USER_PREFERENCE.equals(memory.getMemoryType())) {
            overlap += 1;
        }
        return overlap;
    }

    /**
     * Keyword extraction: split → filter → deduplicate.
     * <p>
     * <ol>
     *   <li>Split: regex [^a-z0-9_\u4e00-\u9fa5]+, keep letters/digits/underscore/CJK</li>
     *   <li>Filter: drop fragments shorter than 2 and stop words</li>
     *   <li>Deduplicate: LinkedHashSet keeps uniqueness and insertion order</li>
     * </ol>
     *
     * @param text original text
     * @return keyword set
     */
    private Set<String> extractKeywords(String text) {
        if (isBlank(text)) {
            return Set.of();
        }
        String normalized = normalize(text);
        String[] parts = normalized.split("[^a-z0-9_\\u4e00-\\u9fa5]+");
        Set<String> keywords = new LinkedHashSet<>();
        Arrays.stream(parts)
                .map(String::trim)
                .filter(part -> part.length() >= 2)
                .filter(part -> !STOP_WORDS.contains(part))
                .forEach(keywords::add);
        return keywords;
    }

    /**
     * Join keywords into a comma-separated string for the DB keywords column.
     */
    private String joinKeywords(Set<String> keywords) {
        return String.join(",", keywords);
    }

    /**
     * Normalize text: lower-case + replace newlines with spaces + trim.
     */
    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('\n', ' ').trim();
    }

    /**
     * Truncate text; overflow is indicated with "...".
     */
    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...";
    }

    private String nullSafe(String text) {
        return text == null ? "" : text;
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /**
     * Whether tool output contains a high-signal failure (permission denied, connection refused, etc.).
     * Only high-signal failures are worth recording as troubleshooting cases.
     */
    private boolean looksLikeHighSignalFailure(String text) {
        String normalized = normalize(text);
        return normalized.contains("permission denied")
                || normalized.contains("connection refused")
                || normalized.contains("no such file")
                || normalized.contains("not found")
                || normalized.contains("failed")
                || normalized.contains("error");
    }

    /**
     * SHA-1 hash used as the memory_key dedup key.
     * Falls back to hashCode on failure so this never throws.
     */
    private String sha1(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    /** Internal scoring record used for ranking. */
    private record MemoryScore(LongTermMemoryEntity memory, int score) {}

    /** Stop-word set filtered out during keyword extraction to avoid noisy matches. */
    private static final Set<String> STOP_WORDS = new LinkedHashSet<>(List.of(
            "请", "帮", "一下", "这个", "那个", "现在", "需要", "进行", "继续", "问题", "情况",
            "please", "help", "this", "that", "now", "need", "continue", "problem", "situation",
            "the", "and", "for", "with", "from", "into", "have", "has"
    ));
}
