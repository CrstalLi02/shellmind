package com.shellmind.cases.react.node;

import com.shellmind.api.dto.ChatRequestDTO;
import com.shellmind.api.dto.ReActResultDTO;
import com.shellmind.cases.react.AbstractAIAgentReActSupport;
import com.shellmind.cases.react.factory.DefaultReActFactory;
import com.shellmind.domain.agent.service.engine.ContextCompressor;
import com.shellmind.types.design.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ReAct loop-decision node.
 *
 * <p>Responsibilities:
 * 1. Check stop conditions (error, max steps, user stop)
 * 2. Decide whether to continue the loop (back to AiCallNode) or route to UserFeedbackNode
 *
 * <p>Stop conditions (see ShellMind streamingAgent.ts):
 * - AI returned a finish instruction
 * - Reached max steps (maxSteps)
 * - Reached max tool-call count (maxToolCalls)
 * - An error occurred
 * - User stopped (user_stop)
 *
 * <p>Loop conditions:
 * - Previous round had tool calls (continue the conversation)
 * - AI did not return a stop instruction
 *
 * @author xiaofuge bugstack.cn
 * 2026/5/4
 */
@Slf4j
@Component("reactLoopDecisionNode")
public class LoopDecisionNode extends AbstractAIAgentReActSupport {

    @Resource
    private ContextCompressor contextCompressor;

