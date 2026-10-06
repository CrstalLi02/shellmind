package com.shellmind.cases.react;

import com.shellmind.api.dto.ProjectContextDTO;
import com.shellmind.domain.agent.service.run.TerminalBindingRegistry;
import com.shellmind.domain.shared.model.RunContext;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.shellmind.api.dto.ChatRequestDTO;
import com.shellmind.api.dto.ReActEventDTO;
import com.shellmind.api.dto.ReActResultDTO;
import com.shellmind.cases.react.factory.DefaultReActFactory;
import com.shellmind.types.design.tree.AbstractMultiThreadStrategyRouter;
import com.shellmind.types.design.tree.StrategyHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import com.shellmind.domain.agent.adapter.port.ClientChannel;

import jakarta.annotation.Resource;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * ReAct support class (abstract base).
 *
 * <p>Follows the AbstractAutoAgentSupport design in mobile-claw-case.
 * Encapsulates capabilities shared by the ReAct loop:
 * - context management (DynamicContext)
 * - SSE event emit
 * - tool-call result parsing
 * - response formatting
 *
 * <p>Node routing chain:
 * RootNode → AiCallNode → ToolCallNode → (ToolResultNode) → [loop or complete]
 *
 * @author xiaofuge bugstack.cn
 * 2026/5/4 13:58
 */
@Slf4j
public abstract class AbstractAIAgentReActSupport extends AbstractMultiThreadStrategyRouter<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> {

    @Getter
    @Setter
    protected StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> defaultStrategyHandler = StrategyHandler.DEFAULT;

    @Resource
    protected ApplicationContext applicationContext;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    /** Session → SSH terminal binding, maintained by the client via bind_terminal. */
    @Resource
    protected TerminalBindingRegistry terminalBindingRegistry;

    @Override
    protected void multiThread(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws ExecutionException, InterruptedException, TimeoutException {
        // No async preload is needed yet
    }

    /**
     * Look up a bean by name.
     */
    protected <T> T getBean(String beanName) {
        return applicationContext.getBean(beanName, (Class<T>) Object.class);
    }

    // ═══════════════════════════════════════════════════════════════
    //  run context
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build this round's run context: prefer a terminal ID from the request; otherwise use the session binding.
     * Sub-agents reuse the source agent (not the runtime agent assembled from the user-selected model).
     */
    protected RunContext buildRunContext(DefaultReActFactory.DynamicContext ctx, String agentId) {
        String sessionId = ctx.getSessionId();
        String terminalSessionId = ctx.getTerminalSessionId();
        if (terminalSessionId == null || terminalSessionId.isEmpty()) {
            terminalSessionId = terminalBindingRegistry.terminalOf(sessionId).orElse(null);
        }
        ProjectContextDTO projectContext = ctx.getProjectContext();
        String rootPath = projectContext != null ? projectContext.getRootPath() : null;
        Path workspace = rootPath != null && !rootPath.isBlank()
                ? Paths.get(rootPath).toAbsolutePath().normalize()
                : null;
        String sourceAgentId = ctx.getSourceAgentId() != null ? ctx.getSourceAgentId() : agentId;
        return new RunContext(
                sessionId,
                ctx.getUserId(),
                sourceAgentId,
                ctx.getLoopState() != null ? ctx.getLoopState().getRunId() : null,
                ctx.isReadOnlyExecution(),
                workspace,
                terminalSessionId,
                false,
                null);
    }

    // ═══════════════════════════════════════════════════════════════
    //  SSE emit helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Send a text event.
     * @return true if sent successfully, false if send failed (client has disconnected)
     */
    protected boolean sendTextEvent(ClientChannel emitter, String content, String fullText, DefaultReActFactory.DynamicContext ctx) {
        try {
            // Do not normalize during streaming — ReactMarkdown can render incremental intermediate states.
            // Normalize once on done (UserFeedbackNode.buildFinalResult) so the transform stays idempotent.
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("text");
            event.setContent(content);
            event.setFullText(fullText);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            // Text event sent successfully → refresh LoopState last-active time so the idle checker does not false-kill during long generation
            com.shellmind.domain.agent.service.engine.LoopState ls = ctx.getLoopState();
            if (ls != null) {
                ls.touch();
            }
            log.info("Sending text event {}", event);
            return true;
        } catch (Exception e) {
            log.warn("Failed to send text event: {}, marking task cancelled", e.getMessage());
            if (ctx != null) {
                ctx.markCancelled("send_text_failed");
            }
            return false;
        }
    }

    /**
     * Send a tool-call event.
     * @return true if sent successfully, false if send failed
     */
    protected boolean sendToolCallEvent(ClientChannel emitter, String toolCallId, String toolName, String status, DefaultReActFactory.DynamicContext ctx) {
        return sendToolCallEventWithArgs(emitter, toolCallId, toolName, "", status, ctx);
    }

    /**
     * Send a tool-call event including args.
     * <p>In ADK auto-execution mode the tool name and args must be extracted from the stateDelta value,
     * because ADK uses the agent's outputKey as the stateDelta key rather than the tool name.
     *
     * @param toolArgs tool-call args (e.g. command string, file path)
     * @return true if sent successfully, false if send failed
     */
    protected boolean sendToolCallEventWithArgs(ClientChannel emitter, String toolCallId, String toolName, String toolArgs, String status, DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("tool_call");
            event.setToolCallId(toolCallId);
            event.setToolName(toolName);
            event.setArgs(toolArgs);
            event.setStatus(status);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("Sending tool-call event: toolName={}, args={}, status={}", toolName, toolArgs != null && toolArgs.length() > 100 ? toolArgs.substring(0, 100) + "..." : toolArgs, status);
            return true;
        } catch (Exception e) {
            log.warn("Failed to send tool-call event: {}, marking task cancelled", e.getMessage());
            if (ctx != null) {
                ctx.markCancelled("send_tool_call_failed");
            }
            return false;
        }
    }

