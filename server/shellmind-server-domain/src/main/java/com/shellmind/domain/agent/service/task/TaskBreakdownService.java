package com.shellmind.domain.agent.service.task;

import com.shellmind.domain.agent.model.valobj.task.TaskBreakdownVO;
import com.shellmind.domain.agent.service.ITaskBreakdownService;
import com.shellmind.domain.llm.adapter.port.LlmClient;
import com.shellmind.domain.llm.model.valobj.LlmRequest;
import com.shellmind.domain.llm.model.valobj.LlmTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task-breakdown service implementation.
 *
 * <p>Two layers: rules + LLM:
 * 1. Rule layer: quickly detect complex-task keywords (deploy, architecture, refactor, migrate, etc.), &lt;1ms
 * 2. LLM layer: call a weaker model to produce a structured breakdown, 100-500ms
 *
 * <p>The LLM layer prefers the model the user configured in settings (modelId is passed through)
 * and falls back to the default intent-ai-api weak model only when the user has configured none.
 *
 * <p>Breakdown cache: each sessionId is broken down once; later rounds reuse the result.
 *
 * @author shellmind dev
 * 2026/6/19
 */
@Slf4j
@Service
public class TaskBreakdownService implements ITaskBreakdownService {

    @Resource
    private ObjectMapper objectMapper;

    /** Sampling temperature when falling back to intent-ai-api */
    private static final double FALLBACK_TEMPERATURE = 0.2;

    @Resource
    private LlmClient llmClient;

    private final Map<String, TaskBreakdownVO> breakdownMap = new ConcurrentHashMap<>();

    private static final Pattern COMPLEX_TASK_PATTERN = Pattern.compile(
            "(?i)(部署|安装并配置|搭建|迁移|重构|架构|持续集成|从零|完整|全流程|然后|接着|之后|最后|" +
            "\\b(?:deploy|install and configure|set up|setup|migrate|refactor|architecture|continuous integration)\\b|CI/CD|" +
            "deploy.*and.*config|set.*up|migrate|refactor|build.*pipeline|" +
            "\\b(?:from scratch|end.to.end|full (?:flow|pipeline)|step.by.step|then|next|after that|finally)\\b)"
    );

    /** Sequencing words that hint at a multi-step request (Chinese, or English as whole words) */
    private static final Pattern STEP_SIGNAL_PATTERN = Pattern.compile(
            "然后|接着|之后|最后|(?i)\\b(?:then|next|after that|finally|and then|afterwards)\\b");

    private static final int MIN_SUB_TASKS = 2;
    private static final int MAX_SUB_TASKS = 10;

    private static final String BREAKDOWN_SYSTEM_PROMPT = """
            You are a task-analysis expert. Break the user's complex operations/development request into an ordered list of subtasks.

            Return pure JSON (no markdown code fence) with this structure:
            {
              "summary": "One-sentence description of the overall plan",
              "subTasks": [
                {
                  "title": "Subtask title (concise, at most 20 words)",
                  "description": "Detailed description of what to do",
                  "expectedTools": "Expected tools, e.g. executeCommand, writeFile"
                }
              ]
            }

            Breakdown principles:
            1. Each subtask should be the smallest independently executable unit
            2. Subtasks have a clear order
            3. Subtask count should be between 2 and 8
            4. Prefer the closed loop "check environment → perform operation → verify result"
            5. If the task is simple (one step), return an empty subTasks array

            Return JSON only, with no other text.
            """;

    @Override
    public boolean shouldBreakdown(String userMessage, String sessionId) {
        if (userMessage == null || userMessage.isBlank()) return false;
        if (breakdownMap.containsKey(sessionId)) return false;

        Matcher matcher = COMPLEX_TASK_PATTERN.matcher(userMessage);
        boolean ruleMatch = matcher.find();

        long stepSignals = STEP_SIGNAL_PATTERN.matcher(userMessage).results().count();

        boolean need = ruleMatch || stepSignals >= 1;
        log.info("Task-breakdown check: sessionId={}, ruleMatch={}, stepSignals={}, result={}",
                sessionId, ruleMatch, stepSignals, need);
        return need;
    }

    @Override
    public TaskBreakdownVO breakdown(String userMessage, String sessionId, String agentId,
                                      List<Map<String, Object>> messageHistory) {
        return breakdown(userMessage, sessionId, agentId, messageHistory, null);
    }

