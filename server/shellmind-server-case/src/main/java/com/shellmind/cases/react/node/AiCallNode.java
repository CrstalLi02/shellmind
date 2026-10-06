package com.shellmind.cases.react.node;

import com.shellmind.api.dto.ChatRequestDTO;
import com.shellmind.api.dto.ReActResultDTO;
import com.shellmind.cases.react.AbstractAIAgentReActSupport;
import com.shellmind.cases.react.factory.DefaultReActFactory;
import com.shellmind.domain.agent.service.IPromptService;
import com.shellmind.domain.agent.service.IChatContextService;
import com.shellmind.domain.memory.service.ILongTermMemoryService;
import com.shellmind.domain.agent.model.valobj.intent.IntentResultVO;
import com.shellmind.domain.agent.model.valobj.intent.IntentTypeEnumVO;
import com.shellmind.domain.agent.service.intent.enhancer.IntentOrchestrator;
import com.shellmind.domain.conversation.adapter.repository.IChatHistoryRepository;
import com.shellmind.domain.conversation.model.entity.ChatMessageEntity;
import com.shellmind.domain.agent.adapter.port.AgentRuntime;
import com.shellmind.domain.agent.model.valobj.runtime.AgentRunRequest;
import com.shellmind.domain.agent.model.valobj.runtime.AgentRuntimeEvent;
import com.shellmind.domain.agent.model.valobj.runtime.InputPart;
import com.shellmind.domain.agent.model.valobj.runtime.ToolResponse;
import com.shellmind.domain.agent.service.command.CommandDispatcher;
import com.shellmind.domain.agent.service.run.AgentRunRegistry;
import com.shellmind.domain.shared.model.RunContext;
import com.shellmind.types.design.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.shellmind.domain.agent.adapter.port.ClientChannel;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * AI-call node (core of the ReAct loop).
 *
 * <p>Responsibilities:
 * 1. Call ADK runner.runAsync() and consume the event stream
 * 2. Handle text content and send SSE events
 * 3. Detect tool-execution results from event.actions().stateDelta()
 * 4. If there are tool calls: store them on context, send SSE events, route to ToolCallNode
 * 5. If there are no tool calls: route to LoopDecisionNode
 *
 * <p>Data-flow notes (2026-06-25 fix):
 * <p>Spring AI automatic tool execution is disabled (internalToolExecutionEnabled=false).
 * ADK Runner auto-executes tools; FunctionResponse events appear in the event stream.
 * AiCallNode extracts the full tool result from FunctionResponse and pushes it to the frontend via SSE.
 * The tool-class onToolResult side channel is disabled to avoid duplicate pushes.
 *
 * <p>stateDelta[outputKey] stores the LlmAgent AI text summary, not the raw tool return value.
 * It is used only for logging and detecting whether the agent produced a final output.
 *
 * <p>ReAct loop flow:
 * <pre>
 * RootNode
 *   └→ AiCallNode (call ADK runner, parse events)
 *         ├→ [stateDelta has a result] ToolCallNode → AiCallNode (loop)
 *         └→ [no tool calls] LoopDecisionNode → UserFeedbackNode
 * </pre>
 *
 * @author xiaofuge bugstack.cn
 * 2026/5/4
 */
@Slf4j
@Component("reactAiCallNode")
public class AiCallNode extends AbstractAIAgentReActSupport {

    @Resource
    private AgentRuntime agentRuntime;

    @Resource
    private AgentRunRegistry runRegistry;

    @Resource
    private CommandDispatcher commandDispatcher;

    @Resource
    private IPromptService promptService;

    @Resource
    private com.shellmind.domain.coding.service.WorkspaceStatusService workspaceStatusService;
    
    @Resource
    private IChatContextService chatContextService;

    @Resource
    private IntentOrchestrator intentOrchestrator;
    
    @Resource
    private IChatHistoryRepository chatHistoryRepository;

    @Resource
    private ILongTermMemoryService longTermMemoryService;

    /** SSE event send interval (character count). */
    private static final int SSE_BATCH_SIZE = 20;

    /** Tool-name mapping: stateDelta key -> default tool name (used when it cannot be inferred from the result). */
    private static final Map<String, String> STATE_DELTA_TOOL_MAPPING = Map.ofEntries(
            Map.entry("ssh_result", "executeCommand"),
            Map.entry("code_result", "executeLocalCommand")
    );

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct AiCallNode - starting AI call, step {}", dynamicContext.getStep() + 1);

        // Send round_start SSE event
        sendRoundStartEvent(dynamicContext.getEmitter(), dynamicContext.getStep(), dynamicContext);

        String agentId = dynamicContext.getAgentId();

        // 1. Confirm the agent is registered (sessions are auto-created by the runtime as needed)
        if (!agentRuntime.isRegistered(agentId)) {
            throw new RuntimeException("Agent not found: " + agentId);
        }

        // 2. Get the latest user message
        String lastUserMessage = getLastUserMessage(requestParameter, dynamicContext);