    /**
     * Send a tool-result event.
     * @return true if sent successfully, false if send failed
     */
    protected boolean sendToolResultEvent(ClientChannel emitter, String toolCallId, String content, String status, DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("tool_result");
            event.setToolCallId(toolCallId);
            event.setContent(content);
            event.setStatus(status);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("Sending tool-result event {}", event);
            return true;
        } catch (Exception e) {
            log.warn("Failed to send tool-result event: {}, marking task cancelled", e.getMessage());
            if (ctx != null) {
                ctx.markCancelled("send_tool_result_failed");
            }
            return false;
        }
    }

    /**
     * Send a round-end event.
     * @return true if sent successfully, false if send failed
     */
    protected boolean sendRoundEndEvent(ClientChannel emitter, int currentStep, int maxSteps, boolean shouldContinue, int totalToolCalls, DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO.StepInfo stepInfo = new ReActEventDTO.StepInfo();
            stepInfo.setCurrentStep(currentStep);
            stepInfo.setMaxSteps(maxSteps);
            stepInfo.setShouldContinue(shouldContinue);
            stepInfo.setTotalToolCalls(totalToolCalls);

            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("round_end");
            event.setStepInfo(stepInfo);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("Sending round_end event {}", event);
            return true;
        } catch (Exception e) {
            log.warn("Failed to send round_end event: {}, marking task cancelled", e.getMessage());
            if (ctx != null) {
                ctx.markCancelled("send_round_end_failed");
            }
            return false;
        }
    }

    /**
     * Send a done event, including a file-change summary.
     * @return true if sent successfully, false if send failed
     */
    protected boolean sendDoneEvent(ClientChannel emitter, ReActResultDTO result, DefaultReActFactory.DynamicContext ctx) {
        try {
            // Done-event content is already the final reply text on ReActResultDTO.content.
            // Pass it through as-is; do not extra-process here.
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("done");
            event.setContent(objectMapper.writeValueAsString(result));
            // Extract a file-change summary from tool-call results
            ReActEventDTO.ChangeSummaryDTO changeSummary = extractChangeSummary(result);
            if (changeSummary != null) {
                event.setChangeSummary(changeSummary);
            }
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("Sending done event {}", event);
            return true;
        } catch (Exception e) {
            log.warn("Failed to send done event: {}, marking task cancelled", e.getMessage());
            if (ctx != null) {
                ctx.markCancelled("send_done_failed");
            }
            return false;
        }
    }

    /**
     * Extract a file-change summary from the ReAct result.
     * <p>Scan path fields in toolResults and classify by operation type.
     */
    private ReActEventDTO.ChangeSummaryDTO extractChangeSummary(ReActResultDTO result) {
        if (result == null || result.getToolResults() == null || result.getToolResults().isEmpty()) {
            return null;
        }

        java.util.List<ReActEventDTO.ChangeFile> created = new java.util.ArrayList<>();
        java.util.List<ReActEventDTO.ChangeFile> modified = new java.util.ArrayList<>();
        java.util.List<ReActEventDTO.ChangeFile> deleted = new java.util.ArrayList<>();
        java.util.Set<String> seenPaths = new java.util.HashSet<>();

        for (java.util.Map<String, Object> toolResult : result.getToolResults()) {
            String toolName = String.valueOf(toolResult.getOrDefault("name", toolResult.getOrDefault("toolName", "")));
            String content = String.valueOf(toolResult.getOrDefault("content", ""));

            // Try to parse a path from content
            String path = extractPathFromContent(content);
            if (path == null || !seenPaths.add(path)) continue;

            String kind = classifyChange(toolName, path, content);
            if (kind == null) continue;

            ReActEventDTO.ChangeFile changeFile = new ReActEventDTO.ChangeFile();
            changeFile.setPath(path);
            changeFile.setKind(kind);

            // simple line-count stats
            int[] lineStats = estimateLineStats(content);
            changeFile.setAddedLines(lineStats[0]);
            changeFile.setRemovedLines(lineStats[1]);

            switch (kind) {
                case "create": created.add(changeFile); break;
                case "delete": deleted.add(changeFile); break;
                default: modified.add(changeFile); break;
            }
        }

        if (created.isEmpty() && modified.isEmpty() && deleted.isEmpty()) {
            return null;
        }

        ReActEventDTO.ChangeSummaryDTO summary = new ReActEventDTO.ChangeSummaryDTO();
        summary.setCreated(created);
        summary.setModified(modified);
        summary.setDeleted(deleted);
        summary.setDescription("Changed " + (created.size() + modified.size() + deleted.size()) + " file(s)");
        return summary;
    }

    /**
     * Extract a file path from tool-result content.
     */
    private String extractPathFromContent(String content) {
        if (content == null || content.isEmpty()) return null;
        // Try JSON parse
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(content);
            if (node.has("path")) return node.get("path").asText();
            if (node.has("filePath")) return node.get("filePath").asText();
            if (node.has("file")) return node.get("file").asText();
        } catch (Exception ignored) {
        }
        // regex match "path": "..."
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"path\"\s*:\s*\"([^\"]+)\"").matcher(content);
        if (m.find()) return m.group(1);
        return null;
    }

    /**
     * classify the change kind from the tool name and path
     */
    private String classifyChange(String toolName, String path, String content) {
        String lowerTool = toolName.toLowerCase();
        String lowerContent = content.toLowerCase();
        if (!isFileMutationTool(lowerTool) || content.contains("\"items\"")) {
            return null;
        }
        if (lowerTool.contains("create") || lowerTool.contains("write") || lowerContent.contains("created") || lowerContent.contains("新建")) {
            return "create";
        }
        if (lowerTool.contains("delete") || lowerTool.contains("remove") || lowerContent.contains("deleted") || lowerContent.contains("删除")) {
            return "delete";
        }
        return "modify";
    }

    private boolean isFileMutationTool(String lowerToolName) {
        return lowerToolName.contains("write")
                || lowerToolName.contains("create")
                || lowerToolName.contains("delete")
                || lowerToolName.contains("remove")
                || lowerToolName.contains("edit")
                || lowerToolName.contains("save")
                || lowerToolName.contains("upload")
                || lowerToolName.contains("rollback");
    }

    /**
     * Rough line-count estimate.
     */
    private int[] estimateLineStats(String content) {
        if (content == null || content.isEmpty()) return new int[]{0, 0};
        int added = 0, removed = 0;
        for (String line : content.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) added++;
            else if (line.startsWith("-") && !line.startsWith("---")) removed++;
        }
        return new int[]{added, removed};
    }

    /**
     * Send a warning event (non-fatal).
     * @return true if sent successfully, false if send failed
     */
    protected boolean sendWarningEvent(ClientChannel emitter, String message, DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("warning");
            event.setContent(message);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.warn("Sending warning event: {}", message);
            return true;
        } catch (Exception e) {
            log.warn("Failed to send warning event: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Send a subtask-progress event.
     * @return true if sent successfully, false if send failed
     */
    protected boolean sendTaskProgressEvent(ClientChannel emitter, int subTaskIndex, String subTaskTitle,
                                          String status, int totalSubTasks, int completedSubTasks,
                                          DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO.TaskProgress progress = new ReActEventDTO.TaskProgress();
            progress.setSubTaskIndex(subTaskIndex);
            progress.setSubTaskTitle(subTaskTitle);
            progress.setStatus(status);
            progress.setTotalSubTasks(totalSubTasks);
            progress.setCompletedSubTasks(completedSubTasks);

            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("task_progress");
            event.setTaskProgress(progress);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("Sending task_progress event: index={}, status={}/{}, completed={}",
                    subTaskIndex, status, totalSubTasks, completedSubTasks);
            return true;
        } catch (Exception e) {
            log.warn("Failed to send task_progress event: {}, marking task cancelled", e.getMessage());
            if (ctx != null) {
                ctx.markCancelled("send_task_progress_failed");
            }
            return false;
        }
    }

    /**
     * Send a permission-confirm event (CONFIRM-level tools need user confirmation).
     * <p>The frontend shows PermissionConfirmModal and writes the approve/deny result back.
     *
     * @param confirmId  unique confirm-request ID
     * @param toolName   tool name
     * @param toolArgs   tool args (command/path, etc.)
     * @param riskLevel  risk level: DENY / CONFIRM / ALLOW
     * @param reason     risk reason
     * @param timeoutMs  timeout in milliseconds; 0 = no timeout
     * @return true if sent successfully, false if send failed
     */
    protected boolean sendPermissionConfirmEvent(ClientChannel emitter, String confirmId,
                                                 String toolName, String toolArgs, String riskLevel,
                                                 String reason, long timeoutMs,
                                                 DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO.PermissionInfo permission = new ReActEventDTO.PermissionInfo();
            permission.setConfirmId(confirmId);
            permission.setToolName(toolName);
            permission.setToolArgs(toolArgs);
            permission.setRiskLevel(riskLevel);
            permission.setReason(reason);
            permission.setTimeoutMs(timeoutMs);

            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("permission_confirm");
            event.setPermission(permission);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("Sending permission_confirm event: tool={}, risk={}, confirmId={}", toolName, riskLevel, confirmId);
            return true;
        } catch (Exception e) {
            log.warn("Failed to send permission_confirm event: {}, marking task cancelled", e.getMessage());
            if (ctx != null) {
                ctx.markCancelled("send_permission_confirm_failed");
            }
            return false;
        }
    }

    /**
     * Send a live tool-output event (incremental stdout/stderr from a long-running command).
     *
     * @param toolCallId  tool-call ID (associated with the tool_call event)
     * @param outputChunk output chunk
     * @param ctx         dynamic context
     * @return true if sent successfully, false if send failed
     */
    protected boolean sendToolOutputEvent(ClientChannel emitter, String toolCallId,
                                          String outputChunk, DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("tool_output");
            event.setToolCallId(toolCallId);
            event.setOutputChunk(outputChunk);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            return true;
        } catch (Exception e) {
            log.warn("Failed to send tool_output event: {}", e.getMessage());
            // A failed tool_output does not cancel the task; drop only that chunk
            return false;
        }
    }

    /**
     * Send a status-update event (context compression / fallback / reconnect, etc.).
     *
     * @param message status description
     * @param ctx     dynamic context
     * @return true if sent successfully, false if send failed
     */
    protected boolean sendStatusEvent(ClientChannel emitter, String message,
                                      DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("status");
            event.setStatusMessage(message);
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("Sending status event: {}", message);
            return true;
        } catch (Exception e) {
            log.warn("Failed to send status event: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Send a round-start event.
     *
     * @param roundIndex current round (1-based)
     * @param ctx        dynamic context
     * @return true if sent successfully, false if send failed
     */
    protected boolean sendRoundStartEvent(ClientChannel emitter, int roundIndex,
                                          DefaultReActFactory.DynamicContext ctx) {
        try {
            ReActEventDTO event = new ReActEventDTO();
            event.setEvent("round_start");
            event.setContent(String.valueOf(roundIndex));
            emitter.send(objectMapper.writeValueAsString(event) + "\n");
            log.info("Sending round_start event: round={}", roundIndex);
            return true;
        } catch (Exception e) {
            log.warn("Failed to send round_start event: {}", e.getMessage());
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Tool-call result parsing (see mobile-claw-case)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse an action string from the AI response.
     * Compatible formats:
     * 1. JSON: ```json { "action": "..." } ```
     * 2. Content wrapped in an &lt;answer&gt;...&lt;/answer&gt; tag
     * 3. DSL: do(action=...) / finish(message=...)
     */
    protected String parseActionString(String response) {
        if (response == null || response.isBlank()) return null;

        String contentToParse = response;

        // 1. Extract <answer>...</answer> tag
        java.util.regex.Pattern answerPattern = java.util.regex.Pattern.compile("<answer>(.*?)</answer>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher answerMatcher = answerPattern.matcher(response);
        if (answerMatcher.find()) {
            contentToParse = answerMatcher.group(1).trim();
        }

        // 2. Try JSON
        try {
            com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(contentToParse);
            if (jsonNode.has("action")) {
                return jsonNode.get("action").asText();
            }
        } catch (Exception ignored) {
        }

        // 3. Try a markdown JSON code block
        java.util.regex.Pattern jsonPattern = java.util.regex.Pattern.compile("```json(.*?)```", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher jsonMatcher = jsonPattern.matcher(contentToParse);
        if (jsonMatcher.find()) {
            try {
                com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(jsonMatcher.group(1).trim());
                if (jsonNode.has("action")) {
                    return jsonNode.get("action").asText();
                }
            } catch (Exception ignored) {
            }
        }

        // 4. Try DSL
        java.util.regex.Pattern dslPattern = java.util.regex.Pattern.compile("(do|finish)\\s*\\(\\s*(action|message)\\s*=", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher dslMatcher = dslPattern.matcher(contentToParse);
        int lastStart = -1;
        while (dslMatcher.find()) {
            lastStart = dslMatcher.start();
        }
        if (lastStart != -1) {
            String action = contentToParse.substring(lastStart).trim();
            if (action.endsWith("```")) {
                action = action.substring(0, action.length() - 3).trim();
            }
            return action;
        }

        return null;
    }

    /**
     * Whether the response contains tool calls (used to decide whether to continue the loop).
     * Compatible with two formats:
     * - ShellMind style: parse tool_calls JSON directly
     * - mobile-claw-case style: parse an action string
     */
    protected boolean hasToolCalls(String response) {
        if (response == null || response.isBlank()) return false;

        // 1. Check <answer> tag content
        java.util.regex.Pattern answerPattern = java.util.regex.Pattern.compile("<answer>(.*?)</answer>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher answerMatcher = answerPattern.matcher(response);
        if (answerMatcher.find()) {
            String content = answerMatcher.group(1).trim();
            return containsToolCallSign(content);
        }

        return containsToolCallSign(response);
    }

    private boolean containsToolCallSign(String content) {
        // Check do(...) / finish(...) DSL pattern
        if (java.util.regex.Pattern.compile("(do|finish)\\s*\\(").matcher(content).find()) {
            return true;
        }
        // Check tool_calls JSON structure
        if (content.contains("tool_calls") || content.contains("\"action\"")) {
            return true;
        }
        return false;
    }

    /**
     * Whether the loop should stop.
     * Stop conditions: finish / max_steps / user_stop / error.
     */
    protected boolean shouldTerminate(String response, int currentStep, int maxSteps) {
        if (response == null || response.isBlank()) return false;

        String actionStr = parseActionString(response);

        // finish → stop
        if (actionStr != null && actionStr.startsWith("finish")) {
            return true;
        }

        // Reached max steps → stop
        if (currentStep >= maxSteps) {
            return true;
        }

        // Detect error keywords
        String lower = response.toLowerCase();
        if (lower.contains("error:") || lower.contains("failed:")) {
            return true;
        }

        return false;
    }

}