    @Override
    protected ReActResultDTO doApply(ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {
        log.info("ReAct LoopDecisionNode - loop decision, current step: {}/{}", 
                dynamicContext.getStep(), dynamicContext.getMaxSteps());

        // 1. Check whether a stop reason is already set
        String stopReason = dynamicContext.getStopReason();
        if (stopReason != null) {
            log.info("Stop reason already set: {}", stopReason);
            return router(requestParameter, dynamicContext);
        }

        // 2. Check max steps
        if (dynamicContext.getStep() >= dynamicContext.getMaxSteps()) {
            log.info("Reached max steps: {}, stopping loop", dynamicContext.getMaxSteps());
            dynamicContext.setStopReason("max_steps");
            dynamicContext.getResult().setMaxStepsReached(true);
            return router(requestParameter, dynamicContext);
        }

        // 3. Check max tool-call count
        if (dynamicContext.getResult().getTotalToolCalls() >= dynamicContext.getMaxToolCalls()) {
            log.info("Reached max tool-call count: {}, stopping loop",
                    dynamicContext.getResult().getTotalToolCalls());
            dynamicContext.setStopReason("max_tool_calls");
            return router(requestParameter, dynamicContext);
        }

        // 4. Check whether the assistant message contains a stop instruction
        String assistantContent = dynamicContext.getAssistantContent() != null
                ? dynamicContext.getAssistantContent().toString()
                : "";

        if (containsFinishCommand(assistantContent)) {
            log.info("AI returned finish; stopping loop");
            dynamicContext.setStopReason("finish");
            return router(requestParameter, dynamicContext);
        }

        // 5. Check for errors
        if (dynamicContext.getErrorMessage() != null) {
            log.info("Error occurred: {}, stopping loop", dynamicContext.getErrorMessage());
            dynamicContext.setStopReason("error");
            return router(requestParameter, dynamicContext);
        }

        // 6. [Phase 1] Dead-loop detection (diminishing returns)
        //    Build this round's tool-call signature and detect consecutive repeats
        String currentSignature = buildRoundSignature(dynamicContext);
        dynamicContext.addRoundSignature(currentSignature);

        if (dynamicContext.isDiminishingReturns()) {
            log.warn("Dead loop detected: {} consecutive rounds with the same tool signature: {}",
                    dynamicContext.getDiminishingReturnsThreshold(), currentSignature);
            sendWarningEvent(dynamicContext.getEmitter(),
                    "Repeated operation pattern detected; stopping automatically to avoid a loop", dynamicContext);
            dynamicContext.setStopReason("diminishing_returns");
            return router(requestParameter, dynamicContext);
        }

        // [FIX-20260929] Also detect a "read-only loop" pattern.
        // The agent may alternate different read-only tools (readFile → listFiles → executeCommand → readFile);
        // different signatures make exact-match detection fail. Switch to tool-name pattern detection.
        if (isReadOnlyLoopPattern(dynamicContext)) {
            log.warn("Read-only loop detected: several rounds of read-only tools with no writes; forcing stop");
            sendWarningEvent(dynamicContext.getEmitter(),
                    "A read-only loop was detected (repeated reads with no writes). Stopping to free budget. Check workspace config and file paths.", dynamicContext);
            dynamicContext.setStopReason("read_only_loop");
            return router(requestParameter, dynamicContext);
        }

        // 7. [Phase 1] Context-compression check
        //    Estimate current token count and compress when over the threshold
        long currentTokens = estimateTokens(dynamicContext.getMessageHistory());
        int messageCount = dynamicContext.getMessageHistory().size();

        if (contextCompressor.needsCompression(currentTokens, messageCount)) {
            log.info("Triggering context compression: tokens={}, messages={}", currentTokens, messageCount);
            sendWarningEvent(dynamicContext.getEmitter(),
                    "Context is long; compressing automatically...", dynamicContext);

            List<Map<String, Object>> compressed = contextCompressor.compress(
                    dynamicContext.getMessageHistory(), currentTokens);
            dynamicContext.setMessageHistory(new java.util.ArrayList<>(compressed));

            long afterTokens = estimateTokens(dynamicContext.getMessageHistory());
            log.info("Context compression complete: {} → {} messages, {} → {} tokens",
                    messageCount, compressed.size(), currentTokens, afterTokens);
        }

        // 8. Check whether the previous round had tool calls (continue-ReAct condition)
        List<Map<String, Object>> currentToolCalls = dynamicContext.getCurrentToolCalls();
        if (currentToolCalls != null && !currentToolCalls.isEmpty()) {
            log.info("Previous round had {} tool calls; continuing ReAct loop", currentToolCalls.size());
            dynamicContext.resetRoundBuffers();
            return router(requestParameter, dynamicContext);
        }

        // 9. No tool calls and no stop instruction → loop complete
        log.info("ReAct loop complete; no more tool calls");
        dynamicContext.setStopReason("completed");
        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ChatRequestDTO, DefaultReActFactory.DynamicContext, ReActResultDTO> get(
            ChatRequestDTO requestParameter, DefaultReActFactory.DynamicContext dynamicContext) throws Exception {

        String stopReason = dynamicContext.getStopReason();

        // Has a stop reason → route to UserFeedbackNode
        if (stopReason != null) {
            switch (stopReason) {
                case "user_stop":
                    dynamicContext.getResult().setUserStopped(true);
                    break;
                case "idle_timeout":
                    dynamicContext.getResult().setIdleTimeout(true);
                    break;
                case "error":
                    break;
                case "max_steps":
                    dynamicContext.getResult().setMaxStepsReached(true);
                    break;
                case "max_tool_calls":
                    break;
                case "completed":
                case "finish":
                case "diminishing_returns":
                default:
                    break;
            }
            return getBean("reactUserFeedbackNode");
        }

        // No stop reason → continue the ReAct loop, back to AiCallNode
        log.info("Continue ReAct loop; routing to AiCallNode");
        return getBean("reactAiCallNode");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Build this round's tool-call signature.
     * <p>Used for dead-loop detection: N consecutive rounds calling the exact same tool sequence is a dead loop.
     * <p>Signature format: "tool1(args_hash)|tool2(args_hash)|..."
     */
    /**
     * [FIX-20260929] Detect a read-only loop pattern.
     * Several consecutive rounds of read-only tools with no writes → the agent is stuck looping without obtaining data.
     */
    private boolean isReadOnlyLoopPattern(DefaultReActFactory.DynamicContext dynamicContext) {
        List<String> signatures = dynamicContext.getRoundSignatures();
        int minRounds = 6;
        if (signatures.size() < minRounds) return false;

        // Check whether the last 6 rounds are all read-only tool calls
        int size = signatures.size();
        for (int i = size - minRounds; i < size; i++) {
            String sig = signatures.get(i);
            if (sig == null || sig.equals("empty")) return false;
            // If the signature includes a write-class tool (write/create/delete/edit/rollback), it is not a read-only loop
            String lowerSig = sig.toLowerCase();
            if (lowerSig.contains("write") || lowerSig.contains("create")
                    || lowerSig.contains("delete") || lowerSig.contains("edit")
                    || lowerSig.contains("rollback")) {
                return false;
            }
        }
        return true;
    }

    private String buildRoundSignature(DefaultReActFactory.DynamicContext dynamicContext) {
        List<Map<String, Object>> toolCalls = dynamicContext.getCurrentToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return "empty";
        }

        return toolCalls.stream()
                .map(tc -> {
                    String name = String.valueOf(tc.getOrDefault("name", "unknown"));
                    String args = String.valueOf(tc.getOrDefault("args", ""));
                    // Use only args length and the first 20 characters as the signature, to keep signatures short
                    String argsSign = args.length() + ":" +
                            (args.length() > 20 ? args.substring(0, 20) : args);
                    return name + "(" + argsSign + ")";
                })
                .collect(Collectors.joining("|"));
    }

    /**
     * Estimate message-history token count.
     */
    private long estimateTokens(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        long total = 0;
        for (Map<String, Object> msg : messages) {
            String content = String.valueOf(msg.get("content"));
            total += content != null ? content.length() / 2L : 0;
        }
        return total;
    }

    /**
     * Whether the content contains a stop instruction.
     * See ShellMind streamingAgent.ts stop-condition checks.
     */
    private boolean containsFinishCommand(String content) {
        if (content == null || content.isBlank()) return false;

        String lower = content.toLowerCase();

        // DSL  style: finish(message=...)
        if (lower.contains("finish(") || lower.contains("finish (")) {
            return true;
        }

        // JSON  style: {"action": "finish"}
        if (lower.contains("\"action\"") && lower.contains("\"finish\"")) {
            return true;
        }

        // tag style: <answer>finish(...)</answer>
        if (lower.contains("<answer>") && lower.contains("finish")) {
            return true;
        }

        // Structured-output marker: 📋 Change summary (means the task is complete)
        if (content.contains("📋") && (content.contains("Change summary") || content.contains("改动摘要") || content.contains("变更摘要") || content.contains("本次变更"))) {
            return true;
        }

        return false;
    }

}
