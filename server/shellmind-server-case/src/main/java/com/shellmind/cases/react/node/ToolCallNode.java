package com.shellmind.cases.react.node;

import com.shellmind.api.dto.ChatRequestDTO;
import com.shellmind.api.dto.ReActResultDTO;
import com.shellmind.domain.agent.service.IPromptService;
import com.shellmind.domain.agent.service.IChatContextService;
import com.shellmind.domain.memory.service.ILongTermMemoryService;
import com.shellmind.domain.conversation.adapter.repository.IChatHistoryRepository;
import com.shellmind.domain.conversation.model.entity.ChatMessageEntity;
import com.shellmind.domain.policy.service.PermissionGuard;
import com.shellmind.cases.react.AbstractAIAgentReActSupport;
import com.shellmind.cases.react.PermissionConfirmManager;
import com.shellmind.cases.react.factory.DefaultReActFactory;
import com.shellmind.domain.agent.service.tool.RemoteCommandToolService;
import com.shellmind.types.design.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.shellmind.domain.agent.adapter.port.ClientChannel;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct tool-execution node.
 *
 * <p>Responsibilities:
 * 1. Read the tool-call list returned by the AI from context (set by AiCallNode)
 * 2. Check whether ADK already auto-executed the tool (FunctionResponse already present)
 * 3. If not executed, run tools manually
 * 4. Append tool results to message history
 * 5. Send tool_call / tool_result SSE events
 * 6. Route: back to AiCallNode to continue, or to LoopDecisionNode to complete
 *
 * <p>Tool execution modes:
 * <ul>
 *   <li>ADK auto-execution mode: ADK runner.runAsync() executes tools internally;
 *       ToolCallNode only handles existing results and routes</li>
 *   <li>Manual-execution mode (future extension): ToolCallNode calls
 *       RemoteCommandToolService and other domain tool services directly</li>
 * </ul>
 *
 * <p>ReAct loop chain:
 * <pre>
 * RootNode
 *   └→ AiCallNode (call the model, parse FunctionCalls)
 *         └→ ToolCallNode (handle tool results)
 *               └→ [has tool results] back to AiCallNode to continue
 *               └→ [no tool calls] LoopDecisionNode
 *                     └→ UserFeedbackNode
 * </pre>
 *
 * @author xiaofuge bugstack.cn
 * 2026/5/4
 */
@Slf4j
@Component("reactToolCallNode")
public class ToolCallNode extends AbstractAIAgentReActSupport {

    @Resource
    private RemoteCommandToolService remoteCommandToolService;
    
    @Resource
    private IPromptService promptService;
    
    @Resource
    private IChatContextService chatContextService;
    
    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    @Resource
    private PermissionGuard permissionGuard;