        // [Phase 3] Use the intent already recognized by RootNode; classify again only as a fallback
        IntentResultVO intentResult = resolveIntentResult(dynamicContext, lastUserMessage);
        log.info("Recognized user intent: {}, confidence: {}", intentResult.getIntent().getLabel(), intentResult.getConfidence());

        // 3. Reset this round's buffers
        dynamicContext.resetRoundBuffers();

        // [Phase 2] Trim message history (token budget is read dynamically from agent config)
        // Note: trim first, then use for intent resolution and context injection, so an oversized session cannot blow the prompt
        int tokenBudget = dynamicContext.getContextTokenBudget() > 0 ? dynamicContext.getContextTokenBudget() : 8000;
        List<Map<String, Object>> trimmedHistory = chatContextService.trimHistory(dynamicContext.getMessageHistory(), tokenBudget);
        dynamicContext.setMessageHistory(new ArrayList<>(trimmedHistory));

        // 4. Build this round's run context: tools, progress notify, and local-command dispatch look up by session ID
        String chatSessionId = dynamicContext.getSessionId();
        RunContext runContext = buildRunContext(dynamicContext, agentId);

        // 5. build dynamic context and inject the user message
        String enrichedMessage = buildEnrichedMessage(lastUserMessage, dynamicContext);
        log.debug("Message length after injecting dynamic context: {} -> {}", lastUserMessage.length(), enrichedMessage.length());