    @Override
    public TaskBreakdownVO breakdown(String userMessage, String sessionId, String agentId,
                                      List<Map<String, Object>> messageHistory, Long modelId) {
        log.info("Running task breakdown: sessionId={}, agentId={}, modelId={}", sessionId, agentId, modelId);

        TaskBreakdownVO result = new TaskBreakdownVO();
        result.setOriginalRequest(userMessage);
        result.setNeedConfirmation(true);

        try {
            String prompt = BREAKDOWN_SYSTEM_PROMPT + "\n\nUser request: " + userMessage;

            Optional<String> response = llmClient.complete(
                    new LlmRequest(LlmTarget.auxiliary(modelId), null, prompt, FALLBACK_TEMPERATURE));
            if (response.isEmpty()) {
                log.warn("Task breakdown has no available model (user has not configured one and fallback is unavailable), skipping LLM breakdown");
                result.setSummary("Weak model unavailable, execute directly");
                result.setSubTasks(Collections.emptyList());
                return result;
            }
            String content = response.get();

            log.info("Task-breakdown LLM response: {}", content.length() > 500 ? content.substring(0, 500) + "..." : content);

            String json = extractJson(content);
            if (json == null) {
                log.warn("No valid JSON found in LLM response, skipping breakdown");
                result.setSummary("Could not parse breakdown result, execute directly");
                result.setSubTasks(Collections.emptyList());
                return result;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            String summary = (String) parsed.get("summary");
            List<Map<String, Object>> subTaskList = (List<Map<String, Object>>) parsed.get("subTasks");

            if (subTaskList == null || subTaskList.isEmpty()) {
                log.info("LLM judged the task simple, no breakdown needed");
                result.setSummary(summary != null ? summary : "Task is simple, execute directly");
                result.setSubTasks(Collections.emptyList());
                return result;
            }

            List<TaskBreakdownVO.SubTask> subTasks = new ArrayList<>();
            for (int i = 0; i < subTaskList.size() && i < MAX_SUB_TASKS; i++) {
                Map<String, Object> st = subTaskList.get(i);
                subTasks.add(TaskBreakdownVO.SubTask.builder()
                        .index(i + 1)
                        .title((String) st.getOrDefault("title", "Subtask " + (i + 1)))
                        .description((String) st.getOrDefault("description", ""))
                        .expectedTools((String) st.getOrDefault("expectedTools", ""))
                        .status("pending")
                        .build());
            }

            if (subTasks.size() < MIN_SUB_TASKS) {
                log.info("Broken-down subtask count {} < {}, skipping breakdown", subTasks.size(), MIN_SUB_TASKS);
                result.setSummary("Task is simple, execute directly");
                result.setSubTasks(Collections.emptyList());
                return result;
            }

            result.setSummary(summary);
            result.setSubTasks(subTasks);
            breakdownMap.put(sessionId, result);

            log.info("Task breakdown complete: sessionId={}, subTasks={}", sessionId, subTasks.size());
            return result;

        } catch (Exception e) {
            log.error("Task breakdown failed", e);
            result.setSummary("Breakdown failed: " + e.getMessage());
            result.setSubTasks(Collections.emptyList());
            return result;
        }
    }

    @Override
    public void updateSubTaskStatus(String sessionId, int subTaskIndex, String status, String result) {
        TaskBreakdownVO breakdown = breakdownMap.get(sessionId);
        if (breakdown == null || breakdown.getSubTasks() == null) return;

        for (TaskBreakdownVO.SubTask st : breakdown.getSubTasks()) {
            if (st.getIndex() == subTaskIndex) {
                st.setStatus(status);
                if (result != null) {
                    st.setResult(result);
                }
                log.info("Subtask status updated: sessionId={}, index={}, status={}", sessionId, subTaskIndex, status);
                break;
            }
        }
    }

    @Override
    public TaskBreakdownVO getBreakdown(String sessionId) {
        return breakdownMap.get(sessionId);
    }

    @Override
    public void clearBreakdown(String sessionId) {
        breakdownMap.remove(sessionId);
    }

    private String extractJson(String content) {
        if (content == null || content.isBlank()) return null;

        String trimmed = content.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        trimmed = trimmed.trim();

        try {
            objectMapper.readTree(trimmed);
            return trimmed;
        } catch (Exception ignored) {
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String extracted = trimmed.substring(start, end + 1);
            try {
                objectMapper.readTree(extracted);
                return extracted;
            } catch (Exception ignored) {
            }
        }

        return null;
    }

}