    @Resource
    private PermissionConfirmManager permissionConfirmManager;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        List<Map<String, Object>> toolCalls = dynamicContext.getCurrentToolCalls();
        List<Map<String, Object>> toolResults = dynamicContext.getCurrentToolResults();

        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("ReAct ToolCallNode - no tool calls, skipping");
            return router(requestParameter, dynamicContext);
        }

        log.info("ReAct ToolCallNode - handling {} tool calls, {} results already present",
                toolCalls.size(), toolResults != null ? toolResults.size() : 0);

        ClientChannel emitter = dynamicContext.getEmitter();

        // Check whether ADK already auto-executed (FunctionResponse already returned)
        boolean adkAutoExecuted = toolResults != null && !toolResults.isEmpty();

        if (adkAutoExecuted) {
            // ─── ADK auto-execution mode ───
            // Tool was already executed inside ADK runner.runAsync(); results are already in currentToolResults
            log.info("Tool already auto-executed by ADK; processing existing results");
            handleAdkToolResults(dynamicContext, toolCalls, toolResults);
        } else {
            // ─── manual-execution mode ───
            // Tool was not auto-executed; run it manually (future-extension path)
            log.info("Tool not executed; running it manually");
            handleManualToolExecution(dynamicContext, toolCalls, emitter);
        }

        // Route
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {

        List<Map<String, Object>> toolCalls = dynamicContext.getCurrentToolCalls();

        // Has tool calls (already executed) → back to AiCallNode to continue
        if (toolCalls != null && !toolCalls.isEmpty()) {
            // Check whether max steps was reached
            if (dynamicContext.getStep() >= dynamicContext.getMaxSteps()) {
                log.info("Reached max steps {}, routing to LoopDecisionNode", dynamicContext.getMaxSteps());
                dynamicContext.setStopReason("max_steps");
                return getBean("reactLoopDecisionNode");
            }

            log.info("Tool-call handling complete; returning to AiCallNode");
            return getBean("reactAiCallNode");
        }

        // no tool calls → route to LoopDecisionNode
        log.info("No tool calls; routing to LoopDecisionNode");
        return getBean("reactLoopDecisionNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  ADK auto-execution mode (current primary path)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Handle ADK auto-executed tool results.
     * <p>ADK runner.runAsync() already ran the tools; FunctionResponse was returned in the event stream.
     * Add a post-hoc safety audit here: detect dangerous commands that already ran and record a warning.
     */
    private void handleAdkToolResults(DefaultReActFactory.DynamicContext dynamicContext,
                                       List<Map<String, Object>> toolCalls,
                                       List<Map<String, Object>> toolResults) {

        Map<String, Map<String, Object>> resultMap = new HashMap<>();
        for (Map<String, Object> result : toolResults) {
            String id = (String) result.get("id");
            if (id != null) {
                resultMap.put(id, result);
            }
        }

        for (Map<String, Object> toolCall : toolCalls) {
            String toolCallId = (String) toolCall.get("id");
            String toolName = (String) toolCall.get("name");
            String argsStr = (String) toolCall.get("args");

            Map<String, Object> matchedResult = resultMap.get(toolCallId);
            if (matchedResult != null) {
                String content = (String) matchedResult.get("content");
                log.info("ADK tool result: id={}, name={}, result_length={}",
                        toolCallId, toolName, content != null ? content.length() : 0);

                // ── Post-hoc safety audit ──
                // ADK already auto-executed the tool, so this cannot prevent it, but we can still detect and record dangerous operations
                PermissionGuard.PermissionDecision postAudit = permissionGuard.check(
                        dynamicContext.getSessionId(), toolName, argsStr);
                if (postAudit.isDenied()) {
                    log.error("[Security audit] ADK auto-executed a rejected tool! tool={}, rule={}, reason={}, args={}",
                            toolName, postAudit.getRuleName(), postAudit.getReason(),
                            argsStr != null && argsStr.length() > 200 ? argsStr.substring(0, 200) + "..." : argsStr);
                    // Inject a security warning into dynamic context so the next-round AI sees it and stops
                    dynamicContext.appendToolMessage(toolCallId,
                            "⚠️ [Security audit warning] Tool " + toolName + " executed an operation rejected by the security policy: "
                                    + postAudit.getReason() + ". Stop any further dangerous operations immediately.");
                }
            } else {
                log.warn("Tool result not found: id={}, name={}", toolCallId, toolName);
            }
        }

        // Clear this round's tool-call markers to avoid a duplicate route
        dynamicContext.getCurrentToolCalls().clear();
    }

    // ═══════════════════════════════════════════════════════════════
    //  Manual-execution mode (future extension)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Manually execute tool calls.
     * <p>When ADK did not auto-execute the tool, ToolCallNode runs it directly.
     * <p>Used for: custom tools, MCP tools, and cases that need pre/post processing.
     */
    private void handleManualToolExecution(DefaultReActFactory.DynamicContext dynamicContext,
                                            List<Map<String, Object>> toolCalls,
                                            ClientChannel emitter) throws Exception {

        for (Map<String, Object> toolCall : toolCalls) {
            String toolCallId = (String) toolCall.get("id");
            String toolName = (String) toolCall.get("name");
            String argsStr = (String) toolCall.get("args");

            if (toolCallId == null || toolName == null) {
                log.warn("Incomplete tool-call info: {}", toolCall);
                continue;
            }

            // [Phase 2] permission check
            PermissionGuard.PermissionDecision decision = permissionGuard.check(
                    dynamicContext.getSessionId(), toolName, argsStr);

            if (decision.isDenied()) {
                // deny execution
                log.warn("Tool blocked by permission: tool={}, rule={}, reason={}",
                        toolName, decision.getRuleName(), decision.getReason());
                String denyMsg = "⛔ " + decision.getReason();

                Map<String, Object> toolResult = new HashMap<>();
                toolResult.put("id", toolCallId);
                toolResult.put("name", toolName);
                toolResult.put("content", denyMsg);
                toolResult.put("status", "denied");
                dynamicContext.getCurrentToolResults().add(toolResult);

                dynamicContext.appendToolMessage(toolCallId, denyMsg);
                sendToolResultEvent(emitter, toolCallId, denyMsg, "denied", dynamicContext);
                continue;
            }

            if (decision.needsConfirmation()) {
                // Needs user confirmation — blocking wait
                log.info("Tool requires confirmation: tool={}, rule={}, command={}",
                        toolName, decision.getRuleName(), decision.getCommand());

                // Send permission_confirm SSE event
                String confirmId = toolCallId + "_confirm_" + System.currentTimeMillis();
                sendPermissionConfirmEvent(
                        emitter,
                        confirmId,
                        toolName,
                        decision.getCommand() != null ? decision.getCommand() : argsStr,
                        decision.getAction().name(),
                        decision.getReason(),
                        30_000L, // 30 s timeout
                        dynamicContext
                );

                // Block and wait for the user confirmation result
                PermissionConfirmManager.PermissionResolveResult resolveResult =
                        permissionConfirmManager.awaitConfirmation(confirmId, 30_000L);

                if (resolveResult == null || !resolveResult.isApproved()) {
                    // User denied or timed out
                    String denyMsg = resolveResult == null
                            ? "⛔ Confirmation timed out; the operation was cancelled"
                            : "⛔ The user rejected the operation: " + decision.getReason();
                    log.info("Permission confirmation did not pass: tool={}, timeout={}", toolName, resolveResult == null);

                    Map<String, Object> toolResult = new HashMap<>();
                    toolResult.put("id", toolCallId);
                    toolResult.put("name", toolName);
                    toolResult.put("content", denyMsg);
                    toolResult.put("status", "denied");
                    dynamicContext.getCurrentToolResults().add(toolResult);

                    dynamicContext.appendToolMessage(toolCallId, denyMsg);
                    sendToolResultEvent(emitter, toolCallId, denyMsg, "denied", dynamicContext);
                    continue;
                }

                // user confirmation — if args were modified, use the modified ones
                if (resolveResult.getModifiedArgs() != null && !resolveResult.getModifiedArgs().isEmpty()) {
                    argsStr = resolveResult.getModifiedArgs();
                    log.info("User modified args: tool={}, newArgs={}", toolName, argsStr);
                }
                permissionGuard.recordConfirmation(dynamicContext.getSessionId(), true);
            }

            // Send tool_call executing event
            sendToolCallEvent(emitter, toolCallId, toolName, "executing", dynamicContext);

            // run tools
            String resultContent;
            String status = "success";
            try {
                resultContent = executeTool(toolName, argsStr, toolCallId, emitter, dynamicContext);
                log.info("Tool succeeded: name={}, result_length={}", toolName, resultContent.length());
            } catch (Exception e) {
                log.error("Tool failed: name={}", toolName, e);
                resultContent = "Error executing tool '" + toolName + "': " + e.getMessage();
                status = "error";
            }

            // Redact + truncate
            resultContent = com.shellmind.domain.policy.service.SecretRedactor.redact(resultContent);
            resultContent = truncateToolResponse(resultContent, 4000);

            // Store the tool result on context
            Map<String, Object> toolResult = new HashMap<>();
            toolResult.put("id", toolCallId);
            toolResult.put("name", toolName);
            toolResult.put("content", resultContent);
            toolResult.put("status", status);
            dynamicContext.getCurrentToolResults().add(toolResult);

            // Append a tool message to history (for the next-round AI call)
            dynamicContext.appendToolMessage(toolCallId, resultContent);

            // record milestones and tool-execution summaries
            promptService.detectAndRecordMilestone(dynamicContext.getUserId(), dynamicContext.getSessionId(), "tool", resultContent);
            chatContextService.pushToolResult(dynamicContext.getSessionId(), toolName, resultContent);

            // [Phase 5] save tool-result messages to the database
            chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                    .sessionId(dynamicContext.getSessionId())
                    .role("tool")
                    .content(resultContent)
                    .toolName(toolName)
                    .toolCallId(toolCallId)
                    .priority("MEDIUM")
                    .tokenCount(resultContent.length() / 2)
                    .build());

            // Long-term memory extraction (tool side): recognize OS, software version, and high-signal failures from output
            longTermMemoryService.recordToolObservation(
                    dynamicContext.getUserId(), dynamicContext.getSessionId(),
                    toolName, resultContent, "success".equals(status));

            // Send tool_result SSE event
            sendToolResultEvent(emitter, toolCallId, resultContent, status, dynamicContext);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  tool execution
    // ═══════════════════════════════════════════════════════════════

    /**
     * Execute the matching tool from the tool name and args.
     */
    private String executeTool(String toolName, String argsStr,
                                String toolCallId,
                                ClientChannel emitter,
                                DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Manually executing tool: name={}, args={}", toolName, argsStr);

        switch (toolName) {
            case "executeCommand":
            case "execute_command":
            case "run_command":
                return executeSshTool(argsStr, toolCallId, emitter, dynamicContext);
            default:
                log.warn("Unknown tool: {}", toolName);
                return "Unknown tool: " + toolName + ". Available tools: executeCommand";
        }
    }

    /**
     * Execute an SSH tool (streaming output).
     * <p>Calls RemoteCommandToolService.executeCommandStreaming() to run the SSH command,
     * and pushes each output chunk to the frontend live via sendToolOutputEvent.
     */
    private String executeSshTool(String argsStr,
                                   String toolCallId,
                                   ClientChannel emitter,
                                   DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        // 1. parse args
        String command = parseToolArg(argsStr, "command");
        if (command == null || command.isBlank()) {
            return "Error: missing 'command' argument";
        }

        // 2. Parse timeout arg (optional)
        Long timeoutMs = null;
        String timeoutStr = parseToolArg(argsStr, "timeoutMs");
        if (timeoutStr != null && !timeoutStr.isBlank()) {
            try {
                timeoutMs = Long.parseLong(timeoutStr);
            } catch (NumberFormatException ignored) {}
        }

        // 3. Execute the SSH command (streaming); each chunk is a live tool_output event
        Map<String, Object> result = remoteCommandToolService.executeCommandStreaming(
                buildRunContext(dynamicContext, dynamicContext.getAgentId()),
                command,
                timeoutMs,
                chunk -> sendToolOutputEvent(emitter, toolCallId, chunk, dynamicContext)
        );

        // 4. format the result
        return formatSshResult(result);
    }

    /**
     * Format an SSH execution result.
     */
    private String formatSshResult(Map<String, Object> result) {
        if (result == null) {
            return "No result";
        }

        StringBuilder sb = new StringBuilder();

        Object output = result.get("output");
        if (output != null && !output.toString().isEmpty()) {
            sb.append(output);
        }

        Object error = result.get("error");
        if (error != null && !error.toString().isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("[ERROR] ").append(error);
        }

        Object exitCode = result.get("exitCode");
        if (exitCode != null) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("[Exit code: ").append(exitCode).append("]");
        }

        return !sb.isEmpty() ? sb.toString() : "Command executed with no output";
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parse a named key from a JSON args string.
     */
    private String parseToolArg(String argsStr, String key) {
        if (argsStr == null || argsStr.isBlank()) return null;

        try {
            Map<String, Object> args = objectMapper.readValue(argsStr,
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            Object value = args.get(key);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.warn("Failed to parse tool args: {}", e.getMessage());
            // Fallback: simple string match
            String pattern = "\"" + key + "\"";
            int idx = argsStr.indexOf(pattern);
            if (idx >= 0) {
                int colonIdx = argsStr.indexOf(":", idx + pattern.length());
                if (colonIdx >= 0) {
                    String remaining = argsStr.substring(colonIdx + 1).trim();
                    if (remaining.startsWith("\"")) {
                        int endQuote = remaining.indexOf("\"", 1);
                        if (endQuote > 0) {
                            return remaining.substring(1, endQuote);
                        }
                    }
                }
            }
            return null;
        }
    }

    /**
     * Truncate an overly long tool response.
     */
    private String truncateToolResponse(String content, int maxLength) {
        if (content == null) return "";
        if (content.length() <= maxLength) return content;
        return content.substring(0, maxLength) + "\n... (truncated, total " + content.length() + " chars)";
    }

    /**
     * Send a permission-confirm request event (unified ReActEventDTO).
     */
    private void sendConfirmationEvent(ClientChannel emitter,
                                        String toolCallId,
                                        String toolName,
                                        PermissionGuard.PermissionDecision decision,
                                        DefaultReActFactory.DynamicContext dynamicContext) {
        String confirmId = toolCallId + "_confirm_" + System.currentTimeMillis();
        sendPermissionConfirmEvent(
                emitter,
                confirmId,
                toolName,
                decision.getCommand() != null ? decision.getCommand() : "",
                decision.getAction().name(),
                decision.getReason(),
                30_000L, // 30 s timeout
                dynamicContext
        );
    }

}
