package com.shellmind.domain.agent.service.prompt.dynamic;

import com.shellmind.domain.conversation.adapter.repository.IChatHistoryRepository;
import com.shellmind.domain.conversation.model.valobj.MilestoneVO;
import com.shellmind.domain.memory.service.CoreMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Milestone tracker.
 * <p>
 * Enhancements (P1-3 in-conversation learning):
 * 1. Expanded correction-detection patterns (8 kinds → covering all Android detectCorrectionAndMemorize patterns)
 * 2. USER_CORRECTION events trigger CoreMemoryService to write long-term memory
 * 3. Tool errors (tool_error) also trigger memory writes
 *
 * @author ShellMind Teaching Edition
 * 2026/6/26
 */
@Slf4j
@Component
public class MilestoneTracker {

    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    @Resource
    private CoreMemoryService coreMemoryService;

    private static final int MAX_MILESTONES = 50;
    private final Map<String, LinkedList<MilestoneVO>> milestones = new ConcurrentHashMap<>();

    // ── Expanded correction-detection patterns (aligned with Android's 8 patterns) ──
    /** Regret: the user realizes a previous decision was wrong */
    private static final Pattern REGRET_PATTERN = Pattern.compile(
            "(?i)(不对|不是这样|不是这样做的|错了|搞错了|失误|后悔|应该|其实应该|本来应该|that's not right|not like that|that's not how|that's wrong|wrong|mistake|regret|should have|actually should|was supposed to)");

    /** Direction change: the user asks to switch approach */
    private static final Pattern DIRECTION_CHANGE_PATTERN = Pattern.compile(
            "(?i)(换个思路|换种方式|换个方向|改一下|换个方案|试试另一种|不如|还是用|重新来|重来|try a different approach|try another way|change direction|change it|try another plan|try another|rather|use this instead|start over|do it over)");

    /** Direct stop: the user asks to stop the current action */
    private static final Pattern USER_CORRECTION_PATTERN = Pattern.compile(
            "(?i)(不要|别|别再|停|停下来|不用了|取消|算了|don't|do not|stop it|stop|never mind|cancel|forget it|no more)");

    /** Tool-error pattern */
    private static final Pattern TOOL_ERROR_PATTERN = Pattern.compile(
            "(?i)(error|failed|exception|permission denied|not found|refused|timeout|crash|fatal)");

    /** Completion pattern */
    private static final Pattern COMPLETE_PATTERN = Pattern.compile(
            "(?i)(完成了|搞定|结束|好了|没问题|done|finished|complete|that's it|OK|ok|all good|no problem)");

    public void detectAndRecord(String userId, String sessionId, String role, String content) {
        if (sessionId == null || content == null || content.isEmpty()) return;

        MilestoneVO.Type type = null;

        if ("user".equals(role)) {
            if (REGRET_PATTERN.matcher(content).matches()) {
                type = MilestoneVO.Type.TASK_CHANGE;
            } else if (COMPLETE_PATTERN.matcher(content).matches()) {
                type = MilestoneVO.Type.TASK_COMPLETE;
            } else if (USER_CORRECTION_PATTERN.matcher(content).matches()) {
                type = MilestoneVO.Type.USER_CORRECTION;
            } else if (DIRECTION_CHANGE_PATTERN.matcher(content).matches()) {
                type = MilestoneVO.Type.TASK_CHANGE;
            }
        }

        if ("tool".equals(role)) {
            if (TOOL_ERROR_PATTERN.matcher(content).matches()) {
                type = MilestoneVO.Type.ERROR;
            }
        }

        if (type != null) {
            push(sessionId, MilestoneVO.builder()
                    .type(type)
                    .content(truncate(content, 200))
                    .timestamp(System.currentTimeMillis())
                    .build());
            log.info("Milestone recorded: sessionId={}, type={}, content={}", sessionId, type, truncate(content, 100));

            // ── P1-3: in-conversation learning ──
            // USER_CORRECTION and TASK_CHANGE(regret) trigger a core-memory write
            if (type == MilestoneVO.Type.USER_CORRECTION || type == MilestoneVO.Type.TASK_CHANGE) {
                try {
                    coreMemoryService.addCorrectionMemory(userId, content, sessionId);
                    log.info("In-conversation learning: correction written to core memory, sessionId={}", sessionId);
                } catch (Exception e) {
                    log.error("In-conversation learning write failed", e);
                }
            }

            // Tool errors can also be recorded as memory (the user may want to know which commands fail often)
            if (type == MilestoneVO.Type.ERROR) {
                try {
                    String keywords = extractErrorKeywords(content);
                    coreMemoryService.addMemory(userId, "user", "Fact",
                            "Tool execution error: " + truncate(content, 30),
                            keywords, truncate(content, 200), 2, sessionId);
                } catch (Exception e) {
                    log.error("Tool-error memory write failed", e);
                }
            }
        }
    }

    private void push(String sessionId, MilestoneVO milestoneVO) {
        LinkedList<MilestoneVO> list = milestones.computeIfAbsent(sessionId, k -> new LinkedList<>());
        synchronized (list) {
            list.addLast(milestoneVO);
            while (list.size() > MAX_MILESTONES) {
                list.removeFirst();
            }
        }

        // [Phase 5] Persist the milestone to the database asynchronously
        try {
            chatHistoryRepository.saveMilestone(sessionId, milestoneVO);
        } catch (Exception e) {
            log.error("Failed to save milestone", e);
        }
    }

    public List<MilestoneVO> getRecent(String sessionId, int limit) {
        // Prefer the database so the session still works after a restart
        try {
            List<MilestoneVO> recentMilestones = chatHistoryRepository.getRecentMilestones(sessionId, limit);
            if (recentMilestones != null && !recentMilestones.isEmpty()) {
                // Returned newest-first; the LLM needs newest last, so reverse
                List<MilestoneVO> reversed = new ArrayList<>(recentMilestones);
                Collections.reverse(reversed);
                return reversed;
            }
        } catch (Exception e) {
            log.error("Failed to load recent milestones", e);
        }

        // Fall back to in-memory
        LinkedList<MilestoneVO> list = milestones.getOrDefault(sessionId, new LinkedList<>());
        synchronized (list) {
            int from = Math.max(0, list.size() - limit);
            return new ArrayList<>(list.subList(from, list.size()));
        }
    }

    public void clear(String sessionId) {
        milestones.remove(sessionId);
    }

    private boolean matches(String content, String regex) {
        return Pattern.compile(".*(" + regex + ").*").matcher(content).matches();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private String extractErrorKeywords(String content) {
        // Extract key error types from the error message
        String lower = content.toLowerCase();
        List<String> keywords = new ArrayList<>();
        if (lower.contains("nullpointer")) keywords.add("NullPointerException");
        if (lower.contains("timeout")) keywords.add("timeout");
        if (lower.contains("permission")) keywords.add("permission-denied");
        if (lower.contains("connection")) keywords.add("connection-error");
        if (lower.contains("not found")) keywords.add("not-found");
        if (lower.contains("oom")) keywords.add("OOM");
        if (keywords.isEmpty()) keywords.add("error");
        return keywords.stream().reduce((a, b) -> a + "," + b).orElse("error");
    }
}