        // [Phase 5] Persist the user message to the database (only the original message on the first round)
        if (dynamicContext.getStep() == 0) {
            chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                    .sessionId(dynamicContext.getSessionId())
                    .role("user")
                    .content(lastUserMessage)
                    .priority("MEDIUM")
                    .tokenCount(lastUserMessage.length() / 2)
                    .build());
            // Long-term memory extraction (user side): recognize "later / default / remember" and similar preference phrasing
            longTermMemoryService.recordUserMessage(
                    dynamicContext.getUserId(), dynamicContext.getSessionId(),
                    lastUserMessage, dynamicContext.getCurrentIntent());
        }

        // 6. Build user input (supports multimodal: text + image)
        List<InputPart> userParts = new ArrayList<>();
        userParts.add(InputPart.text(enrichedMessage));

        List<ChatRequestDTO.InlineData> inlineDatas = requestParameter.getInlineDatas();
        if (inlineDatas != null && !inlineDatas.isEmpty()) {
            for (ChatRequestDTO.InlineData inlineData : inlineDatas) {
                try {
                    byte[] imageBytes = java.util.Base64.getDecoder().decode(inlineData.getData());
                    userParts.add(InputPart.inline(imageBytes, inlineData.getMimeType()));
                    log.info("Appending inline image: mimeType={}, size={} bytes", inlineData.getMimeType(), imageBytes.length);
                } catch (Exception e) {
                    log.warn("Failed to parse inline image data: mimeType={}, error={}", inlineData.getMimeType(), e.getMessage());
                }
            }
        }
        log.info("User input part count: {}", userParts.size());

        // 7. Reset ReAct loop flags
        dynamicContext.setStopReason(null);
        dynamicContext.setErrorMessage(null);

        // 8. call the agent runtime and process the event stream
        ClientChannel emitter = dynamicContext.getEmitter();
        StringBuilder textAccumulator = new StringBuilder();
        int roundToolCalls = 0;
        boolean hasError = false;
        StringBuilder errorBuilder = new StringBuilder();

        log.info("Calling agent runtime, user message: {}", lastUserMessage.length() > 200
                ? lastUserMessage.substring(0, 200) + "..." : lastUserMessage);

        // Register this round's run: tools, progress notify, and local command dispatch take context and the client channel from here by session
        runRegistry.register(runContext, emitter);
        AgentRunRequest runRequest = new AgentRunRequest(
                agentId, dynamicContext.getUserId(), chatSessionId, userParts, true);
        try {

            int maxAttempts = Math.max(1, dynamicContext.getMaxAiRetries() + 1);
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                boolean hasObservableOutput = false;
                int eventCount = 0;
                long lastHeartbeatTime = System.currentTimeMillis();
                final long HEARTBEAT_INTERVAL_MS = 30_000L;

                try {
                    Iterator<AgentRuntimeEvent> events = agentRuntime.run(runRequest);

                    while (events.hasNext()) {
                        if (dynamicContext.isCancelled()) {
                            log.info("Task cancelled (reason={}); exiting event loop", dynamicContext.getCancelReason());
                            dynamicContext.setStopReason("user_stop");
                            break;
                        }

                        AgentRuntimeEvent event = events.next();
                        eventCount++;

                        if (dynamicContext.isCancelled()) {
                            log.info("Task cancelled (reason={}); exiting event loop", dynamicContext.getCancelReason());
                            dynamicContext.setStopReason("user_stop");
                            break;
                        }

                        log.info("[AiCallNode] attempt#{}, event#{}: text_len={}, functionCalls={}, toolResponses={}",
                                attempt, eventCount, event.text().length(),
                                event.functionCallCount(), event.toolResponses().size());

                        long now = System.currentTimeMillis();
                        if (now - lastHeartbeatTime > HEARTBEAT_INTERVAL_MS) {
                            boolean heartbeatOk = commandDispatcher.sendHeartbeat(chatSessionId);
                            if (!heartbeatOk) {
                                log.warn("SSE heartbeat send failed (client may have disconnected)");
                                dynamicContext.markCancelled("heartbeat_send_failed");
                            }
                            lastHeartbeatTime = now;
                        }

                        String eventText = event.text();

                        if (!eventText.isBlank()) {
                            hasObservableOutput = true;
                            appendChunk(textAccumulator, eventText);
                            dynamicContext.setAssistantContent(textAccumulator);
                            if (!sendTextEvent(emitter, eventText, textAccumulator.toString(), dynamicContext)) {
                                log.info("Failed to send text event; exiting event loop");
                                dynamicContext.setStopReason("user_stop");
                                break;
                            }
                            lastHeartbeatTime = System.currentTimeMillis();
                        }

                        // Extract the raw tool return value from the tool-execution receipt
                        List<ToolResponse> funcResponses = event.toolResponses();
                        if (event.hasFunctionCalls()) {
                            dynamicContext.setAssistantContentBoundary(textAccumulator.length());
                        }
                        if (!funcResponses.isEmpty()) {
                            hasObservableOutput = true;
                            dynamicContext.setAssistantContentBoundary(textAccumulator.length());

                            for (ToolResponse fr : funcResponses) {
                                String toolName = fr.toolName();
                                java.util.Optional<java.util.Map<String, Object>> responseOpt = java.util.Optional.ofNullable(fr.response());

                                if (responseOpt.isEmpty()) {
                                    log.warn("[FunctionResponse] ⚠️ Tool {} returned empty response data; building a placeholder so the model still gets feedback", toolName);
                                    java.util.Map<String, Object> emptyResult = new HashMap<>();
                                    emptyResult.put("success", false);
                                    emptyResult.put("error", "Tool " + toolName + " finished but returned empty data. The workspace context may be lost or a serialization error occurred. Use executeLocalCommand(\"pwd && ls -la\") to confirm the workspace.");
                                    emptyResult.put("tool", toolName);
                                    responseOpt = java.util.Optional.of(emptyResult);
                                }

                                java.util.Map<String, Object> rawResult = responseOpt.get();
                                String toolCallId = fr.toolCallId() != null
                                        ? fr.toolCallId()
                                        : "call_" + toolName + "_" + System.currentTimeMillis() + "_" + roundToolCalls;

                                // Keep consuming the ADK event stream so the model can still produce a final summary;
                                // only discard results that exceed this round's budget, so parallel tools are not all dumped into the UI.
                                if (roundToolCalls >= dynamicContext.getMaxToolCallsPerRound()) {
                                    dynamicContext.setOverflowToolCount(dynamicContext.getOverflowToolCount() + 1);
                                    log.warn("[PER-ROUND-OVERFLOW] tool calls this round hit the cap: {}/{}, skipping {}",
                                            roundToolCalls,
                                            dynamicContext.getMaxToolCallsPerRound(),
                                            toolName);
                                    continue;
                                }

                                // Extract tool name and args from the original Map
                                String[] toolInfo = extractToolInfo(rawResult, toolName);
                                String effectiveToolName = toolInfo[0];
                                String toolArgs = toolInfo[1];
                                String resultContent = formatStateValue(rawResult);
                                String toolStatus = inferToolStatus(rawResult, resultContent);

                                log.info("[FunctionResponse] tool execution result: tool={}, effectiveName={}, args_len={}, result_len={}, status={}",
                                        toolName, effectiveToolName, toolArgs != null ? toolArgs.length() : 0, resultContent.length(), toolStatus);

                                Map<String, Object> toolCallInfo = new HashMap<>();
                                toolCallInfo.put("id", toolCallId);
                                toolCallInfo.put("name", effectiveToolName);
                                toolCallInfo.put("args", toolArgs != null ? toolArgs : "");
                                dynamicContext.getCurrentToolCalls().add(toolCallInfo);

                                Map<String, Object> toolResultInfo = new HashMap<>();
                                toolResultInfo.put("id", toolCallId);
                                toolResultInfo.put("name", effectiveToolName);
                                toolResultInfo.put("content", resultContent);
                                toolResultInfo.put("status", toolStatus);
                                dynamicContext.getCurrentToolResults().add(toolResultInfo);
                                dynamicContext.appendToolMessage(toolCallId, resultContent);
                                chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                                        .sessionId(dynamicContext.getSessionId())
                                        .role("tool")
                                        .content(resultContent)
                                        .toolName(effectiveToolName)
                                        .toolCallId(toolCallId)
                                        .priority("MEDIUM")
                                        .tokenCount(resultContent.length() / 2)
                                        .build());
                                longTermMemoryService.recordToolObservation(
                                        dynamicContext.getUserId(), dynamicContext.getSessionId(),
                                        effectiveToolName, resultContent, "success".equals(toolStatus));

                                if (!sendToolCallEventWithArgs(emitter, toolCallId, effectiveToolName, toolArgs != null ? toolArgs : "", "executing", dynamicContext)) {
                                    log.info("Failed to send tool-call event; exiting event loop");
                                    dynamicContext.setStopReason("user_stop");
                                    break;
                                }

                                if (!sendToolResultEvent(emitter, toolCallId, resultContent, toolStatus, dynamicContext)) {
                                    log.info("Failed to send tool-result event; exiting event loop");
                                    dynamicContext.setStopReason("user_stop");
                                    break;
                                }

                                roundToolCalls++;
                                dynamicContext.incrementTotalToolCalls();

                                if ("executeCommand".equals(effectiveToolName) && !resultContent.isEmpty()) {
                                    recordExecutedCommand(dynamicContext, resultContent);
                                }

                                if ("error".equals(toolStatus)) {
                                    dynamicContext.appendAssistantMessage("Tool " + effectiveToolName + " failed. Error details:\n" + truncate(resultContent, 1200));
                                }

                                promptService.detectAndRecordMilestone(
                                        dynamicContext.getUserId(), dynamicContext.getSessionId(), "tool", resultContent);
                                chatContextService.pushToolResult(dynamicContext.getSessionId(), effectiveToolName, resultContent);
                            }
                        }

                        if (dynamicContext.getStopReason() != null) {
                            break;
                        }
                    }

                    if (textAccumulator.length() > 0) {
                        dynamicContext.appendAssistantMessage(textAccumulator.toString());
                    }

                    log.info("Runtime event-stream processing complete, {} events, attempt={}", eventCount, attempt);
                    dynamicContext.resetAiRetryCount();
                    break;
                } catch (Exception e) {
                    if (dynamicContext.isCancelled() || Thread.currentThread().isInterrupted()) {
                        log.info("ADK Runner event stream cancelled; ignoring interrupt: reason={}", dynamicContext.getCancelReason());
                        Thread.interrupted();
                        if (dynamicContext.getStopReason() == null) {
                            dynamicContext.setStopReason("user_stop");
                        }
                        break;
                    }

                    boolean canRetry = attempt < maxAttempts
                            && !hasObservableOutput
                            && isRetryableException(e);

                    if (canRetry) {
                        int retryCount = dynamicContext.incrementAiRetryCount();
                        long delayMs = calculateRetryDelayMs(retryCount);
                        log.warn("ADK Runner call failed; retry {} in {} ms: {}",
                                retryCount, delayMs, e.getMessage());
                        safeSleep(delayMs, dynamicContext);
                        continue;
                    }

                    log.error("ADK Runner call failed", e);
                    hasError = true;
                    errorBuilder.append("ADK Runner error: ").append(e.getMessage());
                    dynamicContext.setErrorMessage(errorBuilder.toString());
                    dynamicContext.setStopReason("error");
                    break;
                }
            }
        } finally {
            runRegistry.unregister(chatSessionId);
        }

        // 9. Update step and tool-call stats
        dynamicContext.incrementStep();
        dynamicContext.getResult().setTotalSteps(dynamicContext.getStep());
        dynamicContext.getResult().setTotalToolCalls(
                dynamicContext.getResult().getTotalToolCalls() + roundToolCalls
        );

        log.info("ReAct AiCallNode - step {} complete, {} tool calls this round, text length {}{}",
                dynamicContext.getStep(), roundToolCalls, textAccumulator.length(),
                dynamicContext.getOverflowToolCount() > 0
                    ? ", ⚠️ " + dynamicContext.getOverflowToolCount() + " tool(s) overflowed to the next round"
                    : "");

        // 10. Send round_end only while the connection is still valid, so we do not write SSE after a disconnect
        if (!dynamicContext.isCancelled()) {
            sendRoundEndEvent(
                    dynamicContext.getEmitter(),
                    dynamicContext.getStep(),
                    dynamicContext.getMaxSteps(),
                    !hasError,
                    dynamicContext.getResult().getTotalToolCalls(),
                    dynamicContext
            );
        }

        // [Phase 5] Persist the assistant reply to the database (final reply, or any reply with substantial content)
        if (textAccumulator.length() > 0) {
            chatHistoryRepository.saveMessage(ChatMessageEntity.builder()
                    .sessionId(dynamicContext.getSessionId())
                    .role("assistant")
                    .content(textAccumulator.toString())
                    .priority("MEDIUM")
                    .tokenCount(textAccumulator.length() / 2)
                    .build());
            // Long-term memory extraction (assistant side): settle replies that include conclusion/reason/suggestion/fix/root cause as troubleshooting cases
            longTermMemoryService.recordAssistantConclusion(
                    dynamicContext.getUserId(), dynamicContext.getSessionId(), textAccumulator.toString());
        }

        // 11. Error handling
        if (hasError) {
            dynamicContext.setStopReason("error");
        }

        // 12. Route
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter,
            DefaultReActFactory.DynamicContext dynamicContext) throws Exception {

        // Check whether we should stop
        String stopReason = dynamicContext.getStopReason();
        if (stopReason != null) {
            log.info("Stop condition detected: {}, routing to UserFeedbackNode", stopReason);
            return getBean("reactUserFeedbackNode");
        }

        // Check whether max steps was reached
        if (dynamicContext.getStep() >= dynamicContext.getMaxSteps()) {
            log.info("Reached max steps {}, routing to UserFeedbackNode", dynamicContext.getMaxSteps());
            dynamicContext.setStopReason("max_steps");
            return getBean("reactUserFeedbackNode");
        }

        // Check whether this round has tool calls (detected from stateDelta)
        if (!dynamicContext.getCurrentToolCalls().isEmpty()) {
            log.info("Detected {} tool calls; routing to ToolCallNode",
                    dynamicContext.getCurrentToolCalls().size());
            return getBean("reactToolCallNode");
        }

        // No tool calls → ReAct loop complete
        log.info("No tool calls; ReAct loop complete; routing to LoopDecisionNode");
        return getBean("reactLoopDecisionNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get the latest user message.
     */
    private String getLastUserMessage(ChatRequestDTO requestParameter,
                                       DefaultReActFactory.DynamicContext dynamicContext) {
        if (requestParameter.getMessage() != null && !requestParameter.getMessage().isEmpty()) {
            return requestParameter.getMessage();
        }

        List<Map<String, Object>> history = dynamicContext.getMessageHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = history.get(i);
            if ("user".equals(msg.get("role"))) {
                return (String) msg.get("content");
            }
        }

        return "";
    }


    /**
     * Parse a tool name from a stateDelta key (default, used when it cannot be inferred from the result).
     */
    private String resolveToolName(String stateKey) {
        // known mapping
        String mapped = STATE_DELTA_TOOL_MAPPING.get(stateKey);
        if (mapped != null) {
            return mapped;
        }

        // Infer from the key: strip a _result suffix
        if (stateKey.endsWith("_result")) {
            return stateKey.substring(0, stateKey.length() - 7);
        }

        return stateKey;
    }

    /**
     * Extract the real tool name and command args from the tool return value.
     * <p>In ADK auto-execution mode all tool results live under the same outputKey (e.g. code_result).
     * The returned Map has characteristic fields that can reverse-infer the tool type:
     * <ul>
     *   <li>executeLocalCommand / compileProject / compileTests / runUnitTests → command/output/exitCode</li>
     *   <li>readFile / writeFile / listFiles → path/content</li>
     *   <li>executeCommand (SSH) → command/output/exitCode plus SSH markers</li>
     * </ul>
     *
     * @return [0] = toolName, [1] = toolArgs (command or an args summary)
     */
    private String[] extractToolInfo(Object stateValue, String stateKey) {
        String defaultToolName = resolveToolName(stateKey);
        String toolArgs = "";

        if (stateValue == null) {
            return new String[]{defaultToolName, toolArgs};
        }

        try {
            // Try to parse stateValue as a JSON object
            com.fasterxml.jackson.databind.JsonNode node;
            if (stateValue instanceof String) {
                node = objectMapper.readTree((String) stateValue);
            } else {
                node = objectMapper.valueToTree(stateValue);
            }

            // Infer tool type from result fields
            String command = node.has("command") ? node.get("command").asText("") : "";
            String path = node.has("path") ? node.get("path").asText("") : "";
            String filePath = node.has("filePath") ? node.get("filePath").asText("") : "";
            boolean hasExitCode = node.has("exitCode");
            boolean hasOutput = node.has("output");
            boolean hasErrorSummary = node.has("errorSummary");
            boolean hasTestFailures = node.has("testFailures");

            if (hasErrorSummary || hasTestFailures) {
        // BuildValidationAdkTool family
                if (command.contains("test") || hasTestFailures) {
                    toolArgs = command;
                    return new String[]{"runUnitTests", toolArgs};
                } else if (command.contains("test-compile") || command.contains("-pl") && command.contains("compile")) {
                    toolArgs = command;
                    return new String[]{"compileTests", toolArgs};
                } else {
                    toolArgs = command;
                    return new String[]{"compileProject", toolArgs};
                }
            }

            if (command != null && !command.isEmpty() && hasExitCode) {
                // Command-execution tools (executeLocalCommand or executeCommand)
                toolArgs = command;
                // SSH outputKey is ssh_result; local is code_result
                if ("ssh_result".equals(stateKey)) {
                    return new String[]{"executeSshCommand", toolArgs};
                }
                return new String[]{"executeLocalCommand", toolArgs};
            }

            if (path != null && !path.isEmpty() || filePath != null && !filePath.isEmpty()) {
                // File-operation tools
                String resolvedPath = path != null && !path.isEmpty() ? path : filePath;
                toolArgs = resolvedPath;
                // Infer local vs remote from stateKey
                if ("code_result".equals(stateKey)) {
                    // Cannot determine the specific operation type; use the default name
                    return new String[]{"codeEditTool", toolArgs};
                }
            }

        } catch (Exception e) {
            log.debug("Failed to extract tool info from stateValue: {}", e.getMessage());
        }

        return new String[]{defaultToolName, toolArgs};
    }

    /**
     * Format a stateDelta value as a string.
     */
    private String formatStateValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String) {
            return (String) value;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return value.toString();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Phase 1: dynamic context injection
    // ═══════════════════════════════════════════════════════════════

    /**
     * extract commands from tool results and record them in the recent-command list
     */
    private void recordExecutedCommand(DefaultReActFactory.DynamicContext dynamicContext, String toolResult) {
        if (toolResult.length() > 1000) {
            dynamicContext.addRecentCommand(truncate(toolResult, 80) + "...");
        } else {
            dynamicContext.addRecentCommand(toolResult);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    /**
     * Build a user message with dynamic context injected.
     * Delegates environment collection, milestone retrieval, and prefix building to IPromptService.
     * [Phase 3] Integrates IntentOrchestrator for coreference resolution and context enhancement.
     */
    private String buildEnrichedMessage(String userMessage, DefaultReActFactory.DynamicContext dynamicContext) {
        // Record a milestone from the user message
        promptService.detectAndRecordMilestone(dynamicContext.getUserId(), dynamicContext.getSessionId(), "user", userMessage);

        // [Phase 3] Intent enhancement: signal extraction + coreference resolution + context enhancement
        // [2026-09-28] Intent routing was removed; currentIntent may be "UNIFIED" (not an enum value).
        // valueOf must be fault-tolerant, otherwise IllegalArgumentException would abort the whole request.
        IntentResultVO intentResult = buildSafeIntentResult(dynamicContext);
        
        // [P2-5] Pass projectRootPath so intent enhancement can fall back to file search
        com.shellmind.api.dto.ProjectContextDTO projectCtxForIntent = dynamicContext.getProjectContext();
        String projectRootPathForIntent = projectCtxForIntent != null ? projectCtxForIntent.getRootPath() : null;
        IntentOrchestrator.EnhancedIntentResult enhancedResult = intentOrchestrator.buildEnhancedResult(
                intentResult, userMessage, dynamicContext.getSessionId(), null, projectRootPathForIntent);
        
        // Use the input after coreference resolution
        String effectiveMessage = enhancedResult.getResolvedInput() != null 
                ? enhancedResult.getResolvedInput() : userMessage;
        
        // delegate to the domain service to build the enriched message
        com.shellmind.api.dto.ProjectContextDTO projectCtx = dynamicContext.getProjectContext();
        String projectName = projectCtx != null ? projectCtx.getName() : null;
        String projectRootPath = projectCtx != null ? projectCtx.getRootPath() : null;
        String enrichedMessage = promptService.buildEnrichedMessage(
                effectiveMessage,
                dynamicContext.getUserId(),
                dynamicContext.getSessionId(),
                dynamicContext.getTerminalSessionId(),
                dynamicContext.getRecentCommands(),
                dynamicContext.getMessageHistory(),
                projectName,
                projectRootPath,
                dynamicContext.getTaskMode()
        );
        
        // [Phase 3] Append intent-enhancement context
        if (enhancedResult.hasEnhancement() && enhancedResult.getEnhancedContext() != null) {
            enrichedMessage = enhancedResult.getEnhancedContext() + "\n" + enrichedMessage;
        }
        
        // Inject workspace status (git branch/status)
        if (projectRootPath != null && !projectRootPath.isBlank()) {
            try {
                com.shellmind.domain.coding.service.WorkspaceStatusService.WorkspaceStatus ws =
                        workspaceStatusService.getStatus(projectRootPath);
                if (ws.gitRepository() && ws.branch() != null) {
                    enrichedMessage = "[Workspace status]\nBranch: " + ws.branch()
                            + "\nChanges: " + (ws.gitStatus() != null ? ws.gitStatus() : "unknown")
                            + "\n---\n" + enrichedMessage;
                }
            } catch (Exception e) {
                log.debug("Failed to inject workspace status: {}", e.getMessage());
            }
        }

        return enrichedMessage;
    }

    /**
     * [2026-09-28 architecture simplification] Intent routing was removed.
     * Intent is only a logging / context-enhancement tag; it no longer drives the execution strategy.
     * The fallback second LLM classification call (extra model call, often wrong) was removed with it.
     * currentIntent may now be "UNIFIED" (not an enum value); parsing must be fault-tolerant.
     */
    private IntentResultVO resolveIntentResult(DefaultReActFactory.DynamicContext dynamicContext, String lastUserMessage) {
        return buildSafeIntentResult(dynamicContext);
    }

    /**
     * Safely build an intent result from DynamicContext: if currentIntent is not a valid enum, return UNKNOWN.
     * Never throws (IllegalArgumentException would abort the entire ReAct request).
     */
    private IntentResultVO buildSafeIntentResult(DefaultReActFactory.DynamicContext dynamicContext) {
        String intentName = dynamicContext.getCurrentIntent();
        if (intentName == null || intentName.isBlank()) {
            intentName = "UNKNOWN";
        }
        try {
            return IntentResultVO.builder()
                    .intent(IntentTypeEnumVO.valueOf(intentName))
                    .confidence(dynamicContext.getCurrentIntentConfidence())
                    .rawResponse("from_context")
                    .build();
        } catch (Exception ignore) {
            // Non-enum tags such as "UNIFIED" → UNKNOWN fallback; full confidence means this is a placeholder, not a low-confidence classification
            return IntentResultVO.builder()
                    .intent(IntentTypeEnumVO.UNKNOWN)
                    .confidence(1.0D)
                    .rawResponse("unified_no_intent")
                    .build();
        }
    }

    private String inferToolStatus(String resultContent) {
        return inferToolStatus(null, resultContent);
    }

    private String inferToolStatus(java.util.Map<String, Object> rawResult, String resultContent) {
        if (resultContent == null || resultContent.isBlank()) {
            return "success";
        }

        if (rawResult != null) {
            Object success = rawResult.get("success");
            if (success instanceof Boolean) {
                return (Boolean) success ? "success" : "error";
            }
        }

        String lower = resultContent.toLowerCase();
        if (lower.contains("\"success\":false")
                || lower.contains("failed")
                || lower.contains("error")
                || lower.contains("执行失败")
                || lower.contains("exception")
                || lower.contains("timeout")
                || lower.contains("timed out")) {
            return "error";
        }

        return "success";
    }


    private boolean isRetryableException(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return true;
        }

        String lower = message.toLowerCase();
        return lower.contains("timeout")
                || lower.contains("temporarily unavailable")
                || lower.contains("connection reset")
                || lower.contains("connection closed")
                || lower.contains("503")
                || lower.contains("504")
                || lower.contains("rate limit")
                || lower.contains("resource exhausted");
    }

    private long calculateRetryDelayMs(int retryCount) {
        return Math.min(8_000L, 1_000L * (1L << Math.max(0, retryCount - 1)));
    }

    private void safeSleep(long delayMs, DefaultReActFactory.DynamicContext dynamicContext) {
        if (delayMs <= 0 || dynamicContext.isCancelled()) {
            return;
        }

        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            dynamicContext.markCancelled("retry_sleep_interrupted");
        }
    }

    /**
     * Smart-concat LLM streaming text chunks; handle || line separators and glued table rows.
     * Note: only fix formatting caused by streaming concatenation. Format errors in the LLM output itself
     * are fully processed by finalMarkdownCleanup before the done event.
     */
    private static void appendChunk(StringBuilder acc, String chunk) {
        if (chunk == null || chunk.isEmpty()) return;

        // 0. Normalize newlines: \r\n → \n, to avoid leftover Windows newlines
        chunk = chunk.replace("\r\n", "\n").replace("\r", "\n");

        // 1. || → newline (keep the previous line's trailing | and following spaces)
        // Note: avoid breaking table format; only handle this outside table context
        String processed = chunk;
        // Handle glued table rows: | content|| → | content|
        // but keep table separator line |---|---| intact
        if (!chunk.trim().startsWith("|") || !chunk.contains("---")) {
            processed = chunk.replaceAll("(\\|)\\|+(\\s*)", "$1\n$2");
        }

        // 2. Inter-block newlines: accumulated text end + new-block start
        if (acc.length() > 0 && !processed.startsWith("\n")) {
            String accStr = acc.toString();
            String lastLine = accStr.contains("\n")
                    ? accStr.substring(accStr.lastIndexOf('\n') + 1)
                    : accStr;

            // Glued table rows: | at end (trailing space allowed) + | at start
            String lastLineTrimmed = lastLine.trim();
            if ((lastLineTrimmed.endsWith("|") || lastLineTrimmed.matches(".*\\|\\s*")) && processed.startsWith("|")) {
                acc.append('\n');
            }
            // List item / heading → table row
            else if (!lastLine.contains("|") && !lastLine.trim().isEmpty()
                    && !lastLine.trim().startsWith("`") && processed.matches("\\|.*\\|.*\\|.*")) {
                acc.append('\n');
            }
            // Block-level elements (--- / ## heading / list) need a blank line before them
            else if (!lastLine.trim().isEmpty() && !lastLine.trim().startsWith("`")
                    && (processed.startsWith("---") || processed.startsWith("##")
                        || processed.matches("[-*+]\\s+.*") || processed.matches("\\d+\\.\\s+.*"))) {
                acc.append("\n\n");
            }
        }

        acc.append(processed);
    }

    /**
     * Full Markdown cleanup, called before the done event.
     * Handles format issues in the LLM output itself, once the complete text is available.
     */
    static String finalMarkdownCleanup(String text) {
        if (text == null || text.isEmpty()) return text;

        // Protect code blocks
        java.util.List<String> codeBlocks = new java.util.ArrayList<>();
        java.util.regex.Matcher cbMatcher = java.util.regex.Pattern.compile("```[\\s\\S]*?```").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (cbMatcher.find()) {
            codeBlocks.add(cbMatcher.group());
            cbMatcher.appendReplacement(sb, "\u0001CB" + (codeBlocks.size() - 1) + "\u0001");
        }
        cbMatcher.appendTail(sb);
        String result = sb.toString();

        // 1. Bold-marker spacing cleanup: ** text ** → **text** (state machine, process in pairs)
        result = cleanBoldSpaces(result);

        // 2. Merge cross-line bold: **Java 17\n3.4.3** → **Java 17 3.4.3**
        result = mergeCrossLineBold(result);

        // 3. Collapse 3+ consecutive blank lines into 2
        result = result.replaceAll("\n{3,}", "\n\n");

        // 4. Fix table-format issues
        result = fixTableFormat(result);

        // Restore code blocks
        for (int i = 0; i < codeBlocks.size(); i++) {
            result = result.replace("\u0001CB" + i + "\u0001", codeBlocks.get(i));
        }

        return result;
    }

    /**
     * Merge cross-line bold markers.
     */
    private static String mergeCrossLineBold(String text) {
        if (text == null || !text.contains("**")) return text;
        String[] lines = text.split("\n", -1);
        java.util.List<String> merged = new java.util.ArrayList<>();
        for (String line : lines) {
            if (!merged.isEmpty()) {
                String prev = merged.get(merged.size() - 1);
                int count = 0;
                for (int j = 0; j < prev.length() - 1; j++) {
                    if (prev.charAt(j) == '*' && prev.charAt(j + 1) == '*') { count++; j++; }
                }
                if (count % 2 != 0) {
                    merged.set(merged.size() - 1, prev + " " + line);
                    continue;
                }
            }
            merged.add(line);
        }
        return String.join("\n", merged);
    }

    /**
     * State-machine cleanup of bold-marker spacing: ** text ** → **text**.
     * Process ** markers in pairs: skip spaces after an opening tag, remove spaces before a closing tag.
     * Does not false-match across already-closed ** pairs.
     */
    static String cleanBoldSpaces(String text) {
        if (text == null || !text.contains("**")) return text;
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        boolean inBold = false;
        while (i < text.length()) {
            if (i + 1 < text.length() && text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                if (!inBold) {
                    // Opening **: skip following spaces
                    sb.append("**");
                    i += 2;
                    while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) {
                        i++;
                    }
                    inBold = true;
                } else {
                    // Closing **: remove accumulated trailing spaces
                    while (sb.length() > 0 && (sb.charAt(sb.length() - 1) == ' ' || sb.charAt(sb.length() - 1) == '\t')) {
                        sb.deleteCharAt(sb.length() - 1);
                    }
                    sb.append("**");
                    i += 2;
                    inBold = false;
                }
            } else {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Fix table-format issues:
     * 1. Extra trailing | on a header row → remove
     * 2. Separator-line format: ---| → |---|---|
     * 3. Ensure a newline between table rows
     */
    private static String fixTableFormat(String text) {
        if (text == null || !text.contains("|")) return text;

        String[] lines = text.split("\n", -1);
        java.util.List<String> fixed = new java.util.ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            // Detect table rows (start and end with |)
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                // Fix extra trailing | on a header row: | a | b || → | a | b |
                // Count |; if even, check whether the row ends with ||
                int pipeCount = 0;
                for (char c : trimmed.toCharArray()) {
                    if (c == '|') pipeCount++;
                }
                if (pipeCount % 2 == 0) {
                    // Even number of |; check whether it ends with ||
                    if (trimmed.endsWith("||")) {
                        line = line.substring(0, line.lastIndexOf("||")) + "|";
                    }
                }
                fixed.add(line);
            }
            // Fix separator-line format: ---| or |--- → complete as |---|---|
            else if (trimmed.matches("^-+\\|.*") || trimmed.matches(".*\\|[-:]+$")) {
                // Try to infer column count from the previous line
                if (!fixed.isEmpty()) {
                    String prevLine = fixed.get(fixed.size() - 1).trim();
                    if (prevLine.startsWith("|") && prevLine.endsWith("|")) {
                        int colCount = prevLine.split("\\|").length - 1;
                        StringBuilder sepLine = new StringBuilder("|");
                        for (int c = 0; c < colCount; c++) {
                            sepLine.append("---|");
                        }
                        fixed.add(sepLine.toString());
                        continue;
                    }
                }
                fixed.add(line);
            }
            else {
                fixed.add(line);
            }
        }

        return String.join("\n", fixed);
    }

}
